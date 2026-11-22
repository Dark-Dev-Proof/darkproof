package it.blackout.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * Stands in for the Tor program in tests: started the same way, it says how far it is the way Tor does and then
 * stays, without reaching any network. A file "ends-after-connecting" in its data folder makes it end a moment
 * after it has connected, as a Tor that keeps failing does.
 */
object FakeTor {
    fun command() = listOf(File(System.getProperty("java.home"), "bin/java" + if (TitleBar.onWindows) ".exe" else "").path, "@" + FakeLlama.classes.path, "it.blackout.desktop.FakeTor")

    @JvmStatic fun main(args: Array<String>) {
        if ("--version" in args) { println("Tor version 0.0.0 (stand-in)."); return }
        fun arg(name: String) = args[args.indexOf(name) + 1]
        // What the app must always say: where to listen (this computer only), where to keep its files, that it only uses the network, and who owns it
        for (must in listOf("-f", "--SocksPort", "--DataDirectory", "--ClientOnly", "--Log", "--__OwningControllerProcess")) if (must !in args) { println("[err] started without $must"); exitProcess(2) }
        if (!arg("--SocksPort").startsWith("127.0.0.1:")) { println("[err] not on this computer only"); exitProcess(2) }
        if (arg("--__OwningControllerProcess").toLong() != ProcessHandle.current().parent().get().pid()) { println("[err] owned by someone else"); exitProcess(2) }
        println("Nov 21 00:00:00.000 [notice] Bootstrapped 0% (starting): Starting"); Thread.sleep(150)
        println("Nov 21 00:00:00.150 [notice] Bootstrapped 45% (requesting_descriptors): Asking for relay descriptors"); Thread.sleep(150)
        println("Nov 21 00:00:00.300 [notice] Bootstrapped 100% (done): Done")
        if (File(arg("--DataDirectory"), "ends-after-connecting").exists()) { Thread.sleep(300); println("Nov 21 00:00:00.600 [err] Something went wrong"); exitProcess(1) }
        Thread.currentThread().join()
    }
}
