package jp.haruya.novelstudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming backup of manuscripts and referenced PNGs. Never reads credential files. */
public final class IllustrationBackup {
    public static final long MAX_TOTAL_BYTES = 250L * 1024 * 1024;
    public static final int MAX_IMAGES = 2000;
    public static final int MAX_IMAGES_PER_CHAPTER = 50;
    public static final int MAX_STATE_BYTES = 30 * 1024 * 1024;
    private static final String IMAGE_PREFIX = "illustrations/";
    private final IllustrationStore store;

    public IllustrationBackup(IllustrationStore store) {
        if (store == null) throw new IllegalArgumentException("Image store is required");
        this.store = store;
    }

    /** Does not close the caller's stream. A missing image fails the whole export. */
    public void exportTo(OutputStream output, String state) throws IOException {
        if (output == null) throw new IOException("バックアップの保存先を開けませんでした。");
        try {
            byte[] stateBytes = stateBytes(state);
            Manifest manifest = parseState(state);
            long expectedTotal = stateBytes.length;
            // Preflight before writing any ZIP bytes. Only immutable, referenced images enter it.
            for (String id : manifest.images.keySet()) {
                expectedTotal += store.sizeOf(id);
                if (expectedTotal > MAX_TOTAL_BYTES) throw tooLarge();
            }
            Counter count = new Counter();
            try (ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(output), StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("state.json"));
                count.add(stateBytes.length);
                zip.write(stateBytes);
                zip.closeEntry();
                byte[] buffer = new byte[8192];
                for (String id : manifest.images.keySet()) {
                    long expectedSize = store.sizeOf(id);
                    long written = 0;
                    zip.putNextEntry(new ZipEntry(IMAGE_PREFIX + id + ".png"));
                    try (InputStream input = store.open(id)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (written + read > IllustrationStore.MAX_IMAGE_BYTES) throw new IOException("保存画像が16MiBを超えています。");
                            count.add(read);
                            zip.write(buffer, 0, read);
                            written += read;
                        }
                    }
                    if (written != expectedSize) throw new IOException("画像の読み取りを完了できませんでした。");
                    zip.closeEntry();
                }
                zip.finish();
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("バックアップを処理するメモリが不足しています。作品を分けて保存してください。");
        }
    }

    /**
     * Validates and stages everything before publishing any images. Returns rewritten state;
     * the caller still validates/confirms and saves that manuscript. Existing images are retained.
     * Does not close the caller's stream.
     */
    public String importFrom(InputStream input) throws IOException {
        if (input == null) throw new IOException("バックアップを開けませんでした。");
        File stage = store.createStage();
        try {
            String state = null;
            long normalizedBytes = 0;
            Counter counter = new Counter();
            Set<String> names = new HashSet<>();
            LinkedHashMap<String, StagedImage> staged = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new NonClosingInputStream(input), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || !names.add(name)) throw new IOException("バックアップ内のファイル名が重複しています。");
                    if (names.size() > MAX_IMAGES + 2) throw new IOException("バックアップ内のファイル数が多すぎます。");
                    if (entry.isDirectory()) {
                        if (!IMAGE_PREFIX.equals(name) || zip.read() != -1) throw new IOException("バックアップ内に不正なフォルダーがあります。");
                        zip.closeEntry();
                        continue;
                    }
                    if ("state.json".equals(name)) {
                        if (state != null) throw new IOException("原稿データが重複しています。");
                        checkDeclaredSize(entry, MAX_STATE_BYTES);
                        state = utf8(readEntry(zip, MAX_STATE_BYTES, counter));
                        if (!state.isEmpty() && state.charAt(0) == '\ufeff') state = state.substring(1);
                    } else {
                        String id = imageId(name);
                        if (id == null) throw new IOException("バックアップ内に未対応または不正なファイル名があります。");
                        if (staged.size() >= MAX_IMAGES) throw new IOException("1つのバックアップには2,000枚までの挿絵を保存できます。");
                        checkDeclaredSize(entry, IllustrationStore.MAX_IMAGE_BYTES);
                        byte[] bytes = readEntry(zip, IllustrationStore.MAX_IMAGE_BYTES, counter);
                        File image = new File(stage, id + ".png");
                        IllustrationStore.ImageInfo info = IllustrationStore.normalizeTo(bytes, image);
                        normalizedBytes += info.bytes;
                        if (normalizedBytes > MAX_TOTAL_BYTES) throw tooLarge();
                        staged.put(id, new StagedImage(image, info));
                    }
                    zip.closeEntry();
                }
            }
            if (state == null) throw new IOException("バックアップにstate.jsonがありません。");
            Manifest manifest = parseState(state);
            if (!staged.keySet().equals(manifest.images.keySet())) {
                if (!staged.keySet().containsAll(manifest.images.keySet())) {
                    throw new IOException("バックアップに必要な挿絵が不足しています。元のバックアップを確認してください。");
                }
                throw new IOException("バックアップに参照されていない画像が含まれています。");
            }
            return publishImport(manifest, staged, normalizedBytes);
        } catch (OutOfMemoryError error) {
            throw new IOException("バックアップを処理するメモリが不足しています。小さいバックアップを使用してください。");
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("バックアップの形式を確認できませんでした。", error);
        } finally {
            store.cleanupStage(stage);
        }
    }

    private String publishImport(Manifest manifest, LinkedHashMap<String, StagedImage> staged,
                                 long normalizedBytes) throws IOException {
        synchronized (IllustrationStore.FILE_LOCK) {
            List<Publication> publications = new ArrayList<>();
            Set<String> reserved = new HashSet<>();
            try {
                for (Map.Entry<String, StagedImage> entry : staged.entrySet()) {
                    String originalId = entry.getKey();
                    String targetId = originalId;
                    StagedImage image = entry.getValue();
                    File target = store.resolve(targetId);
                    boolean reuse = target.isFile() && target.length() <= IllustrationStore.MAX_IMAGE_BYTES
                            && IllustrationStore.sameContents(target, image.file);
                    if ((target.exists() && !reuse) || reserved.contains(targetId)) {
                        do {
                            targetId = UUID.randomUUID().toString();
                            target = store.resolve(targetId);
                        } while (target.exists() || reserved.contains(targetId));
                        reuse = false;
                    }
                    reserved.add(targetId);
                    publications.add(new Publication(targetId, target, image.file, reuse));
                    for (JSONObject ref : manifest.images.get(originalId)) {
                        ref.put("id", targetId);
                        ref.put("mime", "image/png");
                        ref.put("width", image.info.width);
                        ref.put("height", image.info.height);
                        ref.put("createdAt", timestamp(ref));
                        ref.put("source", IllustrationStore.checkedSource(optionalString(ref, "source", "import")));
                        ref.put("prompt", IllustrationStore.checkedPrompt(optionalString(ref, "prompt", "")));
                    }
                }
                // Materialize/validate the final JSON before a file is moved into the live store.
                String result = manifest.root.toString();
                if (stateBytes(result).length + normalizedBytes > MAX_TOTAL_BYTES) throw tooLarge();
                List<File> created = new ArrayList<>();
                try {
                    for (Publication publication : publications) {
                        if (!publication.reuse) {
                            store.publish(publication.staged, publication.id);
                            created.add(publication.target);
                        }
                    }
                } catch (Exception error) {
                    // Only files newly created by this operation are candidates for rollback.
                    for (File file : created) file.delete();
                    if (error instanceof IOException) throw (IOException) error;
                    throw new IOException("挿絵を保存できませんでした。現在の原稿は変更していません。", error);
                }
                return result;
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("挿絵の復元情報を確認できませんでした。", error); }
        }
    }

    private static Manifest parseState(String text) throws IOException {
        stateBytes(text);
        try {
            JSONObject root = new JSONObject(text);
            Object schema = root.opt("schemaVersion");
            if (!"NovelStudio".equals(root.optString("app")) || !(schema instanceof Number)
                    || (((Number) schema).doubleValue() != 1 && ((Number) schema).doubleValue() != 2)) {
                throw new IOException("小説工房のバックアップではありません。");
            }
            JSONArray books = root.optJSONArray("books");
            if (books == null || books.length() > 300) throw new IOException("作品の一覧が不正です。");
            Manifest manifest = new Manifest(root);
            Set<String> itemIds = new HashSet<>();
            int totalChapters = 0;
            for (int i = 0; i < books.length(); i++) {
                JSONObject book = books.optJSONObject(i);
                if (book == null) throw new IOException("作品の形式が不正です。");
                checkItemId(book.opt("id"), itemIds);
                JSONArray chapters = book.optJSONArray("chapters");
                if (chapters == null || chapters.length() == 0 || chapters.length() > 3000) throw new IOException("話の一覧が不正です。");
                totalChapters += chapters.length();
                if (totalChapters > 6000) throw new IOException("1つのバックアップは6,000話までです。");
                for (int j = 0; j < chapters.length(); j++) {
                    JSONObject chapter = chapters.optJSONObject(j);
                    if (chapter == null) throw new IOException("話の形式が不正です。");
                    checkItemId(chapter.opt("id"), itemIds);
                    if (!chapter.has("illustrations") || chapter.isNull("illustrations")) continue;
                    JSONArray images = chapter.optJSONArray("illustrations");
                    if (images == null || images.length() > MAX_IMAGES_PER_CHAPTER) throw new IOException("挿絵は1話につき50枚までです。");
                    Set<String> chapterIds = new HashSet<>();
                    for (int k = 0; k < images.length(); k++) {
                        JSONObject image = images.optJSONObject(k);
                        if (image == null || !(image.opt("id") instanceof String)) throw new IOException("挿絵の情報が不正です。");
                        String id = image.getString("id");
                        if (!IllustrationStore.isValidId(id) || !chapterIds.add(id)) throw new IOException("挿絵のIDが不正または重複しています。");
                        IllustrationStore.checkedPrompt(optionalString(image, "prompt", ""));
                        IllustrationStore.checkedSource(optionalString(image, "source", "import"));
                        timestamp(image);
                        List<JSONObject> refs = manifest.images.get(id);
                        if (refs == null) {
                            if (manifest.images.size() >= MAX_IMAGES) throw new IOException("1つのバックアップには2,000枚までの挿絵を保存できます。");
                            refs = new ArrayList<>();
                            manifest.images.put(id, refs);
                        }
                        refs.add(image);
                    }
                }
            }
            return manifest;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("原稿データの形式が不正です。", error); }
    }

    private static void checkItemId(Object value, Set<String> ids) throws IOException {
        if (!(value instanceof String)) throw new IOException("作品または話のIDが不正です。");
        String id = (String) value;
        if (!id.matches("[A-Za-z0-9_-]{3,100}") || "__proto__".equals(id)
                || "constructor".equals(id) || "prototype".equals(id) || !ids.add(id)) {
            throw new IOException("作品または話のIDが不正または重複しています。");
        }
    }

    private static String optionalString(JSONObject object, String key, String fallback) throws IOException {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IOException("挿絵の文字情報が不正です。");
        return (String) value;
    }

    private static long timestamp(JSONObject object) throws IOException {
        if (!object.has("createdAt") || object.isNull("createdAt")) return System.currentTimeMillis();
        Object value = object.opt("createdAt");
        if (!(value instanceof Number)) throw new IOException("挿絵の日時情報が不正です。");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 0 || number > 9007199254740991d) throw new IOException("挿絵の日時情報が不正です。");
        return ((Number) value).longValue();
    }

    private static String imageId(String name) {
        if (name == null || !name.startsWith(IMAGE_PREFIX) || !name.endsWith(".png")) return null;
        String id = name.substring(IMAGE_PREFIX.length(), name.length() - 4);
        return IllustrationStore.isValidId(id) ? id : null;
    }

    private static byte[] stateBytes(String state) throws IOException {
        if (state == null || state.isEmpty() || state.length() > MAX_STATE_BYTES) throw new IOException("原稿データのサイズが不正です。");
        byte[] bytes = state.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATE_BYTES) throw new IOException("原稿データが30MiBを超えています。");
        return bytes;
    }

    private static void checkDeclaredSize(ZipEntry entry, int limit) throws IOException {
        long size = entry.getSize();
        if (size < -1 || size > limit) throw new IOException("バックアップ内のファイルが大きすぎます。");
    }

    private static byte[] readEntry(InputStream input, int limit, Counter counter) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 65536))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > limit) throw new IOException("バックアップ内のファイルが上限サイズを超えています。");
                counter.add(read);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String utf8(byte[] data) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) { throw new IOException("原稿データの文字コードがUTF-8ではありません。", error); }
    }

    private static IOException tooLarge() { return new IOException("画像付きバックアップの上限は展開後250MiBです。"); }

    private static final class Counter {
        long bytes;
        void add(long amount) throws IOException {
            if (amount < 0 || bytes > MAX_TOTAL_BYTES - amount) throw tooLarge();
            bytes += amount;
        }
    }

    private static final class Manifest {
        final JSONObject root;
        final LinkedHashMap<String, List<JSONObject>> images = new LinkedHashMap<>();
        Manifest(JSONObject root) { this.root = root; }
    }

    private static final class StagedImage {
        final File file;
        final IllustrationStore.ImageInfo info;
        StagedImage(File file, IllustrationStore.ImageInfo info) { this.file = file; this.info = info; }
    }

    private static final class Publication {
        final String id;
        final File target;
        final File staged;
        final boolean reuse;
        Publication(String id, File target, File staged, boolean reuse) {
            this.id = id; this.target = target; this.staged = staged; this.reuse = reuse;
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) { super(input); }
        @Override public void close() { }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) { super(output); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
        @Override public void close() throws IOException { flush(); }
    }
}
