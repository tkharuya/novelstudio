package android.app;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;
/** JVM-only screen/lifecycle recorder. Never included in the APK. */
public class Activity extends Context {
    public boolean finishing, destroyed, launchFails;
    public final List<Intent> launches = new ArrayList<>();
    private final Context application = new Context();
    @Override public Context getApplicationContext() { return application; }
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public void startActivity(Intent intent) {
        if (launchFails) throw new IllegalStateException("PRIVATE_BROWSER_EXCEPTION");
        launches.add(intent);
    }
}
