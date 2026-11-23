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

    /** A file from a newer version: refused, as Android refuses it unless the helper says otherwise. */
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("the saved data was written by a newer DarkProof (version " + oldVersion + ", this one reads " + newVersion + ")", null);
    }

    public synchronized SQLiteDatabase getWritableDatabase() {
        if (db != null) return db;
        SQLiteDatabase d = SQLiteDatabase.open(file);
        int had = d.getVersion();
        if (had != version) {
            boolean ok = false;
            d.beginTransaction();
            try {
                if (had == 0) onCreate(d); else if (had > version) onDowngrade(d, had, version); else onUpgrade(d, had, version);
                d.setVersion(version); d.setTransactionSuccessful(); ok = true;
            } finally { d.endTransaction(); if (!ok) d.close(); }
        }
        return db = d;
    }

    public synchronized void close() { if (db != null) { db.close(); db = null; } }
}
