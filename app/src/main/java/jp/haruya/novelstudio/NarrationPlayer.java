package jp.haruya.novelstudio;

import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.speech.tts.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Application-owned audio. UI navigation never stops playback or synthesis. */
final class NarrationPlayer implements GenerationRuntime.AuxiliaryListener {
    private static NarrationPlayer instance;
    static synchronized NarrationPlayer get(Context c) {if(instance==null)instance=new NarrationPlayer(c.getApplicationContext());return instance;}
    private final Context context;
    private final GenerationRuntime runtime;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    final SpeechAudioClient audio;
    private NarrationStore store;
    private String storeError="";
    private TextToSpeech tts;
    private volatile boolean ttsReady,ttsFailed,wantsPlay;
    private boolean serviceReady,preview;
    private MediaPlayer media;
    private MediaSession session;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private JSONObject plan;
    private int index,offset;
    private volatile int epoch;
    private String phase="idle",message="",utterance="";
    private File deviceTemp,deviceTarget;
    private volatile JSONArray deviceVoices=new JSONArray();
    private volatile JSONObject publicState=new JSONObject();
    private NarrationService service;
    private static final AudioAttributes ATTR=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    private NarrationPlayer(Context c) {
        context=c;runtime=GenerationRuntime.get(c);audio=new SpeechAudioClient(c);
        try{store=new NarrationStore(c);JSONObject cp=store.checkpoint();if(!cp.optString("bookId").isEmpty()){publicState=NarrationStore.copy(cp);publicState.put("phase","paused");publicState.put("message","保存した位置から再開できます。");}}
        catch(Exception e){storeError="読み上げの保存領域を開けませんでした。元の記録は保持しています。空き容量を確認してください。";}
        if(store!=null)try{store.pauseAnalysis("前回の解析は中断されました。");}catch(Exception ignored){}
        runtime.attachAuxiliary(this);
        main.post(this::initializeAudio);
    }
    private void initializeAudio() {
        tts=new TextToSpeech(context,status->main.post(()->{
            ttsReady=status==TextToSpeech.SUCCESS;
            if(ttsReady){int result=tts.setLanguage(Locale.JAPAN);ttsReady=result!=TextToSpeech.LANG_MISSING_DATA&&result!=TextToSpeech.LANG_NOT_SUPPORTED;}
            ttsFailed=!ttsReady;
            if(ttsReady){tts.setAudioAttributes(ATTR);JSONArray list=new JSONArray();Set<Voice> voices=tts.getVoices();if(voices!=null){List<Voice> sorted=new ArrayList<>(voices);sorted.sort(Comparator.comparing(Voice::getName));for(Voice v:sorted)if("ja".equals(v.getLocale().getLanguage()))list.put(NarrationStore.obj("id",v.getName(),"name",v.getName(),"network",v.isNetworkConnectionRequired()));}deviceVoices=list;}
            publish();if(wantsPlay&&plan!=null&&"device".equals(plan.optString("engine"))&&media==null&&utterance.isEmpty())loadSegment();
        }));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){main.post(()->deviceDone(id));}
            @Override public void onError(String id){onError(id,-1);}
            @Override public void onError(String id,int code){main.post(()->{if(id.equals(utterance))fail("端末の音声合成に失敗しました（"+code+"）。音声データ・接続を確認してください。");});}
        });
        session=new MediaSession(context,"NovelStudioNarration");session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){resume();}
            @Override public void onPause(){pause("一時停止しました。");}
            @Override public void onStop(){pause("停止しました。続きから再開できます。");}
            @Override public void onSkipToNext(){skip(1);}
            @Override public void onSkipToPrevious(){skip(-1);}
            @Override public void onSeekTo(long pos){if(media!=null){media.seekTo((int)Math.min(Integer.MAX_VALUE,Math.max(0,pos)));offset=(int)pos;checkpoint();publish();}}
        },main);
        BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(wantsPlay)pause("イヤホンの接続が外れたため停止しました。");}};
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    private void requireStore()throws IOException {if(store==null)throw new IOException(storeError);}
    JSONObject view(String bookId,String chapterId)throws Exception {
        requireStore();JSONObject out=store.view(bookId,chapterId);out.put("player",NarrationStore.copy(publicState));out.put("voices",SpeechCatalog.voices());out.put("deviceVoices",deviceVoices);out.put("apiConfigured",audio.configured());out.put("deviceReady",ttsReady);out.put("deviceFailed",ttsFailed);return out;
    }
    JSONObject exportData()throws Exception {requireStore();return store.exportData();}
    void validateImport(String json)throws Exception {NarrationStore.validateRoot(new JSONObject(json));}
    void importData(String json)throws Exception {
        requireStore();JSONObject restored=NarrationStore.validateRoot(new JSONObject(json));restored.put("pending",new JSONObject());
        FutureTask<Void> task=new FutureTask<>(()->{wantsPlay=false;stopWork();finishService();plan=null;store.importData(restored);publicState=store.checkpoint();publicState.put("phase","paused");publicState.put("message","読み上げ設定と再生位置を復元しました。");publish();return null;});
        if(Looper.myLooper()==Looper.getMainLooper())task.run();else main.post(task);task.get(15,TimeUnit.SECONDS);
    }
    @Override public boolean onAuxiliaryResult(JSONObject event)throws Exception {requireStore();boolean ack=store.accept(event);if(ack)main.post(()->{publish();runtime.emitSpeech(NarrationStore.obj("analysisChanged",true));});return ack;}
    @Override public JSONObject nextAuxiliaryRequest()throws Exception {requireStore();JSONObject pending=store.pending();if(!pending.optString("sourceHash").isEmpty()){JSONObject c=findChapter(findBook(pending.getString("bookId")),pending.getString("chapterId"));if(!NarrationStore.hash(c.optString("body")).equals(pending.optString("sourceHash"))){store.pauseAnalysis("本文が変更されたため解析を一時停止しました。");return null;}}JSONObject next=store.nextRequest();main.post(()->{publish();runtime.emitSpeech(NarrationStore.obj("analysisChanged",true));});return next;}
    @Override public void onAuxiliaryStopped(String reason)throws Exception {requireStore();store.pauseAnalysis(reason);main.post(()->{publish();runtime.emitSpeech(NarrationStore.obj("analysisChanged",true));});}
    void analyze(JSONObject request,boolean ai)throws Exception {
        requireStore();JSONObject active=runtime.snapshot();if(ai&&(active.optBoolean("auxiliaryActive")||!active.optString("activeTextId").isEmpty()||active.optJSONObject("batch")!=null&&"running".equals(active.optJSONObject("batch").optString("status"))))throw new IOException("現在の文章生成・一括生成を停止してから解析してください。");
        if(ai&&!runtime.siwc().status().optBoolean("signedIn"))throw new IOException("話者解析にはChatGPTへのログインが必要です。");
        if(request.optBoolean("resume")){JSONArray records=active.optJSONArray("records");for(int i=0;records!=null&&i<records.length();i++){JSONObject record=records.optJSONObject(i);if(record!=null&&"narration".equals(record.optString("purpose"))&&!"generating".equals(record.optString("phase")))store.accept(record);}runtime.attachAuxiliary(this);if("complete".equals(store.pending().optString("phase"))){main.post(this::publish);return;}}
        String bid=request.getString("bookId"),cid=request.getString("chapterId");JSONObject b=findBook(bid),c=findChapter(b,cid);
        JSONObject r=request.optBoolean("resume")?store.resumeAnalysis(bid,cid,c.optString("body"),request.optString("model")):store.prepare(bid,cid,c.optString("body"),c.optString("title"),request.optString("model"),"登場人物: "+b.optString("characters")+"\nあらすじ: "+b.optString("synopsis"),deviceVoices,ai);
        if(ai&&r!=null)runtime.startText(r);main.post(this::publish);
    }
    void cancelAnalysis()throws Exception {requireStore();JSONObject pending=store.pending(),active=runtime.snapshot();if(active.optBoolean("auxiliaryActive")||"generating".equals(pending.optString("phase"))&&pending.optString("requestId").equals(active.optString("activeTextId")))runtime.cancelText();else store.pauseAnalysis("解析を一時停止しました。");}
    void saveCharacter(String bid,JSONObject raw)throws Exception {requireStore();store.saveCharacter(bid,raw);changed(bid);}
    void saveSegment(String bid,String cid,JSONObject raw)throws Exception {requireStore();store.saveSegment(bid,cid,raw);changed(bid);}
    void settings(String bid,JSONObject settings)throws Exception {requireStore();store.settings(bid,settings);changed(bid);}
    void forget(String bid,String cid)throws Exception {requireStore();store.forgetPlan(bid,cid);changed(bid);}
    private void changed(String bid){main.post(()->{if(plan!=null&&bid.equals(plan.optString("bookId"))){pause("声・読み方を保存しました。再開すると反映されます。");plan=null;}publish();});}
    private JSONObject findBook(String bid)throws Exception {JSONObject b=NarrationStore.find(new JSONObject(runtime.loadState()).getJSONArray("books"),bid);if(b==null)throw new IOException("作品が見つかりません。");return b;}
    private JSONObject findChapter(JSONObject b,String cid)throws Exception {JSONObject c=NarrationStore.find(b.optJSONArray("chapters"),cid);if(c==null)throw new IOException("話が見つかりません。");return c;}
    void play(String bid,String cid,int start) {
        try {
            requireStore();JSONObject c=findChapter(findBook(bid),cid),next=store.plan(bid,cid,c.optString("body"));JSONObject cp=store.checkpoint();
            int at=start>=0?start:bid.equals(cp.optString("bookId"))&&cid.equals(cp.optString("chapterId"))&&next.getString("hash").equals(cp.optString("hash"))?cp.optInt("index",0):0;
            int ms=start<0&&SpeechAudioClient.signature(next,at).equals(cp.optString("voiceHash"))&&at==cp.optInt("index",-1)&&cid.equals(cp.optString("chapterId"))&&next.getString("hash").equals(cp.optString("hash"))?cp.optInt("offset",0):0;
            if(at>=next.getJSONArray("segments").length()){at=0;ms=0;}stopWork();preview=false;plan=next;index=Math.min(Math.max(0,at),next.getJSONArray("segments").length()-1);offset=Math.max(0,ms);wantsPlay=true;phase="preparing";message="音声を準備しています。";if(!checkpoint()){fail("再生位置を保存できません。空き容量を確認してください。");return;}startService();
        }catch(Exception e){fail(userError(e));}
    }
    void sample(String bid,String characterId) {
        try {requireStore();JSONObject b=store.view(bid,""),c=NarrationStore.find(b.getJSONArray("cast"),characterId);if(c==null)throw new IOException("キャラを保存してから試聴してください。");pause("試聴に切り替えました。");
            preview=true;JSONObject segment=NarrationStore.obj("id","s0","text","これは声の試聴です。さあ、物語の続きを始めましょう。","speaker",characterId,"kind","dialogue","emotion","neutral","style","","rate",1,"pitch",1,"volume",1,"pauseMs",0);
            plan=NarrationStore.obj("title",c.optString("name")+"の試聴","bookId",bid,"chapterId","preview","engine",b.getString("engine"),"model",b.getString("model"),"segments",new JSONArray().put(segment),"cast",new JSONArray().put(c));index=0;offset=0;wantsPlay=true;phase="preparing";startService();
        }catch(Exception e){fail(userError(e));}
    }
    void resume() {try{requireStore();JSONObject cp=store.checkpoint();if(plan!=null&&preview){sample(plan.getString("bookId"),plan.getJSONArray("segments").getJSONObject(0).getString("speaker"));return;}play(cp.getString("bookId"),cp.getString("chapterId"),-1);}catch(Exception e){fail("読む話を選択して再生してください。");}}
    void skip(int delta) {if(plan==null){resume();return;}if(preview)return;int at=Math.max(0,Math.min(plan.optJSONArray("segments").length()-1,index+delta));play(plan.optString("bookId"),plan.optString("chapterId"),at);}
    private void startService() {
        publish();try {context.startForegroundService(new Intent(context,NarrationService.class).setAction(NarrationService.START));}
        catch(RuntimeException e){fail("読み上げを開始できませんでした。アプリを開いて再生してください。");}
    }
    void serviceReady(NarrationService service) {
        this.service=service;serviceReady=true;
        if(!wantsPlay){service.finish();return;}
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(manager!=null&&focus!=null)manager.abandonAudioFocusRequest(focus);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(ATTR).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(change<0&&wantsPlay)pause("通話・別の音声のため一時停止しました。");},main).build();
        if(manager==null||manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){fail("他の音声が再生中です。終了してから再生してください。");return;}
        if(session!=null)session.setActive(true);loadSegment();
    }
    void serviceDestroyed(NarrationService owner) {if(service==owner){service=null;serviceReady=false;if(wantsPlay)pause("読み上げが中断されました。保存した位置から再開できます。");}}
    private void loadSegment() {
        if(!wantsPlay||!serviceReady||plan==null)return;
        try {
            JSONArray spans=plan.getJSONArray("segments");while(index<spans.length()&&spans.getJSONObject(index).getString("text").trim().isEmpty()){index++;offset=0;}
            if(index>=spans.length()){finishChapter();return;}
            JSONObject s=spans.getJSONObject(index),c=NarrationStore.find(plan.getJSONArray("cast"),s.getString("speaker"));if(c==null)throw new IOException("話者が見つかりません。");
            phase="preparing";message="音声を準備中";if(!checkpoint()){fail("再生位置を保存できません。空き容量を確認してください。");return;}publish();holdWake();int token=++epoch;
            if("gemini".equals(plan.optString("engine"))) {
                String model=plan.optString("model");network.execute(()->{if(token!=epoch||!wantsPlay)return;try{File f=audio.synthesize(model,c,s,()->token==epoch&&wantsPlay);main.post(()->{if(token==epoch&&wantsPlay)playFile(f,token,c,s);});}catch(Exception e){main.post(()->{if(token==epoch&&wantsPlay)fail(userError(e));});}});
            }else {
                if(!ttsReady){if(ttsFailed)throw new IOException("日本語の端末音声がありません。Androidの「テキスト読み上げ」設定から日本語音声をインストールしてください。");message="端末音声を初期化しています。";publish();return;}
                String name=c.optString("deviceVoice");Voice voice=null;Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice v:voices)if(name.equals(v.getName())&&"ja".equals(v.getLocale().getLanguage())){voice=v;break;}
                if(!name.isEmpty()&&voice==null)throw new IOException("選択した端末音声が見つかりません。「キャラと声」で変更してください。");
                if(voice!=null){if(tts.setVoice(voice)==TextToSpeech.ERROR)throw new IOException("端末音声を選択できませんでした。");}else tts.setLanguage(Locale.JAPAN);
                if(tts.setSpeechRate(SpeechAudioClient.rate(c,s))==TextToSpeech.ERROR||tts.setPitch(SpeechAudioClient.pitch(c,s))==TextToSpeech.ERROR)throw new IOException("読み方を設定できませんでした。");
                String actual=tts.getVoice()==null?"default":tts.getVoice().getName();String key=NarrationStore.hash(tts.getDefaultEngine()+actual+"|"+SpeechAudioClient.rate(c,s)+"|"+SpeechAudioClient.pitch(c,s)+"|"+s.getString("text"));
                deviceTarget=audio.deviceFile(key);if(SpeechAudioClient.validFile(deviceTarget)){playFile(deviceTarget,token,c,s);return;}
                deviceTemp=new File(deviceTarget.getPath()+"."+token+".tmp");utterance="speech_"+token;Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f);
                if(tts.synthesizeToFile(s.getString("text"),params,deviceTemp,utterance)==TextToSpeech.ERROR)throw new IOException("端末音声を合成できませんでした。");
                final String waiting=utterance;main.postDelayed(()->{if(waiting.equals(utterance)&&wantsPlay)fail("音声合成が時間内に完了しませんでした。手動で再開してください。");},120000);
            }
        }catch(Exception e){fail(userError(e));}
    }
    private void deviceDone(String id) {
        if(!wantsPlay||!id.equals(utterance))return;utterance="";
        try {if(!SpeechAudioClient.validFile(deviceTemp)||!deviceTemp.renameTo(deviceTarget))throw new IOException("端末音声の保存を確認できませんでした。");JSONObject s=plan.getJSONArray("segments").getJSONObject(index),c=NarrationStore.find(plan.getJSONArray("cast"),s.getString("speaker"));File target=deviceTarget;deviceTemp=null;deviceTarget=null;audio.trim(target);playFile(target,epoch,c,s);}catch(Exception e){fail(userError(e));}
    }
    private void playFile(File file,int token,JSONObject character,JSONObject segment) {
        try {if(media!=null)media.release();media=new MediaPlayer();MediaPlayer m=media;m.setAudioAttributes(ATTR);m.setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK);m.setDataSource(file.getAbsolutePath());float v="gemini".equals(plan.optString("engine"))?1f:SpeechAudioClient.volume(character,segment);m.setVolume(v,v);
            m.setOnErrorListener((mp,what,extra)->{if(token==epoch)fail("音声を再生できませんでした（"+what+"）。");return true;});
            m.setOnPreparedListener(mp->{if(token!=epoch||!wantsPlay)return;int position=Math.max(0,Math.min(offset,Math.max(0,mp.getDuration()-1)));mp.seekTo(position);});
            m.setOnSeekCompleteListener(mp->{if(token==epoch&&wantsPlay){mp.start();phase="playing";message="";releaseWake();publish();main.removeCallbacks(tick);main.postDelayed(tick,4000);}});
            m.setOnCompletionListener(mp->{if(token!=epoch||!wantsPlay)return;main.removeCallbacks(tick);mp.release();if(media==mp)media=null;index++;offset=0;if(!checkpoint()){fail("再生位置を保存できません。空き容量を確認してください。");return;}phase="preparing";publish();main.postDelayed(()->{if(token==epoch&&wantsPlay)loadSegment();},segment.optInt("pauseMs",150));});
            m.prepareAsync();
        }catch(Exception e){fail("音声ファイルを開けませんでした。");}
    }
    private final Runnable tick=new Runnable(){@Override public void run(){if(!wantsPlay)return;if(media!=null)try{offset=media.getCurrentPosition();}catch(Exception ignored){}if(!checkpoint()){fail("再生位置を保存できません。空き容量を確認してください。");return;}publish();main.postDelayed(this,4000);}};
    private void finishChapter() {
        try {
            if(!preview&&plan.optBoolean("autoNext",true)) {JSONObject b=findBook(plan.getString("bookId"));JSONArray chapters=b.getJSONArray("chapters");for(int i=0;i<chapters.length()-1;i++)if(plan.getString("chapterId").equals(chapters.getJSONObject(i).getString("id"))){JSONObject next=chapters.getJSONObject(i+1);try{JSONObject p=store.plan(b.getString("id"),next.getString("id"),next.optString("body"));plan=p;index=0;offset=0;loadSegment();return;}catch(Exception e){pause("この話を読み終えました。次の話は先に話者解析を実行してください。");return;}}}
            pause(preview?"試聴が終了しました。":"この話を読み終えました。");phase="complete";publish();
        }catch(Exception e){fail(userError(e));}
    }
    void pause(String reason) {if(media!=null)try{offset=media.getCurrentPosition();}catch(Exception ignored){}checkpoint();wantsPlay=false;stopWork();phase="paused";message=reason;publish();finishService();}
    private void fail(String reason){pause(reason);phase="error";publish();}
    private void stopWork(){epoch++;main.removeCallbacks(tick);audio.cancel();if(tts!=null)tts.stop();utterance="";if(deviceTemp!=null){deviceTemp.delete();deviceTemp=null;}if(media!=null){media.release();media=null;}releaseWake();}
    private boolean checkpoint() {if(store==null||plan==null||preview)return true;try{store.checkpoint(NarrationStore.obj("bookId",plan.optString("bookId"),"chapterId",plan.optString("chapterId"),"hash",plan.optString("hash"),"voiceHash",SpeechAudioClient.signature(plan,index),"index",index,"offset",offset,"title",plan.optString("title")));return true;}catch(Exception e){message="再生位置を保存できません。空き容量を確認してください。";return false;}}
    private void holdWake(){releaseWake();PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm!=null){wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NovelStudio:speech");wake.setReferenceCounted(false);wake.acquire(150000);}}
    private void releaseWake(){if(wake!=null){if(wake.isHeld())wake.release();wake=null;}}
    private void finishService(){serviceReady=false;AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);if(manager!=null&&focus!=null)manager.abandonAudioFocusRequest(focus);focus=null;if(session!=null)session.setActive(false);if(service!=null){NarrationService old=service;service=null;old.finish();}}
    private static String userError(Exception e){return e instanceof IOException?e.getMessage():"音声の処理に失敗しました。設定と保存データを確認してください。";}
    JSONObject playerState(){return NarrationStore.copy(publicState);}
    MediaSession.Token token(){return session==null?null:session.getSessionToken();}
    private void publish() {
        JSONObject p=plan==null?NarrationStore.copy(publicState):NarrationStore.obj("bookId",plan.optString("bookId"),"chapterId",plan.optString("chapterId"),"title",plan.optString("title"),"index",index,"total",plan.optJSONArray("segments").length(),"offset",offset,"preview",preview);
        try{if(plan!=null||!message.isEmpty()){p.put("phase",phase);p.put("message",message);}if(plan!=null){JSONArray a=plan.optJSONArray("segments");JSONObject s=a.optJSONObject(index);if(s!=null){JSONObject c=NarrationStore.find(plan.optJSONArray("cast"),s.optString("speaker"));p.put("speaker",c==null?"話者不明":c.optString("name"));p.put("kind",s.optString("kind"));p.put("emotion",s.optString("emotion"));p.put("text",s.optString("text"));}}p.put("active",wantsPlay);publicState=p;
            if(session!=null){long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO;session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(wantsPlay?("playing".equals(phase)?PlaybackState.STATE_PLAYING:PlaybackState.STATE_BUFFERING):PlaybackState.STATE_PAUSED,offset,wantsPlay?1f:0f).build());session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,p.optString("title","小説工房")).putString(MediaMetadata.METADATA_KEY_ARTIST,p.optString("speaker")).build());}
            runtime.emitSpeech(NarrationStore.obj("player",p,"refresh",true));if(service!=null)service.refresh();
        }catch(Exception ignored){}
    }
}
