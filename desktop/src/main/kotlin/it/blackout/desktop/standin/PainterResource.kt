@file:Suppress("DEPRECATION")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import it.blackout.mesh.R

/**
 * Android's painterResource(R.drawable.x), on a desktop: the phone's own picture files (Android vector XML and PNG),
 * read from the classpath by the desktop library's loader. The phone's screens call it as they do on Android.
 */
@Composable fun painterResource(id: Int): Painter = painterResource(R.path(id))
