package com.github.krpkbhdn.astrolabe.update;

import com.github.krpkbhdn.astrolabe.config.AppProperties;
import com.github.krpkbhdn.astrolabe.download.VerifiedDownload;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Looks for a newer GitHub release, downloads the installer for this installation with SHA-256 and signature verification and hands it to its updater. */
@Service
@EnableConfigurationProperties(UpdateProperties.class)
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final String UPDATES_DIRECTORY = "updates";
    private static final String SIGNATURE_SUFFIX = ".asc";
    private static final String FAILED_PREFIX = "FAILED";
    private static final Pattern STEP_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final String STAGING_SUFFIX = ".update";
    private static final Set<String> KEPT_LOGS = Set.of("update.log", "update-msi.log");
    private static final String PACKAGE_STAGING_PREFIX = PosixUpdateScript.STAGING_PREFIX;

    private final UpdateProperties properties;
    private final AppProperties app;
    private final JsonMapper mapper;
    private final List<PlatformUpdater> updaters;
    private final InstallationDetector detector;
    private final Supplier<ReleaseSignature> signatureSource;
    private final VerifiedDownload downloads;
    /** Null when there was no result to read or updates are off; exposed as an Optional. */
    private final UpdateOutcome lastOutcome;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private final AtomicBoolean keyWarned = new AtomicBoolean();
    private final Map<Path, Verified> verified = new ConcurrentHashMap<>();
    private volatile ReleaseSignature signature;
    private volatile Capability capability;
    private volatile PreparedUpdate prepared;

    /** What was checked when a download was accepted; the install step compares the file against it again. */
    private record Verified(String sha256, long size) {
    }

    private record Capability(AvailableUpdate update, boolean installable, boolean needsAuthentication) {
    }

    @Autowired
    UpdateService(UpdateProperties properties, AppProperties app, JsonMapper mapper, List<PlatformUpdater> updaters, InstallationDetector detector,
            VerifiedDownload downloads) {
        this(properties, app, mapper, updaters, detector, UpdateService::bundledSignature, downloads);
    }

    /** The release key is read on the first verification, so a missing or wrong key cannot stop the app from starting. */
    UpdateService(UpdateProperties properties, AppProperties app, JsonMapper mapper, List<PlatformUpdater> updaters, InstallationDetector detector,
            Supplier<ReleaseSignature> signatureSource, VerifiedDownload downloads) {
        this.properties = properties;
        this.app = app;
        this.mapper = mapper;
        this.updaters = List.copyOf(updaters);
        this.detector = detector;
        this.signatureSource = signatureSource;
        this.downloads = downloads;
        // a source run sharing the data dir must not consume the installed app's result
        this.lastOutcome = enabled() ? readAndClearOutcome().orElse(null) : null;
    }

    public boolean enabled() {
        return properties.enabled() && ReleaseVersion.parse(properties.version()).isPresent();
    }

    public String currentVersion() {
        return properties.version();
    }

    public URI releasesUri() {
        return URI.create("https://github.com/" + properties.repository() + "/releases");
    }

    public Duration checkInterval() {
        return Duration.ofHours(properties.checkIntervalHours());
    }

    /** The result the previous update run left behind, read once at start; empty when there was none. */
    public Optional<UpdateOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    /** True when [update] has a signed installer and an updater supports this installation; the UI otherwise only points to the release page. */
    public boolean canInstall(AvailableUpdate update) {
        return capabilityOf(update).installable();
    }

    /** True when installing [update] asks for an administrator password; false when it cannot be installed. */
    public boolean needsAuthentication(AvailableUpdate update) {
        return capabilityOf(update).needsAuthentication();
    }

    /** Computed once per offered update: the updater's support check may run commands. */
    private Capability capabilityOf(AvailableUpdate update) {
        var known = capability;
        if (known == null || !known.update().equals(update)) {
            var chosen = update.installable() ? updater() : Optional.<PlatformUpdater>empty();
            known = new Capability(update, chosen.isPresent(), chosen.map(PlatformUpdater::needsAuthentication).orElse(false));
            // an installation that could not be detected yet (the detector retries later) must not pin "not installable"
            if (installation().isPresent()) {
                capability = known;
            }
        }
        return known;
    }

    /** Empty when up to date, offline, or the latest release has no asset for this installation; failures are logged, not thrown. */
    public Optional<AvailableUpdate> findUpdate() {
        try {
            return checkForUpdate();
        } catch (IllegalStateException e) {
            log.warn("Update check failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Like [findUpdate] but a failed request (offline, HTTP error, unreadable answer) is thrown as [IllegalStateException]. */
    public Optional<AvailableUpdate> checkForUpdate() {
        if (!enabled()) {
            return Optional.empty();
        }
        var current = ReleaseVersion.parse(properties.version()).orElseThrow();
        var detected = installation();
        var latest = URI.create(properties.apiBaseUrl() + "/repos/" + properties.repository() + "/releases/latest");
        try {
            var response = get(latest, "application/vnd.github+json");
            try (var body = response.body()) {
                var release = mapper.readTree(body);
                var found = detected.isPresent() ? ReleaseParser.newerThan(release, current, detected.get(), properties.repository())
                        : ReleaseParser.pageOnlyNewerThan(release, current, properties.repository());
                return found.map(this::pageOnlyWhenKeyUnusable);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    /** Every installer is verified against the bundled key; when that key has expired or is revoked nothing could verify, so only the page is offered. */
    private AvailableUpdate pageOnlyWhenKeyUnusable(AvailableUpdate update) {
        if (!update.installable() || keyUsable()) {
            return update;
        }
        if (keyWarned.compareAndSet(false, true)) {
            log.warn("The bundled release key is expired, revoked or unreadable: updates are offered as a link to the release page only");
        }
        return AvailableUpdate.releasePageOnly(update.version(), update.releasePageUri());
    }

    private boolean keyUsable() {
        try {
            return signature().keyUsable();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * @return the verified installer; [progress] receives 0..1 while downloading (not called when the size is unknown)
     * @throws IllegalStateException when the update cannot be installed here, or the checksum or signature does not match, or another
     *                               download is still running (it would delete the file being staged)
     */
    public Path download(AvailableUpdate update, DoubleConsumer progress) {
        if (!canInstall(update)) {
            throw new IllegalStateException("This update cannot be installed automatically");
        }
        if (!downloading.compareAndSet(false, true)) {
            throw new IllegalStateException("An update download is already running");
        }
        try {
            var expected = expectedChecksum(update);
            var directory = Files.createDirectories(app.dataDir().resolve(UPDATES_DIRECTORY));
            deleteOldDownloads(directory, properties.maxLogBytes());
            var target = directory.resolve(Path.of(update.installerName()).getFileName());
            var size = update.installerSizeBytes() > 0 ? Math.min(update.installerSizeBytes(), properties.maxInstallerBytes()) : properties.maxInstallerBytes();
            var file = downloads.fetch(update.installerUri(), expected, target, Duration.ofSeconds(properties.requestTimeoutSeconds()), progress, size);
            verifySignature(update, file);
            remember(file, expected);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not download the update: " + e.getMessage(), e);
        } finally {
            downloading.set(false);
        }
    }

    /**
     * The slow step (mounting, copying, unpacking): run it off the UI thread and before asking the user to quit. Re-checks that [download] is
     * still the file [download] verified, then lets the updater stage it. Hand the result to [launchAfterExit] or, when the install does
     * not go ahead, to [discard].
     *
     * @throws IllegalStateException when the file was not verified in this run, changed since, or cannot be staged
     */
    public PreparedUpdate prepareInstall(Path download) {
        var detected = installation().orElseThrow(() -> new IllegalStateException("The app was not started from an installed package"));
        var chosen = updater().orElseThrow(() -> new IllegalStateException("This installation cannot be updated automatically"));
        try {
            var real = download.toRealPath();
            var known = verified.get(real);
            if (known == null) {
                throw new IllegalStateException("This update file was not verified by this run of the app");
            }
            if (Files.size(real) != known.size() || !UpdateFiles.sha256(real).equals(known.sha256())) {
                throw new IllegalStateException("The update file changed after it was verified");
            }
            var previous = prepared;
            if (previous != null) {
                discard(previous);
            }
            var result = new PreparedUpdate(chosen.prepare(real, known.sha256(), detected), real);
            prepared = result;
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare the update: " + e.getMessage(), e);
        }
    }

    /** The install starts once this process has exited; the caller must quit right after. */
    public void launchAfterExit(PreparedUpdate update) {
        var detected = installation().orElseThrow(() -> new IllegalStateException("The app was not started from an installed package"));
        var chosen = updater().orElseThrow(() -> new IllegalStateException("This installation cannot be updated automatically"));
        if (!update.equals(prepared)) {
            throw new IllegalStateException("This update was not prepared by this run of the app");
        }
        chosen.launchAfterExit(update.path(), detected);
    }

    /** Removes what [prepareInstall] staged (the verified download stays for a retry); what lies outside the folders updaters stage in is logged and left. */
    public void discard(PreparedUpdate update) {
        if (update.equals(prepared)) {
            prepared = null;
        }
        updater().ifPresent(chosen -> chosen.discard(update.path()));
        var path = canonical(update.path());
        if (path.equals(canonical(update.download()))) {
            return;
        }
        var target = stagingRootOf(path);
        if (target == null) {
            log.warn("The staged update is outside the folders the app cleans and was left in place");
            return;
        }
        try {
            UpdateFiles.deleteTree(target);
        } catch (IOException e) {
            log.warn("Could not remove the staged update: {}", e.getClass().getSimpleName());
        }
    }

    /** The staged path, or its package folder, when it lies where an updater stages: the updates folder, beside the app, or a package temp folder. */
    private Path stagingRootOf(Path path) {
        var updates = canonical(app.dataDir().resolve(UPDATES_DIRECTORY));
        var parent = path.getParent();
        if (path.startsWith(updates) && !path.equals(updates)) {
            return path;
        }
        var besideApp = parent != null && installation().map(found -> canonical(found.root()).getParent()).filter(parent::equals).isPresent();
        if (besideApp && path.getFileName().toString().endsWith(STAGING_SUFFIX)) {
            return path;
        }
        return parent != null && parent.getFileName() != null && parent.getFileName().toString().startsWith(PACKAGE_STAGING_PREFIX) ? parent : null;
    }

    /** Normalized, with the existing parent resolved through symlinks; the last element is never followed (a link is deleted itself). */
    private static Path canonical(Path path) {
        var normal = path.toAbsolutePath().normalize();
        var parent = normal.getParent();
        if (parent == null || normal.getFileName() == null) {
            return normal;
        }
        try {
            return parent.toRealPath().resolve(normal.getFileName());
        } catch (IOException e) {
            return normal;
        }
    }

    private void remember(Path file, String expected) throws IOException {
        var hash = UpdateFiles.sha256(file);
        if (!hash.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("The update file changed while it was verified");
        }
        verified.clear();
        verified.put(file.toRealPath(), new Verified(hash, Files.size(file)));
    }

    private Optional<PlatformUpdater> updater() {
        return installation().flatMap(detected -> updaters.stream().filter(candidate -> candidate.kind() == detected.kind() && candidate.supported(detected)).findFirst());
    }

    /** The detector keeps a definitive answer itself and asks again after a backoff when the query was inconclusive. */
    private Optional<Installation> installation() {
        return detector.detect();
    }

    private void verifySignature(AvailableUpdate update, Path file) throws IOException {
        var signatureFile = file.resolveSibling(file.getFileName() + SIGNATURE_SUFFIX);
        try {
            try (var body = get(update.signatureUri(), "application/octet-stream").body()) {
                var bytes = body.readNBytes(ReleaseSignature.MAX_SIGNATURE_BYTES + 1);
                if (bytes.length > ReleaseSignature.MAX_SIGNATURE_BYTES) {
                    throw new IllegalStateException("The signature file is too large");
                }
                Files.write(signatureFile, bytes);
            }
            signature().verify(file, signatureFile);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("The update signature could not be verified: " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(signatureFile);
        }
    }

    private ReleaseSignature signature() {
        var known = signature;
        if (known == null) {
            known = signatureSource.get();
            signature = known;
        }
        return known;
    }

    private static ReleaseSignature bundledSignature() {
        try {
            return ReleaseSignature.bundled();
        } catch (IOException e) {
            throw new IllegalStateException("The bundled release key is unusable: " + e.getMessage(), e);
        }
    }

    private String expectedChecksum(AvailableUpdate update) throws IOException {
        try (var body = get(update.checksumUri(), "text/plain").body()) {
            var bytes = body.readNBytes(MAX_CHECKSUM_BYTES + 1);
            if (bytes.length > MAX_CHECKSUM_BYTES) {
                throw new IllegalStateException("The checksum file is too large");
            }
            return new String(bytes, StandardCharsets.UTF_8).strip().split("\\s+")[0];
        }
    }

    private HttpResponse<InputStream> get(URI uri, String accept) throws IOException {
        return downloads.get(uri, accept, Duration.ofSeconds(properties.requestTimeoutSeconds()));
    }

    private Optional<UpdateOutcome> readAndClearOutcome() {
        var file = app.dataDir().resolve(UPDATES_DIRECTORY).resolve(PosixUpdateScript.RESULT_FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            var text = Files.readString(file, StandardCharsets.UTF_8).strip();
            Files.delete(file);
            return parseOutcome(text);
        } catch (IOException e) {
            log.warn("Could not read the update result: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The file is written by a script outside this process, so only the known codes and a plain step name reach the UI. */
    static Optional<UpdateOutcome> parseOutcome(String text) {
        if (text.equals(UpdateOutcome.Status.OK.name())) {
            return Optional.of(new UpdateOutcome(UpdateOutcome.Status.OK, ""));
        }
        if (text.equals(UpdateOutcome.Status.AUTH_CANCELLED.name())) {
            return Optional.of(new UpdateOutcome(UpdateOutcome.Status.AUTH_CANCELLED, ""));
        }
        if (text.startsWith(FAILED_PREFIX)) {
            var step = text.substring(FAILED_PREFIX.length()).strip();
            return Optional.of(new UpdateOutcome(UpdateOutcome.Status.FAILED, STEP_NAME.matcher(step).matches() ? step : ""));
        }
        return Optional.empty();
    }

    /**
     * Only the installers of earlier versions are in the folder, so none of them is needed again; symlinks are removed, never followed. The
     * install logs stay (the failure message points to them) and are only emptied above {@code maxLogBytes}; a leftover that cannot be removed
     * never fails the download.
     */
    private static void deleteOldDownloads(Path directory, long maxLogBytes) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            for (var child : children) {
                try {
                    if (KEPT_LOGS.contains(child.getFileName().toString()) && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.size(child) > maxLogBytes) {
                            Files.newOutputStream(child, StandardOpenOption.TRUNCATE_EXISTING).close();
                        }
                    } else {
                        UpdateFiles.deleteTree(child);
                    }
                } catch (IOException e) {
                    log.warn("Could not remove an old update download: {}", e.getClass().getSimpleName());
                }
            }
        }
    }
}
