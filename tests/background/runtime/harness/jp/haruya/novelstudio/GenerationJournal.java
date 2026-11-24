package jp.haruya.novelstudio;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/** In-memory durable-boundary fake. Records the exact point terminal persistence succeeds. */
public final class GenerationJournal {
    public static boolean failConstruction;
    public boolean failBegin, failRecord, failFlush;
    public int begins, records, terminalRecords, flushes, acknowledgements, merges;
    public CountDownLatch beginEntered, beginRelease, terminalEntered, terminalRelease;
    private final Map<String,JSONObject> entries = new LinkedHashMap<>();
    private long sequence;
    public GenerationJournal(Context context) throws IOException {
        if (failConstruction) throw new IOException("CANARY_PRIVATE_JOURNAL_OPEN");
    }
    public void begin(JSONObject request, String kind) throws IOException {
        if (beginEntered != null) beginEntered.countDown();
        await(beginRelease);
        synchronized (this) {
            if (failBegin) throw new IOException("CANARY_PRIVATE_JOURNAL_BEGIN");
            String id = request.optString("requestId");
            if (entries.containsKey(id)) throw new IOException("Duplicate journal request");
            JSONObject saved = TestData.copy(request);
            put(saved, "kind", kind); put(saved, "phase", "generating"); put(saved, "status", "generating");
            put(saved, "seq", ++sequence); put(saved, "text", "");
            entries.put(id, saved); begins++;
            TestData.trace.add("journal:begin:" + id);
        }
    }
    public JSONObject record(String type, JSONObject event) throws IOException {
        boolean terminal = TestData.isTerminal(event.optString("phase"));
        if (terminal && terminalEntered != null) terminalEntered.countDown();
        if (terminal) await(terminalRelease);
        synchronized (this) {
            String id = event.optString("requestId");
            JSONObject previous = entries.get(id);
            if (previous == null || TestData.isTerminal(previous.optString("phase"))) return null;
            JSONObject output = TestData.copy(event);
            for (String field : new String[]{"bookId", "chapterId", "kind", "purpose"}) {
                if (!output.has(field) && previous.has(field)) put(output, field, previous.opt(field));
            }
            if ("delta".equals(event.optString("phase"))) put(previous, "text", previous.optString("text") + event.optString("delta"));
            else if (event.has("text")) put(previous, "text", event.opt("text"));
            if (event.has("image")) put(previous, "image", event.opt("image"));
            put(output, "seq", ++sequence); put(previous, "seq", sequence);
            if (terminal) {
                put(previous, "phase", event.optString("phase")); put(previous, "status", event.optString("phase"));
                terminalRecords++;
            }
            records++;
            if (failRecord) {
                // The real journal retains its latest complete in-memory record when disk
                // persistence fails. Runtime must expose it on the next snapshot/replay.
                TestData.trace.add("journal:ram:" + id + ":" + event.optString("phase"));
                throw new IOException("CANARY_PRIVATE_JOURNAL_WRITE");
            }
            TestData.trace.add("journal:persist:" + id + ":" + event.optString("phase"));
            return output;
        }
    }
    public synchronized JSONArray snapshots() {
        JSONArray result = new JSONArray();
        for (JSONObject record : entries.values()) result.put(TestData.copy(record));
        return result;
    }
    public synchronized String mergeInto(String stored) throws IOException { merges++; return stored; }
    public synchronized void acknowledge(String id, long seq) throws IOException {
        acknowledgements++;
        JSONObject record = entries.get(id);
        if (record != null && TestData.isTerminal(record.optString("phase")) && record.optLong("seq") <= seq) entries.remove(id);
    }
    public synchronized void flush() throws IOException {
        flushes++;
        if (failFlush) throw new IOException("CANARY_PRIVATE_JOURNAL_FLUSH");
        TestData.trace.add("journal:flush");
    }
    private static void await(CountDownLatch latch) throws IOException {
        if (latch == null) return;
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new IOException("Offline journal gate timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Offline journal gate interrupted"); }
    }
    private static void put(JSONObject result, String key, Object value) {
        try { result.put(key, value); } catch (Exception error) { throw new AssertionError(error); }
    }
}
