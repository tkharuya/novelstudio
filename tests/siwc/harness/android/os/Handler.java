package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
/** Explicitly drained JVM main-thread queue, so Activity replacement races are deterministic. */
public final class Handler {
    private static final ConcurrentLinkedQueue<Runnable> MAIN = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) { }
    public boolean post(Runnable action) { MAIN.add(action); return true; }
    public static void drain() {
        Runnable action;
        while ((action = MAIN.poll()) != null) action.run();
    }
    public static void reset() { MAIN.clear(); }
}
