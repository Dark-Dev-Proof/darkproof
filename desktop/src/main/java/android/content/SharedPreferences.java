package android.content;

import java.util.Map;
import java.util.Set;

/** Android's saved preferences, as far as DarkProof uses them. On a desktop: {@link FilePreferences}. */
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    boolean getBoolean(String key, boolean defValue);
    boolean contains(String key);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        /** Saves now. False if the file couldn't be written. */
        boolean commit();
        /** Changes what is read at once and saves soon after, as on Android. */
        void apply();
    }
}
