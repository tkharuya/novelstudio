package jp.haruya.novelstudio;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Tests the real journal. Only Android Context and AtomicFile have JVM replacements. */
public final class JournalChecks {
    private static int checks;
    private static Path root;
    private static final String IMAGE_ID = "bf919d0a-c5b3-4a48-b9cd-87ab5567adac";

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("novelstudio-journal-");
        try {
            lifecycle(); debounce(); restart(); manuscriptMerge(); metadataMerge(); imageMerge();
            limits(); corruption(); diskFailures(); concurrentDeltas();
            System.out.println("PASS: " + checks + " journal assertions");
            System.out.println("Production GenerationJournal; JVM Context/AtomicFile shims; synthetic data; no network or paid API calls.");
        } finally {
            AtomicFile.failStart = false; AtomicFile.failFinish = false;
            try (Stream<Path> paths = Files.walk(root)) {
                Path[] entries = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path entry : entries) Files.deleteIfExists(entry);
            }
        }
    }

    private static void lifecycle() throws Exception {
        try (Session s = session("lifecycle")) {
            JSONObject q = request("job-1").put("sourceBody", "開始時本文").put("prompt", "PROMPT_CANARY")
                    .put("instructions", "INSTRUCTIONS_CANARY").put("apiKey", "KEY_CANARY");
            s.journal.begin(q, "text");
            eq("begin durable phase", "generating", disk(s).getString("phase"));
            eq("begin sequence", 0L, disk(s).getLong("seq"));
            eq("source saved", "開始時本文", disk(s).getString("sourceBody"));
            ok("request credentials omitted", !read(s.file()).contains("KEY_CANARY"));
            ok("resume prompt retained", read(s.file()).contains("PROMPT_CANARY") && read(s.file()).contains("INSTRUCTIONS_CANARY"));
            q.put("sourceBody", "mutated"); eq("request defensively copied", "開始時本文", snapshot(s).getString("sourceBody"));
            io("duplicate begin rejected", () -> s.journal.begin(request("job-1"), "text"));
            eq("duplicate does not add job", 1, s.journal.snapshots().length());
            JSONObject first = s.journal.record("generation", event("job-1", "delta").put("delta", "初稿").put("bookId", "wrong"));
            eq("sequence assigned", 1L, first.getLong("seq"));
            eq("target authoritative", "book-1", first.getString("bookId"));
            ok("compact delta event", first.has("delta") && !first.has("text") && !first.has("sourceBody"));
            s.journal.record("generation", event("job-1", "delta").put("delta", "続き"));
            eq("deltas accumulated", "初稿続き", snapshot(s).getString("text"));
            snapshot(s).put("text", "tampered"); eq("snapshot defensive", "初稿続き", snapshot(s).getString("text"));
            ok("stale delta ignored", s.journal.record("generation", event("job-1", "delta").put("seq", 1).put("delta", "duplicate")) == null);
            eq("stale sequence unchanged", 2L, snapshot(s).getLong("seq"));
            io("wrong event type rejected", () -> s.journal.record("illustration", event("job-1", "error")));
            s.journal.acknowledge("job-1", 2); eq("nonterminal ACK ignored", 1, s.journal.snapshots().length());
            s.journal.flush(); eq("flush durable delta", "初稿続き", disk(s).getString("text"));
            JSONObject done = s.journal.record("generation", event("job-1", "complete").put("text", "完成稿").put("accessToken", "EVENT_CANARY"));
            eq("terminal sequence", 3L, done.getLong("seq"));
            eq("terminal forced to disk", "完成稿", disk(s).getString("text"));
            ok("event whitelist", !done.toString().contains("EVENT_CANARY") && !read(s.file()).contains("EVENT_CANARY") && !read(s.file()).contains("KEY_CANARY"));
            ok("late cancellation ignored", s.journal.record("generation", event("job-1", "cancelled")) == null);
            ok("late delta ignored", s.journal.record("generation", event("job-1", "delta").put("delta", "late")) == null);
            eq("terminal immutable", "complete", snapshot(s).getString("phase"));
            s.journal.acknowledge("job-1", 2); eq("old ACK ignored", 1, s.journal.snapshots().length());
            s.journal.acknowledge("job-1", 3); eq("exact terminal ACK removes", 0, s.journal.snapshots().length());
            eq("ACK persisted", 0, diskRecords(s).length());
        }
    }

    private static void debounce() throws Exception {
        try (Session s = session("debounce")) {
            s.journal.begin(request("debounce-job"), "text");
            s.journal.record("generation", event("debounce-job", "delta").put("delta", "scheduled"));
            Thread.sleep(1350); eq("scheduled delta checkpoint", "scheduled", disk(s).getString("text"));
            AtomicFile.failStart = true;
            s.journal.record("generation", event("debounce-job", "delta").put("delta", " unsaved"));
            Thread.sleep(1350); eq("async failure preserves RAM", "scheduled unsaved", snapshot(s).getString("text"));
            io("async failure surfaces on next delta", () -> s.journal.record("generation", event("debounce-job", "delta").put("delta", "rejected")));
            AtomicFile.failStart = false;
            s.journal.record("generation", event("debounce-job", "error").put("text", "").put("message", "保存を中止"));
            eq("empty error retains partial", "scheduled unsaved", disk(s).getString("text"));
            eq("terminal repairs async failure", "error", disk(s).getString("phase"));
        } finally { AtomicFile.failStart = false; }
    }

    private static void restart() throws Exception {
        try (Session s = session("restart")) {
            s.journal.begin(request("restart-job").put("sourceBody", "start"), "text");
            s.journal.record("generation", event("restart-job", "delta").put("delta", "partial"));
            s.journal.flush(); s.reopen();
            eq("restart marks interrupted", "interrupted", snapshot(s).getString("phase"));
            eq("restart partial retained", "partial", snapshot(s).getString("text"));
            eq("restart increments sequence", 2L, snapshot(s).getLong("seq"));
            ok("restart no auto resend", snapshot(s).getString("message").contains("自動で再送信しません"));
            eq("restart interruption durable", "interrupted", disk(s).getString("phase"));
            s.reopen(); eq("second restart no mutation", 2L, snapshot(s).getLong("seq"));
            eq("restart source retained", "start", snapshot(s).getString("sourceBody"));
            s.journal.acknowledge("restart-job", 2); s.journal.begin(request("image-restart"), "image"); s.reopen();
            eq("image restart interrupted", "interrupted", snapshot(s).getString("phase"));
            ok("restart does not invent image", snapshot(s).isNull("image"));
        }
    }

    private static void manuscriptMerge() throws Exception {
        try (Session s = session("merge")) {
            s.journal.begin(request("merge-job").put("sourceBody", "original source").put("mode", "continue"), "text");
            s.journal.record("generation", event("merge-job", "complete").put("text", "continuation"));
            JSONObject before = state(); chapter(before).put("candidate", json("requestId", "merge-job", "seq", 0, "sourceBody", "prior UI"));
            JSONObject after = new JSONObject(s.journal.mergeInto(before.toString())), c = chapter(after), candidate = c.getJSONObject("candidate");
            eq("body not auto-adopted", "edited body", c.getString("body"));
            eq("chapter title untouched", "chapter title", c.getString("title"));
            eq("review flag untouched", true, c.getBoolean("reviewed"));
            eq("previous draft untouched", "older draft", c.getString("previousBody"));
            eq("generated candidate merged", "continuation", candidate.getString("text"));
            eq("start source retained", "original source", candidate.getString("sourceBody"));
            eq("mode retained", "continue", candidate.getString("mode"));
            eq("candidate terminal phase", "complete", candidate.getString("status"));
            eq("candidate sequence", 1L, candidate.getLong("seq"));
            eq("merge idempotent", after.toString(), s.journal.mergeInto(after.toString()));
            JSONObject newer = state(); chapter(newer).put("candidate", json("requestId", "newer", "text", "newer draft"));
            eq("newer request protected", newer.toString(), s.journal.mergeInto(newer.toString()));
            JSONObject missing = state(); book(missing).put("chapters", new JSONArray());
            eq("removed chapter not recreated", missing.toString(), s.journal.mergeInto(missing.toString()));
            missing.put("books", new JSONArray()); eq("removed book not recreated", missing.toString(), s.journal.mergeInto(missing.toString()));
            eq("unapplied result retained", 1, s.journal.snapshots().length());
            JSONObject receipts = state(); receipts.put("runtimeReceipts", json("merge-job", json("seq", 1, "terminal", true)));
            eq("receipt prevents resurrection", receipts.toString(), s.journal.mergeInto(receipts.toString()));
            receipts.getJSONObject("runtimeReceipts").getJSONObject("merge-job").put("seq", 0);
            ok("older receipt cannot hide result", chapter(new JSONObject(s.journal.mergeInto(receipts.toString()))).optJSONObject("candidate") != null);
            receipts.getJSONObject("runtimeReceipts").getJSONObject("merge-job").put("seq", 9).put("terminal", false);
            ok("nonterminal receipt cannot hide result", chapter(new JSONObject(s.journal.mergeInto(receipts.toString()))).optJSONObject("candidate") != null);
            io("broken draft rejected", () -> s.journal.mergeInto("broken JSON"));
            String other = "{\"app\":\"Other\",\"books\":[]}"; eq("other app preserved", other, s.journal.mergeInto(other));
        }
        try (Session s = session("source-fallback")) {
            s.journal.begin(request("source-job"), "text"); s.journal.record("generation", event("source-job", "complete").put("text", "candidate"));
            JSONObject candidate = chapter(new JSONObject(s.journal.mergeInto(state().toString()))).getJSONObject("candidate");
            ok("missing source flagged", candidate.getBoolean("sourceUnavailable"));
            eq("current body never substituted", "", candidate.getString("sourceBody"));
            JSONObject known = state(); chapter(known).put("candidate", json("requestId", "source-job", "sourceBody", "persisted source"));
            candidate = chapter(new JSONObject(s.journal.mergeInto(known.toString()))).getJSONObject("candidate");
            eq("matching prior source inherited", "persisted source", candidate.getString("sourceBody"));
            ok("known source not flagged", !candidate.optBoolean("sourceUnavailable", false));
            chapter(known).getJSONObject("candidate").put("sourceUnavailable", true);
            candidate = chapter(new JSONObject(s.journal.mergeInto(known.toString()))).getJSONObject("candidate");
            eq("unknown prior source not trusted", "", candidate.getString("sourceBody"));
            ok("unknown prior source remains flagged", candidate.getBoolean("sourceUnavailable"));
        }
    }

    private static void metadataMerge() throws Exception {
        try (Session s = session("metadata")) {
            JSONObject q = request("titles-job").put("purpose", "metadata").put("kind", "titles").put("sourceValue", "old title"); q.remove("chapterId");
            s.journal.begin(q, "text");
            s.journal.record("generation", event("titles-job", "complete").put("text", "```json\n[\"新題A\",\"新題A\",\"新題B\"]\n```"));
            JSONObject b = book(new JSONObject(s.journal.mergeInto(state().toString()))), candidate = b.getJSONObject("titleCandidate");
            eq("book title not auto-adopted", "work title", b.getString("title"));
            eq("title parsing and dedup", "[\"新題A\",\"新題B\"]", candidate.getJSONArray("items").toString());
            eq("title source retained", "old title", candidate.getString("sourceValue"));
            s.journal.begin(request("synopsis-job").put("purpose", "metadata").put("kind", "synopsis").put("sourceValue", "old synopsis"), "text");
            s.journal.record("generation", event("synopsis-job", "complete").put("text", "new synopsis"));
            b = book(new JSONObject(s.journal.mergeInto(state().toString())));
            eq("synopsis not auto-adopted", "edited synopsis", b.getString("synopsis"));
            eq("synopsis candidate", "new synopsis", b.getJSONObject("synopsisCandidate").getString("text"));
            eq("synopsis source retained", "old synopsis", b.getJSONObject("synopsisCandidate").getString("sourceValue"));
            s.journal.begin(request("metadata-limit").put("purpose", "metadata").put("kind", "titles"), "text");
            s.journal.record("generation", event("metadata-limit", "delta").put("delta", "partial"));
            io("metadata bound enforced", () -> s.journal.record("generation", event("metadata-limit", "complete").put("text", repeat('x', 200001))));
            eq("metadata bound retains partial", "partial", s.journal.snapshots().getJSONObject(2).getString("text"));
            s.journal.record("generation", event("metadata-limit", "cancelled").put("text", ""));
            eq("cancel preserves partial", "partial", s.journal.snapshots().getJSONObject(2).getString("text"));
        }
    }

    private static void imageMerge() throws Exception {
        try (Session s = session("images")) {
            s.journal.begin(request("image-job").put("prompt", "RAW_PROMPT_CANARY").put("sourceBody", "ignored"), "image");
            ok("image has no text source", !snapshot(s).has("sourceBody"));
            s.journal.record("illustration", event("image-job", "complete").put("image", image(IMAGE_ID).put("apiKey", "KEY_CANARY")));
            ok("image metadata whitelist", !read(s.file()).contains("CANARY"));
            eq("completed artifact prompt retained", "saved image instructions", snapshot(s).getJSONObject("image").getString("prompt"));
            snapshot(s).getJSONObject("image").put("id", "tampered"); eq("nested image copied", IMAGE_ID, snapshot(s).getJSONObject("image").getString("id"));
            JSONObject merged = new JSONObject(s.journal.mergeInto(state().toString()));
            eq("image attached", IMAGE_ID, chapter(merged).getJSONArray("illustrations").getJSONObject(0).getString("id"));
            eq("image request terminal for ACK", "complete", chapter(merged).getJSONObject("illustrationRequest").getString("status"));
            eq("image sequence retained", 1L, chapter(merged).getJSONObject("illustrationRequest").getLong("seq"));
            eq("image replay dedup", 1, chapter(new JSONObject(s.journal.mergeInto(merged.toString()))).getJSONArray("illustrations").length());
            JSONObject removed = state(); removed.put("runtimeReceipts", json("image-job", json("seq", 1, "terminal", true)));
            eq("receipt respects later image removal", removed.toString(), s.journal.mergeInto(removed.toString()));
            JSONObject full = state(); JSONArray images = new JSONArray();
            for (int i = 0; i < 50; i++) images.put(image(String.format("00000000-0000-4000-8000-%012d", i)));
            chapter(full).put("illustrations", images); eq("full image chapter unchanged", full.toString(), s.journal.mergeInto(full.toString()));
            eq("full target retains pending image", 1, s.journal.snapshots().length());
            JSONObject newer = state(); chapter(newer).put("illustrationRequest", json("requestId", "newer-image", "status", "generating"));
            eq("newer image request protected", newer.toString(), s.journal.mergeInto(newer.toString()));
            s.reopen(); eq("completed image survives restart", IMAGE_ID, snapshot(s).getJSONObject("image").getString("id"));
            eq("completed image sequence unchanged", 1L, snapshot(s).getLong("seq"));
        }
        try (Session s = session("invalid-images")) {
            s.journal.begin(request("bad-image"), "image");
            io("missing image rejected", () -> s.journal.record("illustration", event("bad-image", "complete")));
            io("image path traversal rejected", () -> s.journal.record("illustration", event("bad-image", "complete").put("image", image("../outside"))));
            io("nonnormalized MIME rejected", () -> s.journal.record("illustration", event("bad-image", "complete").put("image", image(IMAGE_ID).put("mime", "image/jpeg"))));
            io("image dimensions bounded", () -> s.journal.record("illustration", event("bad-image", "complete").put("image", image(IMAGE_ID).put("width", 4097))));
            io("image delta rejected", () -> s.journal.record("illustration", event("bad-image", "delta").put("delta", "text")));
            eq("invalid image request unchanged", 0L, snapshot(s).getLong("seq"));
        }
    }

    private static void limits() throws Exception {
        try (Session s = session("limits")) {
            io("prototype ID rejected", () -> s.journal.begin(request("__proto__"), "text"));
            io("path ID rejected", () -> s.journal.begin(request("../escape"), "text"));
            io("bad purpose rejected", () -> s.journal.begin(request("bad-purpose").put("purpose", "login"), "text"));
            io("noninteger time rejected", () -> s.journal.begin(request("bad-time").put("createdAt", 1.5), "text"));
            io("source body bounded", () -> s.journal.begin(request("large-source").put("sourceBody", repeat('x', 4000001)), "text"));
            io("source title bounded", () -> s.journal.begin(request("large-title").put("purpose", "metadata").put("kind", "titles").put("sourceValue", repeat('x', 2001)), "text"));
            eq("bad inputs not recorded", 0, s.journal.snapshots().length());
            s.journal.begin(request("text-limit"), "text"); s.journal.record("generation", event("text-limit", "delta").put("delta", "retained"));
            io("output bound enforced", () -> s.journal.record("generation", event("text-limit", "complete").put("text", repeat('x', GenerationJournal.MAX_TEXT_CHARS + 1))));
            eq("oversize retains partial", "retained", snapshot(s).getString("text"));
            io("fractional event sequence rejected", () -> s.journal.record("generation", event("text-limit", "delta").put("seq", 1.5).put("delta", "bad")));
            io("unsafe event sequence rejected", () -> s.journal.record("generation", event("text-limit", "delta").put("seq", 9007199254740992d).put("delta", "bad")));
        }
        try (Session s = session("job-capacity")) {
            for (int i = 0; i < 32; i++) s.journal.begin(request("image-slot-" + i), "image");
            io("33rd job refused before API", () -> s.journal.begin(request("image-slot-32"), "image"));
            eq("pending jobs not evicted", 32, s.journal.snapshots().length()); eq("all jobs durable", 32, diskRecords(s).length());
        }
        try (Session s = session("byte-capacity")) {
            s.journal.begin(request("reserve-1"), "text"); s.journal.begin(request("reserve-2"), "text");
            io("worst case capacity reserved before API", () -> s.journal.begin(request("reserve-3"), "text"));
            eq("capacity failure retains older jobs", 2, s.journal.snapshots().length()); eq("refused job absent on disk", 2, diskRecords(s).length());
        }
    }

    private static void corruption() throws Exception {
        File dir = Files.createDirectory(root.resolve("corrupt")).toFile(), file = new File(dir, "generation-journal.json"); TestContext context = new TestContext(dir);
        String invalid = "{not valid"; Files.write(file.toPath(), invalid.getBytes(StandardCharsets.UTF_8));
        io("corruption fails closed", () -> new GenerationJournal(context)); eq("corrupt file unchanged", invalid, read(file));
        byte[] badUtf8 = {(byte)0xc3, (byte)0x28}; Files.write(file.toPath(), badUtf8);
        io("malformed UTF8 rejected", () -> new GenerationJournal(context)); ok("invalid bytes unchanged", Arrays.equals(badUtf8, Files.readAllBytes(file.toPath())));
        Files.delete(file.toPath()); try (GenerationJournal j = new GenerationJournal(context)) { j.begin(request("duplicate-file"), "image"); }
        JSONObject document = new JSONObject(read(file)); JSONArray records = document.getJSONArray("records"); records.put(new JSONObject(records.getJSONObject(0).toString()));
        String duplicate = document.toString(); Files.write(file.toPath(), duplicate.getBytes(StandardCharsets.UTF_8));
        io("duplicate disk records rejected", () -> new GenerationJournal(context)); eq("duplicate file preserved", duplicate, read(file));
        records.remove(1); Files.write(file.toPath(), document.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (GenerationJournal j = new GenerationJournal(context)) {
            eq("AtomicFile backup restored", "duplicate-file", j.snapshots().getJSONObject(0).getString("requestId"));
            eq("backup active job interrupted", "interrupted", j.snapshots().getJSONObject(0).getString("phase"));
        }
    }

    private static void diskFailures() throws Exception {
        try (Session s = session("failures")) {
            AtomicFile.failStart = true; io("begin disk failure blocks start", () -> s.journal.begin(request("failed-begin"), "text"));
            eq("failed begin absent from RAM", 0, s.journal.snapshots().length()); AtomicFile.failStart = false; s.journal.flush();
            s.journal.begin(request("terminal-fail"), "text"); s.journal.record("generation", event("terminal-fail", "delta").put("delta", "partial")); s.journal.flush();
            AtomicFile.failStart = true;
            io("terminal failure not reported durable", () -> s.journal.record("generation", event("terminal-fail", "complete").put("text", "complete in RAM")));
            eq("terminal result retained in RAM", "complete in RAM", snapshot(s).getString("text"));
            eq("terminal phase retained in RAM", "complete", snapshot(s).getString("phase"));
            eq("previous durable partial retained", "partial", disk(s).getString("text"));
            ok("late cancel cannot overwrite failed complete", s.journal.record("generation", event("terminal-fail", "cancelled")) == null);
            AtomicFile.failStart = false; s.journal.flush(); eq("flush recovers completion", "complete in RAM", disk(s).getString("text"));
            long seq = snapshot(s).getLong("seq"); AtomicFile.failStart = true;
            io("ACK disk failure reported", () -> s.journal.acknowledge("terminal-fail", seq)); eq("failed ACK restores entry", 1, s.journal.snapshots().length());
            AtomicFile.failStart = false; s.journal.acknowledge("terminal-fail", seq); eq("ACK retry succeeds", 0, s.journal.snapshots().length());
        } finally { AtomicFile.failStart = false; }
        try (Session s = session("rename-failure")) {
            s.journal.begin(request("rename-fail"), "text"); AtomicFile.failFinish = true;
            io("silent AtomicFile rename failure detected", () -> s.journal.record("generation", event("rename-fail", "complete").put("text", "retained result")));
            eq("rename failure result retained", "retained result", snapshot(s).getString("text"));
            eq("rename failure old disk preserved", "generating", disk(s).getString("phase"));
            AtomicFile.failFinish = false; s.journal.flush(); s.reopen(); eq("rename repair survives restart", "retained result", snapshot(s).getString("text"));
        } finally { AtomicFile.failFinish = false; }
    }

    private static void concurrentDeltas() throws Exception {
        try (Session s = session("concurrent")) {
            s.journal.begin(request("concurrent-job"), "text"); CountDownLatch start = new CountDownLatch(1);
            Set<Long> sequences = Collections.synchronizedSet(new HashSet<>()); AtomicReference<Throwable> failure = new AtomicReference<>(); Thread[] threads = new Thread[2];
            for (int t = 0; t < 2; t++) {
                final String delta = t == 0 ? "a" : "b";
                threads[t] = new Thread(() -> { try { start.await(); for (int i = 0; i < 50; i++) sequences.add(s.journal.record("generation", event("concurrent-job", "delta").put("delta", delta)).getLong("seq")); } catch (Throwable error) { failure.compareAndSet(null, error); } });
                threads[t].start();
            }
            start.countDown(); for (Thread thread : threads) thread.join(); if (failure.get() != null) throw new AssertionError("Concurrent test failed", failure.get());
            eq("concurrent unique sequence", 100, sequences.size()); eq("concurrent final sequence", 100L, snapshot(s).getLong("seq"));
            String text = snapshot(s).getString("text"); eq("all deltas retained", 100, text.length());
            eq("writer A retained", 50, text.length() - text.replace("a", "").length()); eq("writer B retained", 50, text.length() - text.replace("b", "").length());
            s.journal.record("generation", event("concurrent-job", "complete")); eq("concurrent completion durable", text, disk(s).getString("text")); eq("completion sequence", 101L, disk(s).getLong("seq"));
        }
    }

    private static Session session(String name) throws Exception { return new Session(Files.createDirectory(root.resolve(name)).toFile()); }
    private static JSONObject snapshot(Session s) throws Exception { return s.journal.snapshots().getJSONObject(0); }
    private static JSONArray diskRecords(Session s) throws Exception { return new JSONObject(read(s.file())).getJSONArray("records"); }
    private static JSONObject disk(Session s) throws Exception { return diskRecords(s).getJSONObject(0); }
    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static JSONObject request(String id) throws Exception { return json("requestId", id, "bookId", "book-1", "chapterId", "chapter-1", "mode", "new", "createdAt", 1000); }
    private static JSONObject event(String id, String phase) throws Exception { return json("requestId", id, "phase", phase); }
    private static JSONObject image(String id) throws Exception { return json("id", id, "mime", "image/png", "width", 64, "height", 64, "createdAt", 1000, "source", "api", "prompt", "saved image instructions"); }
    private static JSONObject state() throws Exception {
        JSONObject c = json("id", "chapter-1", "title", "chapter title", "body", "edited body", "reviewed", true, "previousBody", "older draft", "candidate", JSONObject.NULL, "illustrations", new JSONArray(), "illustrationRequest", JSONObject.NULL);
        JSONObject b = json("id", "book-1", "title", "work title", "synopsis", "edited synopsis", "chapters", new JSONArray().put(c));
        return json("app", "NovelStudio", "schemaVersion", 1, "books", new JSONArray().put(b), "runtimeReceipts", new JSONObject());
    }
    private static JSONObject book(JSONObject state) throws Exception { return state.getJSONArray("books").getJSONObject(0); }
    private static JSONObject chapter(JSONObject state) throws Exception { return book(state).getJSONArray("chapters").getJSONObject(0); }
    private static JSONObject json(Object... fields) throws Exception { JSONObject v = new JSONObject(); for (int i = 0; i < fields.length; i += 2) v.put((String) fields[i], fields[i + 1]); return v; }
    private static String repeat(char c, int count) { char[] v = new char[count]; Arrays.fill(v, c); return new String(v); }
    private static void ok(String name, boolean value) { checks++; if (!value) throw new AssertionError(name); }
    private static void eq(String name, Object expected, Object actual) { ok(name + ": expected " + expected + " got " + actual, expected == null ? actual == null : expected.equals(actual)); }
    private static void io(String name, Throwing action) throws Exception { try { action.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError(name + " did not throw IOException"); }
    private interface Throwing { void run() throws Exception; }
    private static final class TestContext extends Context {
        private final File dir; TestContext(File dir) { this.dir = dir; }
        @Override public File getFilesDir() { return dir; }
    }
    private static final class Session implements AutoCloseable {
        final File dir; final TestContext context; GenerationJournal journal;
        Session(File dir) throws IOException { this.dir = dir; context = new TestContext(dir); journal = new GenerationJournal(context); }
        File file() { return new File(dir, "generation-journal.json"); }
        void reopen() throws IOException { journal.close(); journal = new GenerationJournal(context); }
        @Override public void close() throws IOException { journal.close(); }
    }
}
