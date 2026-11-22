package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Named values the screens save and read back. */
public final class Bundle {
    private final Map<String, Object> values = new HashMap<>();

    public void putInt(String key, int value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public void putStringArrayList(String key, ArrayList<String> value) { values.put(key, value); }
    public int getInt(String key) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : 0; }
    public boolean getBoolean(String key) { Object v = values.get(key); return v instanceof Boolean && (Boolean) v; }
    public String getString(String key) { Object v = values.get(key); return v instanceof String ? (String) v : null; }
    @SuppressWarnings("unchecked") public ArrayList<String> getStringArrayList(String key) { Object v = values.get(key); return v instanceof ArrayList ? (ArrayList<String>) v : null; }
}
