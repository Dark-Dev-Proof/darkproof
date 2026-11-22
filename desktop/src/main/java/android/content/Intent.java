package android.content;

import android.net.Uri;
import java.util.HashMap;
import java.util.Map;

/** Android's request to open or send something, as far as DarkProof's screens use it. A desktop Context acts on it. */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW", ACTION_SEND = "android.intent.action.SEND";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT", EXTRA_SUBJECT = "android.intent.extra.SUBJECT";

    private final String action; private final Uri data; private String type;
    private final Map<String, String> extras = new HashMap<>();

    public Intent(String action) { this(action, null); }
    public Intent(String action, Uri data) { this.action = action; this.data = data; }

    public Intent setType(String type) { this.type = type; return this; }
    public Intent putExtra(String name, String value) { extras.put(name, value); return this; }
    public String getAction() { return action; }
    public Uri getData() { return data; }
    public String getType() { return type; }
    public String getStringExtra(String name) { return extras.get(name); }

    /** Android lets the user pick an app; a desktop has one way to send text, so this is the request itself. */
    public static Intent createChooser(Intent target, CharSequence title) { return target; }
}
