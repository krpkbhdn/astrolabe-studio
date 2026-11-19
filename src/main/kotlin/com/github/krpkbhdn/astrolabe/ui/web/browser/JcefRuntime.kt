package com.github.krpkbhdn.astrolabe.ui.web.browser

import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface Availability {
    data object Ready : Availability
    data class Unsupported(val reason: String) : Availability
}

/** One CEF per process: started lazily on the first browser, disposed once at shutdown. Needs the JBR flavour with JCEF. */
object JcefRuntime {
    private const val INIT_TIMEOUT_SECONDS = 30L
    private const val POLL_MILLIS = 50L
    private const val CEF_CLASS = "org.cef.CefApp"
    private const val SERVER_NAME = "cef_server"
    private const val KILL_WAIT_MILLIS = 2_000L
    private val servers: MutableSet<ProcessHandle> = ConcurrentHashMap.newKeySet()
    private val hookRegistered = AtomicBoolean(false)
    @Volatile private var app: CefApp? = null
    private var keepAlive: CefClient? = null

    fun availability(): Availability =
        availability(Path.of(System.getProperty("java.home")), System.getProperty("os.name").lowercase(), cefClassPresent())

    internal fun availability(javaHome: Path, osName: String, cefPresent: Boolean): Availability = when {
        !cefPresent -> Availability.Unsupported("This Java runtime has no embedded browser (JetBrains Runtime with JCEF is required)")
        helperExecutable(javaHome, osName) == null ->
            Availability.Unsupported("The browser helper (jcef_helper) was not found in the Java runtime at $javaHome")
        serverExecutable(javaHome, osName) == null ->
            Availability.Unsupported("The browser server (cef_server) was not found in the Java runtime at $javaHome")
        else -> Availability.Ready
    }

    /** The separate process that hosts CEF in remote mode (file or app bundle), or null if it is not there. */
    internal fun serverExecutable(javaHome: Path, osName: String): Path? = when {
        "win" in osName -> javaHome.resolve("bin/cef_server.exe")
        "mac" in osName -> javaHome.resolve("../Frameworks/cef_server.app").normalize()
        else -> javaHome.resolve("lib/cef_server")
    }.takeIf { Files.exists(it) }

    /** Where the browser's child-process executable lives in the JBR layout of [osName], or null if it is not there. */
    internal fun helperExecutable(javaHome: Path, osName: String): Path? {
        val candidates = when {
            "win" in osName -> listOf(javaHome.resolve("bin/jcef_helper.exe"))
            "mac" in osName -> listOf(
                javaHome.resolve("../Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper").normalize(),
                javaHome.resolve("../Frameworks/cef_server.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper").normalize(),
            )
            else -> listOf(javaHome.resolve("lib/jcef_helper"), javaHome.resolve("bin/jcef_helper"))
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    @Synchronized
    fun start(dataDir: Path): CefApp {
        app?.let { return it }
        val unsupported = availability() as? Availability.Unsupported
        check(unsupported == null) { unsupported!!.reason }
        val helper = helperExecutable(Path.of(System.getProperty("java.home")), System.getProperty("os.name").lowercase())!!
        Files.createDirectories(dataDir)
        val settings = CefSettings().apply {
            windowless_rendering_enabled = true
            cache_path = dataDir.resolve("jcef-cache").toAbsolutePath().toString()
            log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            // Page console text and URLs end up in this log, so it lives in the app's data dir, not the working directory.
            log_file = dataDir.resolve("jcef.log").toAbsolutePath().toString()
            // The JBR helper runs unsandboxed: accepted risk, pages are the user's own projects.
            no_sandbox = true
            // Without it CEF re-launches the java executable as its child processes and they die on "--type=...".
            browser_subprocess_path = helper.toString()
        }
        // In-process libcef cannot load on Linux (static TLS), so CEF always runs in the separate cef_server process.
        CefApp.setIsRemoteEnabled(true)
        CefApp.startup(emptyArray())
        if (hookRegistered.compareAndSet(false, true)) Runtime.getRuntime().addShutdownHook(Thread(::killServers, "jcef-kill-servers"))
        val created = CefApp.getInstance(emptyArray(), settings, null)
        try {
            snapshotServers()
            awaitInitialized(CefApp::getState, TimeUnit.SECONDS.toMillis(INIT_TIMEOUT_SECONDS))
            // CEF terminates the app when its last client is disposed; this one keeps it usable for the next browser.
            keepAlive = created.createClient()
        } catch (e: Throwable) {
            // A retry must not find a second server next to a half-started one.
            runCatching { created.dispose() }
            killServers()
            throw e
        }
        snapshotServers()
        return created.also { app = it }
    }

    /** Remembers our cef_server processes while the tree is intact: once the root dies its children are re-parented and invisible. */
    private fun snapshotServers() {
        ProcessHandle.current().descendants().filter(::isCefServer).forEach(servers::add)
    }

    private fun isCefServer(p: ProcessHandle) = p.info().command().map { it.contains(SERVER_NAME, ignoreCase = true) }.orElse(false)

    /** CefApp.dispose() in remote mode leaves the server tree running, so end it here; only processes seen as our own descendants. */
    private fun killServers() {
        snapshotServers()
        val victims = servers.filter { it.isAlive }
        victims.forEach { it.destroy() }
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KILL_WAIT_MILLIS)
        while (victims.any { it.isAlive } && System.nanoTime() < end) Thread.sleep(POLL_MILLIS)
        victims.filter { it.isAlive }.forEach { it.destroyForcibly() }
        servers.clear()
    }

    /** Polls the state so a failed start (the app is TERMINATED) is reported at once instead of after the whole timeout. */
    internal fun awaitInitialized(state: () -> CefApp.CefAppState, timeoutMillis: Long, pollMillis: Long = POLL_MILLIS) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            when (state()) {
                CefApp.CefAppState.INITIALIZED -> return
                CefApp.CefAppState.TERMINATED, CefApp.CefAppState.SHUTTING_DOWN -> error("JCEF failed to start")
                else -> check(System.nanoTime() < end) { "JCEF did not initialize" }
            }
            Thread.sleep(pollMillis)
        }
    }

    @Synchronized
    fun shutdown() {
        snapshotServers()
        runCatching { keepAlive?.dispose() }
        keepAlive = null
        runCatching { app?.dispose() }
        app = null
        killServers()
    }

    private fun cefClassPresent() = try {
        Class.forName(CEF_CLASS, false, JcefRuntime::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
