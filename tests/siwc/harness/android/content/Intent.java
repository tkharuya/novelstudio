package android.content;
import android.net.Uri;
import android.os.Bundle;
/** JVM-only intent recorder. No activity or browser is launched by these checks. */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public final String action;
    public final Uri data;
    public Intent(String action, Uri data) { this.action = action; this.data = data; }
    public Intent addCategory(String category) { return this; }
    public Intent putExtras(Bundle extras) { return this; }
    public Intent putExtra(String name, int value) { return this; }
}
