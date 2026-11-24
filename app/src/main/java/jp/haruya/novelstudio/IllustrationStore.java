package jp.haruya.novelstudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private, immutable PNG images. The manuscript contains IDs and metadata only. */
public final class IllustrationStore {
    public static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    public static final long MAX_PIXELS = 16L * 1024 * 1024;
    public static final int MAX_PROMPT_CHARS = 32000;
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // Also protects publication by multiple helper instances in the same process.
    static final Object FILE_LOCK = new Object();
    private final File directory;
    private final File stagingRoot;

    public IllustrationStore(Context context) {
        File files = context.getApplicationContext().getFilesDir();
        directory = new File(files, "illustrations");
        stagingRoot = new File(files, "illustration-staging");
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Call on a worker thread. Oversized input or invalid content is rejected, not truncated. */
    public JSONObject save(byte[] data, String source, String prompt) throws IOException {
        String safeSource = checkedSource(source);
        String safePrompt = checkedPrompt(prompt);
        File stage = createStage();
        try {
            File normalized = new File(stage, "image.png");
            ImageInfo info = normalizeTo(data, normalized);
            String id;
            synchronized (FILE_LOCK) {
                do { id = UUID.randomUUID().toString(); } while (resolve(id).exists());
                publish(normalized, id);
            }
            return metadata(id, info, System.currentTimeMillis(), safeSource, safePrompt);
        } catch (OutOfMemoryError error) {
            throw new IOException("画像を処理するメモリが不足しています。小さい画像を選んでください。");
        } finally {
            cleanupStage(stage);
        }
    }

    /** The returned stream is owned by the caller. No caller-supplied path is accepted. */
    public InputStream open(String id) throws IOException {
        File file = checkedFile(id);
        return new FileInputStream(file);
    }

    public void copyTo(String id, OutputStream output) throws IOException {
        if (output == null) throw new IOException("保存先を開けませんでした。");
        File file = checkedFile(id);
        long expected = file.length();
        long copied = 0;
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (copied + count > MAX_IMAGE_BYTES) throw new IOException("画像ファイルが大きすぎます。");
                output.write(buffer, 0, count);
                copied += count;
            }
        }
        if (copied != expected) throw new IOException("画像ファイルの読み取りを完了できませんでした。");
    }

    public boolean exists(String id) {
        try { checkedFile(id); return true; }
        catch (IOException error) { return false; }
    }

    public long sizeOf(String id) throws IOException { return checkedFile(id).length(); }

    static final class ImageInfo {
        final int width;
        final int height;
        final long bytes;
        ImageInfo(int width, int height, long bytes) {
            this.width = width; this.height = height; this.bytes = bytes;
        }
    }

    static ImageInfo normalizeTo(byte[] data, File target) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            throw new IOException("画像は16MiB以内のPNG・JPEG・WebPを選んでください。");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try { BitmapFactory.decodeByteArray(data, 0, data.length, bounds); }
        catch (RuntimeException error) { throw new IOException("画像の形式を確認できませんでした。", error); }
        String mime = bounds.outMimeType == null ? "" : bounds.outMimeType.toLowerCase(Locale.ROOT);
        if (!("image/png".equals(mime) || "image/jpeg".equals(mime) || "image/webp".equals(mime))
                || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("有効なPNG・JPEG・WebP画像を選んでください。");
        }
        if (bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION
                || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) {
            throw new IOException("画像の縦横は4,096ピクセル以内、総画素数は16メガピクセル以内にしてください。");
        }
        int orientation = orientation(data);
        // A photograph may fit the input byte limit but expand when normalized to PNG.
        // Decode again at a smaller size instead of holding a second full-sized image.
        for (int sample = 1; sample <= 16; sample *= 2) {
            Bitmap decoded = null;
            Bitmap oriented = null;
            boolean overLimit = false;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inSampleSize = sample;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (decoded == null) throw new IOException("画像を読み込めませんでした。画像ファイルを確認してください。");
                oriented = orient(decoded, orientation);
                try (FileOutputStream file = new FileOutputStream(target)) {
                    LimitedOutputStream limited = new LimitedOutputStream(file, MAX_IMAGE_BYTES);
                    boolean written;
                    try { written = encodePng(oriented, limited); }
                    catch (IOException error) {
                        if (!limited.exceeded) throw error;
                        written = false;
                    } catch (RuntimeException error) {
                        if (!limited.exceeded) throw new IOException("画像をPNGに変換できませんでした。", error);
                        written = false;
                    }
                    overLimit = limited.exceeded;
                    if (!overLimit) {
                        if (!written || limited.count == 0) throw new IOException("画像をPNGに変換できませんでした。");
                        file.flush();
                        file.getFD().sync();
                        return new ImageInfo(oriented.getWidth(), oriented.getHeight(), limited.count);
                    }
                }
            } catch (OutOfMemoryError error) {
                // Releasing this attempt before the next decode bounds peak bitmap memory.
                if (sample == 16) throw new IOException("画像を処理するメモリが不足しています。小さい画像を選んでください。");
                overLimit = true;
            } catch (RuntimeException error) {
                throw new IOException("画像を処理できませんでした。画像ファイルを確認してください。", error);
            } finally {
                if (oriented != null && oriented != decoded) oriented.recycle();
                if (decoded != null) decoded.recycle();
            }
            if (!overLimit) break;
        }
        throw new IOException("画像を保存できるサイズに変換できませんでした。");
    }

    private static int orientation(byte[] data) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) { return ExifInterface.ORIENTATION_NORMAL; }
    }

    private static boolean encodePng(Bitmap bitmap, OutputStream output) throws IOException {
        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    }

    private static Bitmap orient(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    static JSONObject metadata(String id, ImageInfo info, long createdAt, String source, String prompt) throws IOException {
        try {
            return new JSONObject().put("id", id).put("mime", "image/png")
                    .put("width", info.width).put("height", info.height)
                    .put("createdAt", createdAt).put("source", checkedSource(source))
                    .put("prompt", checkedPrompt(prompt));
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("画像の情報を保存できませんでした。", error); }
    }

    static String checkedPrompt(String prompt) throws IOException {
        if (prompt == null) return "";
        if (prompt.length() > MAX_PROMPT_CHARS) throw new IOException("挿絵の指示は32,000文字以内にしてください。");
        return prompt;
    }

    static String checkedSource(String source) throws IOException {
        if (source == null || source.isEmpty()) return "import";
        if (source.length() > 80) throw new IOException("画像の出典情報が長すぎます。");
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new IOException("画像の出典情報が不正です。");
        }
        return source;
    }

    File checkedFile(String id) throws IOException {
        File file = resolve(id);
        if (!file.isFile() || file.length() <= 0) throw new IOException("参照されている挿絵が見つかりません。画像を再度取り込んでください。");
        if (file.length() > MAX_IMAGE_BYTES) throw new IOException("保存画像が16MiBを超えています。");
        return file;
    }

    File resolve(String id) throws IOException {
        if (!isValidId(id)) throw new IOException("画像IDの形式が不正です。");
        File base = ensureDirectory(directory);
        File file = new File(base, id + ".png");
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("画像の保存先が不正です。");
        return file;
    }

    File createStage() throws IOException {
        File base = ensureDirectory(stagingRoot);
        File stage = new File(base, "operation-" + UUID.randomUUID().toString());
        if (!stage.mkdir()) throw new IOException("画像の一時保存先を作成できませんでした。空き容量を確認してください。");
        return stage;
    }

    void cleanupStage(File stage) {
        if (stage == null) return;
        try {
            File base = stagingRoot.getCanonicalFile();
            if (!base.equals(stage.getCanonicalFile().getParentFile())
                    || !stage.getName().startsWith("operation-")
                    || !isValidId(stage.getName().substring("operation-".length()))) return;
            deleteTree(stage);
        } catch (IOException ignored) { }
    }

    private static void deleteTree(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    static File ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("画像の保存先を作成できませんでした。空き容量を確認してください。");
        return file.getCanonicalFile();
    }

    /** Caller holds FILE_LOCK and owns the staged file. Never replace an existing image. */
    void publish(File staged, String id) throws IOException {
        File target = resolve(id);
        if (target.exists()) throw new IOException("画像IDが重複しました。もう一度取り込んでください。");
        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            // Both locations are private app storage. No REPLACE_EXISTING option is used.
            Files.move(staged.toPath(), target.toPath());
        }
    }

    static boolean sameContents(File a, File b) throws IOException {
        return a.length() == b.length() && MessageDigest.isEqual(digest(a), digest(b));
    }

    private static byte[] digest(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("画像の整合性を確認できませんでした。", error); }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        final long limit;
        long count;
        boolean exceeded;
        LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            reserve(1); out.write(value); count++;
        }
        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            reserve(length); out.write(buffer, offset, length); count += length;
        }
        private void reserve(int length) throws IOException {
            if (length < 0 || count > limit - length) {
                exceeded = true;
                throw new IOException("正規化した画像が16MiBを超えています。");
            }
        }
    }
}
