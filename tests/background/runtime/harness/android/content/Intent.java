package android.content;
import java.util.HashMap;
import java.util.Map;
/** Records the service request; never sends an Android Intent. */
public final class Intent {
    public final Context context;
    public final Class<?> target;
    public final Map<String,Object> extras = new HashMap<>();
    private String action;
    public Intent(Context context, Class<?> target) { this.context = context; this.target = target; }
    public Intent setAction(String action) { this.action = action; return this; }
    public String getAction() { return action; }
}
