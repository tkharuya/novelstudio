package android.util;
/** JVM-only Android shim; never packaged in the app. */
public final class Base64 {
    public static final int URL_SAFE = 8, NO_WRAP = 2, NO_PADDING = 1;
    public static String encodeToString(byte[] value, int flags) {
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        return ((flags & NO_PADDING) != 0 ? e.withoutPadding() : e).encodeToString(value);
    }
    public static byte[] decode(String value, int flags) {
        return ((flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(value);
    }
}
