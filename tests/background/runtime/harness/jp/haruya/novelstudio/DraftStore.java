package jp.haruya.novelstudio;
import android.content.Context;
/** Runtime orchestration fixture; manuscript validation has separate tests. */
public final class DraftStore {
    public String state = "{\"app\":\"NovelStudio\",\"schemaVersion\":1,\"books\":[]}";
    public DraftStore(Context context) { }
    public String load() { return state; }
}
