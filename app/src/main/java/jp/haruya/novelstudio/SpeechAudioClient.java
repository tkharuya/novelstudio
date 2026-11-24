package jp.haruya.novelstudio;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Opt-in, separately billed Gemini Interactions TTS. No implicit retries. */
final class SpeechAudioClient {
    private final SecureStore credentials;
    private final File directory;
    private volatile HttpURLConnection active;
    SpeechAudioClient(Context context) {
        credentials=new SecureStore(context,"speech-gemini");directory=new File(context.getCacheDir(),"speech-audio");directory.mkdirs();
    }
    synchronized boolean configured(){try{return !credentials.read().optString("key").isEmpty();}catch(Exception e){return false;}}
    synchronized void configure(String key)throws Exception {if(key.length()<20||key.length()>2048||!key.matches("[!-~]+"))throw new IOException("APIキーを確認してください。");credentials.write(NarrationStore.obj("key",key));}
    synchronized void clear()throws Exception {credentials.write(new JSONObject());}
    void cancel(){HttpURLConnection c=active;if(c!=null)c.disconnect();}
    File deviceFile(String key) {return new File(directory,"device-"+key+".wav");}
    static String style(JSONObject speaker,JSONObject segment) {
        String kind=segment.optString("kind"),emotion=segment.optString("emotion");
        return "日本語の小説を、指定された本文だけ正確に読み上げる。説明や前置きを加えない。"+
            ("thought".equals(kind)?"心の声として内省的に、少し抑えた声で。":"dialogue".equals(kind)?"自然な会話として。":"落ち着いた朗読として。")+
            " 感情: "+emotion+"。読み方: "+segment.optString("style")+"。通常を1とした速度 "+rate(speaker,segment)+"、高さ "+pitch(speaker,segment)+"、声量 "+volume(speaker,segment)+"。";
    }
    static String signature(JSONObject plan,int index) {JSONArray segments=plan.optJSONArray("segments");return NarrationStore.hash(plan.optString("engine")+"|"+plan.optString("model")+"|"+plan.optJSONArray("cast")+"|"+(segments==null?"":segments.optJSONObject(index)));}
    static float rate(JSONObject c,JSONObject s){return clamp(c.optDouble("rate",1)*s.optDouble("rate",1),.5,1.8);}
    static float pitch(JSONObject c,JSONObject s){return clamp(c.optDouble("pitch",1)*s.optDouble("pitch",1),.5,1.8);}
    static float volume(JSONObject c,JSONObject s){return clamp(c.optDouble("volume",1)*s.optDouble("volume",1),.05,1);}
    private static float clamp(double v,double a,double b){return(float)Math.max(a,Math.min(b,v));}
    static JSONObject request(String model,String voice,String text,String style)throws Exception {
        if(!SpeechCatalog.model(model)||!SpeechCatalog.voice(voice)||text.isEmpty()||text.length()>1000||style.length()>2000)throw new IOException("音声リクエストが不正です。");
        return NarrationStore.obj("model",model,"input",new JSONArray().put(NarrationStore.obj("type","user_input","content",new JSONArray().put(NarrationStore.obj("type","text","text",text,"annotations",new JSONArray().put(NarrationStore.obj("type","speech_metadata","style",style)))))),"response_format",NarrationStore.obj("type","audio","mime_type","audio/wav","sample_rate",24000),"generation_config",NarrationStore.obj("speech_config",new JSONArray().put(NarrationStore.obj("voice",voice))));
    }
    static String audioData(JSONObject response)throws Exception {
        JSONArray steps=response.optJSONArray("steps");String data="";
        if(steps!=null)for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step==null||!"model_output".equals(step.optString("type")))continue;JSONArray content=step.optJSONArray("content");if(content==null)continue;for(int k=0;k<content.length();k++){JSONObject a=content.optJSONObject(k);if(a!=null&&"audio".equals(a.optString("type"))){String mime=a.optString("mime_type","audio/wav");if(!mime.equals("audio/wav")&&!mime.equals("audio/x-wav"))throw new IOException("未対応の音声形式です。");data=a.optString("data");}}}
        if(data.isEmpty())throw new IOException("音声が返されませんでした。設定・本文を確認してください。");return data;
    }
    static boolean wav(byte[] data){return data.length>44&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'&&data[8]=='W'&&data[9]=='A'&&data[10]=='V'&&data[11]=='E';}
    File synthesize(String model,JSONObject speaker,JSONObject segment,java.util.function.BooleanSupplier allowed)throws Exception {
        if(!allowed.getAsBoolean())throw new InterruptedIOException("音声の準備を停止しました。");
        JSONObject req=request(model,speaker.optString("voice","Charon"),segment.getString("text"),style(speaker,segment));
        File cached=new File(directory,"gemini-"+NarrationStore.hash(req.toString())+".wav");if(validFile(cached)){cached.setLastModified(System.currentTimeMillis());return cached;}
        String key=credentials.read().optString("key");if(key.isEmpty())throw new IOException("設定からGemini音声APIキーを登録してください。");
        HttpURLConnection c=(HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();active=c;
        File tmp=new File(cached.getPath()+".tmp");try {
            if(!allowed.getAsBoolean())throw new InterruptedIOException("音声の準備を停止しました。");
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-goog-api-key",key);
            byte[] body=req.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);if(!allowed.getAsBoolean())throw new InterruptedIOException("音声の準備を停止しました。");try(OutputStream out=c.getOutputStream()){out.write(body);}
            int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("Gemini音声 API HTTP "+status+"。"+(status==401||status==403?"APIキーと利用権限を確認してください。":status==429?"利用上限です。時間を置いて手動で再開してください。":"モデル・API利用設定・接続を確認し、手動で再開してください。"));
            String json;try(InputStream in=c.getInputStream()){json=NarrationStore.read(in,24*1024*1024);}byte[] wav=Base64.decode(audioData(new JSONObject(json)),Base64.DEFAULT);if(!wav(wav))throw new IOException("音声のWAVデータを確認できませんでした。");
            try(FileOutputStream out=new FileOutputStream(tmp)){out.write(wav);out.getFD().sync();}if(!tmp.renameTo(cached))throw new IOException("音声を保存できませんでした。");trim(cached);return cached;
        }finally {tmp.delete();c.disconnect();if(active==c)active=null;}
    }
    static boolean validFile(File f){if(f.length()<45)return false;try(FileInputStream in=new FileInputStream(f)){byte[] h=new byte[45];return in.read(h)==45&&wav(h);}catch(Exception e){return false;}}
    void trim(File keep){File[] files=directory.listFiles((d,n)->n.endsWith(".wav"));if(files==null)return;long size=0;for(File f:files)size+=f.length();Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files)if(size>256L*1024*1024&&!f.equals(keep)){long len=f.length();if(f.delete())size-=len;}}
}
