package jp.haruya.novelstudio;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
final class TestData {
    static final List<String> trace = new CopyOnWriteArrayList<>();
    static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String)values[i], values[i + 1]); }
        catch (Exception error) { throw new AssertionError(error); }
        return result;
    }
    static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    static boolean isTerminal(String phase) { return "complete".equals(phase) || "error".equals(phase) || "cancelled".equals(phase); }
}
