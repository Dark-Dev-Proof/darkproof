package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Android's SQLite database, as far as DarkProof uses it, on the SQLite that comes with the sqlite-jdbc driver. The
 * SQL is the phone's own. As on Android, any thread may use it, one at a time, and a transaction keeps the others
 * waiting until it ends. A query's rows are read at once: DarkProof's tables are small.
 */
public final class SQLiteDatabase {
    public static final int CONFLICT_NONE = 0, CONFLICT_ROLLBACK = 1, CONFLICT_ABORT = 2, CONFLICT_FAIL = 3, CONFLICT_IGNORE = 4, CONFLICT_REPLACE = 5;
    private static final String[] CONFLICT = {"", " OR ROLLBACK", " OR ABORT", " OR FAIL", " OR IGNORE", " OR REPLACE"};

    private final Connection db;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean successful;

    private SQLiteDatabase(Connection db) { this.db = db; }

    static SQLiteDatabase open(File file) {
        try { return new SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())); }
        catch (SQLException e) { throw new SQLiteException("can't open " + file.getName() + ": " + e.getMessage(), e); }
    }

    private interface Work<T> { T run() throws SQLException; }
    private <T> T locked(Work<T> w) {
        lock.lock();
        try { return w.run(); } catch (SQLException e) { throw new SQLiteException(e.getMessage(), e); } finally { lock.unlock(); }
    }

    private static void bind(PreparedStatement s, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) s.setNull(i + 1, java.sql.Types.NULL);
            else if (a instanceof byte[]) s.setBytes(i + 1, (byte[]) a);
            else if (a instanceof Long || a instanceof Integer || a instanceof Short || a instanceof Byte) s.setLong(i + 1, ((Number) a).longValue());
            else if (a instanceof Number) s.setDouble(i + 1, ((Number) a).doubleValue());
            else if (a instanceof Boolean) s.setLong(i + 1, (Boolean) a ? 1 : 0);
            else s.setString(i + 1, a.toString());
        }
    }

    public void execSQL(String sql) { execSQL(sql, null); }

    public void execSQL(String sql, Object[] bindArgs) {
        locked(() -> { try (PreparedStatement s = db.prepareStatement(sql)) { bind(s, bindArgs); s.execute(); } return null; });
    }

    /** As on Android, every argument is compared as text. */
    public Cursor rawQuery(String sql, String[] selectionArgs) {
        return locked(() -> {
            try (PreparedStatement s = db.prepareStatement(sql)) {
                bind(s, selectionArgs);
                try (ResultSet r = s.executeQuery()) {
                    int n = r.getMetaData().getColumnCount(); List<Object[]> rows = new ArrayList<>();
                    while (r.next()) { Object[] row = new Object[n]; for (int i = 0; i < n; i++) row[i] = r.getObject(i + 1); rows.add(row); }
                    return new Rows(rows);
                }
            }
        });
    }

    /** The new row's id, or -1 if nothing was written (a row that was already there, with CONFLICT_IGNORE). */
    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm) {
        Map<String, Object> v = values.entries();
        StringBuilder sql = new StringBuilder("INSERT").append(CONFLICT[conflictAlgorithm]).append(" INTO ").append(table).append('(').append(String.join(",", v.keySet())).append(") VALUES (");
        for (int i = 0; i < v.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(')');
        return locked(() -> {
            try (PreparedStatement s = db.prepareStatement(sql.toString())) {
                bind(s, v.values().toArray());
                if (s.executeUpdate() == 0) return -1L;
            }
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) { return r.next() ? r.getLong(1) : -1L; }
        });
    }

    /** How many rows went. */
    public int delete(String table, String whereClause, String[] whereArgs) {
        return locked(() -> { try (PreparedStatement s = db.prepareStatement("DELETE FROM " + table + (whereClause == null || whereClause.isEmpty() ? "" : " WHERE " + whereClause))) { bind(s, whereArgs); return s.executeUpdate(); } });
    }

    /** Everything until {@link #endTransaction} is kept together or not at all; other threads wait. */
    public void beginTransaction() {
        lock.lock();
        try { if (lock.getHoldCount() > 1) throw new IllegalStateException("a transaction inside a transaction is not supported"); successful = false; db.setAutoCommit(false); }
        catch (SQLException | RuntimeException e) { lock.unlock(); throw e instanceof RuntimeException ? (RuntimeException) e : new SQLiteException(e.getMessage(), e); }
    }

    public void setTransactionSuccessful() { successful = true; }

    public void endTransaction() {
        try { if (successful) db.commit(); else db.rollback(); db.setAutoCommit(true); }
        catch (SQLException e) { throw new SQLiteException(e.getMessage(), e); }
        finally { lock.unlock(); }
    }

    public int getVersion() {
        return locked(() -> { try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { return r.next() ? r.getInt(1) : 0; } });
    }

    public void setVersion(int version) { execSQL("PRAGMA user_version = " + version); }

    public void close() { locked(() -> { db.close(); return null; }); }

    private static final class Rows implements Cursor {
        private final List<Object[]> rows; private int at = -1;
        Rows(List<Object[]> rows) { this.rows = rows; }
        @Override public boolean moveToNext() { return ++at < rows.size(); }
        @Override public int getCount() { return rows.size(); }
        @Override public boolean isNull(int c) { return rows.get(at)[c] == null; }
        @Override public String getString(int c) { Object v = rows.get(at)[c]; return v == null ? null : v instanceof byte[] ? new String((byte[]) v, java.nio.charset.StandardCharsets.UTF_8) : v.toString(); }
        @Override public long getLong(int c) { Object v = rows.get(at)[c]; return v instanceof Number ? ((Number) v).longValue() : v == null ? 0 : Long.parseLong(v.toString()); }
        @Override public int getInt(int c) { return (int) getLong(c); }
        @Override public byte[] getBlob(int c) { Object v = rows.get(at)[c]; return v == null ? null : v instanceof byte[] ? (byte[]) v : v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        @Override public void close() { }
    }
}
