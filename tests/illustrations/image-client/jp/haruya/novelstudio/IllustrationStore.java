package jp.haruya.novelstudio;
import org.json.JSONObject;
/** Test double records commits; real PNG decoding/storage is tested separately. */
final class IllustrationStore {
    volatile int saves;
    volatile boolean failSave;
    synchronized JSONObject save(byte[] bytes, String source, String prompt) throws Exception {
        if (failSave) throw new Exception("PRIVATE_SAVE_FAILURE_CANARY");
        saves++;
        return new JSONObject().put("id", "image_fixture").put("source", source)
                .put("prompt", prompt).put("bytes", bytes.length);
    }
}
