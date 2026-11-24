package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Trusted, offline UI and untrusted website UI are deliberately separate WebViews. */
public final class MainActivity extends Activity implements GenerationRuntime.UiListener {
    private static final String APP_ORIGIN = "https://novelstudio.local/";
    private static final int EXPORT_REQUEST = 701, IMPORT_REQUEST = 702,
        IMAGE_IMPORT_REQUEST = 703, IMAGE_EXPORT_REQUEST = 704, BACKUP_EXPORT_REQUEST = 705,
        NOTIFICATION_PERMISSION_REQUEST = 706;
    private WebView home;
    private FrameLayout frame;
    private PublishingBrowser browser;
    private SiwcClient siwc;
    private GenerationRuntime runtime;
    private NarrationPlayer narration;
    private DraftStore drafts;
    private IllustrationStore illustrations;
    private IllustrationBackup illustrationBackup;
    private ImageGenerationClient imageClient;
    private boolean uiReady;
    private volatile boolean destroyed;
    private byte[] pendingExport;
    private String pendingImageExport, pendingBackupState;
    private JSONObject pendingImageImport;
    private int documentRequestCode;
    private AlertDialog imageSettingsDialog;
    private final ExecutorService fileIo = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> queuedEvents = new ArrayDeque<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreImageDocumentRequest(savedInstanceState);
        runtime = GenerationRuntime.get(this);
        runtime.registerActivity(this);
        narration = NarrationPlayer.get(this);
        drafts = runtime.drafts();
        illustrations = runtime.images();
        illustrationBackup = new IllustrationBackup(illustrations);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(245,243,237));
        setContentView(frame);
        setupInsets();
        siwc = runtime.siwc();
        imageClient = runtime.imageClient();
        setupHome();
        browser = new PublishingBrowser(this, new PublishingBrowser.Listener() {
            @Override public void onClose() {
                browser.getView().setVisibility(View.GONE);
                home.setVisibility(View.VISIBLE);
                runtime.replay();
                emit("webClosed", new JSONObject());
            }
            @Override public void onMessage(String message) { toast(message); }
        });
        frame.addView(browser.getView(), new FrameLayout.LayoutParams(-1,-1));
        browser.getView().setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        home.loadUrl(APP_ORIGIN + "index.html");
    }

    private void setupInsets() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    private void setupHome() {
        home = new WebView(this);
        home.setBackgroundColor(Color.rgb(245,243,237));
        WebSettings settings = home.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSafeBrowsingEnabled(true);
        home.addJavascriptInterface(new NativeBridge(), "NativeBridge");
        home.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"novelstudio.local".equals(uri.getHost()) || uri.getPort() != -1)
                    return denied();
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (!path.matches("/[a-zA-Z0-9_./-]+") || path.contains("..")) return denied();
                try {
                    if (path.startsWith("/illustrations/")) {
                        String id = path.substring("/illustrations/".length());
                        if (!id.endsWith(".png")) return denied();
                        id = id.substring(0, id.length() - 4);
                        if (!IllustrationStore.isValidId(id)) return denied();
                        Map<String,String> headers = new HashMap<>();
                        headers.put("X-Content-Type-Options", "nosniff");
                        headers.put("Cache-Control", "private, no-store");
                        headers.put("Content-Security-Policy", "default-src 'none'");
                        return new WebResourceResponse("image/png", null, 200, "OK", headers, illustrations.open(id));
                    }
                    String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" :
                        path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".png") ? "image/png" :
                        path.endsWith(".woff2") ? "font/woff2" : "text/html";
                    Map<String,String> headers = new HashMap<>();
                    headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                    headers.put("X-Content-Type-Options", "nosniff");
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(path.substring(1)));
                } catch (Exception error) { return denied(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return true;
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "novelstudio.local".equals(uri.getHost()) && uri.getPort() == -1) return false;
                openExternalChecked(uri.toString());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!url.startsWith(APP_ORIGIN)) return;
                uiReady = true;
                while (!queuedEvents.isEmpty()) home.evaluateJavascript(queuedEvents.remove(), null);
                runtime.replay();
                String warning = drafts.takeWarning();
                if (!warning.isEmpty()) emit("error", object("message", warning));
            }
            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
            }
        });
        home.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("小説工房").setMessage(message)
                    .setPositiveButton("OK", (dialog, which) -> result.confirm()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setTitle("確認").setMessage(message)
                    .setPositiveButton("実行", (dialog, which) -> result.confirm())
                    .setNegativeButton("キャンセル", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String value, JsPromptResult result) {
                EditText field = new EditText(MainActivity.this); field.setText(value); field.setSingleLine(false);
                new AlertDialog.Builder(MainActivity.this).setTitle(message).setView(field)
                    .setPositiveButton("保存", (dialog, which) -> result.confirm(field.getText().toString()))
                    .setNegativeButton("キャンセル", (dialog, which) -> result.cancel()).setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
        });
        frame.addView(home, new FrameLayout.LayoutParams(-1,-1));
    }

    private WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", new HashMap<>(),
            new ByteArrayInputStream(new byte[0]));
    }

    private void emit(String type, JSONObject payload) {
        final String script = "if(window.onNativeEvent)window.onNativeEvent(" + JSONObject.quote(type) + "," + payload.toString() + ");";
        runOnUiThread(() -> {
            if (destroyed) return;
            if (uiReady && home != null) home.evaluateJavascript(script, null);
            else queuedEvents.add(script);
        });
    }

    @Override public void onRuntimeEvent(String type, JSONObject payload) {
        runOnUiThread(() -> {
            if (destroyed || !runtime.canDeliverTo(this)) return;
            emit(type, payload);
        });
    }

    private static JSONObject object(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) { }
        return result;
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private void updateAwake() {
        runOnUiThread(() -> {
            if (destroyed) return;
            // CPU wakefulness belongs to the foreground service; the display may turn off.
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private boolean documentAvailable() {
        if (documentRequestCode == 0) return true;
        toast("先に開いているファイル選択を完了してください。");
        return false;
    }

    private boolean openDocument(Intent intent, int code) {
        documentRequestCode = code;
        try { startActivityForResult(intent, code); return true; }
        catch (Exception error) {
            documentRequestCode = 0;
            emit("notice", object("message", "ファイル選択アプリを開けませんでした。"));
            return false;
        }
    }

    private JSONObject imageEvent(JSONObject request, String phase, String message) {
        JSONObject data = new JSONObject();
        try {
            for (String key : new String[]{"requestId", "bookId", "chapterId"})
                data.put(key, request == null ? "" : request.optString(key));
            data.put("phase", phase);
            if (message != null && !message.isEmpty()) data.put("message", message);
        } catch (Exception ignored) { }
        return data;
    }

    private static boolean validImageDocumentRequest(JSONObject value) {
        if (value == null) return false;
        for (String field : new String[]{"requestId", "bookId", "chapterId"})
            if (!value.optString(field).matches("[a-zA-Z0-9_-]{3,100}")) return false;
        return true;
    }

    private void restoreImageDocumentRequest(Bundle state) {
        if (state == null) return;
        try {
            String importing = state.getString("image_document_import", "");
            String exporting = state.getString("image_document_export", "");
            if (!importing.isEmpty() && importing.length() <= 2000) {
                JSONObject value = new JSONObject(importing);
                if (validImageDocumentRequest(value)) {
                    pendingImageImport = value; documentRequestCode = IMAGE_IMPORT_REQUEST;
                }
            } else if (IllustrationStore.isValidId(exporting)) {
                pendingImageExport = exporting; documentRequestCode = IMAGE_EXPORT_REQUEST;
            }
        } catch (Exception ignored) { }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Only small, non-secret identifiers enter the Android saved-state bundle.
        // API keys, manuscripts, image bytes and OAuth values remain in private storage.
        if (documentRequestCode == IMAGE_IMPORT_REQUEST && validImageDocumentRequest(pendingImageImport))
            state.putString("image_document_import", pendingImageImport.toString());
        else if (documentRequestCode == IMAGE_EXPORT_REQUEST && IllustrationStore.isValidId(pendingImageExport))
            state.putString("image_document_export", pendingImageExport);
        super.onSaveInstanceState(state);
    }

    private void showSpeechApiSettings() {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);form.setPadding(pad,pad,pad,0);
        TextView label=new TextView(this);label.setText("Gemini APIの音声合成を使用します。ChatGPTの契約とは別の利用枠・料金です。再生・試聴時に対象区間の本文と声・読み方をGoogleへ送信します。生成済み音声は端末に一時保存し、同じ設定なら再利用します。\n\nAPIキーは端末内で暗号化し、バックアップには含めません。");form.addView(label);
        EditText key=new EditText(this);key.setHint("Gemini APIキー");key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSaveEnabled(false);key.setSaveFromParentEnabled(false);key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);form.addView(key);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("AI音声APIを設定").setView(form).setNegativeButton("キャンセル",null).setPositiveButton("保存",null).create();
        dialog.setOnDismissListener(d->key.setText(""));dialog.setOnShowListener(d->{if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=key.getText().toString().trim();if(value.length()<20||value.length()>2048||!value.matches("[!-~]+")){key.setError("APIキーを確認してください。");return;}dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);fileIo.execute(()->{try{narration.audio.configure(value);runOnUiThread(()->{if(!destroyed){dialog.dismiss();toast("AI音声APIキーを保存しました。");emit("speech",object("refresh",true));}});}catch(Exception e){runOnUiThread(()->{if(!destroyed){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);key.setError("APIキーを保存できませんでした。");}});}});});});dialog.show();
    }

    private void showImageApiSettings() {
        if (imageSettingsDialog != null && imageSettingsDialog.isShowing()) return;
        if (imageClient.status().optBoolean("busy")) { toast("挿絵の生成が完了してから設定してください。"); return; }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);
        TextView explanation = new TextView(this);
        explanation.setText("挿絵の自動生成はOpenAI APIの従量課金です。ChatGPTの契約とは別です。\n\n設定後、「挿絵を作成」を押すと作品設定と対象の本文を送信し、画像を1枚生成します。キーはこの端末で暗号化して保存します。");
        form.addView(explanation);
        EditText key = new EditText(this);
        key.setHint("OpenAI APIキー");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false);
        key.setSaveFromParentEnabled(false);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        form.addView(key);
        TextView modelLabel = new TextView(this); modelLabel.setText("画像モデル"); form.addView(modelLabel);
        Spinner model = new Spinner(this);
        String[] supportedModels = ImageGenerationClient.SUPPORTED_MODELS.toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, supportedModels);
        model.setAdapter(adapter);
        String activeModel = imageClient.status().optString("model", ImageGenerationClient.DEFAULT_MODEL);
        for (int i = 0; i < supportedModels.length; i++) if (supportedModels[i].equals(activeModel)) model.setSelection(i);
        form.addView(model);
        TextView detail = new TextView(this); detail.setText("1回につき1枚・1024×1024・中品質。通信の再試行は手動です。"); form.addView(detail);
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("挿絵APIを設定（別料金）").setView(scroll)
            .setPositiveButton("保存して有効にする", null).setNegativeButton("キャンセル", null).create();
        imageSettingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> { key.setText(""); imageSettingsDialog = null; });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String enteredKey = key.getText().toString().trim();
                String selectedModel = String.valueOf(model.getSelectedItem());
                if (enteredKey.length() < 20 || enteredKey.length() > 2048 || !enteredKey.matches("[!-~]+")) {
                    key.setError("APIキーを入力してください。"); return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                fileIo.execute(() -> {
                    try {
                        imageClient.configure(enteredKey, selectedModel);
                        runOnUiThread(() -> { if (!destroyed) { dialog.dismiss(); toast("挿絵APIを設定しました。生成はAPIの利用枠・料金で処理されます。"); } });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            if (destroyed || !dialog.isShowing()) return;
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            key.setError("設定を保存できませんでした。キーと端末の空き容量を確認してください。");
                        });
                    }
                });
            });
        });
        dialog.show();
    }

    private void requestGenerationNotifications(boolean explicit) {
        if (destroyed || !runtime.canDeliverTo(this)) return;
        if (Build.VERSION.SDK_INT < 33) { if (explicit) openNotificationSettings(); return; }
        String permission = android.Manifest.permission.POST_NOTIFICATIONS;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            if (explicit) openNotificationSettings();
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences("generation-ui", MODE_PRIVATE);
        if (!preferences.getBoolean("notification_prompted", false)) {
            preferences.edit().putBoolean("notification_prompted", true).apply();
            requestPermissions(new String[]{permission}, NOTIFICATION_PERMISSION_REQUEST);
        } else if (explicit) openNotificationSettings();
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (RuntimeException error) { toast("端末の設定から小説工房の通知を有効にしてください。"); }
    }

    private void openBackgroundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) { toast("端末の設定から小説工房のバッテリー利用を確認してください。"); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED)
                toast("通知は許可されていません。生成は続きます。通知設定はアプリの設定から変更できます。");
            runtime.replay();
        }
    }

    public final class NativeBridge {
        @JavascriptInterface public String loadState() { return runtime.loadState(); }
        @JavascriptInterface public boolean saveState(String json) {
            if (destroyed || !runtime.ownsUi(MainActivity.this)) return false;
            try { drafts.save(json); return true; }
            catch (Exception error) { emit("error", object("message", "保存できませんでした: " + error.getMessage())); return false; }
        }
        @JavascriptInterface public String getNarration(String bid,String cid) {try{return narration.view(bid,cid).toString();}catch(Exception e){return object("error",e.getMessage()).toString();}}
        @JavascriptInterface public String narrationCommand(String json) {
            try {
                if(json==null||json.length()>10000)throw new IllegalArgumentException();JSONObject r=new JSONObject(json);String action=r.getString("action"),bid=r.optString("bookId"),cid=r.optString("chapterId");
                switch(action) {
                    case "analyze": narration.analyze(r,true);runOnUiThread(()->MainActivity.this.requestGenerationNotifications(false));break;
                    case "plain": narration.analyze(r,false);break;
                    case "character": narration.saveCharacter(bid,r.getJSONObject("value"));break;
                    case "segment": narration.saveSegment(bid,cid,r.getJSONObject("value"));break;
                    case "settings": narration.settings(bid,r.getJSONObject("value"));break;
                    case "forget": narration.forget(bid,cid);break;
                    case "play": runOnUiThread(()->{narration.play(bid,cid,r.optInt("index",-1));MainActivity.this.requestGenerationNotifications(false);});break;
                    case "pause": runOnUiThread(()->narration.pause("一時停止しました。続きから再開できます。"));break;
                    case "resume": runOnUiThread(()->narration.resume());break;
                    case "next": runOnUiThread(()->narration.skip(1));break;
                    case "previous": runOnUiThread(()->narration.skip(-1));break;
                    case "sample": runOnUiThread(()->narration.sample(bid,r.optString("characterId")));break;
                    case "cancelAnalysis": narration.cancelAnalysis();break;
                    case "configure": runOnUiThread(()->showSpeechApiSettings());break;
                    case "clearKey": narration.audio.clear();break;
                    case "deviceSettings": runOnUiThread(()->{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){toast("Androidの設定から「テキスト読み上げ」を開いてください。");}});break;
                    default: throw new IllegalArgumentException();
                }
                return "{\"ok\":true}";
            }catch(Exception e){return NarrationStore.obj("ok",false,"message",e instanceof java.io.IOException?e.getMessage():"読み上げの設定・操作を確認してください。").toString();}
        }
        @JavascriptInterface public boolean validateNarrationBackup(String json) {try{narration.validateImport(json);return true;}catch(Exception e){return false;}}
        @JavascriptInterface public boolean restoreNarrationBackup(String json) {try{narration.importData(json);return true;}catch(Exception e){return false;}}
        @JavascriptInterface public String getStatus() { return siwc.status().toString(); }
        @JavascriptInterface public String getAppInfo() { return "{\"version\":\"1.4.1\",\"platform\":\"android\"}"; }
        @JavascriptInterface public String getRuntimeSnapshot() { return runtime.snapshot().toString(); }
        @JavascriptInterface public boolean acknowledgeGeneration(String requestId, long seq) { return runtime.acknowledge(requestId, seq); }
        @JavascriptInterface public void requestGenerationNotifications() { runOnUiThread(() -> MainActivity.this.requestGenerationNotifications(true)); }
        @JavascriptInterface public void openBackgroundSettings() { runOnUiThread(() -> MainActivity.this.openBackgroundSettings()); }
        @JavascriptInterface public String getImageStatus() { return imageClient.status().toString(); }
        @JavascriptInterface public void configureImageApi() { runOnUiThread(() -> showImageApiSettings()); }
        @JavascriptInterface public void clearImageApi() {
            fileIo.execute(() -> {
                try { imageClient.clear(); toast("挿絵APIキーを削除しました。"); }
                catch (Exception error) { emit("notice", object("message", "APIキーを削除できませんでした。")); }
            });
        }
        @JavascriptInterface public void generateIllustration(String json) {
            try {
                if (json == null || json.length() > 100000) throw new IllegalArgumentException();
                runtime.startImage(new JSONObject(json));
                runOnUiThread(() -> MainActivity.this.requestGenerationNotifications(false));
            } catch (Exception error) { emit("illustration", imageEvent(null, "error", "挿絵の生成指示を読み込めませんでした。")); }
        }
        @JavascriptInterface public void cancelIllustration() { runtime.cancelImage(); }
        @JavascriptInterface public void importIllustration(String json) {
            JSONObject value;
            try {
                if (json == null || json.length() > 2000) throw new IllegalArgumentException();
                value = new JSONObject(json);
                if (!validImageDocumentRequest(value)) throw new IllegalArgumentException();
            } catch (Exception error) { emit("illustration", imageEvent(null, "error", "画像の保存先を読み込めませんでした。")); return; }
            final JSONObject request = value;
            runOnUiThread(() -> {
                if (!documentAvailable()) { emit("illustration", imageEvent(request, "cancelled", "ファイル選択が既に開いています。")); return; }
                pendingImageImport = request;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp"});
                if (!openDocument(intent, IMAGE_IMPORT_REQUEST)) {
                    pendingImageImport = null;
                    emit("illustration", imageEvent(request, "error", "画像選択を開けませんでした。"));
                }
            });
        }
        @JavascriptInterface public void exportIllustration(String id) {
            runOnUiThread(() -> {
                if (!documentAvailable()) return;
                if (!IllustrationStore.isValidId(id) || !illustrations.exists(id)) { toast("挿絵が見つかりません。"); return; }
                pendingImageExport = id;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/png").putExtra(Intent.EXTRA_TITLE, "novelstudio-illustration-" + id + ".png");
                if (!openDocument(intent, IMAGE_EXPORT_REQUEST)) pendingImageExport = null;
            });
        }
        @JavascriptInterface public void exportBackup(String json) {
            runOnUiThread(() -> {
                if (!documentAvailable()) return;
                if (json == null || json.getBytes(StandardCharsets.UTF_8).length > DraftStore.MAX_BYTES) { toast("バックアップの原稿が30MiBを超えています。"); return; }
                try { JSONObject backup=new JSONObject(json);backup.put("narrationBackup",narration.exportData());pendingBackupState=backup.toString();if(pendingBackupState.getBytes(StandardCharsets.UTF_8).length>29*1024*1024)throw new java.io.IOException("backup too large"); }
                catch(Exception e){toast("読み上げ設定を含むバックアップを作成できませんでした。");return;}
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "novelstudio-backup.zip");
                if (!openDocument(intent, BACKUP_EXPORT_REQUEST)) pendingBackupState = null;
            });
        }
        @JavascriptInterface public void signIn() { runOnUiThread(() -> siwc.signIn()); }
        @JavascriptInterface public void signOut() { siwc.logout(); }
        @JavascriptInterface public void refreshModels() { siwc.loadModels(); }
        @JavascriptInterface public void cancelGeneration() { runtime.cancelText(); }
        @JavascriptInterface public void startBatchGeneration(String json) {
            try { runtime.startBatch(new JSONObject(json));runOnUiThread(() -> MainActivity.this.requestGenerationNotifications(false)); }
            catch(Exception error){emit("notice",object("message","一括生成の設定を確認してください。"));}
        }
        @JavascriptInterface public void resumeBatchGeneration() { runtime.resumeBatch(); }
        @JavascriptInterface public void closeBatchGeneration() { runtime.closeBatch(); }
        @JavascriptInterface public void generate(String json) {
            String requestId = "";
            try {
                JSONObject value = new JSONObject(json);
                requestId = value.getString("requestId");
                if (value.optString("prompt").length() > 800_000) throw new IllegalArgumentException("原稿が長すぎます。送信する範囲を短くしてください。");
                runtime.startText(value);
                runOnUiThread(() -> MainActivity.this.requestGenerationNotifications(false));
            } catch (Exception error) {
                JSONObject payload = object("requestId", requestId);
                try { payload.put("phase", "error").put("message", error.getMessage()); } catch (Exception ignored) { }
                emit("generation", payload);
                updateAwake();
            }
        }
        @JavascriptInterface public void openSite(String json) {
            try {
                final JSONObject payload = new JSONObject(json);
                runOnUiThread(() -> {
                    home.evaluateJavascript("if(window.flushAppState)window.flushAppState();", null);
                    try {
                        browser.open(payload);
                        browser.getView().setVisibility(View.VISIBLE);
                        home.setVisibility(View.GONE);
                    } catch (Exception error) { emit("notice", object("message", "投稿画面を開けませんでした。")); }
                });
            } catch (Exception error) { emit("notice", object("message", "投稿データを読み込めませんでした。")); }
        }
        @JavascriptInterface public void copyText(String text) { copy(text); }
        @JavascriptInterface public void openExternal(String url) { runOnUiThread(() -> openExternalChecked(url)); }
        @JavascriptInterface public void exportFile(String name, String mime, String text) {
            runOnUiThread(() -> beginExport(name, mime, text));
        }
        @JavascriptInterface public void importBackup() {
            runOnUiThread(() -> {
                if (!documentAvailable()) return;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream"});
                openDocument(intent, IMPORT_REQUEST);
            });
        }
    }

    private void copy(String text) {
        runOnUiThread(() -> {
            if (text == null) return;
            if (text.length() > 200_000) {
                toast("長い原稿はTXTへ出力するか、話を分割してコピーしてください。");
                return;
            }
            ClipboardManager manager = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("小説工房の原稿", text);
            if (Build.VERSION.SDK_INT >= 24) {
                PersistableBundle flags = new PersistableBundle(); flags.putBoolean("android.content.extra.IS_SENSITIVE", true);
                data.getDescription().setExtras(flags);
            }
            try { manager.setPrimaryClip(data); }
            catch (RuntimeException error) { toast("コピーできませんでした。原稿を短くするかTXT出力を使ってください。"); return; }
            if (Build.VERSION.SDK_INT < 33) toast("コピーしました");
        });
    }

    private void openExternalChecked(String url) {
        try {
            Uri uri = Uri.parse(url); String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            boolean allowed = false;
            for (String domain : new String[]{"chatgpt.com", "openai.com", "syosetu.com", "kakuyomu.jp"})
                if (host.equals(domain) || host.endsWith("."+domain)) allowed = true;
            if (!allowed) throw new IllegalArgumentException();
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception error) { toast("このリンクを開けませんでした。HTTPSの公式サイトに対応しています。"); }
    }

    private void beginExport(String name, String mime, String text) {
        if (!documentAvailable()) return;
        if (text == null) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DraftStore.MAX_BYTES) { toast("出力データが30MiBを超えています。"); return; }
        String safeName = name == null ? "novelstudio-export.txt" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safeName.length() > 120) safeName = safeName.substring(0, 120);
        if (safeName.trim().isEmpty()) safeName = "novelstudio-export.txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json".equals(mime) ? "application/json" : "text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, safeName);
        pendingExport = bytes;
        if (!openDocument(intent, EXPORT_REQUEST)) pendingExport = null;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws Exception {
        if (input == null) throw new IllegalArgumentException();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) {
                if (size > maximum - output.size()) throw new IllegalArgumentException("File exceeds limit");
                output.write(buffer, 0, size);
            }
            return output.toByteArray();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (browser != null && browser.onActivityResult(requestCode, resultCode, data)) return;
        if (documentRequestCode == requestCode) documentRequestCode = 0;
        if (requestCode == EXPORT_REQUEST) {
            final byte[] bytes = pendingExport; pendingExport = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
            final Uri uri = data.getData();
            fileIo.execute(() -> {
                try {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IllegalStateException();
                        output.write(bytes); output.flush();
                    }
                    toast("ファイルを保存しました");
                } catch (Exception error) { emit("notice", object("message", "ファイルを保存できませんでした。別の保存先を選択してください。")); }
            });
        } else if (requestCode == IMAGE_EXPORT_REQUEST) {
            final String id = pendingImageExport; pendingImageExport = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || id == null) return;
            final Uri uri = data.getData();
            fileIo.execute(() -> {
                try {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IllegalStateException();
                        illustrations.copyTo(id, output); output.flush();
                    }
                    toast("挿絵をPNGで保存しました");
                } catch (Exception error) { emit("notice", object("message", "挿絵を保存できませんでした。空き容量と保存先を確認してください。")); }
            });
        } else if (requestCode == BACKUP_EXPORT_REQUEST) {
            final String snapshot = pendingBackupState; pendingBackupState = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || snapshot == null) return;
            final Uri uri = data.getData();
            toast("原稿と挿絵をバックアップしています…");
            fileIo.execute(() -> {
                try {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IllegalStateException();
                        illustrationBackup.exportTo(output, snapshot); output.flush();
                    }
                    toast("原稿と挿絵のZIPバックアップを保存しました");
                } catch (Exception error) { emit("notice", object("message", "バックアップを保存できませんでした。画像の有無・空き容量・合計250MiBの上限を確認してください。保存先に未完成のファイルが残る場合があります。")); }
            });
        } else if (requestCode == IMAGE_IMPORT_REQUEST) {
            final JSONObject request = pendingImageImport; pendingImageImport = null;
            if (request == null) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                emit("illustration", imageEvent(request, "cancelled", "画像の取り込みをキャンセルしました。")); return;
            }
            final Uri uri = data.getData();
            fileIo.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    byte[] bytes = readBounded(input, 16 * 1024 * 1024);
                    JSONObject image = illustrations.save(bytes, "import", "");
                    JSONObject result = imageEvent(request, "complete", "画像を取り込みました。");
                    result.put("image", image); emit("illustration", result);
                } catch (Exception error) {
                    emit("illustration", imageEvent(request, "error", "画像を取り込めませんでした。PNG/JPEG/WebP・16MiB以下・各辺4096px以下の画像を選んでください。"));
                }
            });
        } else if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            fileIo.execute(() -> {
                try (InputStream raw = getContentResolver().openInputStream(uri)) {
                    if (raw == null) throw new IllegalStateException();
                    try (BufferedInputStream input = new BufferedInputStream(raw)) {
                        input.mark(4);
                        int first = input.read(), second = input.read(); input.reset();
                        String text;
                        if (first == 'P' && second == 'K') text = illustrationBackup.importFrom(input);
                        else {
                            text = new String(readBounded(input, DraftStore.MAX_BYTES), StandardCharsets.UTF_8);
                            if (text.startsWith("\uFEFF")) text = text.substring(1);
                            new JSONObject(text);
                        }
                        emit("import", object("text", text));
                    }
                } catch (Exception error) { emit("notice", object("message", "バックアップを読み込めませんでした。JSON/ZIPの形式・画像・ファイルサイズを確認してください。")); }
            });
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        // This deep link is only a return-to-app action. OAuth codes are accepted solely by the PKCE loopback listener.
        if (runtime != null) runtime.replay();
    }

    @Override protected void onPause() {
        if (home != null && uiReady) home.evaluateJavascript("if(window.flushAppState)window.flushAppState();", null);
        if (runtime != null) runtime.pauseUi(this);
        android.webkit.CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (runtime != null) {
            runtime.attachUi(this, this);
            runtime.replay();
        }
        updateAwake();
    }

    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (browser != null && browser.getView().getVisibility() == View.VISIBLE) {
            if (!browser.goBack()) browser.close();
            return;
        }
        if (home == null) { moveTaskToBack(true); return; }
        home.evaluateJavascript("(function(){if(window.flushAppState)window.flushAppState();return window.handleAppBack?window.handleAppBack():false;})()",
            result -> { if (!"true".equals(result)) moveTaskToBack(true); });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (imageSettingsDialog != null) imageSettingsDialog.dismiss();
        if (runtime != null) runtime.detachUi(this);
        if (browser != null) browser.destroy();
        if (home != null) { home.removeJavascriptInterface("NativeBridge"); home.destroy(); }
        fileIo.shutdown();
        super.onDestroy();
    }
}
