package jp.haruya.novelstudio;
import android.util.AtomicFile;
import org.json.*;
import java.io.File;
import java.nio.file.Files;

/** Production generation runtime and journal integration, Android/provider boundaries mocked. */
public final class SpeechRuntimeChecks {
    static int checks;
    static JSONObject o(Object...a){return NarrationStore.obj(a);}
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
    static JSONObject request(NarrationStore s)throws Exception{return s.prepare("book_fixture","chapter_000","本文。","第1話","mock-model","",new JSONArray(),true);}
    static String result(){return o("characters",new JSONArray(),"segments",new JSONArray().put(o("id","s0","speaker","narrator","kind","narration","emotion","neutral","style","穏やかに","rate",1,"pitch",1,"volume",1,"pauseMs",100))).toString();}
    static boolean manuscriptUnchanged(BatchChecks.H h)throws Exception {JSONObject c=new JSONObject(h.r.loadState()).getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0);return c.optString("body").isEmpty()&&c.isNull("candidate");}
    public static void main(String[] args)throws Exception {
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore store=new NarrationStore(h.context);h.r.attachAuxiliary(store::accept);h.drain();JSONObject request=request(store);h.r.startText(request);h.drain();
            check(h.client.calls.size()==1,"one analysis call");h.r.pauseUi(h.owner);h.r.detachUi(h.owner);h.emit(h.last(),"delta","delta","{\"characters\":");h.drain();check(manuscriptUnchanged(h),"analysis deltas never merge into manuscript");
            h.finish(result());check(store.plan("book_fixture","chapter_000","本文。").optBoolean("analyzed"),"analysis finishes after Activity detaches");check(h.journal.snapshots().length()==0,"durable plan ack removes analysis journal");check(manuscriptUnchanged(h),"completed analysis never merges into manuscript");check(!h.r.hasWork(),"analysis releases text generation gate");
        }
        File dir=Files.createTempDirectory("speech-runtime-recovery-").toFile();
        try(BatchChecks.H h=new BatchChecks.H(dir)){h.seed(1);NarrationStore store=new NarrationStore(h.context);JSONObject req=request(store);h.r.startText(req);h.drain();h.finish(result());check(h.journal.snapshots().length()==1,"unhandled terminal retained");check(manuscriptUnchanged(h),"unhandled result not mistaken for a draft");}
        try(BatchChecks.H h=new BatchChecks.H(dir)){NarrationStore store=new NarrationStore(h.context);h.r.attachAuxiliary(store::accept);h.drain();check(h.client.calls.isEmpty(),"restart performs no API call");check(store.plan("book_fixture","chapter_000","本文。").optBoolean("analyzed"),"stored terminal recovers without UI");check(h.journal.snapshots().length()==0,"recovered terminal acknowledged after save");}
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore store=new NarrationStore(h.context);h.r.attachAuxiliary(store::accept);h.drain();h.r.startText(request(store));h.drain();h.finish("not JSON");check(store.view("book_fixture","chapter_000").getJSONObject("pending").getString("phase").equals("paused"),"malformed AI result gives retryable error");check(store.view("book_fixture","chapter_000").isNull("plan"),"invalid result cannot become a plan");check(h.journal.snapshots().length()==0,"persisted error acknowledged");
        }
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore store=new NarrationStore(h.context);h.r.attachAuxiliary(store::accept);h.drain();h.r.startText(o("requestId","novel_busy","bookId","book_fixture","chapterId","chapter_000","purpose","manuscript","model","mock-model","mode","new","prompt","本文を生成"));h.drain();h.r.startText(request(store));h.drain();check(h.client.calls.size()==1,"busy runtime rejects another analysis");check(store.view("book_fixture","chapter_000").getJSONObject("pending").getString("phase").equals("paused"),"rejection clears pending analysis");
        }
        try(BatchChecks.H h=new BatchChecks.H()){
            h.seed(1);NarrationStore store=new NarrationStore(h.context);h.r.attachAuxiliary(store::accept);h.drain();h.r.startText(request(store));h.drain();h.journal.record("generation",o("requestId",h.last(),"phase","complete","text",result()));AtomicFile.failFinish=true;
            try{h.r.attachAuxiliary(store::accept);h.drain();check(h.journal.snapshots().length()==1,"failed plan commit retains journal");}finally{AtomicFile.failFinish=false;}
            h.r.attachAuxiliary(store::accept);h.drain();check(h.journal.snapshots().length()==0,"storage recovery safely retries local adoption");check(store.plan("book_fixture","chapter_000","本文。").optBoolean("analyzed"),"recovered plan valid");
        }
        System.out.println("PASS "+checks+" narration/runtime durability assertions; mocked Android and inference.");
    }
}
