package jp.haruya.novelstudio;
import android.content.Context;
import org.json.JSONObject;
/** In-memory test double. These checks do not test Android Keystore or disk persistence. */
final class SecureStore {
    static Context lastContext;
    private JSONObject value = new JSONObject();
    SecureStore(Context context) { lastContext = context; }
    synchronized JSONObject read() throws Exception { return new JSONObject(value.toString()); }
    synchronized void write(JSONObject next) throws Exception { value = new JSONObject(next.toString()); }
}
