package android.database;

import java.io.Closeable;

/** The rows a query found, one at a time. Columns are numbered from 0. */
public interface Cursor extends Closeable {
    boolean moveToNext();
    int getCount();
    boolean isNull(int column);
    String getString(int column);
    long getLong(int column);
    int getInt(int column);
    byte[] getBlob(int column);
    @Override void close();
}
