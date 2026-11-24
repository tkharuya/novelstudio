package jp.haruya.novelstudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** User-started cloud generation; no boot receiver, periodic work or silent resubmission. */
public final class GenerationService extends Service implements GenerationRuntime.ServiceListener {
    static final String ACTION_START = "jp.haruya.novelstudio.GENERATION_START";
    static final String ACTION_CANCEL = "jp.haruya.novelstudio.GENERATION_CANCEL";
    static final String CHANNEL_ID = "generation_progress";
    private static final int RUNNING_ID = 1201, FINISHED_ID = 1202;
    private static final long MAX_SESSION_MS = 6L * 60 * 60 * 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private GenerationRuntime runtime;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private boolean promoted, destroyed, refreshQueued;
    private int latestStartId;

    @Override public void onCreate() {
        super.onCreate();
        runtime = GenerationRuntime.get(this);
        notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "小説の生成状況", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("文章と挿絵の生成状況・中止操作・完了のお知らせ");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            channel.enableVibration(false);
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            runtime.cancelAll("");
            if (!promoted && !runtime.hasWork()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(startId); return START_NOT_STICKY; }
        try {
            Notification notice = progressNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(RUNNING_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(RUNNING_ID, notice);
            if (!promoted) {
                promoted = true;
                if (notifications != null) notifications.cancel(FINISHED_ID);
                PowerManager manager = (PowerManager)getSystemService(POWER_SERVICE);
                if (manager != null) {
                    wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelStudio:generation");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire(MAX_SESSION_MS);
                }
                main.postDelayed(sessionDeadline, MAX_SESSION_MS);
            }
            runtime.serviceReady(this);
            onGenerationProgress();
        } catch (RuntimeException error) {
            runtime.serviceStartFailed();
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            promoted = false;
            stopSelf(startId);
        }
        // A killed process must not repeat a paid image request or submit a text prompt again.
        return START_NOT_STICKY;
    }

    private final Runnable sessionDeadline = () -> stopForLimit("長時間の生成を中断しました。受信済みの内容を保存しています。", latestStartId);

    @Override public void onGenerationProgress() {
        main.post(() -> {
            if (destroyed || !promoted || refreshQueued) return;
            refreshQueued = true;
            main.postDelayed(refresh, 500);
        });
    }

    private final Runnable refresh = () -> {
        refreshQueued = false;
        if (destroyed || !promoted) return;
        if (runtime.hasWork()) {
            if (notifications != null) {
                try { notifications.notify(RUNNING_ID, progressNotification()); }
                catch (RuntimeException ignored) { /* Notification denial does not cancel generation. */ }
            }
            return;
        }
        if (!runtime.prepareIdleStop(this)) return;
        promoted = false;
        main.removeCallbacks(sessionDeadline);
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        String result = runtime.consumeCompletionMessage();
        if (!result.isEmpty() && notifications != null && runtime.notificationsEnabled()) {
            Notification finished = base("生成処理が終了しました", result)
                .setAutoCancel(true).setOngoing(false).build();
            try { notifications.notify(FINISHED_ID, finished); } catch (RuntimeException ignored) { }
        }
        stopSelfResult(latestStartId);
    };

    private Notification progressNotification() {
        Intent cancel = new Intent(this, GenerationService.class).setAction(ACTION_CANCEL);
        PendingIntent stop = PendingIntent.getService(this, 1203, cancel,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = base("小説工房で生成中", runtime.progressText())
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "生成を中止", stop).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private Notification.Builder base(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1204, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicVersion = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_generation).setContentTitle("小説工房")
            .setContentText("生成処理の状況を確認できます").setContentIntent(content).build();
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_generation)
            .setContentTitle(title).setContentText(text).setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(true);
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        stopForLimit("Androidのバックグラウンド処理の時間上限で中断しました。受信済み内容はアプリで確認できます。", startId);
    }

    private void stopForLimit(String reason, int endingStartId) {
        // Invalidate readiness before a simultaneous new user request can dispatch work.
        runtime.serviceExpired(this, reason);
        main.removeCallbacks(sessionDeadline);
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        promoted = false;
        stopSelfResult(endingStartId);
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) { }
        wakeLock = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        releaseWakeLock();
        if (runtime != null) runtime.serviceDestroyed(this);
        super.onDestroy();
    }
}
