package android.os;

/** Time since some fixed start that never jumps when the clock is set. */
public final class SystemClock {
    private SystemClock() { }
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000L; }
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
}
