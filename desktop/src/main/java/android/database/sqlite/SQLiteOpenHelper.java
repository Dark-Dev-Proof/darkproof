package android.database.sqlite;

import android.content.Context;
import java.io.File;

/** Opens the app's database file, making its tables on first use and bringing an older file up to this version. */
public abstract class SQLiteOpenHelper {
    private final File file; private final int version;
    private SQLiteDatabase db;

    /** [factory] is always null in DarkProof. */
    public SQLiteOpenHelper(Context context, String name, Object factory, int version) {
        this.file = new File(context.getFilesDir(), name); this.version = version;
    }

    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);

    public synchronized SQLiteDatabase getWritableDatabase() {
        if (db != null) return db;
        SQLiteDatabase d = SQLiteDatabase.open(file);
        int had = d.getVersion();
        if (had != version) {
            if (had > version) { d.close(); throw new SQLiteException("the saved data was written by a newer DarkProof (version " + had + ", this one reads " + version + ")", null); }
            d.beginTransaction();
            try { if (had == 0) onCreate(d); else onUpgrade(d, had, version); d.setVersion(version); d.setTransactionSuccessful(); } finally { d.endTransaction(); }
        }
        return db = d;
    }

    public synchronized void close() { if (db != null) { db.close(); db = null; } }
}
