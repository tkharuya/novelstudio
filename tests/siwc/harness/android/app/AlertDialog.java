package android.app;
import android.content.Context;
import android.content.DialogInterface;
/** JVM-only dialog recorder. Tests exercise eligibility, ownership and deferred dispatch. */
public class AlertDialog implements DialogInterface {
    public static AlertDialog latest;
    public static int shown;
    public final Context context;
    private boolean showing;
    private AlertDialog(Context context) { this.context = context; }
    public void show() { showing = true; latest = this; shown++; }
    public boolean isShowing() { return showing; }
    public void cancel() { dismiss(); }
    public void dismiss() { showing = false; }
    public static final class Builder {
        private final Context context;
        public Builder(Context context) { this.context = context; }
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) { return this; }
        public AlertDialog create() { return new AlertDialog(context); }
    }
}
