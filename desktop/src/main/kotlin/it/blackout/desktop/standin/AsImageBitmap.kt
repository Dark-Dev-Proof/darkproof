package androidx.compose.ui.graphics

import android.graphics.Bitmap

/** Android's Bitmap.asImageBitmap(), which the phones' screens call. */
fun Bitmap.asImageBitmap(): ImageBitmap = image
