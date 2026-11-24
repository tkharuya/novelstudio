package jp.haruya.novelstudio;
import android.content.Context;
import org.json.JSONObject;
/** Non-network test fixture, deliberately not real credentials. */
final class SecureStore {
    SecureStore(Context c,String namespace){}
    JSONObject read(){return new JSONObject();}
    void write(JSONObject value){}
}
