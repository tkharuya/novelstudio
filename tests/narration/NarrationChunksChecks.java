package jp.haruya.novelstudio;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Real runtime, durable journal and narration store; only model/Android are mocked. */
public final class NarrationChunksChecks {
    static int checks;
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
    static JSONObject o(Object...a){return NarrationStore.obj(a);}
    static String body(int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append("「玲司は第").append(i).append("段落の話を始めた。研究を積み重ねて新しい神性の性質を解析し、自分の身体に融合させる。この言葉を省略せず、話者と読み方を順番に決めていく。空白や改行も原文のまま残す。」\n\n");return b.toString();}
    static final class Listener implements GenerationRuntime.AuxiliaryListener {
        final NarrationStore store;Listener(NarrationStore s){store=s;}
        public boolean onAuxiliaryResult(JSONObject r)throws Exception{return store.accept(r);}
        public JSONObject nextAuxiliaryRequest()throws Exception{return store.nextRequest();}
        public void onAuxiliaryStopped(String reason)throws Exception{store.pauseAnalysis(reason);}
    }
    static NarrationStore install(BatchChecks.H h)throws Exception{NarrationStore s=new NarrationStore(h.context);s.pauseAnalysis("プロセスを復元");h.r.attachAuxiliary(new Listener(s));h.drain();return s;}
    static JSONObject start(BatchChecks.H h,NarrationStore s,String body)throws Exception{JSONObject r=s.prepare("book_fixture","chapter_000",body,"第1話","mock-model","玲司は個人研究者。",new JSONArray(),true);h.r.startText(r);h.drain();return r;}
    static JSONObject hero(){return o("id","reiji","name","三枝玲司","aliases","玲司","role","main","gender","male","voice","Charon","deviceVoice","","rate",1,"pitch",1,"volume",1);}
    static JSONObject reply(JSONObject req)throws Exception{
        JSONArray targets=new JSONObject(req.getString("prompt")).getJSONArray("segments"),out=new JSONArray();
        for(int i=0;i<targets.length();i++)out.put(o("id",targets.getJSONObject(i).getString("id"),"speaker","reiji","kind","dialogue","emotion","calm","style","落ち着いて","rate",1,"pitch",1,"volume",1,"pauseMs",150));
        return o("characters",new JSONArray().put(hero()),"segments",out);
    }
    static JSONObject last(BatchChecks.H h){return h.client.calls.get(h.client.calls.size()-1);}
    static void finish(BatchChecks.H h)throws Exception{h.finish(reply(last(h)).toString());}
    public static void main(String[] args)throws Exception {
        String text=body(720);check(text.length()>60000,"fixture exceeds former character cap");int total=NarrationStore.split(text).length();check(total>600,"fixture exceeds former segment cap");
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore s=install(h);start(h,s,text);h.r.pauseUi(h.owner);h.r.detachUi(h.owner);Set<String> received=new HashSet<>();int batches=0;
            while(h.r.hasWork()) {
                check(batches++<100,"bounded loop, no duplicate continuation");JSONObject req=last(h);JSONArray a=new JSONObject(req.getString("prompt")).getJSONArray("segments");check(a.length()<=NarrationStore.CHUNK_SEGMENTS,"one request has bounded model work");check(req.getString("prompt").length()<80000,"request does not include whole long manuscript");
                for(int i=0;i<a.length();i++){JSONObject seg=a.getJSONObject(i);check(received.add(seg.getString("id")),"each spoken span requested once");check(!seg.getString("text").trim().isEmpty(),"blank spans require no AI call");}
                finish(h);
                if(batches==1){JSONObject c=NarrationStore.copy(NarrationStore.find(s.view("book_fixture","chapter_000").getJSONArray("cast"),"reiji"));c.put("voice","Orus");s.saveCharacter("book_fixture",c);}
            }
            JSONObject plan=s.plan("book_fixture","chapter_000",text);check(plan.optBoolean("analyzed"),"all long text analyzed without UI");check(s.pending().optInt("done")==total,"all spans committed");StringBuilder joined=new StringBuilder();JSONArray spans=plan.getJSONArray("segments");for(int i=0;i<spans.length();i++)joined.append(spans.getJSONObject(i).getString("text"));check(joined.toString().equals(text),"all source text preserved exactly");check(received.size()==720,"all spoken spans included");check(h.context.starts.size()==1,"one user-started service across automatic batches");check(h.journal.snapshots().length()==0,"one acknowledged result at a time avoids journal record limit");check(NarrationStore.find(plan.getJSONArray("cast"),"reiji").getString("voice").equals("Orus"),"manual voice kept across subsequent batches");
            System.out.println("PASS long text: "+text.length()+" chars, "+total+" spans, "+batches+" background requests");
        }
        File dir=Files.createTempDirectory("speech-chunks-restart-").toFile();int completed;
        try(BatchChecks.H h=new BatchChecks.H(dir)){h.seed(1);NarrationStore s=install(h);start(h,s,text);finish(h);finish(h);completed=s.pending().getInt("done");h.emit(h.last(),"delta","delta","{partial");h.drain();check(completed>0&&completed<total,"checkpoint after partial progress");}
        try(BatchChecks.H h=new BatchChecks.H(dir)){NarrationStore s=install(h);check(h.client.calls.isEmpty(),"process recreation never silently sends requests");check(s.pending().optBoolean("resumable")&&s.pending().getInt("done")==completed,"progress restored with resume available");h.r.startText(s.resumeAnalysis("book_fixture","chapter_000",text,"mock-model"));h.drain();JSONArray first=new JSONObject(last(h).getString("prompt")).getJSONArray("segments");check(Integer.parseInt(first.getJSONObject(0).getString("id").substring(1))>=completed,"resume skips every committed span");while(h.r.hasWork())finish(h);check(s.plan("book_fixture","chapter_000",text).optBoolean("analyzed"),"resumed long analysis completes");}
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore s=install(h);start(h,s,text);finish(h);int saved=s.pending().getInt("done"),calls=h.client.calls.size();String id=h.last();h.r.cancelText();h.emit(id,"complete","text",reply(last(h)).toString());h.drain();check(h.client.calls.size()==calls&&!h.r.hasWork(),"pause racing successful result never starts another batch");check(s.pending().getInt("done")>saved&&s.pending().optBoolean("resumable"),"racing successful batch retained for resume");
            h.r.startText(s.resumeAnalysis("book_fixture","chapter_000",text,"mock-model"));h.drain();int before=s.pending().getInt("done");h.finish("truncated JSON");check(!h.r.hasWork()&&s.pending().getInt("done")==before,"malformed result preserves previous chunks and stops");h.r.startText(s.resumeAnalysis("book_fixture","chapter_000",text,"mock-model"));h.drain();check(new JSONObject(last(h).getString("prompt")).getJSONArray("segments").length()<=24,"manual retry automatically narrows failed portion");finish(h);check(s.pending().getInt("done")>before,"narrowed retry advances");
            h.r.serviceExpired(h.service,"OS時間制限");h.drain();h.emit(h.last(),"cancelled","text","");h.drain();check(s.pending().optBoolean("resumable")&&!h.r.hasWork(),"service timeout preserves partial plan");
        }
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore s=install(h);start(h,s,text);JSONObject req=last(h),response=reply(req);h.journal.record("generation",o("requestId",h.last(),"phase","complete","text",response.toString()));s.accept(o("requestId",h.last(),"phase","complete","text",response.toString()));int saved=s.pending().getInt("done");s.accept(o("requestId",h.last(),"phase","complete","text",response.toString()));check(s.pending().getInt("done")==saved&&s.pending().getInt("chunksDone")==1,"terminal replay is idempotent");check(s.view("book_fixture","chapter_000").getJSONObject("pending").optJSONObject("plan")==null,"UI progress excludes unfinished full manuscript");
        }
        System.out.println("PASS "+checks+" automatic chunking / resume assertions; production logic, mocked inference.");
    }
}
