package android.util;

/** Android's log. On a desktop: warnings and errors go to the error stream; the rest only with DARKPROOF_DEBUG=1. */
public final class Log {
    private static final boolean VERBOSE = "1".equals(System.getenv("DARKPROOF_DEBUG"));
    private Log() { }
    public static int d(String tag, String msg) { if (VERBOSE) System.out.println(tag + ": " + msg); return 0; }
    public static int i(String tag, String msg) { if (VERBOSE) System.out.println(tag + ": " + msg); return 0; }
    public static int w(String tag, String msg) { System.err.println(tag + ": " + msg); return 0; }
    public static int e(String tag, String msg) { System.err.println(tag + ": " + msg); return 0; }
}
