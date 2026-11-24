package jp.haruya.novelstudio;
import android.content.Context;
import org.json.JSONObject;
/** In-memory test double; no Android Keystore or real API key is used. */
final class SecureStore {
    static JSONObject saved = new JSONObject();
    static boolean failWrite;
    static String namespace;
    SecureStore(Context context, String name) { namespace = name; }
    synchronized JSONObject read() throws Exception { return new JSONObject(saved.toString()); }
    synchronized void write(JSONObject value) throws Exception {
        if (failWrite) throw new Exception("PRIVATE_STORAGE_FAILURE_CANARY");
        saved = new JSONObject(value.toString());
    }
}
