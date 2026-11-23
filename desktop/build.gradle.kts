import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

// DarkProof for desktop computers (Windows first). Run it with: ./gradlew -Pdesktop :desktop:run
//
// The parts of the Android app that use no Android code are compiled here as they are, straight from
// app/src/main/java, so the phone and the desktop run the same packet, encryption and forwarding code. Nothing is
// copied and no Android file is moved. Desktop-only code lives in src/main/kotlin/it/blackout/desktop.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose") version "1.12.2" // Compose Multiplatform: the Android screens' toolkit, on a desktop JVM
}

// Files of the Android app that the desktop compiles as they are. Most use no Android at all. The engine and the files
// around it (MeshEngine, NostrLink, Groups, EncKeys) call a few Android classes, which the desktop answers with
// stand-ins under Android's names (src/main/java/android) and with its own versions of the phone-only objects they
// name (src/main/kotlin/it/blackout/desktop/standin).
val shared = listOf(
    "About", "App", "AppState", "AsciiBackground", "BleProtocol", "Contact", "Core", "Dials", "EncKeys", "Fingerprint", "Groups", "Help", "HighRisk", "Hpke",
    "Joins", "Journey", "LockRules", "MeshCore", "MeshEngine", "MessageKeys", "Meshtastic", "Moderation", "Nostr", "NostrLink", "Notices", "P256",
    "PersonCode", "PlusCode", "Protocol", "QuickCheckIn", "Quiet", "Ready", "Replies", "Schnorr", "ScreenRules", "Screens", "Scrypt", "Security", "Socks5",
    "Times", "Ui", "VoiceNotes", "VoiceViews", "WifiLinks", "WordFilter",
)

// The Android app's unit tests: all of them run again here, on a desktop JVM with no Android.
val sharedTests = listOf(
    "AboutTest", "AppLockFixTest", "ArrivalsTest", "AuthorshipTest", "BleProtocolTest", "CheckInExtrasTest", "CoreTest", "DoorStoreFixTest",
    "FaultTest", "FirstCopyTest", "FormatVectorsTest", "HighRiskTest", "HpkeTest", "HybridTest", "InviteFixTest", "JourneyTest", "KeyChangesTest",
    "KeyringTest", "LeaveTest", "LeaveVectorsTest", "LinkLimitsFixTest", "MeshCoreTest", "MessageKeysTest", "MiscFixTest", "NostrTest",
    "PaddingTest", "PersonCodeTest", "PlusCodeTest", "PrivacyTest", "ProtocolTest", "QuietVectorsTest", "RekeyTest", "RelayAttackTest", "RelayIntakeTest",
    "RelayLimitsFixTest", "RelayLinksTest", "RepliesVectorsTest", "SchnorrTest", "ScryptTest", "SecurityWordsTest", "SharedVectorsTest", "StoredDataFixTest",
    "TestSigning", "TimerTest", "TimerVectorsTest", "VoiceNotesTest", "VoiceVectorsTest", "WifiLinksTest", "WordFilterTest",
)

// The phone's pictures (app/src/main/res/drawable*) go on the desktop's classpath as they are, and the build writes an
// R like Android's that names them, so the phone's screens find their icons under the names they already use.
val phoneRes = layout.projectDirectory.dir("../app/src/main/res")
val generateR = tasks.register("generateR") {
    val dirs = listOf("drawable", "drawable-nodpi").map { phoneRes.dir(it) }
    val out = layout.buildDirectory.dir("generated/r")
    inputs.files(dirs); outputs.dir(out)
    doLast {
        val files = dirs.flatMap { d -> d.asFile.listFiles().orEmpty().filter { it.isFile }.map { "${d.asFile.name}/${it.name}" } }.sortedBy { it.substringAfter('/') }
        val f = out.get().file("it/blackout/desktop/standin/R.kt").asFile; f.parentFile.mkdirs()
        f.writeText(buildString {
            appendLine("package it.blackout.mesh"); appendLine()
            appendLine("/** Written by the build from app/src/main/res: the names Android's R gives the phone's pictures, and where each one is. */")
            appendLine("object R {")
            appendLine("    object drawable {")
            files.forEachIndexed { i, p -> appendLine("        const val ${p.substringAfter('/').substringBeforeLast('.')} = ${i + 1}") }
            appendLine("    }")
            appendLine("    private val paths = arrayOf(" + files.joinToString(", ") { "\"$it\"" } + ")")
            appendLine("    fun path(id: Int): String = paths[id - 1]")
            appendLine("}")
        })
    }
}
sourceSets.named("main") { resources { srcDir(phoneRes); include("drawable/**", "drawable-nodpi/**", "icon.png") } } // icon.png: the desktop's own, see icons/MakeIcon.java

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets {
        named("main") {
            kotlin.srcDir(generateR)
            kotlin.srcDir("../app/src/main/java")
            kotlin.include(shared.map { "it/blackout/mesh/$it.kt" } + "it/blackout/desktop/**")
        }
        named("test") {
            kotlin.srcDir("../app/src/test/java")
            kotlin.include(sharedTests.map { "it/blackout/mesh/$it.kt" } + "it/blackout/desktop/**")
        }
    }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// The Compose build for the computer this runs on. Written out instead of the plugin's `compose.desktop.currentOs`,
// whose sibling aliases (`compose.ui` and so on) are deprecated since Compose Multiplatform 1.10.
val composeTarget = run {
    val os = System.getProperty("os.name").lowercase(); val arch = System.getProperty("os.arch").lowercase()
    val name = when { "mac" in os -> "macos"; "win" in os -> "windows"; else -> "linux" }
    "$name-${if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"}"
}

dependencies {
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$composeTarget:1.12.2")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03") // the Material 3 that Compose Multiplatform 1.12.2 lists
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2") // same version as the Android app
    implementation("org.json:json:20240303") // Android has org.json built in; a desktop JVM does not
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // relay WebSockets, same version as the Android app
    implementation("org.xerial:sqlite-jdbc:3.54.1.0") // SQLite for the message store (Android has its own); Apache 2.0, Windows, macOS and Linux builds inside
    implementation("com.google.zxing:core:3.5.3") // invite QR codes, same version as the Android app
    testImplementation("junit:junit:4.13.2")
}

// A failed test says why in the build's own output: on a build machine there is nothing else to look at.
tasks.withType<Test>().configureEach {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// The AI engine: llama.cpp's own server program (MIT), one pinned build from its authors' releases, checked by SHA-256
// before it is unpacked. It is code from outside, so it is fetched only when asked for with -PaiEngine (the builds on
// GitHub ask). Without it the app builds and runs, and its Ask page says this copy has no AI engine.
val aiEngineBuild = "b12590"
val aiEngines = mapOf( // the computer it is for, to: the folder it gets in the app, the file's name, its SHA-256
    "windows-x64" to listOf(
        Triple("llama", "llama-$aiEngineBuild-bin-win-cpu-x64.zip", "e895687489ceed01770fd5101ac377ad77ed41b5b2b7dafc8e1e1bfca613ba62"), // runs on every processor
        Triple("llama-gpu", "llama-$aiEngineBuild-bin-win-vulkan-x64.zip", "4f11b151fb05e7e0b068893094e8db2406f9a258b3018020d2167c21322803b4"), // uses the graphics card, where it can
    ),
    "macos-arm64" to listOf(Triple("llama", "llama-$aiEngineBuild-bin-macos-arm64.tar.gz", "c0961e9c1ee87fb17357825a8f4a0a294ce7334093e1295917f71f17977d5ad6")), // uses the Mac's graphics chip by itself
)
val engineRoot = layout.projectDirectory.dir("engine") // engine/<computer>/<folder> goes into the app as resources/<folder>: the AI engine, and Tor
val fetchAiEngine = tasks.register("fetchAiEngine") {
    val wanted = providers.gradleProperty("aiEngine").isPresent
    val picks = aiEngines[composeTarget].orEmpty()
    val target = engineRoot.dir(composeTarget).asFile
    val work = layout.buildDirectory.dir("ai-engine").get().asFile
    onlyIf { wanted && picks.isNotEmpty() }
    doLast {
        for ((folder, name, sha) in picks) {
            val archive = File(work, name)
            if (!archive.isFile || sha256(archive) != sha) {
                work.mkdirs()
                uri("https://github.com/ggml-org/llama.cpp/releases/download/$aiEngineBuild/$name").toURL().openStream().use { i -> archive.outputStream().use { i.copyTo(it) } }
            }
            check(sha256(archive) == sha) { "$name is not the file that was pinned (SHA-256 ${sha256(archive)})" }
            val unpacked = File(work, "unpacked-$folder"); unpacked.deleteRecursively(); unpacked.mkdirs()
            if (name.endsWith(".zip")) { // read here: what "tar" is on a Windows computer depends on what else is installed
                ZipInputStream(archive.inputStream()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val out = File(unpacked, e.name).canonicalFile
                        check(out.path.startsWith(unpacked.canonicalPath + File.separator)) { "$name holds a file that would land outside its folder: ${e.name}" }
                        if (e.isDirectory) out.mkdirs() else { out.parentFile.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                    }
                }
            } else {
                val tar = ProcessBuilder("tar", "-xzf", archive.path, "-C", unpacked.path).inheritIO().start()
                check(tar.waitFor() == 0) { "could not unpack $name" }
            }
            val server = unpacked.walkTopDown().firstOrNull { it.name == "llama-server" || it.name == "llama-server.exe" } ?: error("no llama-server in $name")
            val into = File(target, folder); into.deleteRecursively(); into.mkdirs()
            // The server, the libraries it loads and the licences; not the other programs of the archive.
            val libraries: List<File> = if (server.name.endsWith(".exe")) server.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".dll") } else {
                // A Mac's archive names each library three times over (two of them links). The program says which names it loads.
                val seen = LinkedHashSet<String>(); val todo = ArrayDeque(listOf(server))
                while (todo.isNotEmpty()) {
                    val listed = ProcessBuilder("otool", "-L", todo.removeFirst().path).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
                    Regex("@rpath/(\\S+)").findAll(listed).forEach { m -> val f = File(server.parentFile, m.groupValues[1]); if (f.exists() && seen.add(f.name)) todo.add(f) }
                }
                seen.map { File(server.parentFile, it) }
            }
            (listOf(server) + libraries + unpacked.walkTopDown().filter { it.name.startsWith("LICENSE") }).distinct().forEach { f ->
                val out = File(into, f.name)
                Files.copy(f.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING) // a link is copied as the file it points to
                if (f.canExecute() || f == server) out.setExecutable(true)
            }
            check(File(into, server.name).isFile && libraries.isNotEmpty()) { "the AI engine was not unpacked from $name" }
        }
    }
}

// Tor, for the internet relays: the Tor Project's own program from its "expert bundle", one pinned version from its
// permanent archive, checked by SHA-256 before it is unpacked. When they were pinned (2026-11-21) both files were also
// checked against the Tor Browser developers' signature (key EF6E 286D DA85 EA2A 4BA7 DE68 4E2C 6E87 9329 8290).
// Like the AI engine it is code from outside: fetched only when asked for with -Ptor. Without it the app builds and
// runs, and its relays wait for a Tor that this copy does not have.
val torVersion = "16.0.1"
val torBundles = mapOf( // the computer it is for, to the file's name and SHA-256
    "windows-x64" to ("tor-expert-bundle-windows-x86_64-$torVersion.tar.gz" to "9eed3c3ff1fe43df034ce360196c9ec95472560d77ff55614d0cd5120edea27b"),
    "macos-arm64" to ("tor-expert-bundle-macos-aarch64-$torVersion.tar.gz" to "0b7d022017c31e3f972d06465bcd7ac50feb66ac825547419a934914fe4bb156"),
)
val fetchTor = tasks.register("fetchTor") {
    val wanted = providers.gradleProperty("tor").isPresent
    val pick = torBundles[composeTarget]
    val into = engineRoot.dir("$composeTarget/tor").asFile
    val work = layout.buildDirectory.dir("tor").get().asFile
    onlyIf { wanted && pick != null }
    doLast {
        val (name, sha) = pick!!
        val archive = File(work, name)
        if (!archive.isFile || sha256(archive) != sha) {
            work.mkdirs()
            uri("https://archive.torproject.org/tor-package-archive/torbrowser/$torVersion/$name").toURL().openStream().use { i -> archive.outputStream().use { i.copyTo(it) } }
        }
        check(sha256(archive) == sha) { "$name is not the file that was pinned (SHA-256 ${sha256(archive)})" }
        into.deleteRecursively(); into.mkdirs()
        // The archive is read here, entry by entry: the program, the library beside it and their licences are kept. Not the
        // programs for getting around blocks of Tor (they are large, and not used yet) and not the country lists.
        GZIPInputStream(archive.inputStream().buffered()).use { tar ->
            val header = ByteArray(512)
            while (tar.readNBytes(header, 0, 512) == 512 && header.any { it != 0.toByte() }) {
                fun text(from: Int, length: Int) = String(header, from, length, Charsets.US_ASCII).substringBefore('\u0000').trim()
                val entry = text(345, 155).let { if (it.isEmpty()) "" else "$it/" } + text(0, 100)
                val size = text(124, 12).ifEmpty { "0" }.toLong(8)
                val isFile = header[156] == '0'.code.toByte() || header[156] == 0.toByte()
                val out = when {
                    !isFile -> null
                    entry == "tor/tor" || entry == "tor/tor.exe" -> File(into, entry.substringAfter('/'))
                    entry.startsWith("tor/") && entry.count { it == '/' } == 1 && (entry.endsWith(".dylib") || entry.endsWith(".dll")) -> File(into, entry.substringAfter('/'))
                    entry in setOf("docs/tor.txt", "docs/libevent.txt", "docs/openssl.txt", "docs/zlib.txt") -> File(into, "licences/" + entry.substringAfter('/'))
                    else -> null
                }
                out?.parentFile?.mkdirs()
                var left = size; val buffer = ByteArray(1 shl 16)
                (out?.outputStream() ?: OutputStream.nullOutputStream()).use { o ->
                    while (left > 0) { val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); check(n > 0) { "$name ends in the middle of $entry" }; o.write(buffer, 0, n); left -= n }
                }
                tar.skipNBytes((512 - size % 512) % 512)
                if (out != null && out.parentFile == into) out.setExecutable(true)
            }
        }
        check(File(into, "tor").isFile || File(into, "tor.exe").isFile) { "Tor was not unpacked from $name" }
        // The Mac program comes with a signature that a Mac with Apple's own chips rejects, and such a Mac ends an unsigned
        // program the moment it starts (seen 2026-11-21: "invalid signature", ended with 137). It is signed again here, by
        // this computer and in no one's name, as every program built on a Mac is.
        if (composeTarget.startsWith("macos")) {
            val files = into.listFiles().orEmpty().filter { it.isFile && it.name != "NOTICE.txt" }.map { it.path }
            val sign = ProcessBuilder(listOf("codesign", "--force", "-s", "-") + files).inheritIO().start()
            check(sign.waitFor() == 0) { "Tor could not be signed for this Mac" }
        }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchAiEngine, fetchTor) }
// Packing the app drops the mark that says a file is a program (seen on the Mac build of 2026-11-20: every file in
// the packed app came out as a plain file). It is put back in the packed app, which the disk image is then made from.
tasks.matching { it.name == "createDistributable" }.configureEach {
    val packed = layout.buildDirectory.dir("compose/binaries/main/app")
    doLast { packed.get().asFile.walkTopDown().filter { it.isFile && (it.name == "llama-server" || (it.name == "tor" && it.parentFile.name == "tor")) }.forEach { it.setExecutable(true, false) } }
}

compose.desktop {
    application {
        mainClass = "it.blackout.desktop.MainKt"
        // The app calls into the system itself: Windows for a dark title bar (TitleBar.kt), and the database's own library.
        // Java asks to be told so, and later versions will refuse without it.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // The installers. Each has to be built on the system it is for: .github/workflows/desktop-windows.yml builds the
        // Windows one and the Mac one on GitHub. Neither is signed, so the system warns before running it.
        nativeDistributions {
            appResourcesRootDir.set(engineRoot) // the AI engine and its notice (above)
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "DarkProof"
            // 0.4 is the Android app's version (app/build.gradle.kts). The third number is the build's own: every build made on
            // GitHub gets the next one, and Windows lets an installer replace an installed DarkProof only if its number is higher.
            val build = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
            packageVersion = "0.4.$build"
            description = "DarkProof: messages when there is no internet"
            vendor = "DarkProof"
            includeAllModules = true // the whole Java runtime: a trimmed one leaves out parts the encryption and the database need
            windows {
                menuGroup = "DarkProof"; shortcut = true
                iconFile.set(project.file("icons/DarkProof.ico")) // the program's and its shortcuts' icon, drawn by icons/MakeIcon.java
                perUserInstall = true // installs for the person, with no administrator password
                upgradeUuid = "47564acc-4ff5-4925-be87-f8f7e3a80ba7" // never change: it is how a newer installer finds and replaces an older DarkProof
            }
            // The Mac's disk image, built on a Mac (the "mac" job of the same workflow). Not signed with an Apple
            // certificate and not checked by Apple, so a Mac asks before opening it the first time.
            macOS {
                bundleID = "app.darkproof.desktop"
                dockName = "DarkProof"
                packageVersion = "1.4.$build" // a Mac wants a first number of 1 or more
                iconFile.set(project.file("icons/DarkProof.icns")) // drawn by icons/MakeIcon.java
                infoPlist {
                    // A Mac asks the person before a program may look around the local network, and shows them this sentence.
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>DarkProof finds phones and computers with DarkProof on your network and passes messages between them.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_darkproof._tcp</string></array>
                    """.trimIndent()
                }
            }
        }
    }
}
