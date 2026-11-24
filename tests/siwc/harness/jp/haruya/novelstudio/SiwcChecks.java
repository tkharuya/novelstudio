package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import sun.misc.Unsafe;
import java.io.*;
import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;

/** Offline behavioral checks. HTTPS is replaced globally; no account or remote service is used. */
public final class SiwcChecks {
    private static final Class<?> CLIENT = SiwcClient.class;
    private static final Class<?> JOB = nested("Job"), PENDING = nested("Pending");
    private static final String ISSUER = "https://auth.openai.com", API = "https://api.openai.com/v1";
    private static final String CLIENT_ID = "oaiapp_test123";
    private static final String TOKEN_URL = ISSUER + "/api/accounts/oauth/token";
    private static final String JWKS_URL = ISSUER + "/.well-known/jwks.json";
    private static final String PRIVATE_CODE = "code-do-not-display-123";
    private static final String PRIVATE_ACCESS = "access-do-not-display-123";
    private static final String PRIVATE_REFRESH = "refresh-do-not-display-123";
    private static final String PRIVATE_EMAIL = "private-user@example.invalid";
    private static final String PRIVATE_EXCEPTION = "raw-exception-do-not-display-123";
    private static final Map<String,Queue<Response>> replies = new ConcurrentHashMap<>();
    private static final List<Connection> requests = new CopyOnWriteArrayList<>();
    private static String jwks;
    private static int checks;
    private static KeyPair key;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL u) throws IOException {
                Queue<Response> queue = replies.get(u.toString());
                Response response = queue == null ? null : queue.poll();
                if (response == null && JWKS_URL.equals(u.toString())) response = new Response(200, jwks);
                if (response == null) throw new IOException("Unexpected offline request route");
                Connection c = new Connection(u,response); requests.add(c); return c;
            }
        } : null);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey)key.getPublic();
        jwks = obj("keys",new JSONArray().put(obj("kid","test-key","kty","RSA","alg","RS256","use","sig","n",b64(unsigned(pub.getModulus().toByteArray())),"e",b64(unsigned(pub.getPublicExponent().toByteArray()))))).toString();
        checkStatusAndJwt();
        checkCallbackStateAndRegistration();
        checkAuthTransportAndDiagnostics();
        checkForegroundAndDnsRetry();
        checkStreams();
        checkModelsAndEpoch();
        checkActivityLifecycle();
        System.out.println("PASS " + checks + " offline checks: JWT, callback state, registration retry, auth transport/diagnostics, foreground handoff, bounded pre-write DNS retry, BOM/gzip, sanitized status, SSE terminal outcomes, model catalog, epoch race, application-context ownership, Activity replacement and background generation.");
        System.out.println("LIMITS: JVM Android shims and in-memory credential storage; no browser/account/API/Keystore/device verification.");
    }

    private static void checkStatusAndJwt() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject s = f.client.status();
            require(s.getBoolean("signedIn") && !s.getBoolean("busy"),"valid credentials status");
            require(!s.toString().contains("test-access") && !s.toString().contains("test-refresh") && !s.toString().contains(CLIENT_ID) && !s.toString().contains("test-subject"),"sanitized status");
            f.record.put("scope","openid profile email"); f.setState();
            require(!f.client.status().getBoolean("signedIn"),"consent absent status");
            f.resetRecord(); f.record.remove("access_token"); f.record.remove("refresh_token"); f.setState();
            require(!f.client.status().getBoolean("signedIn") && !f.client.status().getBoolean("planEnabled"),"registration-only reconnect status");
            JSONObject claims = claims();
            Object validated = call(f.client,"validateIdToken",new Class<?>[]{String.class,String.class,String.class,String.class},jwt(claims),CLIENT_ID,"test-nonce","test-access");
            require(((JSONObject)validated).getString("sub").equals("test-subject"),"valid signed JWT");
            rejectJwt(f,changed(claims,"iss","https://untrusted.invalid"),"issuer");
            rejectJwt(f,changed(claims,"aud","wrong-client"),"audience");
            rejectJwt(f,changed(claims,"exp",System.currentTimeMillis()/1000-60),"expired");
            rejectJwt(f,changed(claims,"iat",System.currentTimeMillis()/1000+120),"future issuance");
            rejectJwt(f,changed(claims,"nbf",System.currentTimeMillis()/1000+120),"future not-before");
            rejectJwt(f,changed(claims,"nonce","wrong-nonce"),"nonce mismatch");
            JSONObject missingNonce = changed(claims,"nonce",null); rejectJwt(f,missingNonce,"nonce absent");
            rejectJwt(f,changed(claims,"sub",""),"empty subject");
            rejectJwt(f,changed(claims,"at_hash","incorrect"),"access token hash");
            rejectJwt(f,changed(claims,"aud",new JSONArray().put(CLIENT_ID).put("other")),"multiple audiences without azp");
            String valid = jwt(claims); String[] segments = valid.split("\\.");
            byte[] corrupt = Base64.getUrlDecoder().decode(segments[2]); corrupt[0] ^= 1;
            expectFailure(() -> call(f.client,"validateIdToken",new Class<?>[]{String.class,String.class,String.class,String.class},segments[0]+"."+segments[1]+"."+b64(corrupt),CLIENT_ID,"test-nonce","test-access"),"signature");
            String noSignature = b64(obj("alg","none","kid","test-key").toString().getBytes(StandardCharsets.UTF_8))+"."+segments[1]+".";
            expectFailure(() -> call(f.client,"validateIdToken",new Class<?>[]{String.class,String.class,String.class,String.class},noSignature,CLIENT_ID,"test-nonce","test-access"),"algorithm");
            call(f.client,"validateIdToken",new Class<?>[]{String.class,String.class,String.class,String.class},jwt(missingNonce),CLIENT_ID,null,"test-access"); require(true,"refresh ID token without nonce");
        }
    }

    private static void checkCallbackStateAndRegistration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.emptyState();
            Object p = pending(f.client,null); ServerSocket server = new ServerSocket(0,4,InetAddress.getByName("127.0.0.1")); server.setSoTimeout(100);
            set(p,"server",server); set(p,"redirect","http://127.0.0.1:"+server.getLocalPort()+"/auth/callback");
            Future<Boolean> callback = f.aux.submit(() -> (Boolean)call(f.client,"awaitCallback",new Class<?>[]{PENDING},p));
            String state = (String)get(p,"state");
            require(localRequest(server,"/auth/callback?state=incorrect&code=unused",null).startsWith("HTTP/1.1 400"),"callback rejects wrong state");
            require(localRequest(server,"/auth/callback?state="+state+"&state="+state+"&code=unused",null).startsWith("HTTP/1.1 400"),"callback rejects duplicate state");
            require(localRequest(server,"/auth/callback?state="+state+"&code=unused","wrong.invalid").startsWith("HTTP/1.1 400"),"callback rejects wrong Host");
            require(localRequest(server,"/other?state="+state,null).startsWith("HTTP/1.1 404"),"callback rejects other path");
            require(localRequest(server,"/auth/callback?state="+state+"&error=access_denied",null).startsWith("HTTP/1.1 200"),"callback receives explicit decline");
            require(!callback.get(3,TimeUnit.SECONDS),"declined callback is not success"); server.close();
            String initialUrl = (String)call(f.client,"authorizationUrl",new Class<?>[]{PENDING},p);
            require("dynamic_agent_client".equals(Uri.parse(initialUrl).getQueryParameter("client_id")),"initial public registration client");
            enqueue(ISSUER+"/api/accounts/oauth/token",new Response(400,obj("error","invalid_grant").toString()));
            expectFailure(() -> call(f.client,"completeSignIn",new Class<?>[]{PENDING,Uri.class},p,Uri.parse("http://127.0.0.1/auth/callback?client_id="+CLIENT_ID+"&code=unit-code")),"exchange failure");
            JSONObject remembered = (JSONObject)call(f.client,"registration",new Class<?>[]{String.class},CLIENT_ID);
            require(CLIENT_ID.equals(remembered.optString("client_id")) && !remembered.has("access_token") && !remembered.has("refresh_token"),"issued registration retained after failed exchange");
            List<?> choices = (List<?>)call(f.client,"registrations",new Class<?>[]{});
            require(choices.size()==1 && !f.client.status().getBoolean("signedIn"),"registration-only reconnect choice remains");
            Object retry = pending(f.client,remembered); set(retry,"redirect",get(p,"redirect"));
            Uri retryUri = Uri.parse((String)call(f.client,"authorizationUrl",new Class<?>[]{PENDING},retry));
            require(CLIENT_ID.equals(retryUri.getQueryParameter("client_id")) && retryUri.getQueryParameter("agent_name_hint")==null,"retry reuses issued registration");
            require(!get(p,"state").equals(get(retry,"state")) && !get(p,"nonce").equals(get(retry,"nonce")) && !get(p,"verifier").equals(get(retry,"verifier")),"retry refreshes state nonce PKCE");
            expectFailure(() -> call(f.client,"completeSignIn",new Class<?>[]{PENDING,Uri.class},retry,Uri.parse("http://127.0.0.1/auth/callback?client_id=oaiapp_other123&code=unit-code")),"registration mismatch");
        }
    }

    private static void checkAuthTransportAndDiagnostics() throws Exception {
        // A remote timeout must terminate the callback, not enter the localhost accept loop again.
        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requestCount(TOKEN_URL);
            CallbackOutcome result;
            try {
                result = callback(f, p -> {
                    enqueue(TOKEN_URL, Response.failure(new SocketTimeoutException(privateText())));
                    // If a POST is incorrectly retried, this valid response would allow sign-in.
                    enqueue(TOKEN_URL, new Response(200, validTokenResponse(p)));
                });
                checkAuthFailure(f, result, "AUTH_TOKEN_TIMEOUT");
                require(requestCount(TOKEN_URL) - before == 1, "authorization-code POST is not retried after timeout");
                require(!result.html.contains("接続を完了できませんでした。アプリで再接続してください。"), "callback timeout no longer shows generic fallback");
                require(result.html.contains("ログイン結果を受け取りました。小説工房へ戻ると接続を完了します。"), "callback confirms receipt before token exchange");
                require(!result.html.contains("AUTH_TOKEN_TIMEOUT") && !result.html.contains("<h1>接続しました</h1>"), "callback neither claims sign-in nor embeds the later token result");
            } finally {
                Queue<Response> queued = replies.get(TOKEN_URL);
                if (queued != null) queued.clear();
            }
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            CallbackOutcome result = callback(f, p -> enqueue(TOKEN_URL,
                    new Response(200, "<!doctype html><html>" + privateText() + "</html>")
                            .header("Content-Type", "text/html; charset=utf-8")));
            checkAuthFailure(f, result, null);
            require(f.client.status().optString("diagnosticCode").startsWith("AUTH_TOKEN_"), "HTML token response identifies token stage");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            final String canary = "CANARY_SECRET_REFRESH_123";
            CallbackOutcome result = callback(f, p -> enqueue(TOKEN_URL,
                    new Response(400, obj("error", obj("code", canary)).toString())));
            checkAuthFailure(f, result, "AUTH_TOKEN_HTTP_400");
            require(!result.html.contains(canary), "unknown OAuth error code is not echoed into callback HTML");
            require(!f.client.status().optString("message").contains(canary), "unknown OAuth error code is not echoed into status message");
            require(!result.failure.getMessage().contains(canary), "unknown OAuth error code is not echoed into the public failure message");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            CallbackOutcome result = callback(f, p -> {
                JSONObject token = new JSONObject(validTokenResponse(p));
                String malformedHeader = b64(("not-json " + privateText()).getBytes(StandardCharsets.UTF_8));
                token.put("id_token", malformedHeader + ".e30.AA");
                enqueue(TOKEN_URL, new Response(200, token.toString()));
            });
            checkAuthFailure(f, result, "AUTH_IDENTITY_FORMAT");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requestCount(JWKS_URL);
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, new Response(200, validTokenResponse(p)));
                enqueue(JWKS_URL, Response.failure(new SocketTimeoutException(privateText())));
                enqueue(JWKS_URL, new Response(200, jwks));
            });
            require(result.failure == null && result.success && f.client.status().getBoolean("signedIn"), "JWKS GET recovers from one timeout");
            require(requestCount(JWKS_URL) - before == 2, "JWKS timeout retries only once");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requestCount(JWKS_URL);
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, new Response(200, validTokenResponse(p)));
                enqueue(JWKS_URL, new Response(403, "<html>" + privateText() + "</html>"));
            });
            checkAuthFailure(f, result, "AUTH_KEYS_HTTP_403");
            require(requestCount(JWKS_URL) - before == 1, "JWKS permission failure is not retried");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requestCount(JWKS_URL);
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, new Response(200, validTokenResponse(p)));
                enqueue(JWKS_URL, new Response(403, "{\"error\":\"" + privateText())
                        .bodyFailure(new SocketTimeoutException(privateText())));
            });
            checkAuthFailure(f, result, "AUTH_KEYS_HTTP_403_TIMEOUT");
            require(requestCount(JWKS_URL) - before == 1, "HTTP 403 remains nonretryable when its body times out");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, new Response(200, "\uFEFF" + validTokenResponse(p)));
                enqueue(JWKS_URL, new Response(200, "\uFEFF" + jwks));
            });
            require(result.failure == null && result.success && f.client.status().getBoolean("signedIn"), "UTF-8 BOM token and JWKS responses complete sign-in");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requests.size();
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, Response.gzip("\uFEFF" + validTokenResponse(p)));
                enqueue(JWKS_URL, Response.gzip("\uFEFF" + jwks));
            });
            require(result.failure == null && result.success && f.client.status().getBoolean("signedIn"), "gzip token and JWKS responses decode exactly once");
            for (int i = before; i < requests.size(); i++) {
                Connection request = requests.get(i);
                require("gzip".equals(request.getRequestProperty("Accept-Encoding")), "gzip handling explicitly opts out of platform auto-decoding");
            }
        }
    }

    private static void checkForegroundAndDnsRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.emptyState();
            f.client.setForeground(false);
            int before = requestCount(TOKEN_URL);
            final Response[] token = new Response[1];
            try (ActiveCallback active = new ActiveCallback(f, p -> {
                token[0] = new Response(200, validTokenResponse(p));
                token[0].entered = new CountDownLatch(1);
                token[0].release = new CountDownLatch(1);
                enqueue(TOKEN_URL, token[0]);
            })) {
                require(active.html.contains("ログイン結果を受け取りました。小説工房へ戻ると接続を完了します。"), "background callback immediately returns a receipt page");
                require(f.waitingForApp.await(2, TimeUnit.SECONDS), "background callback publishes a waiting-for-app state");
                require(f.client.status().optBoolean("waitingForApp") && !f.client.status().optBoolean("exchanging"), "waiting and exchanging phases are distinct");
                require(requestCount(TOKEN_URL) == before && !active.result.isDone(), "no token request starts while the app is backgrounded");
                require(active.server.isClosed(), "loopback listener is closed before waiting for foreground");
                f.client.setForeground(true);
                try {
                    require(token[0].entered.await(2, TimeUnit.SECONDS), "resuming the app starts token exchange");
                    require(f.client.status().optBoolean("exchanging") && !f.client.status().optBoolean("waitingForApp"), "foreground exchange updates the public phase");
                    f.client.setForeground(true);
                } finally { token[0].release.countDown(); }
                CallbackOutcome result = active.finish(3);
                require(result.success && result.failure == null && f.client.status().getBoolean("signedIn"), "foreground handoff completes authentication");
                require(requestCount(TOKEN_URL) - before == 1, "duplicate resume events do not duplicate the token POST");
            } finally { clearReplies(TOKEN_URL); }
        }

        for (String invalidation : new String[]{"cancelled", "epoch", "expired"}) {
            try (Fixture f = new Fixture()) {
                f.emptyState();
                f.client.setForeground(false);
                int before = requestCount(TOKEN_URL);
                try (ActiveCallback active = new ActiveCallback(f,
                        p -> enqueue(TOKEN_URL, new Response(200, validTokenResponse(p))))) {
                    require(f.waitingForApp.await(2, TimeUnit.SECONDS), "pending sign-in reaches foreground wait before invalidation");
                    synchronized (get(f.client, "foregroundLock")) {
                        if ("cancelled".equals(invalidation)) set(active.pending, "cancelled", true);
                        else if ("epoch".equals(invalidation)) f.epoch.incrementAndGet();
                        else set(active.pending, "deadline", System.currentTimeMillis() - 1);
                    }
                    f.client.setForeground(true);
                    CallbackOutcome result = active.finish(3);
                    require(!result.success && !f.client.status().getBoolean("signedIn"), "cancelled, replaced or expired sign-in cannot activate credentials");
                    require(requestCount(TOKEN_URL) == before, "cancelled, replaced or expired sign-in does not exchange its code");
                } finally { clearReplies(TOKEN_URL); }
            }
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requests.size();
            int tokenCount = requestCount(TOKEN_URL);
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, Response.beforeWriteFailure(new UnknownHostException(privateText())));
                enqueue(TOKEN_URL, new Response(200, validTokenResponse(p)));
            }, 8);
            require(result.success && result.failure == null && f.client.status().getBoolean("signedIn"), "one pre-write DNS failure recovers on the bounded retry");
            require(requestCount(TOKEN_URL) - tokenCount == 2, "pre-write DNS recovery uses exactly two connection attempts");
            List<Connection> tokenRequests = requestsSince(before, TOKEN_URL);
            require(tokenRequests.get(0).out.size() == 0 && tokenRequests.get(1).out.size() > 0, "authorization-code bytes are sent only by the successful retry");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requests.size();
            int tokenCount = requestCount(TOKEN_URL);
            CallbackOutcome result = callback(f, p -> {
                enqueue(TOKEN_URL, Response.beforeWriteFailure(new UnknownHostException(privateText())));
                enqueue(TOKEN_URL, Response.beforeWriteFailure(new UnknownHostException(privateText())));
            }, 8);
            checkAuthFailure(f, result, "AUTH_TOKEN_DNS");
            require(requestCount(TOKEN_URL) - tokenCount == 2, "two DNS failures terminate without a third attempt");
            for (Connection request : requestsSince(before, TOKEN_URL)) {
                require(request.out.size() == 0, "failed pre-write DNS attempts never send the authorization code");
            }
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requests.size();
            int tokenCount = requestCount(TOKEN_URL);
            CallbackOutcome result = callback(f, p -> enqueue(TOKEN_URL,
                    new Response(200, "").afterWriteFailure(new UnknownHostException(privateText()))));
            checkAuthFailure(f, result, "AUTH_TOKEN_DNS");
            require(requestCount(TOKEN_URL) - tokenCount == 1, "DNS-labelled failure after write starts is never retried");
            require(requestsSince(before, TOKEN_URL).get(0).out.size() > 0, "post-write failure fixture actually receives request bytes");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            int before = requests.size();
            int tokenCount = requestCount(TOKEN_URL);
            CallbackOutcome result = callback(f, p -> enqueue(TOKEN_URL,
                    Response.failure(new UnknownHostException(privateText()))));
            checkAuthFailure(f, result, "AUTH_TOKEN_DNS");
            require(requestCount(TOKEN_URL) - tokenCount == 1, "DNS-labelled response failure is never retried");
            require(requestsSince(before, TOKEN_URL).get(0).out.size() > 0, "response failure happens after the complete form was written");
        }
    }

    private interface CallbackSetup { void prepare(Object pending) throws Exception; }

    /** Exercise the real localhost callback with synthetic remote responses and a hard completion bound. */
    private static CallbackOutcome callback(Fixture f, CallbackSetup setup) throws Exception {
        return callback(f, setup, 3);
    }

    private static CallbackOutcome callback(Fixture f, CallbackSetup setup, int seconds) throws Exception {
        try (ActiveCallback active = new ActiveCallback(f, setup)) { return active.finish(seconds); }
    }

    private static final class ActiveCallback implements AutoCloseable {
        final Fixture fixture;
        final Object pending;
        final ServerSocket server;
        Future<Boolean> result;
        String html;

        ActiveCallback(Fixture f, CallbackSetup setup) throws Exception {
            fixture = f;
            pending = pending(f.client, null);
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            try {
                server.setSoTimeout(100);
                set(pending, "server", server);
                set(pending, "redirect", "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback");
                setup.prepare(pending);
                set(f.client, "pending", pending);
                set(f.client, "authenticating", true);
                result = f.aux.submit(() -> {
                    try { return (Boolean)call(f.client, "awaitCallback", new Class<?>[]{PENDING}, pending); }
                    finally {
                        // Mirror beginSignIn's activity-independent cleanup without launching model discovery.
                        if (get(f.client, "pending") == pending) {
                            set(f.client, "pending", null);
                            set(f.client, "authenticating", false);
                        }
                    }
                });
                html = localRequest(server, "/auth/callback?state=" + get(pending, "state")
                        + "&client_id=" + CLIENT_ID + "&code=" + PRIVATE_CODE, null);
            } catch (Exception error) {
                close();
                throw error;
            }
        }

        CallbackOutcome finish(int seconds) throws Exception {
            try {
                return new CallbackOutcome(result.get(seconds, TimeUnit.SECONDS), html, null);
            } catch (ExecutionException error) {
                require(error.getCause() instanceof Exception, "callback reports a bounded authentication failure");
                return new CallbackOutcome(false, html, (Exception)error.getCause());
            } catch (TimeoutException error) {
                throw new AssertionError("Authentication callback exceeded its completion bound");
            }
        }

        public void close() {
            try { server.close(); } catch (IOException ignored) { }
            if (result != null && !result.isDone()) result.cancel(true);
        }
    }

    private static void checkAuthFailure(Fixture f, CallbackOutcome result, String expectedCode) throws Exception {
        require(result.failure != null && !result.success, "authentication callback terminates with failure");
        JSONObject status = f.client.status();
        String code = status.optString("diagnosticCode");
        String stage = status.optString("diagnosticStage");
        require(code.matches("AUTH_[A-Z0-9_]+"), "authentication diagnostic uses a fixed public code");
        if (expectedCode != null) require(expectedCode.equals(code), "authentication failure is assigned the expected stage and category");
        require(!stage.isEmpty() && stage.matches(".*[ぁ-んァ-ヶ一-龯].*"), "authentication diagnostic names its stage in Japanese");
        require(!status.getBoolean("signedIn"), "failed authentication does not activate credentials");
        String visible = obj("message", status.optString("message"), "diagnosticCode", code,
                "diagnosticStage", stage).toString() + result.html + result.failure.getMessage();
        for (String privateValue : new String[]{PRIVATE_CODE, PRIVATE_ACCESS, PRIVATE_REFRESH, PRIVATE_EMAIL,
                PRIVATE_EXCEPTION, CLIENT_ID, "java.net.SocketTimeoutException", "org.json.JSONException"}) {
            require(!visible.contains(privateValue), "authentication diagnostics omit secrets and raw exception content");
        }
    }

    private static String validTokenResponse(Object p) throws Exception {
        JSONObject identity = changed(claims(), "nonce", get(p, "nonce"));
        identity.put("email", PRIVATE_EMAIL);
        return obj("id_token", jwt(identity), "access_token", PRIVATE_ACCESS,
                "refresh_token", PRIVATE_REFRESH, "token_type", "Bearer", "expires_in", 3600,
                "scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct").toString();
    }

    private static String privateText() {
        return PRIVATE_EXCEPTION + " " + PRIVATE_CODE + " " + PRIVATE_ACCESS + " " + PRIVATE_REFRESH + " " + PRIVATE_EMAIL;
    }

    private static int requestCount(String url) {
        int count = 0;
        for (Connection request : requests) if (url.equals(request.getURL().toString())) count++;
        return count;
    }

    private static List<Connection> requestsSince(int index, String url) {
        List<Connection> found = new ArrayList<>();
        for (int i = index; i < requests.size(); i++) {
            Connection request = requests.get(i);
            if (url.equals(request.getURL().toString())) found.add(request);
        }
        return found;
    }

    private static void clearReplies(String url) {
        Queue<Response> queue = replies.get(url);
        if (queue != null) queue.clear();
    }

    private static final class CallbackOutcome {
        final boolean success;
        final String html;
        final Exception failure;
        CallbackOutcome(boolean success, String html, Exception failure) {
            this.success = success; this.html = html; this.failure = failure;
        }
    }

    private static void checkStreams() throws Exception {
        try (Fixture f = new Fixture()) {
            String delta = "data: "+obj("type","response.output_text.delta","delta","途中の原稿").toString()+"\n\n";
            JSONObject result = run(f,delta+"data: [DONE]\n\n");
            require("error".equals(result.optString("phase")) && "途中の原稿".equals(result.optString("text")),"EOF/DONE retains incomplete draft and does not succeed");
            result = run(f,delta+"data: "+obj("type","response.incomplete").toString()+"\n\n");
            require("error".equals(result.optString("phase")) && "途中の原稿".equals(result.optString("text")),"incomplete event preserves partial text");
            result = run(f,delta+"data: "+obj("type","response.failed","response",obj("error",obj("code","subscription_sharing_usage_limit_exceeded"))).toString()+"\n\n");
            require("error".equals(result.optString("phase")) && result.optString("message").contains("利用上限") && "途中の原稿".equals(result.optString("text")),"quota after streaming reports limit and preserves partial text");
            String completed = "data: "+obj("type","response.completed","response",obj("status","completed","output",new JSONArray().put(obj("type","message","content",new JSONArray().put(obj("type","output_text","text","完成した本文")))))).toString()+"\n\n";
            result = run(f,": heartbeat\n\nevent: response.output_text.delta\ndata: {\ndata: \"delta\":\"途中\"}\n\n"+completed);
            require("complete".equals(result.optString("phase")) && "完成した本文".equals(result.optString("text")),"completed event with multiline data and event fallback");
            result = run(f,"data: "+obj("type","response.completed","response",obj("status","completed")).toString()+"\n\n");
            require("error".equals(result.optString("phase")),"empty completed response is error");
            Object job = job(f); ((StringBuilder)get(job,"text")).append("残す本文"); set(f.client,"currentJob",job); f.client.cancel();
            call(f.client,"runGeneration",new Class<?>[]{JOB,String.class,String.class,String.class},job,"test-model","","draft");
            result = f.terminal(); require("cancelled".equals(result.optString("phase")) && "残す本文".equals(result.optString("text")),"user cancellation preserves partial text");
            Object stale = job(f); ((StringBuilder)get(stale,"text")).append("古い接続の途中稿"); f.epoch.incrementAndGet();
            call(f.client,"runGeneration",new Class<?>[]{JOB,String.class,String.class,String.class},stale,"test-model","","draft");
            result = f.terminal(); require("cancelled".equals(result.optString("phase")) && "古い接続の途中稿".equals(result.optString("text")),"session change cancels generation without losing partial text");
            for (Connection c : requests) if (c.getURL().toString().equals(API+"/responses")) {
                JSONObject request = new JSONObject(c.out.toString("UTF-8"));
                require(!request.getBoolean("store") && request.getBoolean("stream") && !request.has("max_output_tokens") && !request.has("previous_response_id"),"preview inference request contract");
            }
        }
    }

    private static void checkModelsAndEpoch() throws Exception {
        try (Fixture f = new Fixture()) {
            enqueue(API+"/models",new Response(200,obj("models",new JSONArray().put(obj("slug","first","display_name","First","visibility","list")).put(obj("slug","hidden","visibility","hide")).put(obj("slug","second","display_name","Second","visibility","list"))).toString()));
            f.client.loadModels(); f.io.submit(()->{}).get(3,TimeUnit.SECONDS);
            JSONArray shown = f.last("models").getJSONArray("models");
            require(shown.length()==2 && "first".equals(shown.getJSONObject(0).getString("id")) && "second".equals(shown.getJSONObject(1).getString("id")),"model visibility and server ordering");
            enqueue(API+"/models",new Response(200,"{\"models\":[]}")); f.client.loadModels(); f.io.submit(()->{}).get(3,TimeUnit.SECONDS);
            JSONObject empty = f.last("models"); require(empty.getJSONArray("models").length()==0 && !empty.optString("message").isEmpty() && !f.client.status().getBoolean("busy"),"empty catalog reports error and clears busy");
            Response old = new Response(200,catalog("stale")); old.entered = new CountDownLatch(1); old.release = new CountDownLatch(1);
            Response fresh = new Response(200,catalog("fresh")); fresh.entered = new CountDownLatch(1); fresh.release = new CountDownLatch(1);
            enqueue(API+"/models",old); enqueue(API+"/models",fresh);
            int before = f.count("models"); f.client.loadModels(); require(old.entered.await(3,TimeUnit.SECONDS),"old catalog starts");
            f.epoch.incrementAndGet(); f.client.loadModels(); old.release.countDown();
            try {
                require(fresh.entered.await(3,TimeUnit.SECONDS),"new session catalog is queued despite old in-flight request");
                require(f.client.status().getBoolean("busy") && f.count("models")==before,"stale completion neither clears new busy nor emits stale models");
            } finally { fresh.release.countDown(); }
            f.io.submit(()->{}).get(3,TimeUnit.SECONDS);
            require("fresh".equals(f.last("models").getJSONArray("models").getJSONObject(0).getString("id")) && !f.client.status().getBoolean("busy"),"new session receives catalog and becomes idle");
        }
    }

    private static void checkActivityLifecycle() throws Exception {
        Handler.reset();
        boolean strongActivityField = false;
        for (Field field : CLIENT.getDeclaredFields()) {
            strongActivityField |= Activity.class.isAssignableFrom(field.getType());
        }
        require(!strongActivityField, "process-owned client has no strong Activity field");
        Context application = new Context();
        SiwcClient independent = new SiwcClient(application, (type, data) -> { });
        try {
            require(SecureStore.lastContext == application, "Context constructor uses application credential storage");
            require(((WeakReference<?>) get(independent, "activityRef")).get() == null,
                    "application constructor needs no attached screen");
        } finally { independent.close(); }
        Activity compatibilityActivity = new Activity();
        SiwcClient compatibility = new SiwcClient(compatibilityActivity, (type, data) -> { });
        try {
            require(SecureStore.lastContext == compatibilityActivity.getApplicationContext(),
                    "Activity constructor passes application context to storage");
            require(((WeakReference<?>) get(compatibility, "activityRef")).get() == compatibilityActivity,
                    "compatibility constructor weakly attaches its screen");
            require(!(Boolean) get(compatibility, "foreground"), "constructing an Activity does not imply resume");
        } finally { compatibility.close(); }

        try (Fixture f = new Fixture()) {
            Activity old = new Activity(), replacement = new Activity();
            f.client.attachActivity(old);
            f.client.setForeground(old, true);
            long epoch = f.epoch.get();
            Object pending = pending(f.client, f.record);
            set(f.client, "pending", pending);
            f.client.attachActivity(replacement);
            require(!(Boolean) get(f.client, "foreground"), "new attachment waits for its own resume");
            f.client.setForeground(old, true);
            require(!(Boolean) get(f.client, "foreground"), "old resume cannot activate a replacement screen");
            f.client.setForeground(replacement, true);
            f.client.setForeground(old, false);
            f.client.detachActivity(old);
            require((Boolean) get(f.client, "foreground")
                    && ((WeakReference<?>) get(f.client, "activityRef")).get() == replacement,
                    "old pause/destroy cannot detach or background the newer screen");
            f.client.detachActivity(replacement);
            require(((WeakReference<?>) get(f.client, "activityRef")).get() == null
                    && !(Boolean) get(f.client, "foreground"), "current detach releases the screen and foreground gate");
            require(f.epoch.get() == epoch && get(f.client, "pending") == pending
                    && !(Boolean) get(pending, "cancelled") && !(Boolean) get(f.client, "closed"),
                    "detach keeps the client, session and pending OAuth attempt alive");
        }

        try (Fixture f = new Fixture()) {
            Activity old = new Activity(), replacement = new Activity();
            f.client.attachActivity(old);
            f.client.setForeground(old, true);
            Response response = new Response(200, completedStream("画面を閉じても続く本文"));
            response.entered = new CountDownLatch(1);
            response.release = new CountDownLatch(1);
            int before = requestCount(API + "/responses");
            enqueue(API + "/responses", response);
            f.client.generate("lifecycle-request", "test-model", "", "draft");
            try {
                require(response.entered.await(2, TimeUnit.SECONDS), "generation has an in-flight native request");
                Object job = get(f.client, "currentJob");
                long epoch = f.epoch.get();
                f.client.setForeground(old, false);
                old.destroyed = true;
                f.client.detachActivity(old);
                require(get(f.client, "currentJob") == job && f.client.status().optBoolean("generating")
                        && !((AtomicBoolean) get(job, "cancelled")).get() && f.epoch.get() == epoch,
                        "pause and detach do not cancel or replace an in-flight generation");
                f.client.attachActivity(replacement);
                f.client.setForeground(replacement, true);
                f.client.detachActivity(old);
                require(get(f.client, "currentJob") == job, "reattachment preserves the same generation job");
            } finally { response.release.countDown(); }
            f.generation.submit(() -> { }).get(3, TimeUnit.SECONDS);
            JSONObject result = f.terminal();
            require("complete".equals(result.optString("phase"))
                    && "画面を閉じても続く本文".equals(result.optString("text")),
                    "generation completes after Activity replacement");
            require(requestCount(API + "/responses") - before == 1, "Activity replacement never resends generation");
        }

        try (Fixture f = new Fixture()) {
            f.client.setForeground(false);
            enqueue(API + "/responses", new Response(200, completedStream("前景フラグに依存しない本文")));
            f.client.generate("background-request", "test-model", "", "draft");
            f.generation.submit(() -> { }).get(3, TimeUnit.SECONDS);
            require("complete".equals(f.terminal().optString("phase"))
                    && !(Boolean) get(f.client, "foreground")
                    && ((WeakReference<?>) get(f.client, "activityRef")).get() == null,
                    "native text generation does not require an attached or foreground screen");
        }

        try (Fixture f = new Fixture()) {
            AlertDialog.latest = null;
            AlertDialog.shown = 0;
            f.client.signIn(); Handler.drain();
            require(AlertDialog.shown == 0, "sign-in does not show a dialog without an Activity");
            Activity old = new Activity();
            f.client.attachActivity(old);
            f.client.signIn(); Handler.drain();
            require(AlertDialog.shown == 0, "sign-in does not show a dialog before resume");
            f.client.setForeground(old, true);
            old.finishing = true;
            f.client.signIn(); Handler.drain();
            require(AlertDialog.shown == 0, "finishing Activity is ineligible for sign-in UI");
            old.finishing = false; old.destroyed = true;
            f.client.signIn(); Handler.drain();
            require(AlertDialog.shown == 0, "destroyed Activity is ineligible for sign-in UI");
            old.destroyed = false;
            f.client.signIn();
            Activity replacement = new Activity();
            f.client.attachActivity(replacement);
            f.client.setForeground(replacement, true);
            Handler.drain();
            require(AlertDialog.shown == 1 && AlertDialog.latest.context == replacement,
                    "queued sign-in action resolves the current Activity when dispatched");
            f.client.signIn(); Handler.drain();
            require(AlertDialog.shown == 1, "duplicate sign-in does not stack account dialogs");
            f.client.detachActivity(old); Handler.drain();
            require(AlertDialog.latest.isShowing(), "stale detach leaves the replacement dialog intact");
            f.client.detachActivity(replacement); Handler.drain();
            require(!AlertDialog.latest.isShowing(), "detached screen's account dialog is dismissed");
        }

        try (Fixture f = new Fixture()) {
            Activity old = new Activity(), replacement = new Activity();
            f.client.attachActivity(old);
            f.client.setForeground(old, true);
            Object pending = pending(f.client, f.record);
            set(pending, "browserUrl", ISSUER + "/api/accounts/authorize");
            set(f.client, "pending", pending);
            call(f.client, "requestBrowserLaunch", new Class<?>[]{PENDING}, pending);
            f.client.detachActivity(old);
            Handler.drain();
            require(old.launches.isEmpty() && !(Boolean) get(pending, "browserOpened")
                    && !(Boolean) get(pending, "browserFailed"),
                    "queued browser launch waits through a missing Activity without failing authentication");
            f.client.attachActivity(replacement);
            f.client.setForeground(replacement, true);
            Handler.drain();
            require(old.launches.isEmpty() && replacement.launches.size() == 1
                    && (Boolean) get(pending, "browserOpened"), "replacement resume launches the pending browser exactly once");
            require(!(Boolean) get(f.client, "foreground"), "browser launch resets the auth foreground gate");
            f.client.setForeground(replacement, true); Handler.drain();
            require(replacement.launches.size() == 1, "resume after browser launch never reopens it");
        }

        try (Fixture f = new Fixture()) {
            f.emptyState();
            Activity old = new Activity(), replacement = new Activity();
            f.client.attachActivity(old);
            f.client.setForeground(old, false);
            int before = requestCount(TOKEN_URL);
            final Response[] token = new Response[1];
            try (ActiveCallback active = new ActiveCallback(f, pending -> {
                token[0] = new Response(200, validTokenResponse(pending));
                token[0].entered = new CountDownLatch(1);
                token[0].release = new CountDownLatch(1);
                enqueue(TOKEN_URL, token[0]);
            })) {
                require(f.waitingForApp.await(2, TimeUnit.SECONDS), "callback waits before replacing its Activity");
                f.client.detachActivity(old);
                f.client.attachActivity(replacement);
                f.client.setForeground(replacement, true);
                try {
                    require(token[0].entered.await(2, TimeUnit.SECONDS), "replacement resume continues the original token exchange");
                    f.client.setForeground(old, false);
                    f.client.detachActivity(old);
                } finally { token[0].release.countDown(); }
                CallbackOutcome outcome = active.finish(3);
                require(outcome.success && outcome.failure == null && f.client.status().optBoolean("signedIn"),
                        "pending OAuth completes across Activity destruction and replacement");
                require(requestCount(TOKEN_URL) - before == 1, "Activity recreation does not duplicate authorization-code exchange");
            } finally { clearReplies(TOKEN_URL); }
        }
        Handler.drain();
    }

    private static String completedStream(String text) throws Exception {
        return "data: " + obj("type", "response.output_text.delta", "delta", text) + "\n\n"
                + "data: " + obj("type", "response.completed", "response", obj("status", "completed")) + "\n\n";
    }

    private static JSONObject run(Fixture f,String sse) throws Exception {
        enqueue(API+"/responses",new Response(200,sse)); Object j=job(f); set(f.client,"currentJob",j);
        call(f.client,"runGeneration",new Class<?>[]{JOB,String.class,String.class,String.class},j,"test-model","Write Japanese fiction","draft"); return f.terminal();
    }
    private static String catalog(String id) throws Exception { return obj("models",new JSONArray().put(obj("slug",id,"display_name",id,"visibility","list"))).toString(); }
    private static String localRequest(ServerSocket server,String path,String host) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())) {
            socket.setSoTimeout(3000); String request="GET "+path+" HTTP/1.1\r\nHost: "+(host==null?"127.0.0.1:"+server.getLocalPort():host)+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); return new String(readAll(socket.getInputStream()),StandardCharsets.UTF_8);
        }
    }
    private static JSONObject claims() throws Exception { long now=System.currentTimeMillis()/1000; return obj("iss",ISSUER,"sub","test-subject","aud",new JSONArray().put(CLIENT_ID),"iat",now,"exp",now+3600,"nonce","test-nonce"); }
    private static void rejectJwt(Fixture f,JSONObject claims,String name) throws Exception { expectFailure(()->call(f.client,"validateIdToken",new Class<?>[]{String.class,String.class,String.class,String.class},jwt(claims),CLIENT_ID,"test-nonce","test-access"),name); }
    private static String jwt(JSONObject claims) throws Exception {
        String data=b64(obj("alg","RS256","kid","test-key").toString().getBytes(StandardCharsets.UTF_8))+"."+b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature s=Signature.getInstance("SHA256withRSA"); s.initSign(key.getPrivate()); s.update(data.getBytes(StandardCharsets.US_ASCII)); return data+"."+b64(s.sign());
    }
    private static JSONObject changed(JSONObject o,String k,Object v) throws Exception { JSONObject copy=new JSONObject(o.toString()); if(v==null)copy.remove(k);else copy.put(k,v);return copy; }
    private static byte[] unsigned(byte[] v) { return v.length>1 && v[0]==0?Arrays.copyOfRange(v,1,v.length):v; }
    private static String b64(byte[] v) { return Base64.getUrlEncoder().withoutPadding().encodeToString(v); }
    private static JSONObject obj(Object... v) throws Exception { JSONObject o=new JSONObject();for(int i=0;i<v.length;i+=2)o.put((String)v[i],v[i+1]);return o; }
    private static Class<?> nested(String n) { try{return Class.forName(CLIENT.getName()+"$"+n);}catch(Exception e){throw new RuntimeException(e);} }
    private static Object get(Object o,String n) throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o); }
    private static void set(Object o,String n,Object v) throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v); }
    private static Object call(Object o,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw(Error)e.getCause();}
    }
    private static Object pending(SiwcClient c,JSONObject previous) throws Exception { Constructor<?> ctor=PENDING.getDeclaredConstructor(CLIENT,long.class,JSONObject.class);ctor.setAccessible(true);return ctor.newInstance(c,((AtomicLong)get(c,"epoch")).get(),previous); }
    private static Object job(Fixture f) throws Exception {Constructor<?> ctor=JOB.getDeclaredConstructor(String.class,long.class);ctor.setAccessible(true);return ctor.newInstance("test-request",f.epoch.get());}
    private static void enqueue(String url,Response r) {replies.computeIfAbsent(url,k->new ConcurrentLinkedQueue<>()).add(r);}
    private static void require(boolean ok,String label) {if(!ok)throw new AssertionError(label);checks++;}
    private interface Checked {void run() throws Exception;}
    private static void expectFailure(Checked c,String name) throws Exception {boolean failed=false;try{c.run();}catch(Exception e){failed=true;}require(failed,"reject "+name);}
    private static byte[] readAll(InputStream in) throws IOException {ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}

    private static final class Fixture implements AutoCloseable {
        final SiwcClient client; final AtomicLong epoch=new AtomicLong();
        final ExecutorService io=Executors.newSingleThreadExecutor(),aux=Executors.newSingleThreadExecutor(),
                auth=Executors.newSingleThreadExecutor(),generation=Executors.newSingleThreadExecutor();
        final List<Event> events=new CopyOnWriteArrayList<>(); JSONObject record;
        final CountDownLatch waitingForApp = new CountDownLatch(1);
        Fixture() throws Exception {
            Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);client=(SiwcClient)((Unsafe)field.get(null)).allocateInstance(CLIENT);
            set(client,"listener",(SiwcClient.Listener)(type,data)->{
                events.add(new Event(type,data));
                if ("status".equals(type) && data.optBoolean("waitingForApp")) waitingForApp.countDown();
            });
            set(client,"epoch",epoch);set(client,"stateLock",new Object());set(client,"refreshLock",new Object());
            set(client,"foregroundLock",new Object());
            set(client,"activityRef",new WeakReference<Activity>(null));
            set(client,"signInDialog",new WeakReference<AlertDialog>(null));
            set(client,"mainHandler",new Handler(Looper.getMainLooper()));
            set(client,"authExecutor",auth);set(client,"generationExecutor",generation);
            set(client,"io",io);set(client,"random",new SecureRandom());set(client,"store",new SecureStore(null));set(client,"message","");set(client,"modelsEpoch",-1L);
            // Unsafe skips field initializers; initialize display strings without coupling to private failure classes.
            for (Field display : CLIENT.getDeclaredFields()) if (display.getType() == String.class && !Modifier.isStatic(display.getModifiers())) {
                display.setAccessible(true);
                if (display.get(client) == null) display.set(client, "");
            }
            resetRecord();
            client.setForeground(true);
        }
        void resetRecord() throws Exception {record=obj("client_id",CLIENT_ID,"subject","test-subject","email","unit@example.invalid","access_token","test-access","refresh_token","test-refresh","scope","openid profile email resource.invoke chatgpt.tokens.use.direct","expires_at_ms",System.currentTimeMillis()+3600000);setState();}
        void setState() throws Exception {set(client,"stateDoc",obj("host_id","urn:uuid:00000000-0000-4000-8000-000000000000","active_client_id",CLIENT_ID,"registrations",new JSONArray().put(record)));}
        void emptyState() throws Exception {set(client,"stateDoc",obj("host_id","urn:uuid:00000000-0000-4000-8000-000000000000","registrations",new JSONArray()));}
        JSONObject last(String type) {for(int i=events.size()-1;i>=0;i--)if(events.get(i).type.equals(type))return events.get(i).data;throw new AssertionError("missing event "+type);}
        JSONObject terminal() {for(int i=events.size()-1;i>=0;i--){Event e=events.get(i);if(e.type.equals("generation")&&!e.data.optString("phase").equals("delta"))return e.data;}throw new AssertionError("missing terminal");}
        int count(String type) {int n=0;for(Event e:events)if(e.type.equals(type))n++;return n;}
        public void close() {client.close();aux.shutdownNow();Handler.drain();}
    }
    private static final class Event {final String type;final JSONObject data;Event(String t,JSONObject d){type=t;data=d;}}
    private static final class Response {
        final int code;
        final byte[] body;
        final Map<String,String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        IOException failure, readFailure, outputFailure, writeFailure;
        CountDownLatch entered, release;
        Response(int code, String body) { this(code, body.getBytes(StandardCharsets.UTF_8)); }
        Response(int code, byte[] body) {
            this.code = code; this.body = body;
            headers.put("Content-Type", "application/json; charset=utf-8");
        }
        Response header(String name, String value) { headers.put(name, value); return this; }
        Response bodyFailure(IOException error) { readFailure = error; return this; }
        Response afterWriteFailure(IOException error) { writeFailure = error; return this; }
        static Response beforeWriteFailure(IOException error) {
            Response value = new Response(200, ""); value.outputFailure = error; return value;
        }
        static Response failure(IOException error) {
            Response value = new Response(200, ""); value.failure = error; return value;
        }
        static Response gzip(String body) throws IOException {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(body.getBytes(StandardCharsets.UTF_8)); }
            return new Response(200, compressed.toByteArray()).header("Content-Encoding", "gzip");
        }
    }
    private static final class Connection extends HttpURLConnection {
        final Response response;final ByteArrayOutputStream out=new ByteArrayOutputStream();
        Connection(URL u,Response r){super(u);response=r;}
        public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
        public OutputStream getOutputStream() throws IOException {
            if (response.outputFailure != null) throw response.outputFailure;
            if (response.writeFailure == null) return out;
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    out.write(value);
                    throw response.writeFailure;
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                    throw response.writeFailure;
                }
            };
        }
        public int getResponseCode() throws IOException {
            if(response.entered!=null)response.entered.countDown();
            if(response.release!=null)try{if(!response.release.await(5,TimeUnit.SECONDS))throw new IOException("Offline fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            if (response.failure != null) throw response.failure;
            return response.code;
        }
        public String getHeaderField(String name) { return name == null ? null : response.headers.get(name); }
        public Map<String,List<String>> getHeaderFields() {
            Map<String,List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<String,String> header : response.headers.entrySet()) result.put(header.getKey(), Collections.singletonList(header.getValue()));
            return result;
        }
        public String getContentEncoding() { return getHeaderField("Content-Encoding"); }
        public String getContentType() { return getHeaderField("Content-Type"); }
        public InputStream getInputStream() {
            if (response.readFailure == null) return new ByteArrayInputStream(response.body);
            return new InputStream() {
                private final ByteArrayInputStream prefix = new ByteArrayInputStream(response.body);
                @Override public int read() throws IOException {
                    int next = prefix.read();
                    if (next < 0) throw response.readFailure;
                    return next;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int size = prefix.read(bytes, offset, length);
                    if (size < 0) throw response.readFailure;
                    return size;
                }
            };
        }
        public InputStream getErrorStream(){return getInputStream();}
    }
}
