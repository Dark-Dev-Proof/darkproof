package android.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** One row to write: column names and their values, in the order they were put. */
public final class ContentValues {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public void put(String key, String value) { values.put(key, value); }
    public void put(String key, Long value) { values.put(key, value); }
    public void put(String key, Integer value) { values.put(key, value); }
    public void put(String key, Boolean value) { values.put(key, value == null ? null : (value ? 1L : 0L)); }
    public void put(String key, byte[] value) { values.put(key, value); }

    public int size() { return values.size(); }
    /** For the database: the columns and values as they were put. */
    public Map<String, Object> entries() { return values; }
}
