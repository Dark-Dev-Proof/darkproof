package android.graphics

import androidx.compose.ui.graphics.ImageBitmap

/** A decoded picture. On a desktop it wraps the picture type the screens draw. */
class Bitmap(val image: ImageBitmap) {
    val width get() = image.width
    val height get() = image.height
}
