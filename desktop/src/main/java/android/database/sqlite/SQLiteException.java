package android.database.sqlite;

/** The database refused something: a full disk, a broken file, bad SQL. */
public class SQLiteException extends RuntimeException {
    public SQLiteException(String message, Throwable cause) { super(message, cause); }
}
