package androidx.activity.compose

import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Android's Back button. A desktop window has none; the screens' own back arrows do the same. */
@Composable fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

/** What the phones' screens launch to ask Android for something (a photo); see [ActivityResultContract]. */
class Launcher<I>(private val run: (I) -> Unit) { fun launch(input: I) = run(input) }

@Composable fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): Launcher<I> {
    val result = rememberUpdatedState(onResult)
    return remember(contract) { Launcher { input -> contract.launch(input) { result.value(it) } } }
}
