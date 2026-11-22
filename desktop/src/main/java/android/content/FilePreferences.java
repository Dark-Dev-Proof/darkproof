package android.content;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Preferences in one JSON file. Reads come from memory. A change is visible at once and written soon after, in the
 * order it was made; what is still unwritten when the app closes is written then. The file is replaced whole, so a
 * crash leaves the old one or the new one, never half of each. Only the user's own account may read it, where the
 * system has such permissions.
 */
final class FilePreferences implements SharedPreferences {
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "prefs-writer"); t.setDaemon(true); return t; });
    private static final List<FilePreferences> open = Collections.synchronizedList(new ArrayList<>());
    static { Runtime.getRuntime().addShutdownHook(new Thread(() -> { synchronized (open) { for (FilePreferences p : open) p.save(); } }, "prefs-flush")); }

    private final File file;
    private final Map<String, Object> values = new HashMap<>(); // String, Boolean, Long, or Set<String>
    private boolean dirty;

    FilePreferences(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (String k : o.keySet()) {
                    Object v = o.get(k);
                    if (v instanceof JSONArray) { Set<String> s = new HashSet<>(); JSONArray a = (JSONArray) v; for (int i = 0; i < a.length(); i++) s.add(a.getString(i)); values.put(k, s); }
                    else if (v instanceof Number) values.put(k, ((Number) v).longValue());
                    else if (v instanceof Boolean || v instanceof String) values.put(k, v);
                }
            }
        } catch (IOException | RuntimeException e) { System.err.println("DarkProof: can't read " + file + ": " + e); } // starts empty, like a new install
        open.add(this);
    }

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public synchronized String getString(String key, String def) { Object v = values.get(key); return v instanceof String ? (String) v : def; }
    @Override public synchronized Set<String> getStringSet(String key, Set<String> def) { Object v = values.get(key); return v instanceof Set ? new HashSet<>(strings(v)) : def; }
    @Override public synchronized int getInt(String key, int def) { Object v = values.get(key); return v instanceof Long ? (int) (long) (Long) v : def; }
    @Override public synchronized long getLong(String key, long def) { Object v = values.get(key); return v instanceof Long ? (Long) v : def; }
    @Override public synchronized boolean getBoolean(String key, boolean def) { Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : def; }
    @Override public synchronized boolean contains(String key) { return values.containsKey(key); }
    @Override public Editor edit() { return new Edit(); }

    @SuppressWarnings("unchecked") private static Set<String> strings(Object v) { return (Set<String>) v; }

    private static final Object REMOVE = new Object();

    private final class Edit implements Editor {
        private final Map<String, Object> changes = new HashMap<>();
        private boolean clear;

        private Editor put(String key, Object v) { changes.put(key, v == null ? REMOVE : v); return this; }
        @Override public Editor putString(String key, String value) { return put(key, value); }
        @Override public Editor putStringSet(String key, Set<String> v) { return put(key, v == null ? null : new HashSet<>(v)); }
        @Override public Editor putInt(String key, int value) { return put(key, (long) value); }
        @Override public Editor putLong(String key, long value) { return put(key, value); }
        @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }
        @Override public Editor remove(String key) { return put(key, null); }
        @Override public Editor clear() { clear = true; return this; }

        private void toMemory() {
            synchronized (FilePreferences.this) {
                if (clear) values.clear(); // as on Android: the clear comes first, whatever the order of the calls
                for (Map.Entry<String, Object> e : changes.entrySet()) { if (e.getValue() == REMOVE) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue()); }
                dirty = true;
            }
        }
        @Override public boolean commit() { toMemory(); return save(); }
        @Override public void apply() { toMemory(); writer.execute(FilePreferences.this::save); }
    }

    /** Writes the file if something changed since the last write. */
    private boolean save() {
        String text;
        synchronized (this) {
            if (!dirty) return true;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Object> e : new TreeMap<>(values).entrySet()) o.put(e.getKey(), e.getValue() instanceof Set ? new JSONArray(new java.util.TreeSet<>(strings(e.getValue()))) : e.getValue());
            text = o.toString(1);
            dirty = false;
        }
        synchronized (file) {
            try {
                Path tmp = new File(file.getPath() + ".tmp").toPath();
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
                try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { } // Windows: the profile folder's own permissions
                try { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                return true;
            } catch (IOException e) {
                synchronized (this) { dirty = true; }
                System.err.println("DarkProof: can't save " + file + ": " + e);
                return false;
            }
        }
    }
}
