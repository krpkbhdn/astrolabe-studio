import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

plugins {
    java
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.github.krpkbhdn"
version = "2.3.35"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.jetbrains.pty4j:pty4j:0.14.0")
    implementation("org.jetbrains.jediterm:jediterm-core:3.76")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:0.41.0-262.10968.63")
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window:0.41.0-262.10968.63")
    implementation("org.jetbrains.runtime:jbr-api:1.9.0")
    implementation("org.graalvm.polyglot:polyglot:25.5.5.0.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    runtimeOnly("org.graalvm.polyglot:js-community:25.5.5.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/** Jewel's decorated window reaches into AWT internals; without these the custom title bar does not drag or resize. */
val awtOpens: List<String> = buildList {
    add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
    add("--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED")
    val os = System.getProperty("os.name").lowercase()
    when {
        "win" in os -> add("--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED")
        "mac" in os -> { add("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"); add("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED") }
        else -> add("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    }
}

/** GraalJS (Code Mode) loads a native library and uses Unsafe; JDK 25 warns about both without these. */
val graalJsArgs = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")

// Explicit override: claude does not count killed short dev runs as fullscreen start failures.
val claudeFullscreen = mapOf("CLAUDE_CODE_NO_FLICKER" to "1")

tasks.test {
    environment(claudeFullscreen)
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
    val slowMillis = 10_000
    // One line per finished class so a long CI run shows progress; classes over slowMillis are marked SLOW.
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ d, r ->
        val ms = r.endTime - r.startTime
        if (d.parent != null && d.className != null) {
            logger.lifecycle("${if (ms > slowMillis) "SLOW " else ""}class ${d.className}: ${r.testCount} tests, ${r.failedTestCount} failed, ${ms / 1000}s")
        }
    }))
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ d, r ->
        val ms = r.endTime - r.startTime
        if (ms > slowMillis) logger.lifecycle("SLOW test ${d.className}.${d.name}: ${ms / 1000}s")
    }))
    // Shorter than the Gradle task timeout so the stack dump below is printed before the build is killed.
    val taskTimeout = Duration.ofMinutes(15)
    val watchdogDelay = Duration.ofMinutes(12)
    timeout.set(taskTimeout)
    if (System.getenv("CI") != null) {
        doFirst {
            Thread({
                Thread.sleep(watchdogDelay.toMillis())
                val jcmd = File(System.getProperty("java.home"), "bin/jcmd").path
                ProcessHandle.current().descendants()
                    .filter { it.info().commandLine().orElse("").contains("Gradle Test Executor") }
                    .forEach { worker ->
                        println("Test worker ${worker.pid()} still running after $watchdogDelay, thread dump:")
                        val dump = ProcessBuilder(jcmd, worker.pid().toString(), "Thread.print").redirectErrorStream(true).start()
                        dump.inputStream.copyTo(System.out)
                    }
            }, "test-watchdog").apply { isDaemon = true }.start()
        }
    }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    // java.awt.event is opened only so the real-browser test can give synthetic key events the native rawCode CEF reads.
    jvmArgs(graalJsArgs + "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED" + awtOpens)
}

val jbrHome: Provider<File> = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }

val verifyJcefRuntime = tasks.register("verifyJcefRuntime") {
    val release = jbrHome.map { File(it, "release") }
    inputs.files(release)
    doLast {
        val file = release.get()
        val text = if (file.isFile) file.readText() else ""
        val isJcefFlavour = Regex("""IMPLEMENTOR_VERSION="[^"]*-jcef"|MODULES="[^"]*jcef""").containsMatchIn(text)
        check(isJcefFlavour) {
            "The build needs JetBrains Runtime with JCEF (jbrsdk_jcef), but the toolchain at ${file.parentFile} is not that flavour " +
                "(its release file has neither an IMPLEMENTOR_VERSION ending in -jcef nor a jcef module). " +
                "Fix: the Gradle toolchain must resolve a JetBrains Runtime with JCEF (Foojay distribution jbrsdk_jcef, JBR 25); " +
                "if an older plain JBR was cached, delete ~/.gradle/jdks/jetbrains* and run the build again."
        }
    }
}
tasks.matching { it.name == "compileKotlin" || it.name == "compileTestKotlin" }.configureEach { dependsOn(verifyJcefRuntime) }

// A linked worktree has a .git file (a clone has a directory); its runs must not touch the main installation's data.
val isLinkedWorktree = layout.projectDirectory.file(".git").asFile.isFile
val worktreeDataDir = File(System.getProperty("java.io.tmpdir"), "astrolabe-studio-worktrees/${projectDir.name}").absolutePath

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    if (isLinkedWorktree && System.getenv("ASTROLABE_DATA_DIR") == null) {
        environment("ASTROLABE_DATA_DIR", worktreeDataDir)
        logger.lifecycle("Worktree detected: data dir $worktreeDataDir")
    }
}

// Tests, previews and shots get their own home, data dir and Claude config dir under build/, so they never touch ~/.astrolabe-studio or ~/.claude.
// Opt out with ASTROLABE_UNSANDBOXED, ASTROLABE_REAL_CLAUDE or ASTROLABE_SMOKE (those need the real claude login); runPreview --real keeps the real
// claude config but gets a fresh data dir under build/.
val sandboxHome = layout.buildDirectory.dir("sandbox-home").get().asFile.absolutePath
val sandboxOptOuts = listOf("ASTROLABE_UNSANDBOXED", "ASTROLABE_REAL_CLAUDE", "ASTROLABE_SMOKE")
val sandboxed = provider { sandboxOptOuts.none { providers.environmentVariable(it).isPresent } }
val sandboxedTasks = setOf("runPreview", "gitShots", "reviewShots", "webShots", "ideShots", "activityShots")

tasks.withType<Test>().configureEach {
    if (sandboxed.get()) {
        systemProperty("user.home", sandboxHome)
        environment("ASTROLABE_DATA_DIR", "$sandboxHome/.astrolabe-studio")
        environment("CLAUDE_CONFIG_DIR", "$sandboxHome/.claude")
    }
}

tasks.register<JavaExec>("runPreview") {
    group = "application"
    description = "Boots the UI with fake services (flags: --light, --shots, --out=<dir>, --review sample visual review, --web real browser on the demo page)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.PreviewMainKt"
    environment(claudeFullscreen)
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.register<JavaExec>("gitShots") {
    group = "application"
    description = "Renders the Git tab offscreen to PNGs (flags: --out=<dir> --only=<names> --dark|--light --size=WxH)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.git.GitShotsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.register<JavaExec>("reviewShots") {
    group = "application"
    description = "Renders the Review screen offscreen to PNGs (flags: --out=<dir> --dark|--light --size=WxH)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.review.ReviewShotsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.register<JavaExec>("ideShots") {
    group = "application"
    description = "Renders the IDE split button and its menu offscreen to PNGs (flags: --out=<dir> --dark|--light)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.ide.IdeShotsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.register<JavaExec>("activityShots") {
    group = "application"
    description = "Renders the Activity empty state offscreen to PNGs (flags: --out=<dir> --dark|--light)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.screens.ActivityShotsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.register<JavaExec>("webShots") {
    group = "application"
    description = "Renders the Web review screen offscreen to PNGs (flags: --out=<dir> --dark|--light --size=WxH)"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.web.WebShotsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--enable-native-access=ALL-UNNAMED", *awtOpens.toTypedArray())
}

tasks.withType<JavaExec>().matching { it.name in sandboxedTasks }.configureEach {
    if (sandboxed.get()) {
        systemProperty("user.home", sandboxHome)
        environment("ASTROLABE_DATA_DIR", "$sandboxHome/.astrolabe-studio")
        environment("CLAUDE_CONFIG_DIR", "$sandboxHome/.claude")
    }
}

tasks.named<JavaExec>("runPreview") {
    doFirst {
        if (args.orEmpty().contains("--real")) {
            systemProperties.remove("user.home")
            val ownConfigDir = System.getenv("CLAUDE_CONFIG_DIR")
            if (ownConfigDir != null) environment("CLAUDE_CONFIG_DIR", ownConfigDir) else environment.remove("CLAUDE_CONFIG_DIR")
            environment("ASTROLABE_DATA_DIR", layout.buildDirectory.dir("preview-real-data").get().asFile.absolutePath)
        }
    }
}

// The jdtls output-redirect OSGi bundle compiles against the plugin jars of the pinned jdtls archive from tools.json
// (jdtls jars are Java 25 bytecode, so javac 25 reads them while the bundle targets release 21).
val jdtlsBundle = sourceSets.create("jdtlsBundle")

val jdtlsPinned: Map<String, String> by lazy {
    val tools = groovy.json.JsonSlurper().parse(file("src/main/resources/toolchain/tools.json")) as Map<*, *>
    val jdtls = (tools["tools"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "jdtls" }
    fun pinnedOf(os: String) = (((jdtls["download"] as Map<*, *>)[os] as Map<*, *>)["pinned"] as? Map<*, *>)
        ?: error("tools.json: jdtls has no download.$os.pinned (url, sha256)")
    val pinned = pinnedOf("windows")
    val other = pinnedOf("linux")
    check(pinned["url"] == other["url"] && pinned["sha256"] == other["sha256"]) { "tools.json: jdtls pinned differs between windows and linux" }
    mapOf("url" to pinned["url"] as String, "sha256" to (pinned["sha256"] as String).lowercase())
}
val jdtlsArchiveCache = gradle.gradleUserHomeDir.resolve("astrolabe-jdtls")

val downloadJdtlsArchive = tasks.register("downloadJdtlsArchive") {
    val toolsJson = file("src/main/resources/toolchain/tools.json")
    inputs.file(toolsJson)
    outputs.file(provider { File(jdtlsArchiveCache, "${jdtlsPinned.getValue("sha256")}.tar.gz") })
    doLast {
        val url = jdtlsPinned.getValue("url")
        val sha256 = jdtlsPinned.getValue("sha256")
        check(url.startsWith("https://")) { "jdtls pinned url must be https: $url" }
        fun sha256Of(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val archive = File(jdtlsArchiveCache, "$sha256.tar.gz")
        if (archive.isFile && sha256Of(archive) == sha256) return@doLast
        jdtlsArchiveCache.mkdirs()
        val partial = File.createTempFile("jdtls-", ".partial", jdtlsArchiveCache)
        try {
            URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256Of(partial)
            check(actual == sha256) { "jdtls archive SHA-256 mismatch: expected $sha256 but got $actual ($url)" }
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }
}

// Only plugins/*.jar, flattened to the file name, so nothing in the untrusted archive can land outside the target folder.
val fetchJdtlsForBundle = tasks.register<Copy>("fetchJdtlsForBundle") {
    dependsOn(downloadJdtlsArchive)
    from(provider { tarTree(resources.gzip(File(jdtlsArchiveCache, "${jdtlsPinned.getValue("sha256")}.tar.gz"))) }) {
        include("plugins/*.jar")
        eachFile { relativePath = RelativePath(true, name) }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("jdtls-plugins"))
}

dependencies {
    "jdtlsBundleCompileOnly"(fileTree(layout.buildDirectory.dir("jdtls-plugins")) { include("*.jar"); builtBy(fetchJdtlsForBundle) })
}

tasks.named<JavaCompile>("compileJdtlsBundleJava") {
    options.release = 21
}

val jdtlsBundleJar = tasks.register<Jar>("jdtlsBundleJar") {
    from(jdtlsBundle.output) { exclude("META-INF/MANIFEST.MF", "composeResources/**") }
    manifest { from(file("src/jdtlsBundle/resources/META-INF/MANIFEST.MF")) }
    archiveFileName = "dev.astrolabe.jdtls.output.jar"
    destinationDirectory = layout.buildDirectory.dir("jdtls-bundle")
}

tasks.processResources {
    from(jdtlsBundleJar) { into("toolchain/jdtls") }
}

compose.desktop {
    application {
        mainClass = "com.github.krpkbhdn.astrolabe.ui.MainKt"
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
        jvmArgs += graalJsArgs + "-Dastrolabe.update.version=$version" + awtOpens
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Astrolabe Studio"
            packageVersion = version.toString()
            vendor = "krpkbhdn"
            // Spring reflects over many JDK modules; a trimmed jlink image breaks it at runtime.
            includeAllModules = true
            linux { iconFile.set(file("packaging/icon.png")) }
            macOS {
                iconFile.set(file("packaging/icon.icns"))
                // Only effective once signing is enabled (macOS { signing { sign.set(true) } }); unsigned builds ignore the entitlements.
                entitlementsFile.set(file("packaging/entitlements.plist"))
                runtimeEntitlementsFile.set(file("packaging/entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Astrolabe Studio uses the microphone for voice dictation.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(file("packaging/icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                menuGroup = "Astrolabe Studio"
                upgradeUuid = "490ea982-606a-4d2e-9037-6d74c6b2c0b1"
            }
        }
    }
}

val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX

// The jlink app image has no JCEF; copy it from the JBR (cp -a keeps the framework symlinks) and re-sign ad hoc.
val addJcefToAppImage = tasks.register("addJcefToAppImage") {
    onlyIf { isMac }
    dependsOn("createDistributable")
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    val jbr = jbrHome
    doLast {
        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
        }
        val app = appDir.get().asFile.listFiles { f -> f.name.endsWith(".app") }?.singleOrNull()
        checkNotNull(app) { "No single .app image found in ${appDir.get().asFile}; createDistributable did not produce one." }
        val runtime = File(app, "Contents/runtime/Contents")
        val home = jbr.get()
        val frameworks = File(home.parentFile, "Frameworks")
        check(frameworks.isDirectory) { "The JBR at $home has no Frameworks folder; JCEF cannot be copied." }
        File(runtime, "Frameworks").deleteRecursively()
        run("cp", "-a", frameworks.absolutePath, File(runtime, "Frameworks").absolutePath)
        File(home, "lib").listFiles { f -> f.name.contains("jcef") || f.name.contains("cef") }.orEmpty().forEach {
            run("cp", "-a", it.absolutePath, File(runtime, "Home/lib").absolutePath + "/")
        }
        val helper = File(runtime, "Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper")
        val serverHelper = File(runtime, "Frameworks/cef_server.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper")
        check(helper.isFile || serverHelper.isFile) { "jcef Helper is missing in $runtime/Frameworks after copying from $frameworks." }
        if (File("/usr/bin/codesign").isFile) run("/usr/bin/codesign", "--force", "--deep", "-s", "-", app.absolutePath)
    }
}
if (isMac) tasks.matching { it.name == "packageDmg" }.configureEach { dependsOn(addJcefToAppImage) }

tasks.register<JavaExec>("generateIcons") {
    group = "application"
    description = "Renders the app icon into packaging/icon.ico, icon.icns and icon.png used by the installers"
    mainClass = "com.github.krpkbhdn.astrolabe.ui.GenerateIconsKt"
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    systemProperty("java.awt.headless", "true")
}
