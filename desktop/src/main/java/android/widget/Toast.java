package android.widget;

import android.content.Context;

/** Android's short message at the bottom of the screen. On a desktop the window shows it (it.blackout.desktop.Toasts). */
public final class Toast {
    public static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    private final String text; private final boolean isLong;

    private Toast(String text, boolean isLong) { this.text = text; this.isLong = isLong; }

    public static Toast makeText(Context context, CharSequence text, int duration) { return new Toast(String.valueOf(text), duration == LENGTH_LONG); }

    public void show() { it.blackout.desktop.Toasts.INSTANCE.show(text, isLong); }
}
