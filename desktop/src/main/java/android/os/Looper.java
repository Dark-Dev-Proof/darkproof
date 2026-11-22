package android.os;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Android's main thread, on a desktop: one thread of its own that runs what {@link Handler}s give it, one at a time, in order. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    final ScheduledThreadPoolExecutor queue = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r, "darkproof-main"); t.setDaemon(true); return t; });

    private Looper() { queue.setRemoveOnCancelPolicy(true); }

    public static Looper getMainLooper() { return MAIN; }
}
