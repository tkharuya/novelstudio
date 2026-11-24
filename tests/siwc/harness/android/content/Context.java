package android.content;
/** JVM-only application context shim. Never included in the APK. */
public class Context {
    public Context getApplicationContext() { return this; }
}
