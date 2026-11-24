package jp.haruya.novelstudio;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-owned inference. Activities only observe it; the foreground service keeps it alive. */
public final class GenerationRuntime {
    public interface UiListener { void onRuntimeEvent(String type, JSONObject data); }
    public interface AuxiliaryListener {
        boolean onAuxiliaryResult(JSONObject data) throws Exception;
        default JSONObject nextAuxiliaryRequest() throws Exception {return null;}
        default void onAuxiliaryStopped(String reason) throws Exception { }
    }
    private volatile AuxiliaryListener auxiliary;
    public interface ServiceListener { void onGenerationProgress(); }
    private static volatile GenerationRuntime instance;
    private final Context context;
    private final Object lock = new Object();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final DraftStore drafts;
    private final IllustrationStore images;
    private final SiwcClient siwc;
    private final ImageGenerationClient imageClient;
    private final GenerationJournal journal;
    private final String journalError;
    private final BatchGeneration batch;
    private volatile boolean batchActive, auxiliaryActive;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private WeakReference<UiListener> ui = new WeakReference<>(null);
    private WeakReference<ServiceListener> service = new WeakReference<>(null);
    private boolean foreground, serviceReady;
    private Work textWork, imageWork;
    private JSONObject lastModels;
    private String completionMessage = "";
    private volatile String message = "別アプリへの切り替えや画面消灯中も生成を続けます。";

    public static GenerationRuntime get(Context context) {
        if (instance == null) synchronized (GenerationRuntime.class) {
            if (instance == null) instance = new GenerationRuntime(context.getApplicationContext());
        }
        return instance;
    }

    private GenerationRuntime(Context context) {
        this.context = context;
        drafts = new DraftStore(context);
        images = new IllustrationStore(context);
        GenerationJournal loaded = null;
        String failure = "";
        try { loaded = new GenerationJournal(context); }
        catch (IOException error) { failure = "途中結果の保存領域を開けませんでした。空き容量を確認してアプリを開き直してください。"; }
        journal = loaded;
        journalError = failure;
        BatchGeneration loadedBatch = null;
        try { loadedBatch = new BatchGeneration(context); loadedBatch.recover(journal); }
        catch (Exception error) { message = "一括生成の記録を読み込めませんでした。記録は保持しています。"; }
        batch = loadedBatch;
        if (!failure.isEmpty()) message = failure;
        siwc = new SiwcClient(context, this::clientEvent);
        imageClient = new ImageGenerationClient(context, images, this::clientEvent);
    }

    DraftStore drafts() { return drafts; }
    IllustrationStore images() { return images; }
    SiwcClient siwc() { return siwc; }
    ImageGenerationClient imageClient() { return imageClient; }

    public void registerActivity(Activity owner) {
        synchronized (lock) { activity = new WeakReference<>(owner); foreground = false; ui.clear(); }
        siwc.attachActivity(owner);
    }

    public void attachUi(Activity owner, UiListener listener) {
        synchronized (lock) {
            activity = new WeakReference<>(owner);
            ui = new WeakReference<>(listener);
            foreground = true;
        }
        siwc.attachActivity(owner);
        siwc.setForeground(owner, true);
    }

    public void pauseUi(Activity owner) {
        synchronized (lock) {
            if (activity.get() == owner) { foreground = false; ui.clear(); }
        }
        siwc.setForeground(owner, false);
        checkpointSoon();
    }

    public void detachUi(Activity owner) {
        pauseUi(owner);
        synchronized (lock) { if (activity.get() == owner) activity.clear(); }
        siwc.detachActivity(owner);
        // Never close either inference client when an Activity is destroyed.
    }

    public boolean ownsUi(Activity owner) {
        synchronized (lock) { return activity.get() == owner; }
    }

    public boolean canDeliverTo(Activity owner) {
        synchronized (lock) { return foreground && activity.get() == owner; }
    }

    public String loadState() {
        String stored = drafts.load();
        if (journal == null) return stored;
        try {
            if (batch != null) stored = batch.mergeInto(stored, journal.snapshots());
            return journal.mergeInto(stored);
        }
        catch (Exception error) {
            message = "途中結果の復元に失敗しました。保存済み原稿を表示しています。途中結果の記録は保持しています。";
            return stored;
        }
    }

    public JSONObject snapshot() {
        JSONArray records;
        String textId, imageId;
        synchronized (lock) {
            records = journal == null ? new JSONArray() : journal.snapshots();
            textId = textWork == null ? "" : textWork.id;
            imageId = imageWork == null ? "" : imageWork.id;
            // Journal terminal commits can precede removal of the corresponding Work by a
            // few instructions. Terminal data always wins when constructing a UI snapshot.
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.optJSONObject(i);
                if (record == null || "generating".equals(record.optString("phase"))) continue;
                String id = record.optString("requestId");
                if (id.equals(textId)) textId = "";
                if (id.equals(imageId)) imageId = "";
            }
        }
        JSONObject batchSnapshot = batch == null ? null : batch.snapshot(records);
        return object("records", records, "activeTextId", textId, "activeImageId", imageId,
            "notificationsEnabled", notificationsEnabled(), "backgroundReady", journal != null,
            "message", message, "auxiliaryActive",auxiliaryActive,"batch", batchSnapshot == null ? JSONObject.NULL : batchSnapshot);
    }

    public void replay() {
        deliver("status", siwc.status());
        deliver("imageStatus", imageClient.status());
        JSONObject models;
        synchronized (lock) { models = lastModels; }
        if (models != null) deliver("models", models);
        deliver("runtimeSnapshot", snapshot());
    }

    public boolean acknowledge(String requestId, long seq) {
        if (journal == null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,200}") || seq < 0) return false;
        try {
            // The UI's call is not itself proof that a candidate reached durable manuscript
            // storage. Confirm the saved receipt before removing the independent journal.
            JSONObject receipts = new JSONObject(drafts.load()).optJSONObject("runtimeReceipts");
            JSONObject receipt = receipts == null ? null : receipts.optJSONObject(requestId);
            if (receipt == null || !receipt.optBoolean("terminal") || receipt.optLong("seq", -1) < seq) return false;
            journal.acknowledge(requestId, seq);
            return true;
        } catch (Exception error) { return false; }
    }

    public void attachAuxiliary(AuxiliaryListener listener) {
        auxiliary=listener;
        control.execute(() -> {
            if(journal==null)return;
            JSONArray records=journal.snapshots();
            for(int i=0;i<records.length();i++){JSONObject r=records.optJSONObject(i);if(r!=null&&"narration".equals(r.optString("purpose"))&&!"generating".equals(r.optString("phase")))routeAuxiliary(r,true);}
            if(!auxiliaryActive)stopAuxiliary("前回の解析位置を復元しました。");
        });
    }
    public void emitSpeech(JSONObject state) { deliver("speech",state); }
    private void stopAuxiliary(String reason) {
        AuxiliaryListener listener=auxiliary;if(listener==null)return;
        try{listener.onAuxiliaryStopped(reason);}catch(Exception e){deliver("notice",object("message","解析の保存に失敗しました。受信結果は生成記録に保持しています。"));}
    }
    private void routeAuxiliary(JSONObject record,boolean durable) {routeAuxiliary(record,durable,false,false);}
    private void routeAuxiliary(JSONObject record,boolean durable,boolean liveChain,boolean canContinue) {
        AuxiliaryListener listener=auxiliary;if(listener==null){if(liveChain)auxiliaryActive=false;return;}
        try {
            boolean saved=listener.onAuxiliaryResult(record);
            if(saved&&durable&&journal!=null)journal.acknowledge(record.optString("requestId"),record.optLong("seq"));
            if(liveChain) {
                if(saved&&durable&&canContinue&&auxiliaryActive&&serviceReady) {
                    JSONObject next=listener.nextAuxiliaryRequest();
                    if(next!=null){advanceAuxiliary(next);return;}
                }
                auxiliaryActive=false;stopAuxiliary("解析を一時停止しました。");
            }
        }catch(Exception e){if(liveChain)auxiliaryActive=false;stopAuxiliary("解析の保存を確認できないため停止しました。");deliver("notice",object("message","音声解析の保存に失敗しました。生成記録は保持しています。空き容量を確認してください。"));}
    }
    /** Authorized continuation of the current analysis. Never starts a new service in the background. */
    private void advanceAuxiliary(JSONObject request) throws Exception {
        if(!"narration".equals(request.optString("purpose"))||!validId(request.optString("requestId"))||!validId(request.optString("bookId"))||!validId(request.optString("chapterId"))||request.optString("model").isEmpty()||request.optString("prompt").isEmpty()||request.optString("prompt").length()>760000)throw new IOException("解析要求が不正です。");
        Work work=new Work(new JSONObject(request.toString()),false);
        synchronized(lock){if(!auxiliaryActive||!serviceReady||textWork!=null||batchActive){auxiliaryActive=false;stopAuxiliary("解析を一時停止しました。");return;}textWork=work;}
        try{journal.begin(work.request,"text");work.prepared=true;deliver("runtimeSnapshot",snapshot());dispatch(work);}
        catch(Exception e){synchronized(lock){if(textWork==work)textWork=null;auxiliaryActive=false;}throw e;}
    }
    public void startText(JSONObject request) { start(request, false); }
    public void startImage(JSONObject request) { start(request, true); }

    public void startBatch(JSONObject input) { launchBatch(input, false); }
    public void resumeBatch() { launchBatch(null, true); }
    private void launchBatch(JSONObject input, boolean resume) {
        if (batch == null || journal == null) { deliver("notice",object("message","一括生成の保存領域を開けません。空き容量を確認してください。"));return; }
        final JSONObject safe = input == null ? null : BatchGeneration.copy(input);
        synchronized(lock) {
            if (!foreground || activity.get()==null || textWork!=null || batchActive || auxiliaryActive) {
                deliver("notice",object("message","アプリを開き、現在の文章生成が終了してから開始してください。"));return;
            }
            batchActive=true;completionMessage="";
            try { context.startForegroundService(new Intent(context,GenerationService.class).setAction(GenerationService.ACTION_START)); }
            catch(RuntimeException e) {batchActive=false;deliver("notice",object("message","バックグラウンド生成を開始できませんでした。アプリを開いて再試行してください。"));return;}
        }
        control.execute(() -> {
            if (!batchActive) {deliver("notice",object("message","一括生成の開始が中断されました。アプリを開いて再試行してください。"));emitBatch();notifyService();return;}
            try {
                if(resume){batch.recover(journal);if(!batch.unfinished()){batchActive=false;emitBatch();notifyService();return;}batch.resume();}else batch.begin(safe,drafts.load());
                advanceBatch();
            } catch(Exception e) {batchActive=false;pauseBatch("一括生成を開始できませんでした。"+(e.getMessage()==null?"":e.getMessage()));}
            emitBatch();notifyService();
        });
    }

    public void closeBatch() {
        control.execute(() -> {
            try {if(batchActive)throw new IOException("一時停止してから終了してください。");if(batch!=null)batch.close(drafts.load());}
            catch(Exception e){deliver("notice",object("message",e.getMessage()));}
            emitBatch();
        });
    }
    private void emitBatch() {
        JSONObject value=batch==null?null:batch.snapshot(journal==null?null:journal.snapshots());
        if(value!=null)deliver("batchSnapshot",value);else deliver("runtimeSnapshot",snapshot());
    }
    private void pauseBatch(String reason) {
        batchActive=false;
        try {if(batch!=null)batch.pause(reason);}catch(Exception e){message="一括生成の保存に失敗しました。途中結果の記録を保持しています。";}
        completionMessage=reason;deliver("notice",object("message",reason));emitBatch();
    }
    /** Runs on the control executor, under the already user-started foreground service. */
    private void advanceBatch() {
        if(!batchActive || batch==null)return;
        try {
            JSONObject next=batch.prepareNext();
            if(next==null){batchActive=false;return;}
            Work work=new Work(next,false);
            synchronized(lock){
                if(!batchActive)return;
                if(textWork!=null)throw new IOException("別の文章生成を処理中です。");
                textWork=work;
            }
            try {journal.begin(next,"text");work.prepared=true;dispatch(work);}
            catch(Exception e){synchronized(lock){if(textWork==work)textWork=null;}throw e;}
            emitBatch();
        }catch(Exception e){pauseBatch("次の話の開始に失敗しました。保存した位置から再開できます。");}
    }

    private void start(JSONObject incoming, boolean image) {
        JSONObject request;
        try {
            request = new JSONObject(incoming.toString());
            if (!validId(request.optString("requestId")) || !validId(request.optString("bookId"))) throw new IllegalArgumentException();
            if (image || !"metadata".equals(request.optString("purpose")))
                if (!validId(request.optString("chapterId"))) throw new IllegalArgumentException();
            Object prompt = request.opt("prompt");
            if (!(prompt instanceof String) || ((String)prompt).trim().isEmpty()
                    || ((String)prompt).length() > (image ? 32000 : 800000)) throw new IllegalArgumentException();
            if (!image && (request.optString("model").isEmpty() || request.optString("model").length() > 300
                    || request.optString("prompt").length()+request.optString("resumePrefix").length()>760000)) throw new IllegalArgumentException();
        } catch (Exception error) {
            reject(image, incoming, "生成指示の形式または長さを確認してください。"); return;
        }
        if (journal == null) { reject(image, request, journalError); return; }
        Work work = new Work(request, image);
        synchronized (lock) {
            if (!foreground || activity.get() == null) {
                reject(image, request, "アプリを開いた状態で生成を開始してください。開始後は画面を閉じても続きます。"); return;
            }
            Work existing = image ? imageWork : textWork;
            if (!image && auxiliaryActive && existing==null) {reject(false,request,"話者解析を一時停止してから開始してください。");return;}
            if (!image && batchActive) {reject(false,request,"一括生成を一時停止してから開始してください。");return;}
            if (existing != null) {
                if (!existing.id.equals(work.id)) reject(image, request, "現在の生成が終わるか、中止してから開始してください。");
                return;
            }
            if (image) imageWork = work; else textWork = work;
            if(!image&&"narration".equals(request.optString("purpose")))auxiliaryActive=true;
            completionMessage = "";
            // Call while the user's generation tap is still in a visible Activity. No prompt
            // or manuscript is placed in an Intent (large text must not cross Binder).
            try {
                context.startForegroundService(new Intent(context, GenerationService.class).setAction(GenerationService.ACTION_START));
            } catch (RuntimeException error) {
                if (image) imageWork = null; else {textWork = null;auxiliaryActive=false;}
                reject(image, request, "バックグラウンド生成を開始できませんでした。アプリを開いたまま再度開始してください。");
                return;
            }
        }
        control.execute(() -> {
            synchronized (lock) { if ((work.image ? imageWork : textWork) != work) return; }
            try {
                if (!work.prepared) journal.begin(request, image ? "image" : "text");
                work.prepared = true;
                notifyService();
                deliver("runtimeSnapshot", snapshot());
                dispatch(work);
            } catch (Exception error) {
                failUnstarted(work, "途中結果を端末へ保存できないため、生成を開始しませんでした。空き容量を確認してください。");
            }
        });
    }

    public void serviceReady(ServiceListener listener) {
        synchronized (lock) { service = new WeakReference<>(listener); serviceReady = true; }
        control.execute(() -> {
            Work text, image;
            synchronized (lock) { text = textWork; image = imageWork; }
            if (text != null) dispatch(text);
            if (image != null) dispatch(image);
            notifyService();
        });
    }

    /** Atomically prevents a new job from using a service which is about to stop normally. */
    public boolean prepareIdleStop(ServiceListener listener) {
        synchronized (lock) {
            if (service.get() != listener || textWork != null || imageWork != null || batchActive || auxiliaryActive) return false;
            serviceReady = false; service.clear(); return true;
        }
    }

    public void serviceDestroyed(ServiceListener listener) {
        synchronized (lock) {
            if (service.get() != listener) return;
            serviceReady = false; service.clear();
        }
        cancelAll("バックグラウンド処理が終了しました。受信済みの内容を保存しています。");
    }

    public void serviceStartFailed() {
        synchronized (lock) { serviceReady = false; service.clear(); }
        cancelAll("生成用の通知を開始できませんでした。アプリの通知・バッテリー設定を確認してください。");
    }

    public void serviceExpired(ServiceListener listener, String reason) {
        Work text, image;
        synchronized (lock) {
            if (service.get() != listener) return;
            serviceReady = false;
            service.clear();
            text = textWork; image = imageWork;
            batchActive = false;auxiliaryActive=false;
            markCancel(text, reason); markCancel(image, reason);
        }
        message = reason;
        control.execute(() -> { stopAuxiliary(reason);if(batch!=null&&batch.running())pauseBatch(reason);cancel(text, reason); cancel(image, reason); checkpoint(); });
    }

    private void dispatch(Work work) {
        boolean cancelled;
        synchronized (lock) {
            if (!serviceReady || !work.prepared || work.launched || (work.image ? imageWork : textWork) != work) return;
            cancelled = work.cancelRequested;
            if (!cancelled) work.launched = true;
        }
        if (cancelled) { cancel(work, work.stopReason); return; }
        try {
            if (work.image) imageClient.generate(work.request);
            else {
                String prefix=work.request.optString("resumePrefix"),prompt=work.request.getString("prompt");
                if(!prefix.isEmpty())prompt += "\n\n【途中まで受信・保存済みの本文】\n"+prefix+"\n\n【再開指示】\n保存済み本文の最後の文字の直後から、この話の未出力部分だけを続けてください。末尾が文の途中ならその文を完成させてください。既存の文・段落や題名を繰り返さず、前置きは不要です。保存済み本文も含めて当初の目安の長さに収めてください。";
                siwc.generate(work.id, work.request.getString("model"), work.request.optString("instructions"), prompt);
            }
        } catch (Exception error) {
            handleClientEvent(work.image ? "illustration" : "generation", terminal(work, "error", "生成を開始できませんでした。"));
        }
    }

    private void failUnstarted(Work work, String reason) {
        synchronized (lock) {
            if ((work.image ? imageWork : textWork) != work) return;
            if (work.image) imageWork = null; else {textWork = null;auxiliaryActive=false;}
            completionMessage = reason;
        }
        message = reason;
        if("narration".equals(work.request.optString("purpose")))routeAuxiliary(terminal(work,"error",reason),false);
        else deliver(work.image ? "illustration" : "generation", terminal(work, "error", reason));
        notifyService();
    }

    public void cancelText() { requestCancel(false, ""); }
    public void cancelImage() { requestCancel(true, ""); }
    public void cancelAll(String reason) {
        Work text, image;
        synchronized (lock) {
            text = textWork; image = imageWork;
            batchActive = false;auxiliaryActive=false;
            markCancel(text, reason); markCancel(image, reason);
        }
        control.execute(() -> { stopAuxiliary(reason);if(batch!=null&&batch.running())pauseBatch(reason.isEmpty()?"一時停止しました。保存した位置から再開できます。":reason);cancel(text, reason); cancel(image, reason); checkpoint(); });
    }

    private void requestCancel(boolean image, String reason) {
        Work work;
        synchronized (lock) { work = image ? imageWork : textWork; markCancel(work, reason);if(!image){batchActive=false;auxiliaryActive=false;} }
        control.execute(() -> {if(!image)stopAuxiliary(reason);if(!image&&batch!=null&&batch.running())pauseBatch("一時停止しました。保存した位置から再開できます。");cancel(work, reason);});
    }

    private static void markCancel(Work work, String reason) {
        if (work == null) return;
        work.cancelRequested = true;
        if (work.stopReason.isEmpty() && reason != null && !reason.isEmpty()) work.stopReason = reason;
    }

    private void cancel(Work work, String reason) {
        if (work == null) return;
        boolean image = work.image;
        synchronized (lock) { if ((image ? imageWork : textWork) != work) return; markCancel(work, reason); }
        if (work.launched) {
            if (image) imageClient.cancel(); else siwc.cancel();
        } else {
            if (!work.prepared) {
                try { journal.begin(work.request, image ? "image" : "text"); work.prepared = true; }
                catch (Exception error) { failUnstarted(work, "生成開始を中止しました。"); return; }
            }
            handleClientEvent(image ? "illustration" : "generation", terminal(work,
                work.stopReason.isEmpty() ? "cancelled" : "error",
                work.stopReason.isEmpty() ? "生成開始を中止しました。" : work.stopReason));
        }
    }

    private void clientEvent(String type, JSONObject data) {
        JSONObject copy;
        try { copy = new JSONObject(data.toString()); }
        catch (Exception error) { return; }
        control.execute(() -> handleClientEvent(type, copy));
    }

    private void handleClientEvent(String type, JSONObject data) {
        if (!"generation".equals(type) && !"illustration".equals(type)) {
            if ("models".equals(type)) synchronized (lock) { lastModels = data; }
            deliver(type, data); return;
        }
        boolean image = "illustration".equals(type);
        Work work;
        synchronized (lock) { work = image ? imageWork : textWork; }
        if (work == null || !work.id.equals(data.optString("requestId"))) return;
        String phase = data.optString("phase");
        boolean terminal = "complete".equals(phase) || "error".equals(phase) || "cancelled".equals(phase);
        if (terminal && !"complete".equals(phase) && !work.stopReason.isEmpty()) {
            put(data, "phase", "error"); put(data, "message", work.stopReason);
        }
        if ("delta".equals(phase)) work.characters += data.optString("delta").length();
        JSONObject recorded;
        boolean stored = true;
        try { recorded = journal.record(type, data); }
        catch (IOException error) {
            stored = false;
            message = "途中結果の保存に失敗しました。空き容量を確認し、アプリを開いて結果を保存してください。";
            deliver("notice", object("message", message));
            if (!terminal && !work.storageFailure) {
                work.storageFailure = true;
                cancel(work, "途中結果を保存できないため生成を中止しました。受信済み内容はアプリで確認してください。");
                return;
            }
            recorded = data;
            if (terminal) {
                put(recorded, "phase", "error"); put(recorded, "message", message);
            }
        }
        if (recorded == null) return;
        if (terminal) {
            synchronized (lock) {
                if (image && imageWork == work) imageWork = null;
                if (!image && textWork == work) textWork = null;
                completionMessage = stored ? "生成処理が終了しました。アプリを開いて結果を確認してください。" : message;
            }
            if (stored) message = "受信済みの結果を端末に保存しました。";
        }
        if(!image && !work.request.optString("batchId").isEmpty()) {
            if(terminal) {
                try {
                    if(!stored)throw new IOException("journal not committed");
                    batch.accept(recorded,batchActive&&!work.cancelRequested&&serviceReady);
                    journal.acknowledge(work.id,recorded.optLong("seq"));
                    if(!batch.running())batchActive=false;
                    if(batchActive)advanceBatch();
                    else completionMessage=batch.progress()+"。";
                }catch(Exception e){pauseBatch("一括生成の保存を確認できないため停止しました。結果の記録は保持しています。");}
                emitBatch();
            } else deliver("batchDelta",recorded);
        } else if("narration".equals(work.request.optString("purpose"))) { routeAuxiliary(recorded,stored,terminal,terminal&&"complete".equals(phase)&&!work.cancelRequested); } else deliver(type, recorded);
        if (terminal && !stored) deliver("runtimeSnapshot", snapshot());
        notifyService();
    }

    private void reject(boolean image, JSONObject request, String reason) {
        JSONObject result = object("phase", "error", "message", reason);
        if (request != null) for (String key : new String[]{"requestId", "bookId", "chapterId"}) put(result, key, request.optString(key));
        if(request!=null&&"narration".equals(request.optString("purpose")))routeAuxiliary(result,false);
        else deliver(image ? "illustration" : "generation", result);
    }

    private static JSONObject terminal(Work work, String phase, String reason) {
        return object("requestId", work.id, "bookId", work.request.optString("bookId"),
            "chapterId", work.request.optString("chapterId"), "phase", phase, "message", reason);
    }

    private void deliver(String type, JSONObject data) {
        UiListener listener;
        synchronized (lock) { listener = foreground ? ui.get() : null; }
        if (listener != null) listener.onRuntimeEvent(type, data);
    }

    private void notifyService() {
        ServiceListener listener;
        synchronized (lock) { listener = service.get(); }
        if (listener != null) listener.onGenerationProgress();
    }

    public boolean hasWork() { synchronized (lock) { return textWork != null || imageWork != null || batchActive || auxiliaryActive; } }

    public String progressText() {
        synchronized (lock) {
            if(batchActive && batch!=null)return batch.progress()+(textWork==null?"・準備中":"・現在 "+textWork.characters+"字受信");
            if (textWork != null && imageWork != null) return "文章と挿絵を生成しています。受信 " + textWork.characters + "字";
            if (imageWork != null) return imageWork.launched ? "挿絵を生成・保存しています。" : "挿絵の生成を準備しています。";
            if(textWork!=null&&"narration".equals(textWork.request.optString("purpose")))return "話者・心の声・読み方を解析中（"+textWork.characters+"字受信）";
            if (textWork != null) return textWork.launched ? "文章を生成しています。受信 " + textWork.characters + "字" : "文章の生成を準備しています。";
            return "生成処理が終了しました。";
        }
    }

    public String consumeCompletionMessage() {
        synchronized (lock) { String result = completionMessage; completionMessage = ""; return result; }
    }

    public boolean notificationsEnabled() {
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(GenerationService.CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public void checkpointSoon() { control.execute(this::checkpoint); }
    private void checkpoint() {
        if (journal == null) return;
        try { journal.flush(); }
        catch (IOException error) { message = "途中結果を保存できませんでした。空き容量を確認してください。"; }
    }

    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{3,100}"); }
    private static void put(JSONObject data, String key, Object value) { try { data.put(key, value); } catch (Exception ignored) { } }
    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) put(result, (String)values[i], values[i + 1]);
        return result;
    }

    private static final class Work {
        final String id;
        final JSONObject request;
        final boolean image;
        volatile boolean prepared, launched, storageFailure, cancelRequested;
        volatile long characters;
        volatile String stopReason = "";
        Work(JSONObject request, boolean image) { this.request = request; this.image = image; this.id = request.optString("requestId"); }
    }
}
