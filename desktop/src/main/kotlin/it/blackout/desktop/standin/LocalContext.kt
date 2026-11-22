package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Android's LocalContext, which the phones' screens read. The desktop window provides its own Context here. */
val LocalContext = staticCompositionLocalOf<Context> { error("the window didn't provide a Context") }
