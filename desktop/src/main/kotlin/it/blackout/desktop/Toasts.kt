package it.blackout.desktop

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/** The short messages the phones show at the bottom of the screen ("Copied"). The window draws [text] while it is set. */
object Toasts {
    var text by mutableStateOf<String?>(null); private set
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { text = null }

    fun show(message: String, long: Boolean) {
        main.removeCallbacks(hide); text = message
        main.postDelayed(hide, if (long) 4_500 else 2_500)
    }
}

/** What a desktop does where a phone's screen asks Android to open or send something. */
object Opener {
    fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    /** Links and mail addresses open in the computer's own apps; text to send goes on the clipboard. Throws if nothing here can act on it. */
    fun open(i: Intent) {
        when (i.action) {
            Intent.ACTION_SEND -> { copy(i.getStringExtra(Intent.EXTRA_TEXT) ?: error("nothing to send")); Toasts.show("Copied. Paste it where you want to send it.", true) }
            Intent.ACTION_VIEW -> {
                val uri = URI(i.data?.toString() ?: error("nothing to open"))
                when (uri.scheme) {
                    "http", "https" -> Desktop.getDesktop().browse(uri)
                    "mailto" -> Desktop.getDesktop().mail(uri)
                    else -> error("nothing on this computer opens ${uri.scheme} links")
                }
            }
            else -> error("not on the desktop: ${i.action}")
        }
    }
}
