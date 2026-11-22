package com.github.krpkbhdn.astrolabe.git;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.github.krpkbhdn.astrolabe.git.GitWatchPaths.Zone;
import com.sun.nio.file.ExtendedWatchEventModifier;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tells the UI when a repository changed under it (commits, checkouts, staging by Claude or an IDE): watches the .git
 * directory (HEAD, index, refs, merge/rebase state files) and the working tree, debounced.
 *
 * <p>One daemon thread serves every repository; a repository is watched while it has subscribers. Registering a
 * directory is costly on Windows, so a tree with more than {@code maxWatchedDirectories} directories (build output and
 * dependency folders are skipped) is watched at its root only, plus .git. Directories are not filtered by .gitignore.
 */
@Component
public class GitWatcher {

    private static final Logger log = LoggerFactory.getLogger(GitWatcher.class);

    private record Registration(Watch watch, Zone zone) {}

    private final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private final GitRunner git;
    private final Duration debounce;
    private final Object lock = new Object();
    private final Map<Path, Watch> watches = new HashMap<>();
    private final Map<WatchKey, Registration> registrations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("git-watcher-debounce").unstarted(runnable));
    private final ExecutorService callbacks = Executors.newVirtualThreadPerTaskExecutor();
    private WatchService service;

    @Autowired
    public GitWatcher(GitRunner git) {
        this.git = git;
        this.debounce = git.properties().watchDebounce();
    }

    /** @return idempotent unsubscribe handle; the callback runs on an arbitrary thread, at most a few times per second */
    public Runnable subscribe(Path repo, Runnable onChange) {
        var root = repo.toAbsolutePath().normalize();
        var listener = new Listener(onChange);
        List<Path> gitDirectories = null;
        while (!addListener(root, listener, gitDirectories)) {
            gitDirectories = resolveGitDirectories(root);
        }
        var unsubscribed = new AtomicBoolean();
        return () -> {
            if (unsubscribed.compareAndSet(false, true)) {
                unsubscribe(root, listener);
            }
        };
    }

    /** @return false when the root is not watched yet and {@code gitDirectories} (resolved outside the lock) is still null */
    private boolean addListener(Path root, Listener listener, List<Path> gitDirectories) {
        synchronized (lock) {
            var watch = watches.get(root);
            if (watch == null && gitDirectories != null) {
                watch = open(root, gitDirectories);
                watches.put(root, watch);
            }
            if (watch == null) {
                return false;
            }
            watch.listeners.add(listener);
            return true;
        }
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            watches.values().forEach(Watch::cancelKeys);
            watches.clear();
            stopService();
        }
        debouncer.shutdownNow();
        callbacks.shutdownNow();
    }

    private Watch open(Path root, List<Path> gitDirectories) {
        startService();
        var watch = new Watch(root);
        gitDirectories.forEach(gitDir -> {
            register(watch, gitDir, Zone.GIT_ROOT);
            registerTree(watch, gitDir.resolve("refs"), Zone.GIT_REFS, Integer.MAX_VALUE);
        });
        if (windows && registerFileTree(watch, root)) {
            return watch;
        }
        if (!registerTree(watch, root, Zone.TREE, git.properties().maxWatchedDirectories())) {
            watch.rootOnly = true;
            register(watch, root, Zone.TREE);
        }
        return watch;
    }

    /** One recursive handle for the whole tree: a handle per directory would block renaming or moving folders on Windows. */
    private boolean registerFileTree(Watch watch, Path root) {
        try {
            var key = root.register(service, new WatchEvent.Kind<?>[] {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY}, ExtendedWatchEventModifier.FILE_TREE);
            registrations.put(key, new Registration(watch, Zone.TREE));
            watch.keys.add(key);
            watch.fileTree = true;
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("recursive watch of {} unavailable: {}", root, e.getMessage());
            return false;
        }
    }

    /** The repository's git directory and, in a linked worktree, the common one that holds branch and remote refs. */
    private List<Path> resolveGitDirectories(Path root) {
        try {
            var result = git.tryRun(root, "rev-parse", "--absolute-git-dir", "--git-common-dir");
            return result.ok() ? result.stdout().lines().map(root::resolve).map(Path::normalize).distinct().toList() : List.of();
        } catch (GitException e) {
            return List.of();
        }
    }

    private void unsubscribe(Path root, Listener listener) {
        synchronized (lock) {
            var watch = watches.get(root);
            if (watch == null) {
                return;
            }
            watch.listeners.remove(listener);
            if (watch.listeners.isEmpty()) {
                watches.remove(root);
                watch.cancelKeys();
            }
            if (watches.isEmpty()) {
                stopService();
            }
        }
    }

    private void startService() {
        if (service != null) {
            return;
        }
        try {
            var created = FileSystems.getDefault().newWatchService();
            service = created;
            Thread.ofPlatform().daemon().name("git-watcher").start(() -> dispatch(created));
        } catch (IOException e) {
            throw new GitException(GitException.Kind.OTHER, "Cannot watch the file system", String.valueOf(e.getMessage()));
        }
    }

    private void stopService() {
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (IOException e) {
            log.debug("closing watch service failed: {}", e.getMessage());
        }
        service = null;
        registrations.clear();
    }

    private void dispatch(WatchService watchService) {
        try {
            while (true) {
                handle(watchService.take());
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            log.debug("git watcher stopped");
        }
    }

    private void handle(WatchKey key) {
        var registration = registrations.get(key);
        if (registration == null) {
            key.cancel();
            return;
        }
        var changed = false;
        for (var event : key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                changed = true;
            } else if (GitWatchPaths.isRelevant(registration.zone(), (Path) event.context())) {
                var path = ((Path) key.watchable()).resolve((Path) event.context());
                if (!GitWatchPaths.isDirectoryTimestampNoise(registration.zone(), event.kind(), path)) {
                    changed = true;
                }
                onCreated(registration, path, event.kind());
            }
        }
        if (!key.reset()) {
            registrations.remove(key);
            registration.watch().keys.remove(key);
        }
        if (changed) {
            registration.watch().changed();
        }
    }

    /** Lets tests wait until a new folder is registered instead of sleeping. */
    boolean isWatching(Path directory) {
        return registrations.keySet().stream().anyMatch(key -> isSameDirectory((Path) key.watchable(), directory));
    }

    private static boolean isSameDirectory(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            return false;
        }
    }

    private void onCreated(Registration registration, Path path, WatchEvent.Kind<?> kind) {
        if (kind != ENTRY_CREATE || registration.zone() == Zone.GIT_ROOT || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        synchronized (lock) {
            if (service != null && !registration.watch().rootOnly && !(registration.watch().fileTree && registration.zone() == Zone.TREE) && registration.watch().isActive()) {
                var limit = registration.zone() == Zone.TREE ? git.properties().maxWatchedDirectories() - registration.watch().treeKeyCount() : Integer.MAX_VALUE;
                registerTree(registration.watch(), path, registration.zone(), limit);
            }
        }
    }

    /** @return false when the tree has more than {@code limit} directories (nothing is registered then) */
    private boolean registerTree(Watch watch, Path start, Zone zone, int limit) {
        var registered = new HashSet<Path>();
        while (true) {
            var directories = GitWatchPaths.walkDirectories(start, zone, limit);
            if (directories.size() > limit) {
                return false;
            }
            directories.removeIf(registered::contains);
            if (directories.isEmpty()) {
                return true;
            }
            directories.forEach(directory -> register(watch, directory, zone));
            registered.addAll(directories);
            // folders created while registering produce no event, so walk again until nothing new shows up
        }
    }

    private void register(Watch watch, Path directory, Zone zone) {
        try {
            var key = directory.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            registrations.put(key, new Registration(watch, zone));
            watch.keys.add(key);
        } catch (IOException | ClosedWatchServiceException e) {
            log.debug("cannot watch {}: {}", directory, e.getMessage());
        }
    }

    private record Listener(Runnable action) {}

    private final class Watch {
        final Path root;
        final List<Listener> listeners = new CopyOnWriteArrayList<>();
        final List<WatchKey> keys = new CopyOnWriteArrayList<>();
        private final AtomicBoolean pending = new AtomicBoolean();
        volatile boolean rootOnly;
        volatile boolean fileTree;

        Watch(Path root) {
            this.root = root;
        }

        boolean isActive() {
            return !listeners.isEmpty();
        }

        int treeKeyCount() {
            return (int) keys.stream().filter(key -> registrations.get(key) != null && registrations.get(key).zone() == Zone.TREE).count();
        }

        void changed() {
            if (pending.compareAndSet(false, true)) {
                try {
                    debouncer.schedule(this::fire, debounce.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    pending.set(false);
                }
            }
        }

        private void fire() {
            pending.set(false);
            for (var listener : listeners) {
                callbacks.execute(() -> notify(listener));
            }
        }

        private void notify(Listener listener) {
            try {
                listener.action().run();
            } catch (RuntimeException e) {
                log.warn("git change listener for {} failed: {}", root, e.getMessage());
            }
        }

        void cancelKeys() {
            keys.forEach(key -> {
                key.cancel();
                registrations.remove(key);
            });
            keys.clear();
        }
    }
}
