package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import javax.net.ssl.SSLException;

/**
 * Official Sign in with ChatGPT public-client flow for a personal, local app.
 * Tokens and authorization requests never cross the WebView bridge.
 * Contract: https://developers.openai.com/siwc/token-sharing-open-source/sign-in
 */
public final class SiwcClient {
    public interface Listener { void onEvent(String type, JSONObject data); }

    private static final String ISSUER = "https://auth.openai.com";
    private static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    private static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    private static final String JWKS = ISSUER + "/.well-known/jwks.json";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final int OUTPUT_LIMIT = 2 * 1024 * 1024;

    // The process-owned client must never keep a screen alive while generation continues.
    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<AlertDialog> signInDialog = new WeakReference<>(null);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final SecureStore store;
    private final Object stateLock = new Object();
    private final Object refreshLock = new Object();
    private final Object foregroundLock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService generationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong epoch = new AtomicLong();
    private final SecureRandom random = new SecureRandom();
    private JSONObject stateDoc = new JSONObject();
    private volatile Pending pending;
    private volatile Job currentJob;
    private volatile boolean authenticating;
    private volatile boolean loadingModels;
    private volatile long modelsEpoch = -1;
    private volatile boolean loggingOut;
    private volatile boolean closed;
    private volatile boolean foreground;
    private volatile String message = "";
    // Fixed labels and reason codes only. Never expose an exception message, URL or credential.
    private volatile String diagnosticCode = "";
    private volatile String diagnosticStage = "";

    public SiwcClient(Context context, Listener listener) {
        this.listener = listener;
        this.store = new SecureStore(context.getApplicationContext());
        try {
            stateDoc = store.read();
            if (!stateDoc.optString("host_id").startsWith("urn:uuid:")) {
                stateDoc.put("host_id", "urn:uuid:" + UUID.randomUUID().toString());
                store.write(stateDoc);
            }
        } catch (Exception ignored) {
            stateDoc = new JSONObject();
            put(stateDoc, "host_id", "urn:uuid:" + UUID.randomUUID().toString());
            message = "保存した接続情報を読み込めませんでした。ChatGPTに再接続してください。";
        }
    }

    /** Compatibility overload. onResume still supplies the foreground state. */
    public SiwcClient(Activity activity, Listener listener) {
        this((Context) activity, listener);
        attachActivity(activity);
    }

    /** Attach the latest screen without changing generation or the pending OAuth attempt. */
    public void attachActivity(Activity activity) {
        if (activity == null) return;
        WeakReference<AlertDialog> previousDialog = null;
        synchronized (foregroundLock) {
            if (closed || activityRef.get() == activity) return;
            activityRef = new WeakReference<>(activity);
            foreground = false;
            previousDialog = signInDialog;
            signInDialog = new WeakReference<>(null);
            foregroundLock.notifyAll();
        }
        dismissDialog(previousDialog);
    }

    /** A stale screen cannot detach a replacement screen or stop its generation. */
    public void detachActivity(Activity activity) {
        WeakReference<AlertDialog> previousDialog;
        synchronized (foregroundLock) {
            if (activity == null || activityRef.get() != activity) return;
            activityRef.clear();
            foreground = false;
            previousDialog = signInDialog;
            signInDialog = new WeakReference<>(null);
            foregroundLock.notifyAll();
        }
        dismissDialog(previousDialog);
    }

    /** Only display metadata is returned. Never add tokens, subjects, or client IDs here. */
    public JSONObject status() {
        JSONObject record = activeRecord();
        boolean credentials = !record.optString("access_token").isEmpty()
                || !record.optString("refresh_token").isEmpty();
        boolean enabled = hasScope(record.optString("scope"), "chatgpt.tokens.use.direct")
                && hasScope(record.optString("scope"), "resource.invoke");
        JSONObject result = object(
                "signedIn", credentials && enabled && !loggingOut,
                "busy", authenticating || loadingModels || loggingOut || currentJob != null,
                "planEnabled", enabled && credentials && !loggingOut,
                "email", record.optString("email"),
                "message", message);
        put(result, "authenticating", authenticating);
        put(result, "generating", currentJob != null);
        Pending attempt = pending;
        put(result, "waitingForApp", authenticating && attempt != null && attempt.waitingForApp);
        put(result, "exchanging", authenticating && attempt != null && attempt.exchanging);
        put(result, "diagnosticCode", diagnosticCode);
        put(result, "diagnosticStage", diagnosticStage);
        return result;
    }

    /** Called by Activity lifecycle, never by the WebView or an untrusted deep link. */
    public void setForeground(boolean active) {
        synchronized (foregroundLock) {
            if (closed) return;
            foreground = active;
            foregroundLock.notifyAll();
        }
        if (active) requestBrowserLaunch(pending);
    }

    /** Prefer this owner-aware overload for lifecycle callbacks from replaceable screens. */
    public void setForeground(Activity activity, boolean active) {
        synchronized (foregroundLock) {
            if (closed || activity == null || activityRef.get() != activity) return;
            foreground = active;
            foregroundLock.notifyAll();
        }
        if (active) requestBrowserLaunch(pending);
    }

    public void signIn() {
        if (closed || loggingOut) return;
        Pending attempt = pending;
        if (authenticating && attempt != null && (attempt.waitingForApp || attempt.exchanging)) return;
        // The queued action captures this client only. Resolve the current Activity when run.
        mainHandler.post(this::showSignInChoices);
    }

    private void showSignInChoices() {
        if (closed || loggingOut) return;
        Activity activity = foregroundActivity();
        if (activity == null) {
            message = "ログインするには小説工房の画面を開いてください。";
            emit("status", status());
            return;
        }
        Pending attempt = pending;
        if (authenticating && attempt != null && (attempt.waitingForApp || attempt.exchanging)) return;
        synchronized (foregroundLock) {
            AlertDialog existing = signInDialog.get();
            if (existing != null && existing.isShowing()) return;
        }
        List<JSONObject> records = registrations();
        if (records.isEmpty()) {
            beginSignIn(null);
            return;
        }
        String[] choices = new String[records.size() + 1];
        for (int i = 0; i < records.size(); i++) {
            String email = records.get(i).optString("email");
            choices[i] = (email.isEmpty() ? "未完了の接続" : email) + "（接続 " + (i + 1) + "）";
        }
        choices[records.size()] = "別のアカウント・ワークスペースを追加";
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("ChatGPTの接続を選択")
                .setItems(choices, (choice, which) ->
                        beginSignIn(which < records.size() ? records.get(which) : null))
                .setNegativeButton("キャンセル", null)
                .create();
        synchronized (foregroundLock) { signInDialog = new WeakReference<>(dialog); }
        try { dialog.show(); }
        catch (RuntimeException ignored) {
            message = "ログイン画面を開けませんでした。小説工房の画面から再接続してください。";
            emit("status", status());
        }
    }

    private Activity foregroundActivity() {
        synchronized (foregroundLock) {
            Activity activity = activityRef.get();
            return !closed && foreground && activity != null && !activity.isFinishing()
                    && !activity.isDestroyed() ? activity : null;
        }
    }

    private void dismissDialog(WeakReference<AlertDialog> reference) {
        if (reference == null || reference.get() == null) return;
        mainHandler.post(() -> {
            AlertDialog dialog = reference.get();
            if (dialog != null) try { dialog.dismiss(); } catch (RuntimeException ignored) { }
        });
    }

    private void requestBrowserLaunch(Pending attempt) {
        if (attempt == null || attempt.browserOpened || attempt.browserUrl == null) return;
        mainHandler.post(() -> launchBrowserIfReady(attempt));
    }

    /** Only runs on the main thread; no Activity is captured by the auth worker. */
    private void launchBrowserIfReady(Pending started) {
        if (closed || pending != started || started.cancelled || started.browserOpened
                || started.epoch != epoch.get() || System.currentTimeMillis() >= started.deadline) return;
        Activity activity = foregroundActivity();
        if (activity == null) return; // The current/resumed replacement screen will retry launch.
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(started.browserUrl));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null);
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
            // Even an immediate browser callback must wait for the Activity to resume.
            started.browserOpened = true;
            setForeground(activity, false);
            activity.startActivity(intent);
            started.browserUrl = null;
        } catch (Exception ignored) {
            started.browserFailed = true;
            closeSocket(started.server);
            // If launch failed without leaving the screen, a second sign-in remains possible.
            synchronized (foregroundLock) {
                if (activityRef.get() == activity && !activity.isFinishing() && !activity.isDestroyed()) {
                    foreground = true;
                    foregroundLock.notifyAll();
                }
            }
        }
    }

    private void beginSignIn(JSONObject previous) {
        if (closed || loggingOut || foregroundActivity() == null) return;
        cancel();
        stopPending();
        final long expectedEpoch = epoch.incrementAndGet();
        authenticating = true;
        clearDiagnostic();
        message = "ブラウザでChatGPTにログインし、このアプリのプラン利用を許可してください。";
        emit("status", status());
        authExecutor.execute(() -> {
            Pending attempt = null;
            boolean success = false;
            try {
                attempt = new Pending(expectedEpoch, previous);
                ServerSocket server = new ServerSocket();
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
                server.setSoTimeout(750);
                attempt.server = server;
                attempt.redirect = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";
                if (closed || expectedEpoch != epoch.get()) {
                    server.close();
                    return;
                }
                pending = attempt;
                attempt.browserUrl = authorizationUrl(attempt);
                requestBrowserLaunch(attempt);
                success = awaitCallback(attempt);
            } catch (Exception error) {
                if (!closed && expectedEpoch == epoch.get()) {
                    AuthFailure failure = attempt != null && attempt.browserFailed
                            ? new AuthFailure("BROWSER", "OPEN", "ログイン用ブラウザを開けませんでした。Chrome等をインストールしてください。")
                            : authFailure(attempt == null ? "LOCAL" : "CALLBACK", error);
                    recordAuthFailure(failure, expectedEpoch);
                }
            } finally {
                if (attempt != null) closeSocket(attempt.server);
                if (pending == attempt) pending = null;
                if (!closed && expectedEpoch == epoch.get()) {
                    authenticating = false;
                    emit("auth", status());
                    emit("status", status());
                    if (success) loadModels();
                }
            }
        });
    }

    private String authorizationUrl(Pending p) throws Exception {
        JSONObject old = p.previous;
        String clientId = old == null ? "dynamic_agent_client" : old.optString("client_id");
        Uri.Builder builder = Uri.parse(AUTHORIZE).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("ext_agent_host_id", hostId())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", p.redirect)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("resource", RESOURCE)
                .appendQueryParameter("state", p.state)
                .appendQueryParameter("nonce", p.nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", b64(MessageDigest.getInstance("SHA-256")
                        .digest(p.verifier.getBytes(StandardCharsets.US_ASCII))));
        if (old == null) {
            builder.appendQueryParameter("agent_name_hint", "NovelStudio");
        } else {
            if (!old.optString("id_token").isEmpty()) builder.appendQueryParameter("id_token_hint", old.optString("id_token"));
            if (!old.optString("email").isEmpty()) builder.appendQueryParameter("login_hint", old.optString("email"));
        }
        return builder.build().toString();
    }

    private boolean awaitCallback(Pending p) throws Exception {
        Uri received = null;
        while (!closed && epoch.get() == p.epoch && System.currentTimeMillis() < p.deadline) {
            Socket client;
            try { client = p.server.accept(); }
            catch (SocketTimeoutException ignored) { continue; }
            try (Socket socket = client) {
                socket.setSoTimeout(4000);
                Uri uri;
                try { uri = readCallbackRequest(socket, p); }
                catch (SocketTimeoutException ignored) {
                    // Only an incomplete LOCAL request may keep waiting. A timeout during
                    // token exchange or key retrieval must finish the sign-in attempt.
                    continue;
                }
                if (uri == null) continue;
                if (closed || epoch.get() != p.epoch || System.currentTimeMillis() >= p.deadline) {
                    reply(socket, false, "このログイン操作の有効期限が切れました。", 400);
                    return false;
                }
                if (uri.getQueryParameter("error") != null) {
                    clearDiagnostic();
                    message = "ChatGPTの接続がキャンセルされました。必要なときに再接続できます。";
                    reply(socket, false, message, 200);
                    return false;
                }
                try {
                    if (uri.getQueryParameters("code").size() != 1) {
                        throw new FriendlyException("認証コードを受け取れませんでした。再接続してください。");
                    }
                    callbackClientId(p, uri);
                    received = uri;
                    // This is receipt of an authorization result, NOT a successful login.
                    // No token request starts until MainActivity.onResume has run.
                    replyReceived(socket);
                } catch (Exception error) {
                    AuthFailure failure = authFailure("CALLBACK", error);
                    recordAuthFailure(failure, p.epoch);
                    reply(socket, false, failure.getMessage() + " 診断コード: " + failure.code, 200);
                    throw failure;
                }
            }
            if (received != null) break;
        }
        if (received != null) {
            closeSocket(p.server);
            try {
                awaitForeground(p);
                completeSignIn(p, received);
                return true;
            } catch (Exception error) {
                AuthFailure failure = authFailure("CALLBACK", error);
                recordAuthFailure(failure, p.epoch);
                throw failure;
            } finally {
                p.waitingForApp = false;
                p.exchanging = false;
            }
        }
        if (!closed && p.epoch == epoch.get()) {
            throw new FriendlyException("ログインの待機時間が切れました。ChatGPTに再接続してください。");
        }
        return false;
    }

    private void awaitForeground(Pending p) throws Exception {
        synchronized (foregroundLock) {
            checkPending(p);
            if (!foreground) {
                p.waitingForApp = true;
                p.exchanging = false;
                message = "ログイン結果を受信しました。小説工房へ戻ると接続を完了します。";
                emit("status", status());
            }
            while (!foreground) {
                checkPending(p);
                foregroundLock.wait(Math.min(750, Math.max(1, p.deadline - System.currentTimeMillis())));
            }
            checkPending(p);
            p.waitingForApp = false;
            p.exchanging = true;
        }
        message = "ChatGPTの接続を完了しています。この画面を開いたままお待ちください。";
        emit("status", status());
    }

    private void checkPending(Pending p) throws FriendlyException {
        if (closed || p.cancelled || epoch.get() != p.epoch || Thread.currentThread().isInterrupted()) {
            throw new FriendlyException("接続操作が変更されたため処理を中止しました。");
        }
        if (System.currentTimeMillis() >= p.deadline) {
            throw new FriendlyException("ログインの待機時間が切れました。ChatGPTに再接続してください。");
        }
    }

    private JSONObject exchangeAuthorizationCode(Pending p, String form) throws Exception {
        for (int attempt = 0; ; attempt++) {
            awaitForeground(p);
            try { return requestJson(TOKEN, "POST", null, form); }
            catch (UnsentDnsFailure error) {
                // getOutputStream failed before ANY form byte was written. The code has
                // not been sent. Never retry a timeout, a write failure or a response error.
                if (attempt != 0) throw error;
                message = "接続先を再確認しています。この画面を開いたままお待ちください。";
                emit("status", status());
                long retryAt = System.nanoTime() + 2_500_000_000L;
                synchronized (foregroundLock) {
                    while (System.nanoTime() < retryAt) {
                        checkPending(p);
                        long remaining = Math.max(1, (retryAt - System.nanoTime()) / 1_000_000L);
                        foregroundLock.wait(Math.min(remaining, 250));
                    }
                }
            }
        }
    }

    private Uri readCallbackRequest(Socket socket, Pending p) throws Exception {
        InputStream in = socket.getInputStream();
        String requestLine = readHttpLine(in, 16384);
        if (requestLine == null) return null;
        String host = "";
        int hostCount = 0;
        int headerSize = requestLine.length();
        boolean completeHeaders = false;
        for (int i = 0; i < 80; i++) {
            String line = readHttpLine(in, 8192);
            if (line == null) break;
            if (line.isEmpty()) { completeHeaders = true; break; }
            headerSize += line.length();
            if (headerSize > 32768) throw new FriendlyException("ログイン応答が大きすぎます。再接続してください。");
            int colon = line.indexOf(':');
            if (colon > 0 && "host".equalsIgnoreCase(line.substring(0, colon))) {
                host = line.substring(colon + 1).trim();
                hostCount++;
            }
        }
        String[] parts = requestLine.split(" ", 3);
        if (!completeHeaders || hostCount != 1 || parts.length != 3 || !"GET".equals(parts[0])
                || !host.equals("127.0.0.1:" + p.server.getLocalPort()) || !parts[1].startsWith("/")) {
            reply(socket, false, "ログイン応答を確認できません。アプリから操作し直してください。", 400);
            return null;
        }
        Uri uri = Uri.parse("http://" + host + parts[1]);
        if (!"/auth/callback".equals(uri.getPath())) {
            reply(socket, false, "このページはログインの受け取り専用です。", 404);
            return null;
        }
        if (uri.getQueryParameters("state").size() != 1
                || !constantEquals(p.state, uri.getQueryParameter("state"))) {
            reply(socket, false, "ログイン応答を検証できませんでした。アプリから再接続してください。", 400);
            return null;
        }
        return uri;
    }

    private String callbackClientId(Pending p, Uri uri) throws FriendlyException {
        String clientId = uri.getQueryParameter("client_id");
        if (uri.getQueryParameters("client_id").size() > 1) throw new FriendlyException("接続IDを検証できませんでした。");
        if (p.previous != null) {
            String expectedId = p.previous.optString("client_id");
            if (clientId != null && !expectedId.equals(clientId)) throw new FriendlyException("接続IDが一致しません。再接続してください。");
            clientId = expectedId;
        }
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{6,512}") || "dynamic_agent_client".equals(clientId)) {
            throw new FriendlyException("アプリの接続IDを受け取れませんでした。再接続してください。");
        }
        String code = uri.getQueryParameter("code");
        if (code == null || code.isEmpty() || code.length() > 8192) throw new FriendlyException("認証コードが無効です。再接続してください。");
        return clientId;
    }

    private void completeSignIn(Pending p, Uri uri) throws Exception {
        String stage = "CALLBACK";
        try {
            String clientId = callbackClientId(p, uri);
            String code = uri.getQueryParameter("code");
            // Retain the issued registration even when a later exchange fails.
            stage = "REGISTRATION";
            JSONObject existing = registration(clientId);
            if (existing.length() == 0) saveRecord(object("client_id", clientId), false, p.epoch);
            stage = "TOKEN";
            JSONObject tokens = exchangeAuthorizationCode(p, form(
                    "grant_type", "authorization_code", "client_id", clientId,
                    "code", code, "code_verifier", p.verifier, "redirect_uri", p.redirect, "resource", RESOURCE));
            stage = "IDENTITY";
            awaitForeground(p);
            JSONObject identity = validateIdToken(tokens.optString("id_token"), clientId, p.nonce, tokens.optString("access_token"));
            String expectedSubject = p.previous == null ? existing.optString("subject") : p.previous.optString("subject");
            if (!expectedSubject.isEmpty() && !constantEquals(expectedSubject, identity.optString("sub"))) {
                throw new FriendlyException("選択したChatGPTアカウントとログイン結果が一致しません。別の接続として追加してください。");
            }
            JSONObject record = object("client_id", clientId, "subject", identity.getString("sub"),
                    "issuer", ISSUER, "email", identity.optString("email"), "name", identity.optString("name"));
            stage = "TOKEN";
            mergeTokens(record, tokens, true);
            stage = "STORE";
            saveRecord(record, true, p.epoch);
            stage = "CONSENT";
            if (!hasScope(record.optString("scope"), "chatgpt.tokens.use.direct")
                    || !hasScope(record.optString("scope"), "resource.invoke")) {
                throw new FriendlyException("ログインしましたがChatGPTプランの利用が許可されていません。再接続してプラン利用を許可してください。");
            }
            clearDiagnostic();
            message = "ChatGPTプランで接続しました。このアプリでの生成はプランの利用量に含まれます。";
        } catch (Exception error) {
            throw authFailure(stage, error);
        }
    }

    public void loadModels() {
        final long expectedEpoch;
        synchronized (this) {
            expectedEpoch = epoch.get();
            if (closed || loggingOut || (loadingModels && modelsEpoch == expectedEpoch)) return;
            loadingModels = true;
            modelsEpoch = expectedEpoch;
        }
        emit("status", status());
        io.execute(() -> {
            try {
                if (closed || expectedEpoch != epoch.get()) return;
                JSONObject response;
                try {
                    response = requestJson(RESOURCE + "/models", "GET", accessToken(false), null);
                } catch (HttpFailure failure) {
                    if (failure.status != 401) throw failure;
                    response = requestJson(RESOURCE + "/models", "GET", accessToken(true), null);
                }
                if (closed || expectedEpoch != epoch.get()) return;
                JSONArray source = response.optJSONArray("models");
                boolean accountCatalog = source != null;
                if (source == null) source = response.optJSONArray("data");
                if (source == null) throw new FriendlyException("モデル一覧の形式を確認できません。再取得してください。");
                JSONArray list = new JSONArray();
                for (int i = 0; i < source.length(); i++) {
                    JSONObject model = source.optJSONObject(i);
                    if (model == null) continue;
                    if (accountCatalog && !"list".equals(model.optString("visibility"))) continue;
                    String id = model.optString("slug", model.optString("id"));
                    if (id.isEmpty()) continue;
                    list.put(object("id", id, "name", model.optString("display_name", id)));
                }
                if (list.length() == 0) throw new FriendlyException("この接続で選択できるモデルがありません。ChatGPTのプラン利用設定を確認してください。");
                message = "";
                emit("models", object("models", list));
            } catch (Exception error) {
                if (!closed && expectedEpoch == epoch.get()) {
                    message = friendly(error, "モデル一覧を取得できませんでした。通信を確認して再取得してください。");
                    emit("models", object("models", new JSONArray(), "message", message));
                }
            } finally {
                synchronized (this) {
                    if (modelsEpoch == expectedEpoch) loadingModels = false;
                }
                emit("status", status());
            }
        });
    }

    public void generate(String requestId, String model, String instructions, String prompt) {
        if (closed) return;
        if (requestId == null || requestId.isEmpty() || requestId.length() > 160) return;
        if (model == null || model.trim().isEmpty() || model.length() > 200) {
            emit("generation", object("requestId", requestId, "phase", "error", "message", "利用するモデルを選択してください。"));
            return;
        }
        if (prompt == null || prompt.trim().isEmpty()
                || prompt.length() + (instructions == null ? 0 : instructions.length()) > 1000000) {
            emit("generation", object("requestId", requestId, "phase", "error", "message", "生成する内容を入力してください。入力は100万文字以内です。"));
            return;
        }
        final Job job;
        synchronized (this) {
            if (currentJob != null || authenticating || loggingOut) {
                emit("generation", object("requestId", requestId, "phase", "error", "message", "現在の処理が終わってから生成してください。"));
                return;
            }
            job = new Job(requestId, epoch.get());
            currentJob = job;
        }
        emit("status", status());
        generationExecutor.execute(() -> runGeneration(job, model.trim(), instructions == null ? "" : instructions, prompt));
    }

    private void runGeneration(Job job, String model, String instructions, String prompt) {
        String phase = "error";
        String resultMessage = "";
        try {
            checkJob(job);
            JSONObject body = object("model", model, "store", false, "stream", true,
                    "input", new JSONArray().put(object("role", "user", "content", prompt)));
            if (!instructions.isEmpty()) body.put("instructions", instructions);
            for (int attempt = 0; attempt < 2; attempt++) {
                checkJob(job);
                String token = accessToken(attempt > 0);
                checkJob(job);
                HttpURLConnection connection = connection(RESOURCE + "/responses", "POST", token);
                job.connection = connection;
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                int code = connection.getResponseCode();
                if (code == 401 && attempt == 0) {
                    connection.disconnect();
                    job.connection = null;
                    continue;
                }
                if (code < 200 || code >= 300) throw httpFailure(code, readLimited(connection.getErrorStream(), JSON_LIMIT));
                readStream(connection, job);
                break;
            }
            checkJob(job);
            if (!job.completed) throw new FriendlyException("生成の完了を確認できませんでした。途中までの原稿を保存しました。");
            if (job.text.length() == 0) throw new FriendlyException("本文が返されませんでした。条件を変更して再生成してください。");
            phase = "complete";
        } catch (Exception error) {
            if (job.cancelled.get() || job.epoch != epoch.get()) {
                phase = "cancelled";
                resultMessage = "生成を中止しました。途中までの原稿を残しています。";
            } else {
                resultMessage = friendly(error, "通信が中断されました。途中までの原稿を残しています。");
            }
        } finally {
            if (job.connection != null) job.connection.disconnect();
            synchronized (this) { if (currentJob == job) currentJob = null; }
            if (!closed) {
                emit("generation", object("requestId", job.id, "phase", phase, "text", job.text.toString(), "message", resultMessage));
                emit("status", status());
            }
        }
    }

    private void readStream(HttpURLConnection connection, Job job) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String event = "";
            String line;
            while ((line = reader.readLine()) != null) {
                checkJob(job);
                if (line.length() > JSON_LIMIT) throw new FriendlyException("生成応答が大きすぎます。途中までの原稿を残しました。");
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        if (consumeEvent(data.toString(), event, job)) return;
                        data.setLength(0);
                    }
                    event = "";
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).replaceFirst("^ ", ""));
                    if (data.length() > JSON_LIMIT) throw new FriendlyException("生成応答が大きすぎます。");
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                }
            }
            if (data.length() > 0) consumeEvent(data.toString(), event, job);
        }
    }

    private boolean consumeEvent(String data, String event, Job job) throws Exception {
        if ("[DONE]".equals(data.trim())) return false; // Not a substitute for response.completed.
        JSONObject value = new JSONObject(data);
        String type = value.optString("type", event);
        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
            String delta = value.optString("delta");
            if (job.text.length() + delta.length() > OUTPUT_LIMIT) {
                throw new FriendlyException("原稿が長すぎるため生成を停止しました。章を分けて続けてください。");
            }
            job.text.append(delta);
            emit("generation", object("requestId", job.id, "phase", "delta", "delta", delta));
        } else if ("response.completed".equals(type)) {
            JSONObject response = value.optJSONObject("response");
            if (response != null) {
                String status = response.optString("status");
                if (!status.isEmpty() && !"completed".equals(status)) throw new FriendlyException("生成を完了できませんでした。途中までの原稿を残しています。");
                String finalText = outputText(response);
                if (!finalText.isEmpty()) {
                    if (finalText.length() > OUTPUT_LIMIT) throw new FriendlyException("返された原稿が大きすぎます。");
                    job.text.setLength(0);
                    job.text.append(finalText);
                }
            }
            job.completed = true;
            return true;
        } else if ("response.failed".equals(type) || "error".equals(type)) {
            JSONObject response = value.optJSONObject("response");
            throw apiFailure(0, response == null ? value : response);
        } else if ("response.incomplete".equals(type)) {
            throw new FriendlyException("生成が途中で終了しました。途中までの原稿を残したので、続きを生成できます。");
        }
        return false;
    }

    private String accessToken(boolean forceRefresh) throws Exception {
        if (closed || loggingOut) throw new FriendlyException("ChatGPTにログインしてください。");
        synchronized (refreshLock) {
            JSONObject record = activeRecord();
            long expectedEpoch = epoch.get();
            if (record.optString("subject").isEmpty()
                    || !hasScope(record.optString("scope"), "chatgpt.tokens.use.direct")
                    || !hasScope(record.optString("scope"), "resource.invoke")) {
                throw new FriendlyException("ChatGPTにログインし、プランの利用を許可してください。");
            }
            if (!forceRefresh && !record.optString("access_token").isEmpty()
                    && record.optLong("expires_at_ms") > System.currentTimeMillis() + 60000) {
                return record.optString("access_token");
            }
            if (record.optString("refresh_token").isEmpty()) throw new FriendlyException("ChatGPTの接続期限が切れました。再接続してください。");
            try {
                JSONObject tokens = requestJson(TOKEN, "POST", null, form(
                        "grant_type", "refresh_token", "client_id", record.optString("client_id"),
                        "refresh_token", record.optString("refresh_token"), "resource", RESOURCE));
                if (!tokens.optString("id_token").isEmpty()) {
                    JSONObject identity = validateIdToken(tokens.optString("id_token"), record.optString("client_id"), null, tokens.optString("access_token"));
                    if (!constantEquals(record.optString("subject"), identity.optString("sub"))) {
                        throw new FriendlyException("ChatGPTの接続情報が一致しません。再接続してください。");
                    }
                }
                mergeTokens(record, tokens, false);
                saveRecord(record, true, expectedEpoch);
                if (!hasScope(record.optString("scope"), "chatgpt.tokens.use.direct")
                        || !hasScope(record.optString("scope"), "resource.invoke")) {
                    throw new FriendlyException("ChatGPTプランの利用が解除されています。再接続してください。");
                }
                return record.getString("access_token");
            } catch (HttpFailure failure) {
                if (failure.status == 401 || "invalid_grant".equals(failure.code) || "invalid_client".equals(failure.code)) {
                    clearRecordTokens(record);
                    saveRecord(record, false, expectedEpoch);
                    throw new FriendlyException("ChatGPTの接続が失効しました。再接続してください。");
                }
                throw failure;
            }
        }
    }

    private void mergeTokens(JSONObject record, JSONObject tokens, boolean initial) throws Exception {
        String access = tokens.optString("access_token");
        if (access.isEmpty() || !"Bearer".equalsIgnoreCase(tokens.optString("token_type", "Bearer"))) {
            throw new FriendlyException("ChatGPTプラン用の認証情報を取得できませんでした。再接続してください。");
        }
        long expires = tokens.optLong("expires_in", 0);
        if (expires <= 0 || expires > 604800) throw new FriendlyException("認証情報の有効期限を確認できませんでした。");
        record.put("access_token", access);
        record.put("token_type", "Bearer");
        record.put("expires_at_ms", System.currentTimeMillis() + expires * 1000);
        record.put("saved_at_ms", System.currentTimeMillis());
        if (initial || tokens.has("scope")) record.put("scope", tokens.optString("scope"));
        if (!tokens.optString("refresh_token").isEmpty()) record.put("refresh_token", tokens.getString("refresh_token"));
        if (!tokens.optString("id_token").isEmpty()) record.put("id_token", tokens.getString("id_token"));
    }

    public void cancel() {
        Job job = currentJob;
        if (job != null) {
            job.cancelled.set(true);
            HttpURLConnection connection = job.connection;
            if (connection != null) connection.disconnect();
        }
    }

    public void logout() {
        if (closed || loggingOut) return;
        cancel();
        stopPending();
        final long expectedEpoch = epoch.incrementAndGet();
        final JSONObject record = activeRecord();
        loggingOut = true;
        clearDiagnostic();
        message = "ChatGPTの接続を解除しています。";
        emit("status", status());
        // Clear local credentials immediately, retaining only the account/registration mapping.
        // The in-memory copy below is kept only for the revocation attempt.
        try {
            JSONObject cleared = copy(record);
            clearRecordTokens(cleared);
            if (!cleared.optString("client_id").isEmpty()) saveRecord(cleared, false, expectedEpoch);
        } catch (Exception ignored) {
            message = "保存した接続情報を削除できませんでした。アプリを再起動して再度接続解除してください。";
            loggingOut = false;
            emit("auth", status());
            return;
        }
        io.execute(() -> {
            boolean revoked = record.optString("refresh_token").isEmpty();
            try {
                if (!revoked) {
                    JSONObject discovery = requestJson(ISSUER + "/.well-known/openid-configuration", "GET", null, null);
                    if (!ISSUER.equals(discovery.optString("issuer"))) throw new FriendlyException("認証サーバーを検証できませんでした。");
                    String endpoint = discovery.optString("revocation_endpoint");
                    URL target = new URL(endpoint);
                    if (!"https".equals(target.getProtocol()) || !"auth.openai.com".equals(target.getHost())
                            || (target.getPort() != -1 && target.getPort() != 443) || target.getUserInfo() != null) {
                        throw new FriendlyException("接続解除先を検証できませんでした。");
                    }
                    requestJson(endpoint, "POST", null, form("token", record.optString("refresh_token"),
                            "token_type_hint", "refresh_token", "client_id", record.optString("client_id")));
                    revoked = true;
                }
            } catch (Exception ignored) {
                // Local sign-out remains effective; report that remote revocation was not confirmed.
            } finally {
                loggingOut = false;
                if (!closed && expectedEpoch == epoch.get()) {
                    message = revoked ? "ChatGPTからログアウトしました。"
                            : "端末のログイン情報を削除しました。サーバー側の解除は未確認です。ChatGPTの設定から接続を解除できます。";
                    emit("auth", status());
                    emit("status", status());
                }
            }
        });
    }

    public void close() {
        closed = true;
        epoch.incrementAndGet();
        cancel();
        stopPending();
        WeakReference<AlertDialog> dialog;
        synchronized (foregroundLock) {
            activityRef.clear();
            foreground = false;
            dialog = signInDialog;
            signInDialog = new WeakReference<>(null);
        }
        dismissDialog(dialog);
        io.shutdownNow();
        authExecutor.shutdownNow();
        generationExecutor.shutdownNow();
    }

    private JSONObject validateIdToken(String jwt, String clientId, String nonce, String accessToken) throws Exception {
        if (jwt == null || jwt.length() > 65536) throw new FriendlyException("認証結果が無効です。");
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new FriendlyException("認証結果を検証できませんでした。");
        JSONObject header = new JSONObject(new String(unb64(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")) || header.optString("kid").isEmpty()
                || (header.optJSONArray("crit") != null && header.optJSONArray("crit").length() > 0)
                || (header.has("b64") && !header.optBoolean("b64", true))) {
            throw new FriendlyException("この認証結果の署名方式に対応していません。接続情報は保存していません。");
        }
        JSONObject keys;
        try { keys = verificationKeys(); }
        catch (Exception error) { throw authFailure("KEYS", error); }
        JSONArray list = keys.optJSONArray("keys");
        JSONObject jwk = null;
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject candidate = list.optJSONObject(i);
                if (candidate == null || !header.optString("kid").equals(candidate.optString("kid"))) continue;
                if (!"RSA".equals(candidate.optString("kty"))) continue;
                if (candidate.has("use") && !"sig".equals(candidate.optString("use"))) continue;
                if (candidate.has("alg") && !"RS256".equals(candidate.optString("alg"))) continue;
                jwk = candidate;
                break;
            }
        }
        if (jwk == null) throw new FriendlyException("認証結果の署名鍵を確認できませんでした。再接続してください。");
        BigInteger modulus = new BigInteger(1, unb64(jwk.getString("n")));
        BigInteger exponent = new BigInteger(1, unb64(jwk.getString("e")));
        if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || exponent.signum() <= 0) {
            throw new FriendlyException("認証結果の署名鍵が無効です。");
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(unb64(parts[2]))) throw new FriendlyException("認証結果の署名が一致しません。");
        JSONObject claims = new JSONObject(new String(unb64(parts[1]), StandardCharsets.UTF_8));
        long now = System.currentTimeMillis() / 1000;
        if (!ISSUER.equals(claims.optString("iss")) || !audienceMatches(claims.opt("aud"), clientId)
                || claims.optString("sub").isEmpty() || claims.optLong("exp", 0) <= now - 5
                || claims.optLong("iat", 0) <= 0 || claims.optLong("iat", 0) > now + 5
                || claims.optLong("exp", 0) < claims.optLong("iat", 0)
                || (claims.has("nbf") && claims.optLong("nbf") > now + 5)
                || (nonce != null && !constantEquals(nonce, claims.optString("nonce")))) {
            throw new FriendlyException("認証結果の発行元・接続先・有効期限を検証できませんでした。端末の日時も確認してください。");
        }
        JSONArray audiences = claims.optJSONArray("aud");
        if ((claims.has("azp") || (audiences != null && audiences.length() > 1))
                && !clientId.equals(claims.optString("azp"))) {
            throw new FriendlyException("認証結果の接続先が一致しません。");
        }
        if (claims.has("at_hash")) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            byte[] half = new byte[digest.length / 2];
            System.arraycopy(digest, 0, half, 0, half.length);
            if (!constantEquals(b64(half), claims.optString("at_hash"))) throw new FriendlyException("認証情報の組み合わせを検証できませんでした。");
        }
        return claims;
    }

    private static boolean audienceMatches(Object audience, String expected) {
        if (audience instanceof String) return expected.equals(audience);
        if (audience instanceof JSONArray) {
            JSONArray values = (JSONArray) audience;
            for (int i = 0; i < values.length(); i++) if (expected.equals(values.optString(i))) return true;
        }
        return false;
    }

    private JSONObject verificationKeys() throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return requestJson(JWKS, "GET", null, null); }
            catch (Exception error) {
                // A public GET is safe to repeat. Authorization codes and rotated refresh
                // tokens are single-use, so requestJson itself NEVER retries a POST.
                if (attempt != 0 || closed || Thread.currentThread().isInterrupted()
                        || !retryableKeyRequest(error)) throw error;
            }
        }
    }

    private static boolean retryableKeyRequest(Exception error) {
        int status = error instanceof HttpFailure ? ((HttpFailure) error).status
                : error instanceof ResponseReadFailure ? ((ResponseReadFailure) error).status : 0;
        if (status == 408 || status == 500 || status == 502 || status == 503 || status == 504) return true;
        if (status >= 300) return false;
        Throwable cause = error instanceof ResponseReadFailure ? error.getCause() : error;
        return cause instanceof IOException && !(cause instanceof SSLException)
                && !(cause instanceof ZipException)
                && (!(cause instanceof InterruptedIOException) || cause instanceof SocketTimeoutException);
    }

    private JSONObject requestJson(String address, String method, String token, String form) throws Exception {
        HttpURLConnection connection = connection(address, method, token);
        int code = 0;
        try {
            // Set this explicitly so Android does not also transparently decompress gzip.
            // The decoded byte limit below protects against oversized compressed responses.
            connection.setRequestProperty("Accept-Encoding", "gzip");
            if (form != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream stream;
                try { stream = connection.getOutputStream(); }
                catch (UnknownHostException error) {
                    // Headers can be opened lazily here, but the authorization code is
                    // exclusively in the body, and none of that body has been written.
                    throw new UnsentDnsFailure();
                }
                try (OutputStream out = stream) { out.write(bytes); }
            }
            code = connection.getResponseCode();
            String text;
            try (InputStream raw = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream()) {
                String encoding = connection.getContentEncoding();
                InputStream decoded = raw;
                if (raw != null && encoding != null && !encoding.trim().isEmpty() && !"identity".equalsIgnoreCase(encoding.trim())) {
                    if (!"gzip".equalsIgnoreCase(encoding.trim())) throw new ZipException("Unsupported response encoding");
                    decoded = new GZIPInputStream(raw);
                }
                text = readLimited(decoded, JSON_LIMIT);
            }
            // A UTF-8 BOM is harmless, but not accepted by every Android JSON implementation.
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') text = text.substring(1);
            if (code < 200 || code >= 300) throw httpFailure(code, text);
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (IOException error) {
            // Preserve only the received numeric HTTP status, never headers or response text.
            if (code > 0) throw new ResponseReadFailure(code, error);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connection(String address, String method, String token) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !("api.openai.com".equals(url.getHost()) || "auth.openai.com".equals(url.getHost()))) {
            throw new FriendlyException("接続先を検証できませんでした。");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "NovelStudio/1.0.2 (Android; personal local client)");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static HttpFailure httpFailure(int status, String text) {
        try { return apiFailure(status, new JSONObject(text)); }
        catch (Exception ignored) { return new HttpFailure(status, "", httpMessage(status, "")); }
    }

    private static HttpFailure apiFailure(int status, JSONObject body) {
        JSONObject error = body.optJSONObject("error");
        if (error == null) error = body;
        String code = error.optString("code", error.optString("type"));
        if (body.opt("error") instanceof String) code = body.optString("error");
        if (!code.matches("[A-Za-z0-9_.-]{1,100}")) code = "";
        return new HttpFailure(status, code, httpMessage(status, code));
    }

    private static String httpMessage(int status, String code) {
        if ("subscription_sharing_usage_limit_exceeded".equals(code) || status == 429) {
            return "ChatGPTプランまたはこのアプリの利用上限に達しました。ChatGPTの利用状況設定を確認してください。";
        }
        if ("subscription_sharing_usage_unavailable".equals(code)) return "ChatGPTプランの利用が現在できません。ChatGPTの利用状況設定を確認してください。";
        if ("invalid_grant".equals(code) || "invalid_client".equals(code) || status == 401) {
            return "ChatGPTの認証が失効したか、接続を確認できませんでした。再接続してください。";
        }
        if (status == 403) return "このアカウント・モデルでの利用が許可されていません。ChatGPTの接続とプラン利用設定を確認してください。";
        if (status >= 500) return "OpenAI側の処理が一時的に失敗しました。少し時間を置いて再実行してください。";
        String suffix = code.isEmpty() ? (status > 0 ? "HTTP " + status : "応答エラー") : code;
        return "OpenAIの処理を完了できませんでした（" + suffix + "）。途中までの原稿は残しています。";
    }

    private static String outputText(JSONObject response) {
        String direct = response.optString("output_text");
        if (!direct.isEmpty()) return direct;
        StringBuilder result = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                String text = "output_text".equals(part.optString("type")) ? part.optString("text")
                        : "refusal".equals(part.optString("type")) ? part.optString("refusal") : "";
                if (!text.isEmpty()) {
                    if (result.length() > 0) result.append("\n\n");
                    result.append(text);
                }
            }
        }
        return result.toString();
    }

    private void checkJob(Job job) throws FriendlyException {
        if (closed || job.cancelled.get() || job.epoch != epoch.get()) throw new FriendlyException("生成を中止しました。");
    }

    private String hostId() { synchronized (stateLock) { return stateDoc.optString("host_id"); } }
    private JSONObject activeRecord() {
        synchronized (stateLock) { return registrationLocked(stateDoc.optString("active_client_id")); }
    }
    private JSONObject registration(String id) {
        synchronized (stateLock) { return registrationLocked(id); }
    }
    private JSONObject registrationLocked(String id) {
        JSONArray records = stateDoc.optJSONArray("registrations");
        if (records != null) for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null && id.equals(record.optString("client_id"))) return copy(record);
        }
        return new JSONObject();
    }
    private List<JSONObject> registrations() {
        List<JSONObject> list = new ArrayList<>();
        synchronized (stateLock) {
            JSONArray records = stateDoc.optJSONArray("registrations");
            if (records != null) for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record != null && !record.optString("client_id").isEmpty()) list.add(copy(record));
            }
        }
        return list;
    }
    private void saveRecord(JSONObject record, boolean activate, long expectedEpoch) throws Exception {
        synchronized (stateLock) {
            if (closed || epoch.get() != expectedEpoch) throw new FriendlyException("接続操作が変更されたため処理を中止しました。");
            JSONObject next = copy(stateDoc);
            JSONArray records = next.optJSONArray("registrations");
            if (records == null) { records = new JSONArray(); next.put("registrations", records); }
            String id = record.getString("client_id");
            int index = records.length();
            for (int i = 0; i < records.length(); i++) {
                JSONObject other = records.optJSONObject(i);
                if (other != null && id.equals(other.optString("client_id"))) { index = i; break; }
            }
            records.put(index, copy(record));
            if (activate) next.put("active_client_id", id);
            try { store.write(next); }
            catch (Exception ignored) { throw new FriendlyException("接続情報を端末に保存できませんでした。空き容量を確認してください。"); }
            stateDoc = next;
        }
    }
    private static void clearRecordTokens(JSONObject record) {
        for (String key : new String[] {"access_token", "refresh_token", "id_token", "scope", "expires_at_ms", "saved_at_ms"}) record.remove(key);
    }
    private void stopPending() {
        Pending previous = pending;
        pending = null;
        authenticating = false;
        if (previous != null) {
            previous.cancelled = true;
            closeSocket(previous.server);
        }
        synchronized (foregroundLock) { foregroundLock.notifyAll(); }
    }
    private static void closeSocket(ServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
    }
    private void emit(String type, JSONObject data) {
        if (!closed) try { listener.onEvent(type, data); } catch (Exception ignored) {}
    }
    private static String readLimited(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = in.read(bytes)) != -1) {
                out.write(bytes, 0, n);
                if (out.size() > limit) throw new FriendlyException("接続先の応答が大きすぎます。");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static String readHttpLine(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < limit; i++) {
            int b = input.read();
            if (b == -1) return line.size() == 0 ? null : line.toString("US-ASCII");
            if (b == '\n') {
                String text = line.toString("US-ASCII");
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(b);
        }
        throw new FriendlyException("ログイン応答が長すぎます。");
    }
    private static void replyReceived(Socket socket) {
        replyPage(socket, "アプリに戻って完了", "ログイン結果を受け取りました。小説工房へ戻ると接続を完了します。",
                "小説工房へ戻って接続を完了", 200);
    }

    private static void reply(Socket socket, boolean success, String message, int status) {
        replyPage(socket, success ? "接続しました" : "接続の確認", message, "小説工房へ戻る", status);
    }

    private static void replyPage(Socket socket, String title, String message, String linkText, int status) {
        try {
            String text = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
            String html = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>小説工房</title><style>body{font:17px system-ui;margin:12vh auto;padding:28px;max-width:480px;background:#10151f;color:#eef0f5;line-height:1.8}"
                    + "a{display:inline-block;color:#fff;background:#334f9e;padding:12px 22px;border-radius:12px;text-decoration:none}</style>"
                    + "<h1>" + title + "</h1><p>" + text + "</p>"
                    + "<p><a href=\"novelstudio://auth-done\">" + linkText + "</a></p><p>戻らない場合は、この画面を閉じてアプリを開いてください。</p>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                    + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'\r\n"
                    + "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {}
    }
    private String randomValue(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return b64(b); }
    private static String b64(byte[] bytes) { return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static byte[] unb64(String text) { return Base64.decode(text, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static boolean constantEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean hasScope(String scopes, String expected) {
        for (String scope : scopes.trim().split("\\s+")) if (expected.equals(scope)) return true;
        return false;
    }
    private static String form(String... values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(values[i], "UTF-8")).append('=').append(URLEncoder.encode(values[i + 1], "UTF-8"));
        }
        return result.toString();
    }
    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) put(result, (String) values[i], values[i + 1]);
        return result;
    }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) {} }
    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); } catch (Exception ignored) { return new JSONObject(); }
    }
    private static String friendly(Exception error, String fallback) {
        return error instanceof FriendlyException ? error.getMessage() : fallback;
    }

    private void clearDiagnostic() { diagnosticCode = ""; diagnosticStage = ""; }

    private void recordAuthFailure(AuthFailure failure, long expectedEpoch) {
        if (closed || epoch.get() != expectedEpoch) return;
        message = failure.getMessage();
        diagnosticCode = failure.code;
        diagnosticStage = authStageLabel(failure.stage);
    }

    private static AuthFailure authFailure(String stage, Exception error) {
        if (error instanceof AuthFailure) return (AuthFailure) error;
        String reason;
        String detail;
        Throwable cause = error instanceof ResponseReadFailure ? error.getCause() : error;
        if (error instanceof HttpFailure) {
            HttpFailure failure = (HttpFailure) error;
            int status = failure.status;
            reason = "HTTP_" + (status > 0 ? status : "ERROR");
            // Unknown provider error strings may contain echoed user data. Keep only
            // recognized OAuth conditions and the numeric HTTP status in auth displays.
            boolean known = "invalid_grant".equals(failure.code) || "invalid_client".equals(failure.code)
                    || "subscription_sharing_usage_limit_exceeded".equals(failure.code)
                    || "subscription_sharing_usage_unavailable".equals(failure.code);
            detail = known || status == 401 || status == 403 || status == 429 || status >= 500
                    ? httpMessage(status, known ? failure.code : "")
                    : "認証サーバーからエラーが返されました（HTTP " + status + "）。接続を再試行してください。";
        } else if (cause instanceof SocketTimeoutException) {
            reason = "TIMEOUT";
            detail = "通信が時間切れになりました。通信を確認して、接続を再試行してください。";
        } else if (cause instanceof UnknownHostException) {
            reason = "DNS";
            detail = "接続先の名前を解決できませんでした。小説工房を開いた状態で再試行してください。続く場合はWi-Fiとモバイル回線を切り替えて再試行してください。";
        } else if (cause instanceof SSLException) {
            reason = "TLS";
            detail = "安全な通信を確立できませんでした。端末の日時と通信環境を確認してください。";
        } else if (cause instanceof ConnectException) {
            reason = "CONNECT";
            detail = "サーバーへ接続できませんでした。通信を確認して、接続を再試行してください。";
        } else if (cause instanceof ZipException) {
            reason = "ENCODING";
            detail = "応答を読み取れませんでした。接続を再試行してください。";
        } else if (cause instanceof InterruptedIOException || cause instanceof InterruptedException) {
            reason = "INTERRUPTED";
            detail = "通信が中断されました。アプリに戻って接続を再試行してください。";
        } else if (cause instanceof IOException) {
            reason = "NETWORK";
            detail = "通信が中断されました。通信を確認して、接続を再試行してください。";
        } else if (cause instanceof JSONException || cause instanceof IllegalArgumentException) {
            reason = "FORMAT";
            detail = "形式の異なる応答を受信しました。接続を再試行してください。";
        } else if (cause instanceof GeneralSecurityException) {
            reason = "CRYPTO";
            detail = "認証結果を安全に検証できませんでした。接続を再試行してください。";
        } else if (cause instanceof SecurityException) {
            reason = "PERMISSION";
            detail = "端末が接続処理を許可しませんでした。アプリの通信設定を確認してください。";
        } else if (error instanceof FriendlyException) {
            reason = "REJECTED";
            detail = error.getMessage();
        } else {
            reason = "INTERNAL";
            detail = "処理を完了できませんでした。アプリに戻って接続を再試行してください。";
        }
        if (error instanceof ResponseReadFailure) reason = "HTTP_" + ((ResponseReadFailure) error).status + "_" + reason;
        return new AuthFailure(stage, reason, authStageLabel(stage) + "：" + detail);
    }

    private static String authStageLabel(String stage) {
        switch (stage) {
            case "BROWSER": return "ログイン画面の起動";
            case "LOCAL": return "接続の準備";
            case "CALLBACK": return "ログイン結果の受信";
            case "REGISTRATION": case "STORE": return "接続情報の保存";
            case "TOKEN": return "認証情報の受信";
            case "KEYS": return "認証サーバーの確認";
            case "IDENTITY": return "ログイン結果の検証";
            case "CONSENT": return "プラン利用の確認";
            default: return "ChatGPTの接続";
        }
    }

    private static class FriendlyException extends Exception { FriendlyException(String message) { super(message); } }
    private static final class UnsentDnsFailure extends UnknownHostException {
        UnsentDnsFailure() { super("DNS failed before request body was written"); }
    }
    private static final class AuthFailure extends FriendlyException {
        final String stage;
        final String code;
        AuthFailure(String stage, String reason, String message) {
            super(message);
            this.stage = stage;
            this.code = "AUTH_" + stage + "_" + reason;
        }
    }
    private static final class ResponseReadFailure extends IOException {
        final int status;
        ResponseReadFailure(int status, IOException cause) {
            super("Response could not be read", cause);
            this.status = status;
        }
    }
    private static final class HttpFailure extends FriendlyException {
        final int status;
        final String code;
        HttpFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
    private final class Pending {
        final long epoch;
        final JSONObject previous;
        final String state = randomValue(32);
        final String nonce = randomValue(32);
        final String verifier = randomValue(64);
        final long deadline = System.currentTimeMillis() + 10 * 60 * 1000;
        volatile boolean browserFailed;
        volatile boolean browserOpened;
        volatile String browserUrl;
        volatile boolean waitingForApp;
        volatile boolean exchanging;
        volatile boolean cancelled;
        ServerSocket server;
        String redirect;
        Pending(long epoch, JSONObject previous) { this.epoch = epoch; this.previous = previous == null ? null : copy(previous); }
    }
    private static final class Job {
        final String id;
        final long epoch;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        volatile HttpURLConnection connection;
        boolean completed;
        Job(String id, long epoch) { this.id = id; this.epoch = epoch; }
    }
}
