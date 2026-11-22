package android.net;

/** An address: a web page, a mail address, a position, or on a desktop a file's path. */
public final class Uri {
    private final String text;
    private Uri(String text) { this.text = text; }
    public static Uri parse(String text) { return new Uri(text); }
    public static Uri fromFile(java.io.File f) { return new Uri(f.toURI().toString()); }
    public String getScheme() { int i = text.indexOf(':'); return i < 0 ? null : text.substring(0, i); }
    @Override public String toString() { return text; }
}
