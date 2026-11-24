package jp.haruya.novelstudio;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;

/**
 * Optional, separately metered Images API access using the user's own API key.
 * Credentials stay native; this client does not accept ChatGPT OAuth tokens.
 * https://developers.openai.com/api/reference/resources/images/methods/generate
 */
public final class ImageGenerationClient {
    public interface Listener { void onEvent(String type, JSONObject data); }

    public static final String DEFAULT_MODEL = "gpt-image-2.5-flare";
    public static final List<String> SUPPORTED_MODELS = Collections.unmodifiableList(
            Arrays.asList(DEFAULT_MODEL, "gpt-image-2.5-sunburst"));
    private static final String ENDPOINT = "https://api.openai.com/v1/images/generations";
    private static final int MAX_PROMPT_CHARS = 32000;
    private static final int MAX_RESPONSE_BYTES = 24 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final String CANCEL_MESSAGE =
            "挿絵の受信を中止しました。送信済みの生成は課金される場合があります。";

    private final SecureStore credentials;
    private final IllustrationStore images;
    private final Listener listener;
    private final Object stateLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private String message = "挿絵の自動作成にはOpenAI APIキーの設定が必要です。";
    private long epoch;
    private boolean closed;
    private Job currentJob;

    public ImageGenerationClient(Context context, IllustrationStore images, Listener listener) {
        this.credentials = new SecureStore(context.getApplicationContext(), "illustration-api");
        this.images = images;
        this.listener = listener;
        try {
            JSONObject saved = credentials.read();
            String savedKey = saved.optString("api_key", "");
            String savedModel = saved.optString("model", DEFAULT_MODEL);
            if (!savedKey.isEmpty()) {
                apiKey = checkedKey(savedKey);
                model = checkedModel(savedModel);
                message = "画像APIを設定済みです。生成はAPIの利用枠・料金で処理されます。";
            }
        } catch (Exception ignored) {
            apiKey = "";
            model = DEFAULT_MODEL;
            message = "保存したAPI設定を読み込めませんでした。APIキーを設定し直してください。";
        }
    }

    /** Memory-only display metadata; no key, key fragment, prompt or HTTP body. */
    public JSONObject status() {
        synchronized (stateLock) {
            return object("configured", !closed && !apiKey.isEmpty(),
                    "busy", currentJob != null, "model", model, "message", message);
        }
    }

    /** Only the native settings dialog may call this method. */
    public void configure(String key, String selectedModel) throws Exception {
        String validatedKey = checkedKey(key);
        String validatedModel = checkedModel(selectedModel);
        synchronized (stateLock) {
            if (closed) throw new Exception("アプリを開き直してください。");
            if (currentJob != null) throw new Exception("挿絵の生成を中止してから設定を変更してください。");
            try {
                credentials.write(object("api_key", validatedKey, "model", validatedModel));
            } catch (Exception ignored) {
                throw new Exception("APIキーを安全に保存できませんでした。");
            }
            apiKey = validatedKey;
            model = validatedModel;
            epoch++;
            message = "画像APIを設定しました。生成はAPIの利用枠・料金で処理されます。";
            emitStatusLocked();
        }
    }

    public void clear() throws Exception {
        Job job;
        Exception failure = null;
        synchronized (stateLock) {
            if (closed) throw new Exception("アプリを開き直してください。");
            job = currentJob;
            if (job != null) job.cancelled = true;
            currentJob = null;
            epoch++;
            try {
                credentials.write(new JSONObject());
                apiKey = "";
                model = DEFAULT_MODEL;
                message = "画像APIの設定を削除しました。";
            } catch (Exception ignored) {
                message = "端末に保存したAPIキーを削除できませんでした。もう一度削除してください。";
                failure = new Exception(message);
            }
            if (job != null) emit("illustration", event(job, "cancelled", CANCEL_MESSAGE));
            emitStatusLocked();
        }
        if (job != null) {
            disconnect(job);
            Future<?> future = job.future;
            if (future != null) future.cancel(true);
        }
        if (failure != null) throw failure;
    }

    public void generate(JSONObject input) {
        String requestId = safeId(input, "requestId");
        String bookId = safeId(input, "bookId");
        String chapterId = safeId(input, "chapterId");
        Object value = input == null ? null : input.opt("prompt");
        String prompt = value instanceof String ? (String) value : "";
        synchronized (stateLock) {
            if (closed) return;
            if (requestId.isEmpty() || bookId.isEmpty() || chapterId.isEmpty()) {
                rejectLocked(requestId, bookId, chapterId, "挿絵を保存する話を確認できませんでした。");
                return;
            }
            if (prompt.trim().isEmpty() || prompt.length() > MAX_PROMPT_CHARS) {
                rejectLocked(requestId, bookId, chapterId, "挿絵の指示は1文字以上、32,000文字以内にしてください。");
                return;
            }
            if (currentJob != null) {
                // A repeated tap with the same request ID must not send another paid request.
                if (requestId.equals(currentJob.requestId)) return;
                rejectLocked(requestId, bookId, chapterId, "作成中の挿絵が完了するか、中止してから開始してください。");
                return;
            }
            if (apiKey.isEmpty()) {
                rejectLocked(requestId, bookId, chapterId, "挿絵の自動作成にはOpenAI APIキーを設定してください。");
                return;
            }
            Job job = new Job(++epoch, requestId, bookId, chapterId, prompt, apiKey, model);
            currentJob = job;
            message = "挿絵を1枚作成しています。";
            emitStatusLocked();
            try {
                job.future = executor.submit(() -> run(job));
            } catch (RejectedExecutionException ignored) {
                currentJob = null;
                message = "挿絵の作成を開始できませんでした。アプリを開き直してください。";
                emit("illustration", event(job, "error", message));
                emitStatusLocked();
            }
        }
    }

    public void cancel() {
        Job job;
        synchronized (stateLock) {
            job = currentJob;
            if (job == null) return;
            job.cancelled = true;
            currentJob = null;
            epoch++;
            message = CANCEL_MESSAGE;
            if (!closed) {
                emit("illustration", event(job, "cancelled", CANCEL_MESSAGE));
                emitStatusLocked();
            }
        }
        disconnect(job);
        Future<?> future = job.future;
        if (future != null) future.cancel(true);
    }

    public void close() {
        Job job;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            epoch++;
            job = currentJob;
            if (job != null) job.cancelled = true;
            currentJob = null;
            apiKey = "";
        }
        if (job != null) disconnect(job);
        executor.shutdownNow();
    }

    private void run(Job job) {
        try {
            byte[] image = requestImage(job);
            synchronized (stateLock) {
                checkActiveLocked(job);
                // The commit and terminal event share the cancellation lock. A cancelled or
                // superseded job can never save an image or publish a late completion.
                JSONObject saved;
                try {
                    saved = images.save(image, "api", job.prompt);
                } catch (Exception ignored) {
                    throw new SafeFailure("画像を受信しましたが端末に保存できませんでした。空き容量を確認してください。");
                }
                checkActiveLocked(job);
                currentJob = null;
                message = "挿絵を保存しました。";
                JSONObject result = event(job, "complete", message);
                put(result, "image", saved);
                emit("illustration", result);
                emitStatusLocked();
            }
        } catch (Exception error) {
            synchronized (stateLock) {
                if (closed || job.cancelled || currentJob != job || job.epoch != epoch) return;
                currentJob = null;
                message = safeMessage(error);
                emit("illustration", event(job, "error", message));
                emitStatusLocked();
            }
        } finally {
            disconnect(job);
        }
    }

    private byte[] requestImage(Job job) throws Exception {
        checkActive(job);
        JSONObject body = object("model", job.model, "prompt", job.prompt,
                "n", 1, "size", "1024x1024", "quality", "medium",
                "output_format", "png", "stream", false);
        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        // Fixed-length streaming avoids replaying a buffered POST after an auth/redirect response.
        connection.setFixedLengthStreamingMode(request.length);
        connection.setRequestProperty("Authorization", "Bearer " + job.key);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        // Explicit encoding prevents Android's transparent gzip from being decoded twice.
        connection.setRequestProperty("Accept-Encoding", "gzip");
        synchronized (stateLock) {
            checkActiveLocked(job);
            job.connection = connection;
        }
        try {
            checkActive(job);
            try (OutputStream output = connection.getOutputStream()) {
                checkActive(job);
                output.write(request);
            }
            checkActive(job);
            int status = connection.getResponseCode();
            checkActive(job);
            if (status != HttpURLConnection.HTTP_OK) {
                String knownCode = "";
                try {
                    String errorBody = readBody(connection, connection.getErrorStream(), MAX_ERROR_BYTES, job);
                    JSONObject error = new JSONObject(stripBom(errorBody)).optJSONObject("error");
                    if (error != null) knownCode = error.optString("code", "");
                } catch (Exception ignored) {
                    // Response bodies and unknown provider codes are never displayed or logged.
                }
                checkActive(job);
                throw new SafeFailure(httpMessage(status, knownCode));
            }
            String raw = readBody(connection, connection.getInputStream(), MAX_RESPONSE_BYTES, job);
            checkActive(job);
            JSONObject response;
            try {
                response = new JSONObject(stripBom(raw));
            } catch (JSONException ignored) {
                throw new SafeFailure("画像APIから正しい画像データを受信できませんでした。");
            }
            if (response.has("error") && !response.isNull("error")) {
                throw new SafeFailure("画像APIは画像を返しませんでした。設定と生成内容を確認してください。");
            }
            JSONArray data = response.optJSONArray("data");
            JSONObject first = data == null || data.length() != 1 ? null : data.optJSONObject(0);
            Object encodedValue = first == null ? null : first.opt("b64_json");
            if (!(encodedValue instanceof String)) {
                throw new SafeFailure("画像APIの応答に完成した画像が含まれていませんでした。");
            }
            String encoded = (String) encodedValue;
            if (encoded.isEmpty() || encoded.length() > ((MAX_IMAGE_BYTES + 2L) / 3L) * 4L + 1024L) {
                throw new SafeFailure("受信した画像が空、または保存できるサイズを超えています。");
            }
            String format = response.optString("output_format", "png");
            if (!"png".equals(format)) throw new SafeFailure("受信した画像形式を確認できませんでした。");
            byte[] bytes;
            try {
                bytes = Base64.decode(encoded, Base64.DEFAULT);
            } catch (IllegalArgumentException ignored) {
                throw new SafeFailure("受信した画像データを読み込めませんでした。");
            }
            checkActive(job);
            if (bytes.length > MAX_IMAGE_BYTES || !isPng(bytes)) {
                throw new SafeFailure("受信した画像が壊れているか、保存できるサイズを超えています。");
            }
            return bytes;
        } finally {
            connection.disconnect();
            if (job.connection == connection) job.connection = null;
        }
    }

    private String readBody(HttpURLConnection connection, InputStream stream, int limit, Job job)
            throws Exception {
        if (stream == null) return "";
        String encoding = connection.getContentEncoding();
        try (InputStream original = stream) {
            if (connection.getContentLengthLong() > limit) {
                throw new SafeFailure("画像APIの応答が受信できるサイズを超えています。");
            }
            InputStream decoded = original;
            if (encoding != null && !encoding.trim().isEmpty() && !"identity".equalsIgnoreCase(encoding.trim())) {
                if (!"gzip".equalsIgnoreCase(encoding.trim())) {
                    throw new SafeFailure("画像APIの応答形式を読み込めませんでした。");
                }
                decoded = new GZIPInputStream(original);
            }
            try (InputStream input = decoded; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkActive(job);
                    if (count > limit - output.size()) {
                        throw new SafeFailure("画像APIの応答が受信できるサイズを超えています。");
                    }
                    output.write(buffer, 0, count);
                }
                checkActive(job);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private void checkActive(Job job) throws Cancelled {
        synchronized (stateLock) { checkActiveLocked(job); }
    }

    private void checkActiveLocked(Job job) throws Cancelled {
        if (closed || job.cancelled || currentJob != job || job.epoch != epoch
                || Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private void rejectLocked(String requestId, String bookId, String chapterId, String reason) {
        emit("illustration", object("requestId", requestId, "bookId", bookId,
                "chapterId", chapterId, "phase", "error", "message", reason));
        if (currentJob == null) message = reason;
        emitStatusLocked();
    }

    private void emitStatusLocked() { emit("imageStatus", status()); }

    private void emit(String type, JSONObject data) {
        try { if (listener != null) listener.onEvent(type, data); }
        catch (RuntimeException ignored) { /* A closed UI cannot leak an HTTP or credential error. */ }
    }

    private static JSONObject event(Job job, String phase, String message) {
        return object("requestId", job.requestId, "bookId", job.bookId, "chapterId", job.chapterId,
                "phase", phase, "message", message);
    }

    private static String checkedKey(String value) throws Exception {
        String key = value == null ? "" : value.trim();
        if (key.length() < 20 || key.length() > 2048) throw new Exception("有効なOpenAI APIキーを入力してください。");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 33 || c > 126) throw new Exception("APIキーに空白や使用できない文字が含まれています。");
        }
        return key;
    }

    private static String checkedModel(String selected) throws Exception {
        String value = selected == null || selected.trim().isEmpty() ? DEFAULT_MODEL : selected.trim();
        if (!SUPPORTED_MODELS.contains(value)) throw new Exception("対応している画像モデルを選択してください。");
        return value;
    }

    private static String safeId(JSONObject input, String name) {
        Object value = input == null ? null : input.opt(name);
        if (!(value instanceof String)) return "";
        String id = (String) value;
        return id.matches("[A-Za-z0-9_-]{1,200}") ? id : "";
    }

    private static String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 33 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78
                && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10
                && bytes[12] == 73 && bytes[13] == 72 && bytes[14] == 68 && bytes[15] == 82;
    }

    private static String httpMessage(int status, String code) {
        // Map exact known constants only. Never return a provider-controlled code or message.
        if ("content_policy_violation".equals(code) || "moderation_blocked".equals(code)) {
            return "この内容では画像を生成できませんでした。場面や表現を変更してください。";
        }
        if ("insufficient_quota".equals(code) || "billing_hard_limit_reached".equals(code)) {
            return "画像APIの利用枠または予算上限に達しました。APIの残高・支払い設定を確認してください。";
        }
        if (status == 401) return "APIキーを認証できませんでした。APIキーを設定し直してください。";
        if (status == 403) return "画像APIの利用が許可されていません。API権限や組織の確認状況を確認してください。";
        if (status == 429) return "画像APIの利用上限に達しました。利用枠を確認し、時間をおいて再度操作してください。";
        if (status >= 300 && status < 400) return "画像APIの接続先変更は受け付けられませんでした。";
        if (status == 400 || status == 404 || status == 422) {
            return "画像APIが要求を受け付けませんでした。モデルの利用権限と生成内容を確認してください。";
        }
        if (status >= 500) return "画像APIで障害が発生しました。自動再送は行っていません。";
        return "画像APIに接続できませんでした。APIの利用状況を確認してください。";
    }

    private static String safeMessage(Exception error) {
        if (error instanceof SafeFailure) return error.getMessage();
        if (error instanceof UnknownHostException) return "画像APIの接続先を確認できませんでした。端末の通信状態を確認してください。";
        if (error instanceof SocketTimeoutException) {
            return "画像APIの応答を待つ時間を超えました。送信済みの生成は課金される場合があります。自動再送は行っていません。";
        }
        if (error instanceof SSLException) return "画像APIの安全な接続を確認できませんでした。端末の通信設定を確認してください。";
        if (error instanceof IOException) {
            return "画像APIとの通信が途切れました。送信済みの生成は課金される場合があります。自動再送は行っていません。";
        }
        return "挿絵の作成を完了できませんでした。自動再送は行っていません。";
    }

    private static void disconnect(Job job) {
        HttpURLConnection connection = job.connection;
        if (connection != null) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        }
    }

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) put(result, (String) values[i], values[i + 1]);
        return result;
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException ignored) { }
    }

    private static final class Job {
        final long epoch;
        final String requestId, bookId, chapterId, prompt, key, model;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Future<?> future;
        Job(long epoch, String requestId, String bookId, String chapterId,
                String prompt, String key, String model) {
            this.epoch = epoch;
            this.requestId = requestId;
            this.bookId = bookId;
            this.chapterId = chapterId;
            this.prompt = prompt;
            this.key = key;
            this.model = model;
        }
    }

    private static final class SafeFailure extends Exception {
        SafeFailure(String message) { super(message); }
    }
    private static final class Cancelled extends Exception { }
}
