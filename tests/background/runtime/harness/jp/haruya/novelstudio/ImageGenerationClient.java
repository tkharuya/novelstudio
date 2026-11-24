package jp.haruya.novelstudio;
import android.content.Context;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/** Paid image API boundary fake. It never calls the network. */
public final class ImageGenerationClient {
    public interface Listener { void onEvent(String type, JSONObject data); }
    public final List<JSONObject> calls = new CopyOnWriteArrayList<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public int closes;
    public boolean autoCancel = true;
    private final Listener listener;
    private String current = "";
    private boolean busy;
    public ImageGenerationClient(Context context, IllustrationStore images, Listener listener) { this.listener = listener; }
    public JSONObject status() { return TestData.object("configured", true, "busy", busy, "model", "gpt-image-2.5-flare"); }
    public void generate(JSONObject request) {
        current = request.optString("requestId"); busy = true;
        calls.add(TestData.copy(request));
        TestData.trace.add("api:image:" + current);
    }
    public void cancel() {
        cancelled.add(current);
        if (autoCancel && !current.isEmpty()) emit(TestData.object("requestId", current, "phase", "cancelled"));
    }
    public void close() { closes++; }
    public void emit(JSONObject event) {
        if (current.equals(event.optString("requestId")) && TestData.isTerminal(event.optString("phase"))) busy = false;
        listener.onEvent("illustration", event);
    }
}
