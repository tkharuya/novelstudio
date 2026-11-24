package android.app;
public final class NotificationManager {
    public static final int IMPORTANCE_NONE = 0;
    public boolean enabled = true;
    public NotificationChannel channel;
    public boolean areNotificationsEnabled() { return enabled; }
    public NotificationChannel getNotificationChannel(String id) { return channel; }
}
