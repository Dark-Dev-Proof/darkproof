package android.os;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Android's Handler, as far as DarkProof uses it: run this on the looper's thread, now or after a delay, or not after all. */
public class Handler {
    private final Looper looper;
    private final Map<Runnable, List<ScheduledFuture<?>>> waiting = new IdentityHashMap<>();

    public Handler(Looper looper) { this.looper = looper; }

    public Looper getLooper() { return looper; }

    public final boolean post(Runnable r) { return postDelayed(r, 0); }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        synchronized (waiting) {
            ScheduledFuture<?>[] mine = new ScheduledFuture<?>[1];
            mine[0] = looper.queue.schedule(() -> {
                synchronized (waiting) { List<ScheduledFuture<?>> l = waiting.get(r); if (l != null) { l.remove(mine[0]); if (l.isEmpty()) waiting.remove(r); } }
                try { r.run(); } catch (Throwable t) { System.err.println("DarkProof: on the main thread: " + t); t.printStackTrace(); } // Android would end the app; a desktop keeps its other work
            }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
            waiting.computeIfAbsent(r, k -> new ArrayList<>()).add(mine[0]);
        }
        return true;
    }

    /** Takes back every run of this very object that hasn't started. */
    public final void removeCallbacks(Runnable r) {
        synchronized (waiting) { List<ScheduledFuture<?>> l = waiting.remove(r); if (l != null) for (ScheduledFuture<?> f : l) f.cancel(false); }
    }

    /** With null, as DarkProof calls it: takes back everything this handler still has waiting. */
    public final void removeCallbacksAndMessages(Object token) {
        if (token != null) throw new UnsupportedOperationException("only null is supported");
        synchronized (waiting) { for (List<ScheduledFuture<?>> l : waiting.values()) for (ScheduledFuture<?> f : l) f.cancel(false); waiting.clear(); }
    }
}
