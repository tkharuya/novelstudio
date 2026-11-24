package android.content;
import android.app.NotificationManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/** Records foreground-service starts. No Android framework or remote API is invoked. */
public class Context {
    private final java.io.File files;
    public Context() { try { files=java.nio.file.Files.createTempDirectory("novelstudio-runtime-").toFile(); }catch(Exception e){throw new IllegalStateException(e);} }
    public Context(java.io.File directory) { files=directory; }
    public java.io.File getFilesDir(){return files;}
    public static final String NOTIFICATION_SERVICE = "notification";
    public final List<Intent> starts = new CopyOnWriteArrayList<>();
    public final NotificationManager notifications = new NotificationManager();
    public RuntimeException startFailure;
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return NOTIFICATION_SERVICE.equals(name) ? notifications : null; }
    public ComponentName startForegroundService(Intent intent) {
        starts.add(intent);
        if (startFailure != null) throw startFailure;
        return new ComponentName("jp.haruya.novelstudio", "GenerationService");
    }
}
