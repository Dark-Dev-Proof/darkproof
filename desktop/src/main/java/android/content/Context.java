package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * What DarkProof's shared code asks of Android's Context, on a desktop: a folder for this app's data.
 *
 * <p>About the {@code android.*} classes in this module: the phone's engine (app/src/main/java/it/blackout/mesh) is
 * compiled for the desktop exactly as it is. It asks Android for a few things: saved preferences, a timer on the main
 * thread, a log line, a small database. These classes answer those calls on a desktop computer, under the names
 * Android uses, so the phone's files need no change. They are Java so that Kotlin sees them as it sees the real
 * Android classes (a preference read as {@code String!}, not {@code String?}). Only what DarkProof calls is here: when
 * the phone's code starts using another Android method, the desktop build stops at that line until it is added.
 * There is deliberately no {@code android.os.Build}: some libraries decide they are on Android when that class exists.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private final File dir;
    /** One object for each preferences file, whichever Context asks: two would overwrite each other's changes. */
    private static final Map<String, SharedPreferences> prefs = new HashMap<>();

    public Context(File dir) { this.dir = dir; dir.mkdirs(); }

    public Context getApplicationContext() { return this; }

    /** The folder everything is kept in. */
    public File getFilesDir() { return dir; }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        File f = new File(dir, name + ".json");
        synchronized (prefs) { return prefs.computeIfAbsent(f.getAbsolutePath(), p -> new FilePreferences(f)); }
    }

    /**
     * "Erase everything": the preferences kept under {@code dir} are emptied in memory and forgotten, so that nothing
     * of them is written again, now or when the app closes. Their files go with the folder.
     */
    public static void forgetPreferences(File dir) {
        String under = dir.getAbsolutePath() + File.separator;
        synchronized (prefs) {
            prefs.entrySet().removeIf(e -> {
                if (!e.getKey().startsWith(under)) return false;
                if (e.getValue() instanceof FilePreferences) ((FilePreferences) e.getValue()).forget();
                return true;
            });
        }
    }

    /** Opens a link or mail address, or puts text to send on the clipboard. Throws if nothing here can act on it. */
    public void startActivity(Intent intent) { it.blackout.desktop.Opener.INSTANCE.open(intent); }
}
