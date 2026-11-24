package jp.haruya.novelstudio;
import android.app.Activity;
import android.content.Context;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/** API boundary fake. Calls are recorded; no credentials, sockets or HTTP are used. */
public final class SiwcClient {
    public interface Listener { void onEvent(String type, JSONObject data); }
    public final List<JSONObject> calls = new CopyOnWriteArrayList<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public int closes;
    public boolean autoCancel = true;
    public WeakReference<Activity> attached = new WeakReference<>(null);
    public boolean foreground;
    private final Listener listener;
    private String current = "";
    private boolean busy;
    public SiwcClient(Context context, Listener listener) { this.listener = listener; }
    public void attachActivity(Activity owner) { attached = new WeakReference<>(owner); }
    public void detachActivity(Activity owner) { if (attached.get() == owner) { attached.clear(); foreground = false; } }
    public void setForeground(Activity owner, boolean value) { if (attached.get() == owner) foreground = value; }
    public JSONObject status() { return TestData.object("signedIn", true, "generating", busy, "busy", busy); }
    public void generate(String id, String model, String instructions, String prompt) {
        current = id; busy = true;
        calls.add(TestData.object("requestId", id, "model", model, "instructions", instructions, "prompt", prompt));
        TestData.trace.add("api:text:" + id);
    }
    public void cancel() {
        cancelled.add(current);
        if (autoCancel && !current.isEmpty()) emit("generation", TestData.object("requestId", current, "phase", "cancelled", "text", "途中の本文"));
    }
    public void close() { closes++; }
    public void emit(String type, JSONObject event) {
        if ("generation".equals(type) && current.equals(event.optString("requestId"))
                && TestData.isTerminal(event.optString("phase"))) busy = false;
        listener.onEvent(type, event);
    }
}
