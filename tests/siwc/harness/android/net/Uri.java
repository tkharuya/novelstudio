package android.net;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
/** JVM-only subset used to exercise native OAuth parsing; never packaged. */
public final class Uri {
    private final URI value;
    private Uri(String text) { value = URI.create(text); }
    public static Uri parse(String text) { return new Uri(text); }
    public String getPath() { return value.getPath(); }
    public String getQueryParameter(String key) {
        List<String> values = getQueryParameters(key);
        return values.isEmpty() ? null : values.get(0);
    }
    public List<String> getQueryParameters(String key) {
        List<String> values = new ArrayList<>();
        String raw = value.getRawQuery();
        if (raw != null) for (String item : raw.split("&")) {
            String[] pair = item.split("=", 2);
            if (key.equals(decode(pair[0]))) values.add(pair.length == 1 ? "" : decode(pair[1]));
        }
        return values;
    }
    private static String decode(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    public Builder buildUpon() { return new Builder(value.toString()); }
    public String toString() { return value.toString(); }
    public static final class Builder {
        private final StringBuilder text;
        Builder(String value) { text = new StringBuilder(value); }
        public Builder appendQueryParameter(String name, String value) {
            try {
                text.append(text.indexOf("?") < 0 ? '?' : '&').append(URLEncoder.encode(name,"UTF-8")).append('=').append(URLEncoder.encode(value,"UTF-8"));
                return this;
            } catch(Exception e) { throw new IllegalArgumentException(e); }
        }
        public Uri build() { return Uri.parse(text.toString()); }
    }
}
