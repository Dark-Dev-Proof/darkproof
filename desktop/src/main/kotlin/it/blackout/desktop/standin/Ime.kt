package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable

/** On a phone: whether the on-screen keyboard is showing. A desktop has none. */
val WindowInsets.Companion.isImeVisible: Boolean @Composable get() = false
