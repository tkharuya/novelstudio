package android.app;
import android.content.Context;
/** JVM-only screen identity. */
public class Activity extends Context {
    private final Context application;
    public Activity(Context application) { this.application = application; }
    @Override public Context getApplicationContext() { return application; }
}
