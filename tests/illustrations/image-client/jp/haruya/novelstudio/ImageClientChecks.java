package jp.haruya.novelstudio;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/** Offline-only API contract, billing replay and cancellation regressions. */
public final class ImageClientChecks {
    private static final String KEY = String.format("sk-fixture-%040d", 0);
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jXioAAAAASUVORK5CYII=";
    private static final List<FakeConnection> opened = new ArrayList<>();
    private static FakeConnection next;
    private static int checks;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) throws IOException {
                synchronized (opened) {
                    if (!"https://api.openai.com/v1/images/generations".equals(url.toString())) {
                        throw new IOException("Unexpected endpoint in offline test");
                    }
                    if (next == null) throw new IOException("Offline fixture missing; no network allowed");
                    FakeConnection result = next;
                    next = null;
                    opened.add(result);
                    return result;
                }
            }
        } : null);
        testConfiguration();
        testSuccess();
        testHttpFailures();
        testInvalidResponses();
        testTimeoutNoRetry();
        testCancelAndDuplicate();
        testClear();
        testStorageFailure();
        System.out.println("PASS " + checks + " offline image API checks; no real network or API calls");
    }

    private static void testConfiguration() throws Exception {
        try (Harness h = harness()) {
            ok(!h.client.status().getBoolean("configured"), "No configuration by default");
            ok("illustration-api".equals(SecureStore.namespace), "Separate encrypted namespace");
            expectConfigureFailure(h, "", ImageGenerationClient.DEFAULT_MODEL);
            expectConfigureFailure(h, "short", ImageGenerationClient.DEFAULT_MODEL);
            expectConfigureFailure(h, KEY + "\nheader", ImageGenerationClient.DEFAULT_MODEL);
            expectConfigureFailure(h, KEY, "untrusted-model");
            h.client.generate(request("unconfigured"));
            ok("error".equals(h.terminal().getString("phase")), "Unconfigured generation rejects");
            ok(openCount() == 0, "No HTTP before explicit key configuration");
            h.configure();
            ok(h.client.status().getBoolean("configured"), "Configured state");
            ok(!h.client.status().toString().contains(KEY), "Status omits full key");
            ok(ImageGenerationClient.DEFAULT_MODEL.equals(h.client.status().getString("model")), "Verified default model");
            SecureStore.failWrite = true;
            try { h.client.configure(String.format("sk-fixture-alternate-%040d", 1), "gpt-image-2.5-sunburst"); fail("Configure write should fail"); }
            catch (Exception expected) { ok(!expected.getMessage().contains("CANARY"), "Configure storage error is fixed text"); }
            ok(ImageGenerationClient.DEFAULT_MODEL.equals(h.client.status().getString("model")), "Failed configure preserves model");
            SecureStore.failWrite = false;
        }
    }

    private static void testSuccess() throws Exception {
        try (Harness h = harness()) {
            h.configure();
            FakeConnection connection = fixture(200, validBody());
            h.client.generate(request("success"));
            JSONObject result = h.terminal();
            ok("complete".equals(result.getString("phase")), "Successful image completes");
            ok(h.images.saves == 1, "Successful image committed once");
            ok("api".equals(result.getJSONObject("image").getString("source")), "Native image metadata source");
            ok(!h.client.status().getBoolean("busy"), "Success clears busy");
            JSONObject sent = new JSONObject(new String(connection.request.toByteArray(), StandardCharsets.UTF_8));
            ok(sent.getInt("n") == 1, "One generated image");
            ok("1024x1024".equals(sent.getString("size")), "Bounded documented size");
            ok("medium".equals(sent.getString("quality")), "Explicit documented quality");
            ok("png".equals(sent.getString("output_format")), "PNG output requested");
            ok(!sent.has("response_format"), "Legacy response_format omitted");
            ok(!sent.getBoolean("stream"), "Complete JSON response requested");
            ok(("Bearer " + KEY).equals(connection.getRequestProperty("Authorization")), "Native Authorization header");
            ok("POST".equals(connection.getRequestMethod()), "POST transport");
            ok(!connection.getInstanceFollowRedirects(), "Redirects disabled");
            ok(connection.getReadTimeout() == 180000, "Read timeout is 180 seconds");
            ok(connection.fixedLength == connection.request.size(), "Unbuffered fixed-length request");
            ok(openCount() == 1, "Success sends exactly one request");
        }
        try (Harness h = harness()) {
            h.configure();
            FakeConnection connection = fixture(200, "\ufeff" + validBody());
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(compressed)) { out.write(connection.body); }
            connection.body = compressed.toByteArray();
            connection.encoding = "gzip";
            h.client.generate(request("gzip"));
            ok("complete".equals(h.terminal().getString("phase")), "Gzip and UTF-8 BOM accepted once");
            ok("gzip".equals(connection.getRequestProperty("Accept-Encoding")), "Explicit gzip ownership");
        }
    }

    private static void testHttpFailures() throws Exception {
        for (int status : new int[] {400, 401, 403, 429, 500, 302}) {
            try (Harness h = harness()) {
                h.configure();
                fixture(status, "{\"error\":{\"code\":\"CANARY_SECRET_REFRESH_123\",\"message\":\"" + KEY + "\"}}");
                h.client.generate(request("http_" + status));
                JSONObject result = h.terminal();
                ok("error".equals(result.getString("phase")), "HTTP " + status + " rejected");
                ok(!result.toString().contains("CANARY") && !result.toString().contains(KEY), "HTTP " + status + " no secret echo");
                ok(!h.client.status().toString().contains("CANARY") && !h.client.status().toString().contains(KEY), "HTTP " + status + " sanitized status");
                ok(h.images.saves == 0 && openCount() == 1, "HTTP " + status + " no commit or retry");
            }
        }
        try (Harness h = harness()) {
            h.configure();
            fixture(429, "{\"error\":{\"code\":\"insufficient_quota\"}}");
            h.client.generate(request("quota"));
            ok(h.terminal().getString("message").contains("予算上限"), "Known quota code has actionable fixed text");
        }
    }

    private static void testInvalidResponses() throws Exception {
        String[] bodies = {"<html>CANARY_PRIVATE_BODY</html>", "{}", "{\"data\":[]}",
                "{\"data\":[{\"url\":\"https://untrusted.example/image\"}]}",
                "{\"data\":[{\"b64_json\":\"%%%\"}]}",
                "{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}",
                "{\"error\":{\"message\":\"CANARY_SECRET\"}}",
                "{\"output_format\":\"svg\",\"data\":[{\"b64_json\":\"" + PNG + "\"}]}"};
        for (int i = 0; i < bodies.length; i++) {
            try (Harness h = harness()) {
                h.configure();
                fixture(200, bodies[i]);
                h.client.generate(request("invalid_" + i));
                JSONObject result = h.terminal();
                ok("error".equals(result.getString("phase")), "Invalid response " + i + " rejected");
                ok(!result.toString().contains("CANARY") && h.images.saves == 0, "Invalid response " + i + " not echoed or committed");
            }
        }
        try (Harness h = harness()) {
            h.configure();
            FakeConnection connection = fixture(200, validBody());
            connection.advertisedLength = 24 * 1024 * 1024 + 1;
            h.client.generate(request("oversize"));
            ok("error".equals(h.terminal().getString("phase")) && h.images.saves == 0, "Oversized response bounded before reading");
        }
    }

    private static void testTimeoutNoRetry() throws Exception {
        for (boolean beforeWrite : new boolean[] {true, false}) {
            try (Harness h = harness()) {
                h.configure();
                FakeConnection connection = fixture(200, validBody());
                connection.outputTimeout = beforeWrite;
                connection.responseTimeout = !beforeWrite;
                h.client.generate(request("timeout_" + beforeWrite));
                JSONObject result = h.terminal();
                ok("error".equals(result.getString("phase")), "Timeout terminates");
                ok(!result.toString().contains("CANARY"), "Timeout exception is not echoed");
                ok(openCount() == 1 && connection.outputAttempts == 1 && h.images.saves == 0, "No automatic retry around billed POST");
            }
        }
    }

    private static void testCancelAndDuplicate() throws Exception {
        try (Harness h = harness()) {
            h.configure();
            FakeConnection connection = fixture(200, validBody());
            connection.blockRead = true;
            JSONObject request = request("cancel");
            h.client.generate(request);
            ok(connection.readEntered.await(3, TimeUnit.SECONDS), "Response read started");
            h.client.generate(request);
            ok(openCount() == 1 && h.countTerminal() == 0, "Repeated active ID sends no second request or error");
            try { h.client.configure(KEY, "gpt-image-2.5-sunburst"); fail("Busy configuration should fail"); }
            catch (Exception expected) { ok(true, "Cannot change key during generation"); }
            h.client.cancel();
            ok("cancelled".equals(h.terminal().getString("phase")), "Cancellation event");
            ok(connection.disconnected, "Cancellation disconnects HTTP");
            ok(!h.client.status().getBoolean("busy"), "Cancellation clears busy");
            fixture(200, validBody());
            h.resetEvents();
            h.client.generate(request("after_cancel"));
            ok("complete".equals(h.terminal().getString("phase")), "New request can complete after cancellation");
            ok(h.images.saves == 1 && h.countTerminal() == 1, "Cancelled worker cannot commit or send a late event");
        }
        try (Harness h = harness()) {
            h.configure();
            FakeConnection connection = fixture(200, validBody());
            connection.blockRead = true;
            h.client.generate(request("close"));
            ok(connection.readEntered.await(3, TimeUnit.SECONDS), "Close test response started");
            h.client.close();
            ok(connection.disconnected && !h.client.status().getBoolean("busy"), "Close disconnects and ends busy state");
            ok(h.images.saves == 0 && h.countTerminal() == 0, "Close publishes no completion");
        }
    }

    private static void testClear() throws Exception {
        try (Harness h = harness()) {
            h.configure();
            SecureStore.failWrite = true;
            try { h.client.clear(); fail("Clear write should fail"); }
            catch (Exception expected) { ok(!expected.getMessage().contains("CANARY"), "Clear failure hides storage details"); }
            ok(h.client.status().getBoolean("configured"), "Failed clear preserves configured memory state");
            ok(KEY.equals(SecureStore.saved.getString("api_key")), "Failed clear preserves stored credential consistently");
            SecureStore.failWrite = false;
            FakeConnection connection = fixture(200, validBody());
            connection.blockRead = true;
            h.client.generate(request("clear"));
            ok(connection.readEntered.await(3, TimeUnit.SECONDS), "Clear test response started");
            h.client.clear();
            ok("cancelled".equals(h.terminal().getString("phase")), "Clear cancels active generation");
            ok(!h.client.status().getBoolean("configured") && !h.client.status().getBoolean("busy"), "Successful clear removes active configuration");
            ok(SecureStore.saved.length() == 0, "Successful clear persists empty credentials");
            ok(h.images.saves == 0 && connection.disconnected, "Clear prevents image commit");
        }
    }

    private static void testStorageFailure() throws Exception {
        try (Harness h = harness()) {
            h.configure();
            h.images.failSave = true;
            fixture(200, validBody());
            h.client.generate(request("save_failure"));
            JSONObject result = h.terminal();
            ok("error".equals(result.getString("phase")), "Storage failure is not completed");
            ok(!result.toString().contains("CANARY"), "Storage failure is sanitized");
            ok(openCount() == 1, "Storage failure does not regenerate a billed image");
        }
    }

    private static Harness harness() {
        synchronized (opened) { opened.clear(); next = null; }
        SecureStore.saved = new JSONObject();
        SecureStore.failWrite = false;
        return new Harness();
    }

    private static FakeConnection fixture(int status, String body) throws Exception {
        FakeConnection connection = new FakeConnection(status, body);
        synchronized (opened) { next = connection; }
        return connection;
    }

    private static int openCount() { synchronized (opened) { return opened.size(); } }
    private static JSONObject request(String id) throws Exception {
        return new JSONObject().put("requestId", id).put("bookId", "book_fixture")
                .put("chapterId", "chapter_fixture").put("prompt", "本文から作った挿絵の指示");
    }
    private static String validBody() { return "{\"created\":123,\"data\":[{\"b64_json\":\"" + PNG + "\"}]}"; }
    private static void expectConfigureFailure(Harness h, String key, String model) throws Exception {
        try { h.client.configure(key, model); fail("Invalid configuration accepted"); }
        catch (Exception expected) { ok(true, "Invalid native configuration rejected"); }
    }
    private static void ok(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    private static void fail(String label) { throw new AssertionError(label); }

    private static final class Harness implements AutoCloseable {
        final IllustrationStore images = new IllustrationStore();
        final List<JSONObject> terminalEvents = new ArrayList<>();
        final ImageGenerationClient client = new ImageGenerationClient(new Context(), images, (type, event) -> {
            if ("illustration".equals(type)) synchronized (terminalEvents) {
                terminalEvents.add(event);
                terminalEvents.notifyAll();
            }
        });
        void configure() throws Exception { client.configure(KEY, ImageGenerationClient.DEFAULT_MODEL); resetEvents(); }
        void resetEvents() { synchronized (terminalEvents) { terminalEvents.clear(); } }
        int countTerminal() { synchronized (terminalEvents) { return terminalEvents.size(); } }
        JSONObject terminal() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (terminalEvents) {
                while (terminalEvents.isEmpty()) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) throw new AssertionError("Terminal event timed out");
                    TimeUnit.NANOSECONDS.timedWait(terminalEvents, left);
                }
                return terminalEvents.get(terminalEvents.size() - 1);
            }
        }
        public void close() { client.close(); }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        byte[] body;
        String encoding;
        long advertisedLength = -1;
        int fixedLength;
        int outputAttempts;
        boolean outputTimeout, responseTimeout, blockRead;
        volatile boolean disconnected;
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        FakeConnection(int status, String body) throws Exception {
            super(new URL("https://api.openai.com/v1/images/generations"));
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; releaseRead.countDown(); }
        public void setFixedLengthStreamingMode(int length) { fixedLength = length; super.setFixedLengthStreamingMode(length); }
        public OutputStream getOutputStream() throws IOException {
            outputAttempts++;
            if (outputTimeout) throw new SocketTimeoutException("CANARY_OUTPUT_SECRET");
            return request;
        }
        public int getResponseCode() throws IOException {
            if (responseTimeout) throw new SocketTimeoutException("CANARY_RESPONSE_SECRET");
            return status;
        }
        public String getContentEncoding() { return encoding; }
        public long getContentLengthLong() { return advertisedLength < 0 ? body.length : advertisedLength; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(body); }
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body) {
                private boolean waited;
                public synchronized int read(byte[] target, int offset, int length) {
                    if (blockRead && !waited) {
                        waited = true;
                        readEntered.countDown();
                        try { releaseRead.await(4, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    return super.read(target, offset, length);
                }
            };
        }
    }
}
