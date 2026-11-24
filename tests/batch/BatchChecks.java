package jp.haruya.novelstudio;

import android.app.Activity;
import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Real runtime, journal, batch and manuscript stores; only OS/client boundaries are faked. */
public final class BatchChecks {
    static int checks;
    public static void main(String[] args)throws Exception {
        hundredWithoutScreen();pauseAndResume();processRecovery();commitRecovery();storageFailure();singleResume();limits();timeoutAndClose();
        System.out.println("PASS "+checks+" batch/resume assertions (real durable stores, mocked network/Android)");
    }
    static void hundredWithoutScreen()throws Exception{
        try(H h=new H()){
            h.begin(100);h.r.pauseUi(h.owner);h.r.detachUi(h.owner);h.drain();
            for(int i=0;i<100;i++){
                check(h.client.calls.size()==i+1,"one sequential request for episode "+i);
                String id=h.last();h.emit(id,"delta","delta","第"+(i+1)+"話の");
                h.emit(id,"complete","text","第"+(i+1)+"話の完成本文。"+repeat("物語が進む。",400));h.drain();
            }
            check(!h.r.hasWork(),"100 episodes finish without UI callbacks");
            check(h.batch().getInt("completed")==100,"100 committed completions");
            check(h.journal.snapshots().length()==0,"journal drained; no 32-record ceiling");
            check(h.context.starts.size()==1,"one user-started foreground service for entire batch");
            JSONObject state=new JSONObject(h.r.loadState());JSONArray chapters=state.getJSONArray("books").getJSONObject(0).getJSONArray("chapters");
            for(int i=0;i<100;i++)check(chapters.getJSONObject(i).getString("body").startsWith("第"+(i+1)+"話の完成"),"all body results restored "+i);
            h.r.drafts().save(state.toString());chapters.getJSONObject(0).put("body","利用者の修正本文");h.r.drafts().save(state.toString());
            check(new JSONObject(h.r.loadState()).getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).optString("body").equals("利用者の修正本文"),"completed edits survive repeated replay");
            check(h.client.calls.get(99).optString("prompt").contains("第99話の完成本文"),"next prompt includes preceding generated episode");
        }
        System.out.println("PASS 100-episode background sequence and edit preservation");
    }
    static void pauseAndResume()throws Exception{
        try(H h=new H()){
            h.begin(3);h.finish("第1話の完成");String second=h.last();
            h.emit(second,"delta","delta","主人公は");h.drain();h.r.cancelText();h.drain();
            h.emit(second,"cancelled","text","主人公は");h.drain();
            check(h.client.calls.size()==2,"pause does not start next episode");
            check(h.batch().getInt("completed")==1,"pause retains completed count");
            h.r.resumeBatch();h.drain();check(h.client.calls.size()==3,"resume creates only one continuation request");
            check(h.client.calls.get(2).optString("prompt").contains("主人公は"),"resume includes partial text");
            check(!second.equals(h.last()),"resume has a new request identity");
            h.finish("扉を開いた。");check(h.batch().getJSONArray("items").getJSONObject(1).getString("text").equals("主人公は扉を開いた。"),"resume preserves prefix exactly once");
            h.finish("第3話の完成");check(h.client.calls.size()==4&&h.batch().getInt("completed")==3,"completed episodes not repeated");
        }
        // A successful terminal racing the pause is kept, but no further request is dispatched.
        try(H h=new H()){
            h.begin(2);String id=h.last();h.r.cancelText();h.emit(id,"complete","text","完了済み");h.drain();
            check(h.client.calls.size()==1&&h.batch().optInt("completed")==1,"pause/complete race stops at committed boundary");
            h.r.resumeBatch();h.drain();check(h.client.calls.size()==2,"resume after boundary starts next only");
        }
        System.out.println("PASS partial continuation and pause/completion race");
    }
    static void processRecovery()throws Exception{
        File dir=Files.createTempDirectory("batch-restart-").toFile();
        try(H h=new H(dir)){h.begin(3);h.finish("第1話完了");h.emit(h.last(),"delta","delta","消えない途中の本文");h.r.checkpointSoon();h.drain();}
        try(H h=new H(dir)){
            check(h.client.calls.isEmpty()&&!h.r.hasWork(),"restart does not silently resend");
            check(h.batch().optInt("completed")==1&&"paused".equals(h.batch().optString("status")),"restart recovers count and paused state");
            JSONObject state=new JSONObject(h.r.loadState());JSONObject c=state.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(1);
            check(c.getJSONObject("candidate").getString("text").equals("消えない途中の本文"),"restart restores checkpointed partial");
            h.r.resumeBatch();h.drain();check(h.client.calls.size()==1&&h.client.calls.get(0).optString("prompt").contains("消えない途中の本文"),"manual restart resumes interrupted episode");
            h.finish("の続き");h.finish("最後の話");check(h.batch().optInt("completed")==3,"restart completes remaining queue");
        }
        System.out.println("PASS process recreation with partial text");
    }
    static void commitRecovery()throws Exception{
        File dir=Files.createTempDirectory("batch-commit-").toFile();
        try(H h=new H(dir)){
            h.begin(2);h.journal.record("generation",o("requestId",h.last(),"phase","complete","text","記録だけ完了"));
        }
        try(H h=new H(dir)){
            check(h.batch().optInt("completed")==1,"crash between journal and queue commit adopts once");
            h.r.resumeBatch();h.drain();check(h.client.calls.size()==1&&h.client.calls.get(0).optString("prompt").contains("第2話"),"journal-completed chapter is not resubmitted");
            h.finish("次の完成");check(h.batch().optInt("completed")==2,"recovered queue finishes");
        }
        System.out.println("PASS commit-boundary recovery");
    }
    static void storageFailure()throws Exception{
        try(H h=new H()){
            h.begin(2);AtomicFile.failStart=true;
            try{h.finish("保存失敗でも保持する本文");check(h.client.calls.size()==1&&!h.r.hasWork(),"disk failure stops further requests");}
            finally{AtomicFile.failStart=false;}
            h.r.resumeBatch();h.drain();
            check(h.batch().optInt("completed")==1,"retry reconciles retained terminal before resuming");
            check(h.client.calls.size()==2,"storage recovery skips already complete episode");h.finish("2話目完成");
        }
        System.out.println("PASS storage failure stops queue and retains results");
    }
    static void singleResume()throws Exception{
        try(H h=new H()){
            h.r.startText(o("requestId","single_resume","bookId","book_fixture","chapterId","chapter_000","model","mock-model","purpose","manuscript","mode","rewrite","sourceBody","原文","prompt","最初の指示","instructions","本文だけ","resumePrefix","前半"));h.drain();
            h.emit(h.last(),"delta","delta","後半");h.emit(h.last(),"complete","text","後半");h.drain();
            JSONObject r=h.journal.snapshots().getJSONObject(0);
            check("前半後半".equals(r.getString("text")),"single continuation combines prefix once");
            check("原文".equals(r.getString("sourceBody"))&&"rewrite".equals(r.getString("mode")),"rewrite source and mode retained");
            check("最初の指示".equals(r.getJSONObject("requestSpec").getString("prompt")),"original prompt survives terminal and restart");
        }
        System.out.println("PASS single-chapter resume");
    }
    static void limits()throws Exception{
        try(H h=new H()){
            h.begin(101);check(h.client.calls.isEmpty(),"101 episodes rejected before inference");
        }
        try(H h=new H()){
            h.seed(2);JSONObject state=new JSONObject(h.r.drafts().load());state.getJSONArray("books").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("body","既存本文");h.r.drafts().save(state.toString());h.r.startBatch(h.input(2));h.drain();
            check(h.client.calls.isEmpty(),"existing manuscript cannot be overwritten by batch start");
        }
        System.out.println("PASS count/overwrite guards");
    }
    static void timeoutAndClose()throws Exception{
        try(H h=new H()){
            h.begin(2);String id=h.last();h.emit(id,"delta","delta","時間上限時の本文");h.drain();
            h.r.serviceExpired(h.service,"Android時間上限");h.emit(id,"cancelled","text","時間上限時の本文");h.drain();
            check("paused".equals(h.batch().optString("status"))&&h.client.calls.size()==1,"service timeout checkpoints and stops queue");
            h.r.resumeBatch();h.drain();check(h.client.calls.size()==1,"resume waits for a newly ready service");
            h.r.serviceReady(h.service);h.drain();check(h.client.calls.size()==2,"resumed ready service dispatches once");
            h.emit(id,"complete","text","遅れた旧要求の結果");h.drain();check(h.batch().optInt("completed")==0,"late prior callback ignored after resume");
            h.finish("の続き。");h.finish("最終話");h.r.drafts().save(h.r.loadState());h.r.closeBatch();h.drain();
            check("closed".equals(h.batch().optString("status")),"completed batch closes after durable manuscript save");
            h.begin(1);check(h.client.calls.size()==4,"closed batch permits a new queue after old manuscripts are replaced");h.finish("新しい一括生成");
        }
        System.out.println("PASS service timeout, stale callbacks and next queue");
    }
    static class H implements AutoCloseable {
        final Context context;final Activity owner;final GenerationRuntime r;final SiwcClient client;final GenerationJournal journal;final ExecutorService control;
        final GenerationRuntime.ServiceListener service=()->{};
        H()throws Exception{this(Files.createTempDirectory("batch-checks-").toFile());}
        H(File dir)throws Exception{
            context=new Context(dir);owner=new Activity(context);Constructor<GenerationRuntime> ctor=GenerationRuntime.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);r=ctor.newInstance(context);
            client=r.siwc();client.autoCancel=false;journal=(GenerationJournal)field(r,"journal");control=(ExecutorService)field(r,"control");r.attachUi(owner,(t,d)->{if(d==null)throw new AssertionError("null UI payload");});r.serviceReady(service);drain();
        }
        void seed(int count)throws Exception{
            JSONArray chapters=new JSONArray();for(int i=0;i<count;i++)chapters.put(o("id",String.format("chapter_%03d",i),"title","第"+(i+1)+"話","body","","candidate",JSONObject.NULL));
            r.drafts().save(o("app","NovelStudio","schemaVersion",1,"books",new JSONArray().put(o("id","book_fixture","title","試験作品","world","研究と成長","chapters",chapters))).toString());
        }
        JSONObject input(int count){JSONArray a=new JSONArray();for(int i=0;i<count;i++)a.put(o("id",String.format("chapter_%03d",i)));return o("bookId","book_fixture","model","mock-model","items",a,"targetLength",3000,"hint","成長を速く","plan","各話を進める");}
        void begin(int count)throws Exception{seed(count);r.startBatch(input(count));drain();}
        String last(){return client.calls.get(client.calls.size()-1).optString("requestId");}
        void emit(String id,String phase,String key,String value){client.emit("generation",o("requestId",id,"phase",phase,key,value));}
        void finish(String text)throws Exception{emit(last(),"complete","text",text);drain();}
        JSONObject batch(){return r.snapshot().optJSONObject("batch");}
        void drain()throws Exception{for(int i=0;i<5;i++)control.submit(()->{}).get(8,TimeUnit.SECONDS);}
        public void close()throws Exception{drain();control.shutdownNow();control.awaitTermination(3,TimeUnit.SECONDS);journal.close();}
    }
    static String repeat(String t,int n){StringBuilder s=new StringBuilder();while(n-->0)s.append(t);return s.toString();}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static JSONObject o(Object...v){return BatchGeneration.object(v);}
    static void check(boolean c,String s){if(!c)throw new AssertionError(s);checks++;}
}
