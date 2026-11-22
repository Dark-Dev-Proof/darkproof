package androidx.activity;

import android.content.Context;
import java.io.File;

/** Android's screen-owning object. On a desktop it is only the Context the screens are given: the window is the desktop's own. */
public class ComponentActivity extends Context {
    public ComponentActivity(File dir) { super(dir); }
}
