package jp.haruya.novelstudio;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;

/** Media foreground service, separate from text generation's dataSync time budget. */
public final class NarrationService extends Service {
    static final String START="speech.start",PAUSE="speech.pause",NEXT="speech.next",PREVIOUS="speech.previous";
    private static final String CHANNEL="narration_playback";
    private NarrationPlayer player;
    private boolean promoted;
    @Override public void onCreate(){super.onCreate();player=NarrationPlayer.get(this);NotificationManager nm=getSystemService(NotificationManager.class);if(nm!=null){NotificationChannel c=new NotificationChannel(CHANNEL,"小説の読み上げ",NotificationManager.IMPORTANCE_LOW);c.enableVibration(false);c.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);nm.createNotificationChannel(c);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();if(PAUSE.equals(action)){player.pause("通知から一時停止しました。");return START_NOT_STICKY;}
        if(NEXT.equals(action)){player.skip(1);return START_NOT_STICKY;}if(PREVIOUS.equals(action)){player.skip(-1);return START_NOT_STICKY;}
        if(!START.equals(action)){stopSelf();return START_NOT_STICKY;}
        try {if(Build.VERSION.SDK_INT>=29)startForeground(1401,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(1401,notification());promoted=true;player.serviceReady(this);}
        catch(RuntimeException e){player.pause("読み上げ通知を開始できませんでした。アプリを開いて再生してください。");finish();}
        return START_NOT_STICKY;
    }
    private PendingIntent control(String action,int code){return PendingIntent.getService(this,code,new Intent(this,NarrationService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){JSONObject p=player.playerState();PendingIntent open=PendingIntent.getActivity(this,1402,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_generation).setContentTitle(p.optString("title","小説工房の読み上げ")).setContentText(p.optString("speaker")+" · "+Math.min(p.optInt("index")+1,p.optInt("total"))+" / "+p.optInt("total")+"区間").setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,"前へ",control(PREVIOUS,1403)).build()).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"一時停止",control(PAUSE,1404)).build()).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,"次へ",control(NEXT,1405)).build());
        Notification.MediaStyle style=new Notification.MediaStyle().setShowActionsInCompactView(0,1,2);if(player.token()!=null)style.setMediaSession(player.token());b.setStyle(style);if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);return b.build();}
    void refresh(){if(promoted){NotificationManager nm=getSystemService(NotificationManager.class);if(nm!=null)try{nm.notify(1401,notification());}catch(RuntimeException ignored){}}}
    void finish(){promoted=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){player.serviceDestroyed(this);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
