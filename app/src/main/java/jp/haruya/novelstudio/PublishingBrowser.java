package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Official websites in an isolated WebView; manuscript transfer is always user initiated. */
public final class PublishingBrowser {
    public interface Listener {
        void onClose();
        void onMessage(String message);
    }

    private static final int FILE_REQUEST = 501;
    private static final int INK = Color.rgb(26, 48, 59);
    private static final int PAPER = Color.rgb(248, 247, 242);
    private static final int LINE = Color.rgb(218, 224, 222);
    private static final int ACCENT = Color.rgb(31, 85, 79);
    private static final String TARGET_ATTRIBUTE = "data-novelstudio-target";

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout root;
    private final LinearLayout pageContainer;
    private final LinearLayout copyRow;
    private final TextView address;
    private final TextView manuscriptName;
    private final TextView hint;
    private final ProgressBar progress;
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private WebView webView;
    private JSONObject payload = new JSONObject();
    private String site = "narou";
    private boolean opened;
    private boolean destroyed;
    private ValueCallback<Uri[]> fileCallback;
    private AlertDialog externalDialog;

    public PublishingBrowser(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setVisibility(View.GONE);

        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(INK);
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(48)));
        addNavButton(nav, "‹", "前のページへ戻る", 40, v -> goBack());
        addNavButton(nav, "閉じる", "原稿に戻る", 52, v -> close());
        address = new TextView(activity);
        address.setTextColor(Color.WHITE);
        address.setTextSize(12);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        address.setPadding(dp(4), 0, dp(4), 0);
        nav.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        addNavButton(nav, "↻", "再読み込み", 40, v -> {
            if (webView != null && isAllowedNavigation(webView.getUrl())) webView.reload();
        });
        addNavButton(nav, "↗", "外部ブラウザで開く", 40, v -> {
            if (webView != null) confirmExternal(webView.getUrl());
        });

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        pageContainer = new LinearLayout(activity);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        View divider = new View(activity);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        manuscriptName = new TextView(activity);
        manuscriptName.setTextColor(INK);
        manuscriptName.setTextSize(12);
        manuscriptName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        manuscriptName.setSingleLine(true);
        manuscriptName.setEllipsize(TextUtils.TruncateAt.END);
        manuscriptName.setPadding(dp(12), dp(7), dp(12), dp(2));
        root.addView(manuscriptName, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        copyRow = new LinearLayout(activity);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        copyRow.setPadding(dp(6), 0, dp(6), 0);
        scroll.addView(copyRow, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(46)));
        hint = new TextView(activity);
        hint.setTextColor(ACCENT);
        hint.setTextSize(10);
        hint.setMaxLines(2);
        hint.setPadding(dp(12), dp(1), dp(12), dp(7));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    }

    public View getView() { return root; }

    public void open(JSONObject data) {
        if (destroyed) throw new IllegalStateException("ブラウザは終了しています。");
        JSONObject next;
        try { next = data == null ? new JSONObject() : new JSONObject(data.toString()); }
        catch (Exception ex) { throw new IllegalArgumentException("原稿データを開けませんでした。", ex); }
        String nextSite = next.optString("site", "narou");
        if (!"narou".equals(nextSite) && !"kakuyomu".equals(nextSite)
                && !"chatgpt".equals(nextSite)) {
            throw new IllegalArgumentException("対応していないサイトです。");
        }
        boolean changedSite = !site.equals(nextSite);
        if (changedSite) dismissDialogs();
        site = nextSite;
        payload = next;
        String requested = next.optString("url", "");
        String initialUrl = defaultUrl(site);
        boolean explicitUrl = !requested.isEmpty() && !initialUrl.equals(requested);
        if (requested.isEmpty()) requested = initialUrl;
        if (!isAllowedNavigation(requested)) {
            message("公式サイトの入口を開きます。");
            requested = initialUrl;
            explicitUrl = false;
        }
        if (changedSite || webView == null) {
            releaseWebView();
            createWebView();
        }
        opened = true;
        root.setVisibility(View.VISIBLE);
        configureCopyBar();
        webView.onResume();
        // Reopening the default entrance preserves an in-progress form or conversation.
        if (changedSite || webView.getUrl() == null || explicitUrl) webView.loadUrl(requested);
        updateAddress(webView.getUrl() == null ? requested : webView.getUrl());
    }

    public boolean goBack() {
        if (!opened || destroyed) return false;
        if (webView != null && webView.canGoBack()) webView.goBack();
        else close();
        return true;
    }

    public void close() {
        if (!opened) return;
        opened = false;
        cancelFileChooser();
        dismissDialogs();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        root.setVisibility(View.GONE);
        if (listener != null) listener.onClose();
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        opened = false;
        dismissDialogs();
        releaseWebView();
        root.setVisibility(View.GONE);
    }

    private void createWebView() {
        final WebView browser = new WebView(activity);
        webView = browser;
        browser.setBackgroundColor(Color.WHITE);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSaveFormData(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(browser, false);
        // No native JavaScript bridge is registered on this external WebView.
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame()) return !isHttps(url);
                return handleNavigation(view, url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(view, url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // POST navigation may bypass shouldOverrideUrlLoading.
                if (request.isForMainFrame() && !isAllowedNavigation(request.getUrl().toString())) {
                    activity.runOnUiThread(() -> {
                        if (view == webView && opened) message("この移動先はアプリ内で開けません。外部ブラウザを使用してください。");
                    });
                    byte[] text = "This navigation is not allowed in the embedded browser."
                            .getBytes(StandardCharsets.UTF_8);
                    return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                            Collections.emptyMap(), new ByteArrayInputStream(text));
                }
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (view != webView) return;
                if (!isAllowedNavigation(url)) {
                    view.stopLoading();
                    message("許可されていない移動を停止しました。");
                    return;
                }
                updateAddress(url);
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != webView) return;
                updateAddress(url);
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (view == webView) message("安全な接続を確認できないため、ページを開きませんでした。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == webView && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    message("ページを読み込めません。再読み込みまたは外部ブラウザを使用してください。");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == webView && request.isForMainFrame() && response.getStatusCode() >= 400) {
                    message("サイトがエラーを返しました（" + response.getStatusCode() + "）。外部ブラウザでも確認できます。");
                }
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (view != webView) return;
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
            @Override public boolean onJsAlert(WebView view, String url, String text, JsResult result) {
                return showSiteDialog(view, url, text, result, 0);
            }
            @Override public boolean onJsConfirm(WebView view, String url, String text, JsResult result) {
                return showSiteDialog(view, url, text, result, 1);
            }
            @Override public boolean onJsBeforeUnload(WebView view, String url, String text, JsResult result) {
                return showSiteDialog(view, url, "保存していない変更が失われる場合があります。ページを離れますか？", result, 2);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (view != webView || !opened || !isAllowedNavigation(view.getUrl())) {
                    callback.onReceiveValue(null);
                    return true;
                }
                cancelFileChooser();
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setType("*/*");
                List<String> mimeTypes = new ArrayList<>();
                String[] accepted = params.getAcceptTypes();
                if (accepted != null) for (String entry : accepted) {
                    if (entry == null) continue;
                    for (String type : entry.split(",")) {
                        String clean = type.trim();
                        if (clean.matches("[A-Za-z0-9!#$&^_.+*\\-]+/[A-Za-z0-9!#$&^_.+*\\-]+")) mimeTypes.add(clean);
                    }
                }
                if (mimeTypes.size() == 1) intent.setType(mimeTypes.get(0));
                else if (!mimeTypes.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { activity.startActivityForResult(intent, FILE_REQUEST); }
                catch (Exception ex) {
                    cancelFileChooser();
                    message("ファイル選択を開けませんでした。");
                }
                return true;
            }
        });
        browser.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (isHttps(url)) confirmExternal(url);
            else message("このファイルは外部ブラウザから保存してください。");
        });
        pageContainer.addView(browser, new LinearLayout.LayoutParams(-1, -1));
    }

    private boolean showSiteDialog(WebView view, String url, String text, JsResult result, int kind) {
        if (view != webView || !canShowDialog() || !isAllowedNavigation(url)) {
            result.cancel();
            return true;
        }
        final boolean[] answered = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(kind == 2 ? "ページを離れる" : hostOf(url))
                .setMessage(text)
                .setPositiveButton(kind == 2 ? "離れる" : "OK", (d, w) -> {
                    answered[0] = true;
                    result.confirm();
                });
        if (kind != 0) builder.setNegativeButton("キャンセル", (d, w) -> {
            answered[0] = true;
            result.cancel();
        });
        AlertDialog dialog = builder.create();
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {
            dialogs.remove(dialog);
            if (!answered[0]) { answered[0] = true; result.cancel(); }
        });
        dialog.show();
        return true;
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_REQUEST) return false;
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback == null) return true;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (resultCode == Activity.RESULT_OK && data != null && opened && !destroyed) {
            ClipData clips = data.getClipData();
            if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) selected.add(uri);
            }
            Uri single = data.getData();
            if (single != null && "content".equalsIgnoreCase(single.getScheme())) selected.add(single);
        }
        callback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
        return true;
    }

    private void cancelFileChooser() {
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback != null) callback.onReceiveValue(null);
    }

    private void releaseWebView() {
        cancelFileChooser();
        if (webView != null) {
            WebView old = webView;
            webView = null;
            old.stopLoading();
            old.setWebChromeClient(null);
            old.setWebViewClient(new WebViewClient());
            pageContainer.removeView(old);
            old.destroy();
        }
        CookieManager.getInstance().flush();
    }

    private boolean handleNavigation(WebView view, String url) {
        if (view != webView || destroyed) return true;
        if (isAllowedNavigation(url)) return false;
        if (isHttps(url)) confirmExternal(url);
        else message("HTTPS以外の移動先はアプリ内で開けません。");
        return true;
    }

    private boolean isAllowedNavigation(String url) {
        if (!isHttps(url)) return false;
        String host = hostOf(url);
        if ("narou".equals(site)) return domain(host, "syosetu.com");
        if ("kakuyomu".equals(site)) return "kakuyomu.jp".equals(host)
                || "www.kakuyomu.jp".equals(host) || "hatena.ne.jp".equals(host)
                || "www.hatena.ne.jp".equals(host) || "accounts.hatena.ne.jp".equals(host);
        return "chatgpt.com".equals(host) || "www.chatgpt.com".equals(host)
                || "auth.chatgpt.com".equals(host) || "auth.openai.com".equals(host)
                || "auth0.openai.com".equals(host) || "chat.openai.com".equals(host)
                || "openai.com".equals(host) || "www.openai.com".equals(host)
                || "help.openai.com".equals(host);
    }

    private static boolean isHttps(String url) {
        if (url == null || url.isEmpty()) return false;
        // Check every character; regex dots can miss additional CR/LF characters.
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') return false;
        }
        try {
            Uri uri = Uri.parse(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isEmpty() && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ex) { return false; }
    }

    private static boolean domain(String host, String base) {
        return host.equals(base) || host.endsWith("." + base);
    }

    private static String hostOf(String url) {
        try {
            String host = Uri.parse(url == null ? "" : url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) { return ""; }
    }

    private static String defaultUrl(String site) {
        if ("kakuyomu".equals(site)) return "https://kakuyomu.jp/my/works";
        if ("chatgpt".equals(site)) return "https://chatgpt.com/";
        return "https://syosetu.com/usernovel/list/";
    }

    private void updateAddress(String url) {
        address.setText(hostOf(url));
        address.setContentDescription("現在のサイト " + hostOf(url));
    }

    private void confirmExternal(String url) {
        if (!canShowDialog()) return;
        if (!isHttps(url)) { message("HTTPSのページだけ外部ブラウザで開けます。"); return; }
        if (externalDialog != null) return;
        externalDialog = new AlertDialog.Builder(activity)
                .setTitle("外部ブラウザで開く")
                .setMessage(hostOf(url) + "\nアプリ内と外部ブラウザのログイン状態は別です。")
                .setPositiveButton("開く", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    try { activity.startActivity(intent); }
                    catch (Exception ex) { message("外部ブラウザを開けませんでした。"); }
                })
                .setNegativeButton("キャンセル", null).create();
        externalDialog.setOnDismissListener(d -> externalDialog = null);
        externalDialog.show();
    }

    private void configureCopyBar() {
        copyRow.removeAllViews();
        String name = payload.optString("title", "");
        if (name.isEmpty()) name = payload.optString("workTitle", "原稿");
        if (name.isEmpty()) name = "原稿";
        manuscriptName.setText(("chatgpt".equals(site) ? "ChatGPT · " : "投稿原稿 · ") + name);
        if ("chatgpt".equals(site)) {
            addCopyButton("指示コピー", "body", "指示");
            hint.setText("illustration".equals(payload.optString("purpose"))
                ? "指示を貼り付けて生成。画像を保存し、アプリの「画像を取り込む」で追加。保存できない場合は↗で外部へ。"
                : "指示を貼り付けて送信。回答はChatGPTのコピー操作で原稿へ取り込みます。");
        } else {
            addCopyButton("本文コピー", "body", "本文");
            addCopyButton("話名コピー", "title", "話名");
            addCopyButton("作品名コピー", "workTitle", "作品名");
            addCopyButton("あらすじコピー", "synopsis", "あらすじ");
            addCopyButton("タグコピー", "tags", "タグ");
            Button insert = smallButton("選択欄へ入力");
            insert.setOnClickListener(v -> showInsertMenu());
            copyRow.addView(insert, new LinearLayout.LayoutParams(-2, dp(44)));
            hint.setText("コピー後、サイトの入力欄を長押しして貼り付け。公開はサイトのボタンで行います。");
        }
    }

    private void addCopyButton(String text, String key, String label) {
        Button button = smallButton(text);
        button.setOnClickListener(v -> {
            String value = valueFor(key);
            if (value.isEmpty()) { message(label + "がありません。"); return; }
            if (value.length() > 200_000) { message("長い原稿はTXT出力するか、話を分割してコピーしてください。"); return; }
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) { message("クリップボードを使えません。"); return; }
            try {
                ClipData clip = ClipData.newPlainText(label, value);
                android.os.PersistableBundle flags = new android.os.PersistableBundle();
                flags.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(flags);
                clipboard.setPrimaryClip(clip);
            } catch (RuntimeException error) { message("コピーできませんでした。原稿を短くするかTXT出力を使ってください。"); return; }
            message(label + "をコピーしました。");
        });
        copyRow.addView(button, new LinearLayout.LayoutParams(-2, dp(44)));
    }

    private String valueFor(String key) {
        Object value = payload.opt(key);
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONArray) {
            JSONArray entries = (JSONArray) value;
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < entries.length(); i++) {
                String entry = entries.optString(i, "");
                if (entry.isEmpty()) continue;
                if (joined.length() > 0) joined.append(' ');
                joined.append(entry);
            }
            return joined.toString();
        }
        return String.valueOf(value);
    }

    private void showInsertMenu() {
        if (!canShowDialog() || webView == null || "chatgpt".equals(site)) return;
        String[] labels = {"本文", "話名", "作品名", "あらすじ", "タグ"};
        String[] keys = {"body", "title", "workTitle", "synopsis", "tags"};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("選択中の入力欄へ入れる原稿")
                .setItems(labels, (d, which) -> inspectInput(keys[which], labels[which]))
                .setNegativeButton("キャンセル", null).create();
        trackAndShow(dialog);
    }

    private void inspectInput(String key, String label) {
        final WebView browser = webView;
        if (!canShowDialog() || browser == null || !isAllowedNavigation(browser.getUrl())) return;
        final String value = valueFor(key);
        if (value.isEmpty()) { message(label + "がありません。"); return; }
        final String url = browser.getUrl();
        final String token = "ns_" + UUID.randomUUID().toString().replace("-", "");
        JSONObject args = inputArgs("inspect", key, value, token, url, "", 0);
        browser.evaluateJavascript(inputScript(args), result -> {
            if (!samePage(browser, url)) return;
            JSONObject info = decodeResult(result);
            if (!info.optBoolean("ok", false)) {
                message(info.optString("message", "入力欄を確認できません。コピーして長押しで貼り付けてください。"));
                return;
            }
            int length = info.optInt("length", 0);
            String hash = info.optString("hash", "");
            if (length == 0) {
                insertInput(browser, url, key, label, value, token, hash, length);
                return;
            }
            if (!canShowDialog()) { clearMarker(browser, token); return; }
            final boolean[] confirmed = {false};
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("入力欄を置き換える")
                    .setMessage("この欄の既存の文章（" + length + "文字）を、" + label
                            + "（" + value.length() + "文字）に置き換えます。")
                    .setPositiveButton("置き換える", (d, w) -> {
                        confirmed[0] = true;
                        insertInput(browser, url, key, label, value, token, hash, length);
                    })
                    .setNegativeButton("キャンセル", null).create();
            dialogs.add(dialog);
            dialog.setOnDismissListener(d -> {
                dialogs.remove(dialog);
                if (!confirmed[0]) clearMarker(browser, token);
            });
            dialog.show();
        });
    }

    private void insertInput(WebView browser, String url, String key, String label,
                             String value, String token, String hash, int length) {
        if (!samePage(browser, url)) { message("ページが変わりました。入力欄を選び直してください。"); return; }
        JSONObject args = inputArgs("insert", key, value, token, url, hash, length);
        browser.evaluateJavascript(inputScript(args), result -> {
            if (!samePage(browser, url)) return;
            JSONObject info = decodeResult(result);
            if (info.optBoolean("ok", false)) message(label + "を入力しました。サイト上の内容を確認してください。");
            else message(info.optString("message", "入力できません。コピーして長押しで貼り付けてください。"));
            clearMarker(browser, token);
        });
    }

    private JSONObject inputArgs(String mode, String key, String text, String token,
                                 String url, String hash, int length) {
        JSONObject args = new JSONObject();
        try {
            args.put("mode", mode); args.put("site", site); args.put("kind", key);
            args.put("text", text); args.put("token", token); args.put("url", url);
            args.put("hash", hash); args.put("length", length);
        } catch (Exception ignored) { }
        return args;
    }

    /** Only the focused, visible, positively identified editing field may be changed. */
    private static String inputScript(JSONObject args) {
        return "(function(){'use strict';var p=JSON.parse(" + jsQuote(args.toString()) + ");"
                + "function out(v){return JSON.stringify(v);}function no(m){return out({ok:false,message:m});}"
                + "var manual='この欄には自動入力できません。コピーして長押しで貼り付けてください。';"
                + "try{if(window.top!==window||location.href!==p.url||location.protocol!=='https:')return no('ページが変わりました。入力欄を選び直してください。');"
                + "var allowed=(p.site==='narou'&&location.hostname==='syosetu.com'&&/^\\/(usernovel|userwrittingnovel|userwritingnovel)(?:\\/|$)/.test(location.pathname))"
                + "||(p.site==='kakuyomu'&&location.hostname==='kakuyomu.jp'&&/^\\/my\\/works(?:\\/|$)/.test(location.pathname));"
                + "if(!allowed)return no('公式の原稿編集画面で入力欄を選んでください。使えない欄はコピーして貼り付けできます。');"
                + "var e=document.activeElement;if(!e)return no(manual);"
                + "if(e.isContentEditable){while(e.parentElement&&e.parentElement.isContentEditable)e=e.parentElement;}"
                + "var ta=e.tagName==='TEXTAREA';if((!ta&&!e.isContentEditable)||e.disabled||e.readOnly||e.closest('[inert]'))return no(manual);"
                + "var r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "if(!e.isConnected||!e.getClientRects().length||r.width<=0||r.height<=0||r.bottom<=0||r.right<=0||r.top>=innerHeight||r.left>=innerWidth||s.display==='none'||s.visibility!=='visible'||s.pointerEvents==='none')return no(manual);"
                + "var a=['aria-label','placeholder','title','name','id'],m='';"
                + "for(var i=0;i<a.length;i++)m+=' '+(e.getAttribute(a[i])||'');"
                + "if(e.labels)for(var j=0;j<e.labels.length;j++)m+=' '+e.labels[j].textContent;"
                + "var ids=(e.getAttribute('aria-labelledby')||'').split(/\\s+/);for(var k=0;k<ids.length;k++){var n=document.getElementById(ids[k]);if(n)m+=' '+n.textContent;}m=m.slice(0,2000);"
                + "if(/ログイン|log.?in|sign.?in|パスワード|password|メール|e-?mail|検索|search|認証|ワンタイム|\\botp\\b|captcha|user.?name|ユーザ.?名/i.test(m))return no(manual);"
                + "var body=/本文|\\bbody\\b|manuscript|episode[-_ ]?(body|content)|novel[-_ ]?(body|content)/i.test(m);"
                + "var summary=/あらすじ|紹介文|synopsis|summary|description|outline/i.test(m);"
                + "var title=/タイトル|題名|title|subtitle/i.test(m);"
                + "var tag=/タグ|キーワード|(?:^|[\\s_-])(?:tags?|keywords?)(?:$|[\\s_-])/i.test(m);"
                + "var fit=p.kind==='body'?body&&!summary&&!title&&!tag:p.kind==='synopsis'?summary&&!body&&!title&&!tag:(p.kind==='title'||p.kind==='workTitle')?title&&!body&&!summary&&!tag:p.kind==='tags'?tag&&!body&&!summary&&!title:false;"
                + "if(!fit)return no('選んだ原稿と入力欄の種類を確認できません。コピーして長押しで貼り付けてください。');"
                + "var max=ta?e.maxLength:Number(e.getAttribute('maxlength')||-1);if(max>=0&&p.text.length>max)return no('この欄の文字数上限を超えています。文章は切り捨てません。原稿を調整してください。');"
                + "function val(){return ta?e.value:e.innerText;}function hash(t){var h=2166136261;for(var x=0;x<t.length;x++){h^=t.charCodeAt(x);h=Math.imul(h,16777619);}return(h>>>0).toString(16);}"
                + "var old=val();if(typeof old!=='string')return no(manual);"
                + "if(p.mode==='inspect'){e.setAttribute('" + TARGET_ATTRIBUTE + "',p.token);return out({ok:true,length:old.length,hash:hash(old)});}"
                + "if(p.mode!=='insert'||e.getAttribute('" + TARGET_ATTRIBUTE + "')!==p.token||old.length!==p.length||hash(old)!==p.hash)return no('入力欄か文章が変わりました。欄を選び直してください。');"
                + "e.removeAttribute('" + TARGET_ATTRIBUTE + "');e.focus();"
                + "if(ta){var setter=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;setter.call(e,p.text);"
                + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p.text}));e.dispatchEvent(new Event('change',{bubbles:true}));e.setSelectionRange(e.value.length,e.value.length);"
                + "}else{var range=document.createRange();range.selectNodeContents(e);var selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);if(!document.execCommand('insertText',false,p.text))return no(manual);}"
                + "function norm(t){return t.replace(/\\r\\n?/g,'\\n');}if(norm(val())!==norm(p.text))return no('入力内容の完全一致を確認できません。サイト上の欄を確認してください。');"
                + "return out({ok:true});}catch(ex){return no(manual);}})()";
    }

    private static String jsQuote(String text) {
        return JSONObject.quote(text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static JSONObject decodeResult(String result) {
        try {
            Object value = new JSONTokener(result == null ? "null" : result).nextValue();
            if (value instanceof String) return new JSONObject((String) value);
            if (value instanceof JSONObject) return (JSONObject) value;
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private void clearMarker(WebView browser, String token) {
        if (browser != webView || destroyed) return;
        String script = "(function(){var a=document.querySelectorAll('[" + TARGET_ATTRIBUTE
                + "]');for(var i=0;i<a.length;i++)if(a[i].getAttribute('" + TARGET_ATTRIBUTE
                + "')===" + jsQuote(token) + ")a[i].removeAttribute('" + TARGET_ATTRIBUTE + "');})()";
        browser.evaluateJavascript(script, null);
    }

    private boolean samePage(WebView browser, String url) {
        return opened && !destroyed && browser == webView && url != null
                && url.equals(browser.getUrl()) && isAllowedNavigation(url);
    }

    private boolean canShowDialog() {
        return opened && !destroyed && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void trackAndShow(AlertDialog dialog) {
        if (!canShowDialog()) return;
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> dialogs.remove(dialog));
        dialog.show();
    }

    private void dismissDialogs() {
        if (externalDialog != null) externalDialog.dismiss();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
    }

    private void addNavButton(LinearLayout nav, String text, String description, int width, View.OnClickListener action) {
        Button button = new Button(activity);
        button.setText(text);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setTextSize("閉じる".equals(text) ? 11 : 23);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(action);
        nav.addView(button, new LinearLayout.LayoutParams(dp(width), -1));
    }

    private Button smallButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setTextColor(ACCENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        return button;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void message(String text) {
        if (!destroyed && listener != null) listener.onMessage(text);
    }
}
