package it.blackout.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

/**
 * The bar along the top of the window, with its name and the buttons to close it. Windows draws it, and draws it light
 * unless the program asks otherwise. This asks: dark on Windows 10 and 11, and on Windows 11 in the window's own
 * colour, so the bar and the app under it read as one piece. Windows keeps drawing it, so dragging, snapping to a
 * screen edge and the three buttons work as in any program.
 *
 * It calls Windows' own functions (in dwmapi.dll and user32.dll) through Java's way of calling into the system, with
 * no library added. Whatever goes wrong leaves the bar as Windows draws it.
 */
internal object TitleBar {
    val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** What Windows answered to each request (0 means done), or why it wasn't asked. */
    data class Answer(val dark: Int? = null, val colour: Int? = null, val edge: Int? = null, val error: String? = null) {
        val isDark get() = dark == 0
        override fun toString() = error ?: "dark: ${word(dark)}; the window's colour: ${word(colour)}; edge: ${word(edge)}"
        private fun word(code: Int?) = when (code) { null -> "not asked"; 0 -> "done"; else -> "refused (0x%08X)".format(code) }
    }

    /** One of Windows' own libraries, by its full path: never a file that only has its name and lies next to the program. */
    fun library(name: String, arena: Arena): SymbolLookup =
        SymbolLookup.libraryLookup(Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", name), arena)

    /**
     * Asks Windows for a dark title bar on the window with this [handle], in [colour] (red, green, blue: 0xRRGGBB) and
     * with an [edge] of that colour around the window. Only Windows 11 takes the two colours; Windows 10 refuses them
     * and keeps its own dark. [redraw] has a window that is already open draw its frame again, which Windows 10 needs.
     */
    fun darken(handle: Long, colour: Int, edge: Int, redraw: Boolean = false): Answer {
        if (!onWindows) return Answer(error = "not Windows")
        if (handle == 0L) return Answer(error = "the window has no handle yet")
        return try {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val user = library("user32.dll", arena)
                // The handle may be of the part of the window that Compose draws in: the bar belongs to the window around it.
                val ancestor = linker.downcallHandle(user.find("GetAncestor").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
                val given = MemorySegment.ofAddress(handle)
                val top = (ancestor.invoke(given, 2) as MemorySegment).takeIf { it.address() != 0L } ?: given // 2: the top window
                val set = linker.downcallHandle(library("dwmapi.dll", arena).find("DwmSetWindowAttribute").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
                val value = arena.allocate(JAVA_INT)
                fun ask(what: Int, v: Int): Int { value.set(JAVA_INT, 0, v); return set.invoke(top, what, value, 4) as Int }
                var dark = ask(20, 1) // "use dark mode"
                if (dark != 0) dark = ask(19, 1) // its number on Windows 10 before the 2020 update
                val coloured = ask(35, bgr(colour)); val edged = ask(34, bgr(edge)) // the bar's colour and the edge's: Windows 11
                if (redraw) {
                    val place = linker.downcallHandle(user.find("SetWindowPos").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
                    place.invoke(top, MemorySegment.NULL, 0, 0, 0, 0, 0x0037) // same place, size and order: only "the frame changed"
                }
                Answer(dark, coloured, edged)
            }
        } catch (t: Throwable) { Answer(error = t.toString()) }
    }

    /** Windows writes a colour blue first. */
    fun bgr(rgb: Int) = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
}
