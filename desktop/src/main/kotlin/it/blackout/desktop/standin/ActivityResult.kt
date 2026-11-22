package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.concurrent.thread

/** On a desktop: asks for something with the computer's own dialog and hands back the answer. */
abstract class ActivityResultContract<I, O> { abstract fun launch(input: I, done: (O) -> Unit) }

class ActivityResultContracts {
    /** "Pick a photo": the computer's file dialog, pictures only. Null if the user closed it. */
    class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        object ImageOnly
        override fun launch(input: PickVisualMediaRequest, done: (Uri?) -> Unit) {
            thread(name = "pick-photo") { // the dialog waits for the user: not on the window's thread
                val d = FileDialog(null as Frame?, "Choose a photo", FileDialog.LOAD)
                d.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") }
                d.isVisible = true
                done(d.file?.let { Uri.fromFile(File(d.directory, it)) })
            }
        }
    }
}
