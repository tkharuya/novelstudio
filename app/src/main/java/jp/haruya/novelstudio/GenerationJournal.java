package jp.haruya.novelstudio;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Independent native result journal. Never adopts text or submits/re-submits a request. */
public final class GenerationJournal implements AutoCloseable {
    public static final int MAX_RECORDS = 32;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 2 * 1024 * 1024;
    private static final int MAX_METADATA_CHARS = 200000;
    private static final int MAX_SOURCE_CHARS = 4000000;
    private static final int MAX_MESSAGE_CHARS = 5000;
    private static final int MAX_DRAFT_BYTES = 30 * 1024 * 1024;
    private static final long MAX_SEQUENCE = 9007199254740991L;
    private static final String FORMAT = "NovelStudioGenerationJournal";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,200}");
    private static final Pattern IMAGE_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final AtomicFile file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduled;
    private boolean dirty;
    private boolean closed;
    private IOException asynchronousFailure;

    public GenerationJournal(Context context) throws IOException {
        File base = new File(context.getApplicationContext().getFilesDir(), "generation-journal.json");
        file = new AtomicFile(base);
        if (base.exists() || new File(base.getPath() + ".bak").exists()) load();
        boolean recovered = false;
        for (Entry entry : entries.values()) {
            if ("generating".equals(entry.phase)) {
                entry.phase = "interrupted";
                entry.seq = nextSequence(entry.seq);
                entry.updatedAt = System.currentTimeMillis();
                entry.message = "image".equals(entry.type)
                        ? "アプリの処理が終了しました。画像生成の完了は未確認です。自動で再送信しません。"
                        : "アプリの処理が終了し、生成が中断されました。保存済みの候補を残しています。自動で再送信しません。";
                recovered = true;
            }
        }
        if (recovered) { dirty = true; writeNow(); }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NovelStudioJournal");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Must succeed before invoking a paid generation operation. Duplicate request IDs fail. */
    public synchronized void begin(JSONObject request, String type) throws IOException {
        ensureOpen();
        if (request == null) throw new IOException("生成の保存情報がありません。");
        Entry entry = fromRequest(request, type);
        if (entries.containsKey(entry.requestId)) throw new IOException("同じ生成処理はすでに記録されています。");
        if (entries.size() >= MAX_RECORDS) throw new IOException("未反映の生成結果が32件あります。結果を原稿へ保存してから再度生成してください。");
        if (dirty || asynchronousFailure != null) writeNow();
        entries.put(entry.requestId, entry);
        try {
            checkReservedCapacity();
            dirty = true;
            writeNow();
        } catch (IOException error) {
            entries.remove(entry.requestId);
            // AtomicFile may already contain this unstarted request if verification failed.
            // Retaining dirty state lets a later flush repair it without losing older results.
            dirty = true;
            throw error;
        }
    }

    /**
     * Returns a safe event with an assigned sequence. Late/duplicate terminal events return null.
     * Delta writes are batched for at most one second; terminal writes complete before return.
     */
    public synchronized JSONObject record(String eventType, JSONObject event) throws IOException {
        ensureOpen();
        if (event == null) throw new IOException("生成結果がありません。");
        String id = requiredIdentifier(event, "requestId", 200);
        Entry entry = entries.get(id);
        if (entry == null) throw new IOException("生成結果の保存先を確認できませんでした。");
        String type = "generation".equals(eventType) || "text".equals(eventType) ? "text"
                : "illustration".equals(eventType) || "image".equals(eventType) ? "image" : "";
        if (!entry.type.equals(type)) throw new IOException("生成結果の種類が一致しません。");
        if (terminal(entry.phase)) return null;
        if (event.has("seq") && !event.isNull("seq") && sequence(event.opt("seq")) <= entry.seq) return null;
        String incomingPhase = string(event, "phase", "", 24);
        boolean terminal = terminal(incomingPhase);
        if (!("delta".equals(incomingPhase) || "generating".equals(incomingPhase) || terminal)) {
            throw new IOException("生成結果の状態が不正です。");
        }
        if (asynchronousFailure != null && !terminal) {
            throw new IOException("途中結果を端末へ保存できませんでした。空き容量を確認してください。", asynchronousFailure);
        }
        String delta = "";
        String replacement = null;
        if ("delta".equals(incomingPhase)) {
            if (!"text".equals(type)) throw new IOException("画像の結果に文章差分を追加することはできません。");
            delta = string(event, "delta", "", textLimit(entry));
            if ((long) entry.text.length() + delta.length() > textLimit(entry)) throw new IOException("生成候補が保存できる文字数の上限に達しました。受信済みの候補は保持しています。");
        } else if ("text".equals(type) && event.has("text") && !event.isNull("text")) {
            String full = string(event, "text", "", textLimit(entry));
            // An empty error payload must not erase an already received partial manuscript.
            if (!full.isEmpty()) replacement = entry.resumePrefix + full;
        }
        String message = string(event, "message", entry.message, MAX_MESSAGE_CHARS);
        JSONObject image = entry.image;
        if ("image".equals(type) && event.has("image") && !event.isNull("image")) {
            if (!terminal || !(event.opt("image") instanceof JSONObject)) throw new IOException("画像の保存情報が不正です。");
            image = cleanImage(event.optJSONObject("image"));
        }
        if ("image".equals(type) && "complete".equals(incomingPhase) && image == null) {
            throw new IOException("作成した画像の保存情報がありません。");
        }
        long next = nextSequence(entry.seq);
        if (replacement != null) { entry.text.setLength(0); entry.text.append(replacement); }
        else if (!delta.isEmpty()) entry.text.append(delta);
        entry.image = image;
        entry.message = message;
        entry.phase = terminal ? incomingPhase : "generating";
        entry.seq = next;
        entry.updatedAt = System.currentTimeMillis();
        dirty = true;
        if (terminal) {
            cancelScheduled();
            // On failure, keep the terminal result in RAM for snapshots/flush; never replace
            // it with a later cancellation or automatically re-send the network request.
            writeNow();
        } else {
            scheduleWrite();
        }
        // Streaming must not copy the entire accumulated manuscript for every token.
        JSONObject result = entry.snapshot(!"delta".equals(incomingPhase), false);
        put(result, "phase", incomingPhase);
        // Source snapshots belong to the journal/reconciliation path, not every streamed
        // browser event. The UI already persisted these before starting the operation.
        if ("delta".equals(incomingPhase)) {
            put(result, "delta", delta);
        }
        return result;
    }

    /** Defensive full records. This remains usable after a disk failure or after close(). */
    public synchronized JSONArray snapshots() {
        JSONArray result = new JSONArray();
        for (Entry entry : entries.values()) result.put(entry.snapshot());
        return result;
    }

    /**
     * Returns an overlaid copy, without writing DraftStore or acknowledging records. Existing
     * body/title/synopsis values and candidates belonging to newer requests are preserved.
     */
    public synchronized String mergeInto(String draftJson) throws IOException {
        if (draftJson == null) throw new IOException("原稿データがありません。");
        if (draftJson.length() > MAX_DRAFT_BYTES) throw new IOException("原稿データが大きすぎます。");
        final JSONObject root;
        try { root = new JSONObject(draftJson); }
        catch (Exception error) { throw new IOException("原稿データを読み込めませんでした。", error); }
        JSONArray books = root.optJSONArray("books");
        if (!"NovelStudio".equals(root.optString("app")) || books == null || entries.isEmpty()) return draftJson;
        boolean changed = false;
        for (Entry entry : entries.values()) {
            if (receiptCovers(root, entry)) continue;
            if (!entry.batchId.isEmpty() || "narration".equals(entry.purpose)) continue; // BatchGeneration owns adoption and progress.
            JSONObject book = find(books, entry.bookId);
            if (book == null) continue;
            try {
                if ("image".equals(entry.type)) {
                    JSONObject chapter = find(book.optJSONArray("chapters"), entry.chapterId);
                    if (chapter != null) changed |= mergeImage(chapter, entry);
                } else if ("metadata".equals(entry.purpose)) {
                    changed |= mergeCandidate(book, "titles".equals(entry.kind) ? "titleCandidate" : "synopsisCandidate", entry);
                } else {
                    JSONObject chapter = find(book.optJSONArray("chapters"), entry.chapterId);
                    if (chapter != null) changed |= mergeCandidate(chapter, "candidate", entry);
                }
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("生成候補を原稿に関連付けできませんでした。結果は生成記録に保持しています。", error); }
        }
        if (!changed) return draftJson;
        String result = root.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
            throw new IOException("生成候補を含む原稿が30MiBを超えています。結果は生成記録に保持しています。");
        }
        return result;
    }

    /** UI calls this only after saving the terminal result and its receipt to DraftStore. */
    public synchronized void acknowledge(String requestId, long seq) throws IOException {
        ensureOpen();
        Entry entry = entries.get(requestId);
        if (entry == null || !terminal(entry.phase) || entry.seq != seq) return;
        LinkedHashMap<String, Entry> previous = new LinkedHashMap<>(entries);
        entries.remove(requestId);
        dirty = true;
        try { writeNow(); }
        catch (IOException error) {
            entries.clear(); entries.putAll(previous);
            dirty = true;
            throw error;
        }
    }

    public synchronized void flush() throws IOException {
        ensureOpen();
        cancelScheduled();
        if (dirty || asynchronousFailure != null) writeNow();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        cancelScheduled();
        try { if (dirty || asynchronousFailure != null) writeNow(); }
        finally { closed = true; scheduler.shutdownNow(); }
    }

    private void load() throws IOException {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) output.size() + count > MAX_BYTES) throw new IOException("生成記録が保存上限を超えています。元の記録は変更していません。");
                output.write(buffer, 0, count);
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
            JSONObject root = new JSONObject(text);
            JSONArray records = root.optJSONArray("records");
            if (!FORMAT.equals(root.optString("format")) || root.optInt("version") != 1
                    || records == null || records.length() > MAX_RECORDS) throw new IOException("生成記録の形式を確認できません。元の記録は変更していません。");
            for (int i = 0; i < records.length(); i++) {
                JSONObject object = records.optJSONObject(i);
                if (object == null) throw new IOException("生成記録の内容が不正です。");
                Entry entry = fromStored(object);
                if (entries.put(entry.requestId, entry) != null) throw new IOException("生成記録に重複したIDがあります。");
            }
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("保存した生成記録を読み込めませんでした。元の記録は変更していません。", error); }
    }

    private void scheduleWrite() throws IOException {
        if (scheduled != null) return;
        try {
            // Schedule from the FIRST dirty delta, rather than postponing on every delta.
            scheduled = scheduler.schedule(() -> {
                synchronized (GenerationJournal.this) {
                    scheduled = null;
                    if (closed || !dirty) return;
                    try { writeNow(); }
                    catch (IOException error) { asynchronousFailure = error; }
                }
            }, 1, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            throw new IOException("生成記録の保存を開始できませんでした。", error);
        }
    }

    private void cancelScheduled() {
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
    }

    private void writeNow() throws IOException {
        JSONObject document = new JSONObject();
        put(document, "format", FORMAT);
        put(document, "version", 1);
        put(document, "records", snapshots());
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("生成記録が32MiBの上限に達しました。未反映の結果は保持しています。");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            // Some Android AtomicFile implementations log a failed rename instead of throwing.
            // Verify the committed bytes before permitting a paid start/terminal notification.
            verifyWritten(bytes);
            dirty = false;
            asynchronousFailure = null;
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            dirty = true;
            IOException safe = error instanceof IOException ? (IOException) error
                    : new IOException("生成記録を端末へ保存できませんでした。", error);
            asynchronousFailure = safe;
            throw safe;
        }
    }

    private void verifyWritten(byte[] bytes) throws IOException {
        try (FileInputStream input = file.openRead()) {
            byte[] buffer = new byte[8192];
            int offset = 0, count;
            while ((count = input.read(buffer)) != -1) {
                if (offset + count > bytes.length) throw new IOException("生成記録の保存を確認できませんでした。");
                for (int i = 0; i < count; i++) if (buffer[i] != bytes[offset + i]) throw new IOException("生成記録の保存を確認できませんでした。");
                offset += count;
            }
            if (offset != bytes.length) throw new IOException("生成記録の保存を確認できませんでした。");
        }
    }

    private void checkReservedCapacity() throws IOException {
        long reserved = 4096;
        for (Entry entry : entries.values()) {
            JSONObject base = entry.snapshot();
            if ("generating".equals(entry.phase)) {
                base.remove("text"); base.remove("message"); base.remove("image");
                // Six bytes per UTF-16 unit covers JSON control-character escapes as well.
                reserved += base.toString().getBytes(StandardCharsets.UTF_8).length
                        + 6L * textLimit(entry) + 6L * MAX_MESSAGE_CHARS + 4096;
                if ("image".equals(entry.type)) reserved += 6L * 32000 + 4096;
            } else {
                reserved += base.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            if (reserved > MAX_BYTES) throw new IOException("生成結果を確実に保存する空き枠が不足しています。未反映の結果を原稿へ保存してから生成してください。");
        }
    }

    private static Entry fromRequest(JSONObject request, String type) throws IOException {
        if (!("text".equals(type) || "image".equals(type))) throw new IOException("生成の種類が不正です。");
        Entry entry = new Entry();
        entry.type = type;
        entry.requestId = requiredIdentifier(request, "requestId", 200);
        entry.bookId = requiredIdentifier(request, "bookId", 100);
        entry.purpose = string(request, "purpose", "manuscript", 32);
        if (!("manuscript".equals(entry.purpose) || "metadata".equals(entry.purpose) || "narration".equals(entry.purpose))) throw new IOException("生成の目的が不正です。");
        if ("image".equals(type) && "metadata".equals(entry.purpose)) throw new IOException("画像の生成先が不正です。");
        entry.chapterId = "metadata".equals(entry.purpose) ? string(request, "chapterId", "", 100)
                : requiredIdentifier(request, "chapterId", 100);
        entry.kind = string(request, "kind", "image".equals(type) ? "api" : "", 32);
        if ("metadata".equals(entry.purpose) && !("titles".equals(entry.kind) || "synopsis".equals(entry.kind))) throw new IOException("作品情報の生成先が不正です。");
        if ("image".equals(type) && !("api".equals(entry.kind) || "import".equals(entry.kind))) throw new IOException("画像生成の種類が不正です。");
        entry.mode = string(request, "mode", "new", 24);
        if (!("new".equals(entry.mode) || "continue".equals(entry.mode) || "rewrite".equals(entry.mode))) throw new IOException("文章の生成方法が不正です。");
        entry.batchId = string(request, "batchId", "", 100);
        if ("text".equals(type) && "manuscript".equals(entry.purpose)) {
        entry.model = string(request, "model", "", 300);
        entry.instructions = string(request, "instructions", "", 100000);
        entry.prompt = string(request, "prompt", "", 800000);
        entry.resumePrefix = string(request, "resumePrefix", "", MAX_TEXT_CHARS);
        if (!entry.resumePrefix.isEmpty() && (!"text".equals(type) || !"manuscript".equals(entry.purpose))) throw new IOException("この生成は途中再開に対応していません。");
        entry.text.append(entry.resumePrefix);
        }
        entry.createdAt = time(request.opt("createdAt"), System.currentTimeMillis());
        entry.updatedAt = entry.createdAt;
        entry.hasSourceBody = "text".equals(type) && "manuscript".equals(entry.purpose)
                && request.has("sourceBody") && !request.isNull("sourceBody");
        if (entry.hasSourceBody) entry.sourceBody = string(request, "sourceBody", "", MAX_SOURCE_CHARS);
        entry.hasSourceValue = "text".equals(type) && "metadata".equals(entry.purpose)
                && request.has("sourceValue") && !request.isNull("sourceValue");
        if (entry.hasSourceValue) entry.sourceValue = string(request, "sourceValue", "", "titles".equals(entry.kind) ? 2000 : 100000);
        return entry;
    }

    private static Entry fromStored(JSONObject object) throws IOException {
        Entry entry = fromRequest(object, string(object, "type", "", 16));
        entry.phase = string(object, "phase", "", 24);
        if (!("generating".equals(entry.phase) || terminal(entry.phase))) throw new IOException("生成記録の状態が不正です。");
        entry.seq = sequence(object.opt("seq"));
        entry.updatedAt = time(object.opt("updatedAt"), entry.createdAt);
        entry.text.setLength(0);
        entry.text.append(string(object, "text", "", textLimit(entry)));
        entry.message = string(object, "message", "", MAX_MESSAGE_CHARS);
        if (object.has("image") && !object.isNull("image")) {
            if (!(object.opt("image") instanceof JSONObject) || !"image".equals(entry.type)) throw new IOException("保存した画像情報が不正です。");
            entry.image = cleanImage(object.optJSONObject("image"));
        }
        if ("image".equals(entry.type) && "complete".equals(entry.phase) && entry.image == null) throw new IOException("画像の生成記録が不完全です。");
        return entry;
    }

    private static boolean mergeCandidate(JSONObject owner, String key, Entry entry) throws Exception {
        JSONObject existing = owner.optJSONObject(key);
        if (existing != null && !entry.requestId.equals(existing.optString("requestId"))) return false;
        if (existing != null && existing.has("seq") && safeSequence(existing.opt("seq")) >= entry.seq) return false;
        if (owner.has(key) && !owner.isNull(key) && existing == null) return false;
        JSONObject candidate = new JSONObject();
        put(candidate, "requestId", entry.requestId);
        put(candidate, "seq", entry.seq);
        put(candidate, "text", entry.text.toString());
        put(candidate, "status", entry.phase);
        put(candidate, "message", entry.message);
        put(candidate, "createdAt", entry.createdAt);
        put(candidate, "batchId", entry.batchId);
        put(candidate, "requestSpec", objectSpec(entry));
        if ("metadata".equals(entry.purpose)) {
            put(candidate, "kind", entry.kind);
            put(candidate, "items", "titles".equals(entry.kind) && terminal(entry.phase) ? parseTitles(entry.text.toString()) : new JSONArray());
            boolean sourceKnown = entry.hasSourceValue || (existing != null && existing.opt("sourceValue") instanceof String
                    && !existing.optBoolean("sourceUnavailable", false));
            put(candidate, "sourceValue", entry.hasSourceValue ? entry.sourceValue : sourceKnown ? existing.getString("sourceValue") : "");
            if (!sourceKnown) put(candidate, "sourceUnavailable", true);
        } else {
            put(candidate, "mode", entry.mode);
            boolean sourceKnown = entry.hasSourceBody || (existing != null && existing.opt("sourceBody") instanceof String
                    && !existing.optBoolean("sourceUnavailable", false));
            put(candidate, "sourceBody", entry.hasSourceBody ? entry.sourceBody : sourceKnown ? existing.getString("sourceBody") : "");
            if (!sourceKnown) put(candidate, "sourceUnavailable", true);
        }
        owner.put(key, candidate);
        return true;
    }

    private static boolean mergeImage(JSONObject chapter, Entry entry) throws Exception {
        JSONObject request = chapter.optJSONObject("illustrationRequest");
        boolean matching = request == null || entry.requestId.equals(request.optString("requestId"));
        if (!matching) return false;
        if (chapter.has("illustrationRequest") && !chapter.isNull("illustrationRequest") && request == null) return false;
        boolean changed = false;
        if (terminal(entry.phase) && entry.image != null) {
            JSONArray images = chapter.optJSONArray("illustrations");
            if (images == null && chapter.has("illustrations") && !chapter.isNull("illustrations")) return false;
            if (images == null) images = new JSONArray();
            String id = entry.image.getString("id");
            boolean present = false;
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image != null && id.equals(image.optString("id"))) { present = true; break; }
            }
            if (!present) {
                if (images.length() >= 50) return false;
                images.put(copy(entry.image));
                chapter.put("illustrations", images);
                changed = true;
            }
        }
        if (request != null && request.has("seq") && safeSequence(request.opt("seq")) >= entry.seq) return changed;
        JSONObject next = new JSONObject();
        put(next, "requestId", entry.requestId); put(next, "kind", entry.kind);
        put(next, "status", entry.phase); put(next, "seq", entry.seq); put(next, "createdAt", entry.createdAt);
        chapter.put("illustrationRequest", next);
        chapter.put("illustrationMessage", entry.message);
        return true;
    }

    private static boolean receiptCovers(JSONObject root, Entry entry) {
        if (!terminal(entry.phase)) return false;
        JSONObject receipts = root.optJSONObject("runtimeReceipts");
        JSONObject receipt = receipts == null ? null : receipts.optJSONObject(entry.requestId);
        return receipt != null && Boolean.TRUE.equals(receipt.opt("terminal"))
                && safeSequence(receipt.opt("seq")) >= entry.seq;
    }

    private static JSONArray parseTitles(String text) {
        String clean = text.trim().replaceFirst("(?i)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        List<String> candidates = new ArrayList<>();
        try {
            Object parsed = new JSONTokener(clean).nextValue();
            JSONArray values = parsed instanceof JSONArray ? (JSONArray) parsed
                    : parsed instanceof JSONObject ? ((JSONObject) parsed).optJSONArray("titles") : null;
            if (values != null) for (int i = 0; i < values.length(); i++) if (values.opt(i) instanceof String) candidates.add(values.getString(i));
        } catch (Exception ignored) { }
        if (candidates.isEmpty()) {
            String[] lines = clean.split("\\r?\\n");
            int nonempty = 0; String single = "";
            Pattern numbered = Pattern.compile("^(?:\\d{1,2}[.．、)）:\\s]|[-*・]\\s).*");
            for (String line : lines) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                nonempty++; single = value;
                if (numbered.matcher(value).matches()) {
                    value = value.replaceFirst("^(?:\\d{1,2}[.．、)）:\\s]+|[-*・]\\s*)", "").trim();
                    if (value.startsWith("「") && value.endsWith("」") && value.length() >= 2) value = value.substring(1, value.length() - 1);
                    candidates.add(value);
                }
            }
            if (candidates.isEmpty() && nonempty == 1 && !clean.startsWith("{") && !clean.startsWith("[") && clean.length() <= 2000) candidates.add(single);
        }
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>();
        for (String candidate : candidates) {
            String value = candidate.trim();
            if (!value.isEmpty() && candidate.length() <= 2000 && seen.add(value)) result.put(value);
            if (result.length() >= 20) break;
        }
        return result;
    }

    private static JSONObject cleanImage(JSONObject image) throws IOException {
        String id = string(image, "id", "", 36);
        if (!IMAGE_ID.matcher(id).matches() || !"image/png".equals(string(image, "mime", "", 40))) throw new IOException("画像の参照情報が不正です。");
        long width = integer(image.opt("width")), height = integer(image.opt("height"));
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || width * height > 16L * 1024 * 1024) throw new IOException("画像のサイズ情報が不正です。");
        JSONObject result = new JSONObject();
        put(result, "id", id); put(result, "mime", "image/png"); put(result, "width", width); put(result, "height", height);
        put(result, "createdAt", time(image.opt("createdAt"), System.currentTimeMillis()));
        put(result, "source", string(image, "source", "api", 40));
        // This is the completed image artifact's existing metadata, not request.prompt.
        put(result, "prompt", string(image, "prompt", "", 32000));
        return result;
    }

    private static JSONObject find(JSONArray objects, String id) {
        if (objects == null) return null;
        for (int i = 0; i < objects.length(); i++) {
            JSONObject object = objects.optJSONObject(i);
            if (object != null && id.equals(object.optString("id"))) return object;
        }
        return null;
    }

    private static int textLimit(Entry entry) {
        return "image".equals(entry.type) ? 0 : "metadata".equals(entry.purpose) ? MAX_METADATA_CHARS : MAX_TEXT_CHARS;
    }

    private static boolean terminal(String phase) {
        return "complete".equals(phase) || "error".equals(phase) || "cancelled".equals(phase) || "interrupted".equals(phase);
    }

    private static String requiredIdentifier(JSONObject object, String key, int max) throws IOException {
        String value = string(object, key, "", max);
        if (!IDENTIFIER.matcher(value).matches() || "__proto__".equals(value)
                || "constructor".equals(value) || "prototype".equals(value)) throw new IOException("生成の識別情報が不正です。");
        return value;
    }

    private static String string(JSONObject object, String key, String fallback, int max) throws IOException {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).length() > max) throw new IOException("生成記録の文字情報が不正または長すぎます。");
        return (String) value;
    }

    private static long integer(Object value) throws IOException {
        if (!(value instanceof Number)) throw new IOException("生成記録の数値情報が不正です。");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > MAX_SEQUENCE) throw new IOException("生成記録の数値情報が不正です。");
        return ((Number) value).longValue();
    }

    private static long time(Object value, long fallback) throws IOException {
        return value == null || value == JSONObject.NULL ? fallback : integer(value);
    }

    private static long sequence(Object value) throws IOException { return integer(value); }
    private static long safeSequence(Object value) { try { return sequence(value); } catch (IOException ignored) { return -1; } }
    private static long nextSequence(long value) throws IOException {
        if (value >= MAX_SEQUENCE) throw new IOException("生成記録の連番が上限に達しました。");
        return value + 1;
    }

    private void ensureOpen() throws IOException { if (closed) throw new IOException("生成記録は終了しています。"); }

    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (Exception error) { throw new IllegalStateException("Invalid internal journal record", error); }
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (Exception error) { throw new IllegalStateException("Invalid internal journal value", error); }
    }

    private static final class Entry {
        String type, requestId, bookId, chapterId, purpose, kind, mode;
        String batchId = "", model = "", instructions = "", prompt = "", resumePrefix = "";
        String phase = "generating";
        String message = "";
        String sourceBody = "", sourceValue = "";
        boolean hasSourceBody, hasSourceValue;
        long seq, createdAt, updatedAt;
        final StringBuilder text = new StringBuilder();
        JSONObject image;
        JSONObject snapshot() { return snapshot(true, true); }
        JSONObject snapshot(boolean includeText, boolean includeSource) {
            JSONObject value = new JSONObject();
            put(value, "type", type); put(value, "requestId", requestId); put(value, "bookId", bookId);
            put(value, "chapterId", chapterId); put(value, "purpose", purpose); put(value, "kind", kind); put(value, "mode", mode);
            put(value, "phase", phase); put(value, "seq", seq);
            if (includeText) put(value, "text", text.toString());
            put(value, "image", image == null ? JSONObject.NULL : copy(image)); put(value, "message", message);
            put(value, "createdAt", createdAt); put(value, "updatedAt", updatedAt);
            put(value, "batchId", batchId);
            if (includeSource) {
                put(value,"model",model); put(value,"instructions",instructions); put(value,"prompt",prompt);
                put(value,"resumePrefix",resumePrefix); put(value,"requestSpec",objectSpec(this));
            }
            if (includeSource && hasSourceBody) put(value, "sourceBody", sourceBody);
            if (includeSource && hasSourceValue) put(value, "sourceValue", sourceValue);
            return value;
        }
    }

    private static JSONObject objectSpec(Entry e) {
        JSONObject o=new JSONObject();put(o,"model",e.model);put(o,"instructions",e.instructions);put(o,"prompt",e.prompt);return o;
    }
}
