package jp.haruya.novelstudio;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Offline manuscripts only. OAuth secrets never enter this file or its exports. */
final class DraftStore {
    static final int MAX_BYTES = 30 * 1024 * 1024;
    private final AtomicFile current;
    private final AtomicFile previous;
    private long lastSnapshot;
    private String loadWarning = "";

    DraftStore(Context context) {
        current = new AtomicFile(new File(context.getFilesDir(), "manuscripts.json"));
        previous = new AtomicFile(new File(context.getFilesDir(), "manuscripts.previous.json"));
    }

    synchronized String load() {
        try {
            if (!current.getBaseFile().exists() && !new File(current.getBaseFile()+".bak").exists()) {
                if (!previous.getBaseFile().exists() && !new File(previous.getBaseFile()+".bak").exists()) return "{}";
                String recovered = read(previous);
                new JSONObject(recovered);
                loadWarning = "以前の保存データから原稿を復元しました。バックアップを出力してください。";
                return recovered;
            }
            String value = read(current);
            new JSONObject(value);
            return value;
        } catch (Exception error) {
            // Retain damaged bytes for recovery before a later save creates a new document.
            File damaged = current.getBaseFile();
            if (damaged.exists()) damaged.renameTo(new File(damaged.getParentFile(), "manuscripts.corrupt."+System.currentTimeMillis()+".json"));
            try {
                String fallback = read(previous);
                new JSONObject(fallback);
                loadWarning = "直近の保存を読み込めなかったため、以前の保存データを復元しました。バックアップを出力してください。";
                return fallback;
            } catch (Exception ignored) {
                loadWarning = "保存データを読み込めませんでした。元ファイルは残しています。JSONバックアップがある場合は設定から復元してください。";
                return "{}";
            }
        }
    }

    synchronized String takeWarning() {
        String message = loadWarning;
        loadWarning = "";
        return message;
    }

    synchronized void save(String value) throws Exception {
        if (value == null) throw new IOException("保存するデータがありません。");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("保存データが30MiBを超えました。バックアップを書き出して作品を整理してください。");
        JSONObject json = new JSONObject(value);
        if (json.length() == 0) throw new IOException("空のデータでは保存を上書きしません。");
        // At most once a minute, preserve the previously valid document independently.
        if (current.getBaseFile().exists() && System.currentTimeMillis()-lastSnapshot > 60_000) {
            try {
                String old = read(current);
                new JSONObject(old);
                write(previous, old.getBytes(StandardCharsets.UTF_8));
                lastSnapshot = System.currentTimeMillis();
            } catch (Exception ignored) {
                // Keep any older valid snapshot. A corrupt current file must not replace it.
            }
        }
        write(current, bytes);
    }

    private static String read(AtomicFile file) throws IOException {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (result.size() + size > MAX_BYTES) throw new IOException("保存ファイルが大きすぎます。");
                result.write(buffer, 0, size);
            }
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }
}
