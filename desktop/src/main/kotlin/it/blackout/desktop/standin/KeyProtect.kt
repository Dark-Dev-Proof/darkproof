package it.blackout.mesh

import java.io.File
import java.security.MessageDigest
import java.util.Base64

// Protects the desktop Vault's 32-byte master key with the operating system's own per-user key protection, so a copy of
// the data folder alone (a stolen or imaged disk, a backup, a roamed/synced profile) can't read what the Vault encrypts.
// The phones keep this key in hardware (Android Keystore, iPhone Keychain); a desktop has no one place that works the
// same everywhere, so:
//   - macOS: the login Keychain (the key never sits in the file).
//   - Windows: DPAPI, tied to the Windows account (the file holds only the wrapped blob).
//   - Linux: the Secret Service (secret-tool) when it is present; otherwise the key stays in the file, as before.
//
// vault.key holds either a legacy/plain 32-byte raw key (older installs, and tests) or a secured blob that starts with
// MAGIC. In tests the app leaves [secureStore] false, so a fresh key is written as a plain 32-byte file exactly as
// before (StoreStandInTest checks the 32-byte length and the "damaged file is not replaced" guard); the app turns
// [secureStore] on at start-up, after the self-test, so only the real app reaches the OS store.
object KeyProtect {
    @Volatile var secureStore = false

    private val MAGIC = "DPK1".toByteArray(Charsets.US_ASCII)
    private const val DPAPI = 0x01; private const val KEYCHAIN = 0x02; private const val SECRETSERVICE = 0x03
    private const val SERVICE = "DarkProof vault key"
    private val os = System.getProperty("os.name").lowercase()
    private val isMac = "mac" in os; private val isWin = "win" in os

    private fun account(dir: File): String =
        "darkproof-vault-" + MessageDigest.getInstance("SHA-256").digest(dir.canonicalPath.toByteArray()).copyOf(8).joinToString("") { "%02x".format(it) }

    /**
     * The 32-byte master key stored in [f] for data folder [dir], or null when [f] is absent or empty (caller then makes
     * a new one). Throws IllegalStateException when [f] is present but can't be read back, so a key is never lost by a
     * silent overwrite.
     */
    fun read(dir: File, f: File): ByteArray? {
        if (!f.exists() || f.length() == 0L) return null
        if (f.length() == 32L) { // a legacy/plain raw key (older install, or a test)
            val raw = f.readBytes()
            if (secureStore) runCatching { write(dir, f, raw) } // move it into the OS store from now on; harmless if that store is absent
            return raw
        }
        val blob = f.readBytes()
        check(blob.size > MAGIC.size + 1 && blob.copyOf(MAGIC.size).contentEquals(MAGIC)) { "vault.key is damaged: what it protects can't be read" }
        val body = blob.copyOfRange(MAGIC.size + 1, blob.size)
        val key = when (blob[MAGIC.size].toInt() and 0xFF) {
            DPAPI -> dpapi(false, body)
            KEYCHAIN -> run(listOf("security", "find-generic-password", "-a", String(body, Charsets.US_ASCII), "-s", SERVICE, "-w"))?.let { decode(it) }
            SECRETSERVICE -> run(listOf("secret-tool", "lookup", "service", SERVICE, "account", String(body, Charsets.US_ASCII)))?.let { decode(it) }
            else -> null
        }
        check(key != null && key.size == 32) { "vault.key is damaged: what it protects can't be read" }
        return key
    }

    /** Writes the 32-byte master key [raw] to [f] under the best scheme this computer offers. Falls back to a plain file. */
    fun write(dir: File, f: File, raw: ByteArray) {
        fun plain() = atomicWrite(f, raw)
        if (!secureStore) return plain()
        val b64 = Base64.getEncoder().encodeToString(raw)
        when {
            isWin -> dpapi(true, raw)?.let { store(f, DPAPI, it) } ?: plain()
            // The whole command (the key included) is fed to `security -i` on stdin, never on argv, so another same-user
            // process can't read the key from `ps`; and -i never falls back to a terminal prompt the way a lone -w does.
            // (Windows uses DPAPI, Linux secret-tool stdin.) The account is hex and the key base64, so neither can break -i parsing.
            isMac -> run(listOf("security", "-i"), input = "add-generic-password -a ${account(dir)} -s \"$SERVICE\" -U -w $b64\n")
                ?.let { store(f, KEYCHAIN, account(dir).toByteArray(Charsets.US_ASCII)) } ?: plain()
            hasSecretTool() -> run(listOf("secret-tool", "store", "--label=DarkProof", "service", SERVICE, "account", account(dir)), input = b64)
                ?.let { store(f, SECRETSERVICE, account(dir).toByteArray(Charsets.US_ASCII)) } ?: plain()
            else -> plain() // no OS store here: keep today's behaviour rather than fail to start
        }
    }

    /** Removes any OS-store item for data folder [dir]: "Erase everything" ([Wipe]), and tests that exercise the secure path. */
    fun clear(dir: File) {
        when {
            isMac -> run(listOf("security", "delete-generic-password", "-a", account(dir), "-s", SERVICE))
            hasSecretTool() -> run(listOf("secret-tool", "clear", "service", SERVICE, "account", account(dir)))
        }
    }

    private fun store(f: File, scheme: Int, body: ByteArray) = atomicWrite(f, MAGIC + byteArrayOf(scheme.toByte()) + body)
    private fun decode(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s.trim()) }.getOrNull()?.takeIf { it.size == 32 }
    private fun perms(f: File) {
        runCatching { java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }

    /** Writes [bytes] to [f] through a temp file moved into place, so a crash mid-write can't leave an empty vault.key
     *  (which would read as "no key" and make a fresh one, overwriting the OS-store item and losing every saved group). */
    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes); perms(tmp)
        runCatching { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) } // Windows has no atomic replace
            .getOrThrow()
        perms(f)
    }

    private val secretTool: Boolean by lazy { run(listOf("secret-tool", "--version")) != null }
    private fun hasSecretTool() = secretTool

    // Windows DPAPI through Windows PowerShell (always present), CurrentUser scope. The key crosses as base64 in an
    // environment variable, never on the command line, so another process can't read it from the argument list.
    private fun dpapi(protect: Boolean, data: ByteArray): ByteArray? {
        if (!isWin) return null
        val call = if (protect) "Protect" else "Unprotect"
        val script = "Add-Type -AssemblyName System.Security; " +
            "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::$call([Convert]::FromBase64String(\$env:DPK),\$null,'CurrentUser'))"
        val out = run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), env = mapOf("DPK" to Base64.getEncoder().encodeToString(data))) ?: return null
        return runCatching { Base64.getDecoder().decode(out.trim()) }.getOrNull()
    }

    // Runs a program and returns its stdout on success (empty string when it prints nothing), or null if the program
    // isn't found, times out, or exits non-zero. [input] is written to its stdin; [env] adds environment variables. A
    // store command that succeeds prints nothing, so a non-null result (even "") means "the call worked".
    private fun run(cmd: List<String>, input: String? = null, env: Map<String, String> = emptyMap()): String? = runCatching {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true) // one stream to drain: no stdout/stderr deadlock
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val p = pb.start()
        val out = StringBuilder()
        // Drained on a daemon thread so a child that never closes its output (e.g. one waiting for a terminal) can't block past the timeout.
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } } }
            .apply { isDaemon = true; start() }
        if (input != null) runCatching { p.outputStream.use { it.write(input.toByteArray()) } } else p.outputStream.close()
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
        reader.join(2000)
        if (p.exitValue() != 0) null else synchronized(out) { out.toString() }
    }.getOrNull()
}
