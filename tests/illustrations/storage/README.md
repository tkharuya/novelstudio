# Illustration storage and ZIP backup checks

Run from the project directory:

```bash
bash tests/illustrations/storage/run.sh /path/org-json.jar
```

Use JDK 17 or later on PATH, or set `ILLUSTRATION_JAVA_HOME`. The JSON jar may also be supplied through `ILLUSTRATION_JSON_JAR`.

The harness compiles the production `IllustrationStore` and `IllustrationBackup` classes with isolated JVM substitutes for Android Context, BitmapFactory, Bitmap, Matrix and ExifInterface. Java ImageIO supplies actual PNG/JPEG decoding and encoding. The JSON dependency is the same standalone org.json jar used by the separate authentication harness. Production Android code has no additional dependency.

The checks cover image normalization, alpha, dimensions and byte limits, canonical UUID paths, prompt limits, binary export, caller stream ownership, ZIP round trips, referenced-file selection, missing images, repeated references, existing-ID reuse and collision remapping, duplicate ZIP entries, traversal, malformed UTF-8, wrong image bytes, per-chapter limits, entry and total expansion limits, import validation before publication, staging cleanup and rejection of symlinks into unrelated private files. A compressed fixture expands beyond 250 MiB without holding that archive expansion in memory.

Run with a 256 MiB JVM heap. Temporary files are scoped to a new test directory and removed at completion. Authentication harness files are not modified.

## Limits of this harness

This is not an Android emulator or device test. Android's actual WebP codec, EXIF parser, native bitmap memory behavior, Storage Access Framework providers and WebView image rendering require device validation. The EXIF substitute reports normal orientation; it does not claim to test device EXIF decoding.

## Production contracts

- Image bytes live in private `files/illustrations/<lowercase UUID>.png` files. Original image metadata is removed by PNG normalization. Source/prompt fields are separate JSON metadata.
- Input is PNG/JPEG/WebP, at most 16 MiB, 4,096 pixels on either side and 16,777,216 total pixels. Normalization may decode at a smaller size to fit the PNG byte/memory budget. The returned dimensions are the saved dimensions.
- ZIP contains `state.json` and exactly the unique images referenced by chapter `illustrations` arrays. Limits are 50 images per chapter, 2,000 unique images per archive, 30 MiB of manuscript JSON and 250 MiB of expanded input or normalized image output plus manuscript JSON.
- Images are validated and staged before publication. A different existing image under the same ID is retained and the imported ID is remapped. The returned state carries the new IDs and actual normalized dimensions.
- `importFrom()` publishes validated immutable image files and returns state; it does not replace the manuscript. The caller performs its full schema validation, user confirmation and final manuscript save. A later cancelled/failed manuscript restoration may leave unreferenced private images. No image is automatically garbage-collected because prior manuscript snapshots may still need it.
- Caller-owned import/export streams remain open. Failed export may leave a partial file at the selected destination; callers must report failure and never label it a valid backup.
