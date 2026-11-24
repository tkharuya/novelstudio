package android.media;
import java.io.IOException;
import java.io.InputStream;
/** JVM fixture: device EXIF decoding is deliberately not emulated. */
public final class ExifInterface {
    public static final String TAG_ORIENTATION="Orientation";
    public static final int ORIENTATION_NORMAL=1,ORIENTATION_FLIP_HORIZONTAL=2,ORIENTATION_ROTATE_180=3,
        ORIENTATION_FLIP_VERTICAL=4,ORIENTATION_TRANSPOSE=5,ORIENTATION_ROTATE_90=6,
        ORIENTATION_TRANSVERSE=7,ORIENTATION_ROTATE_270=8;
    public ExifInterface(InputStream input) throws IOException { }
    public int getAttributeInt(String tag,int fallback) { return fallback; }
}
