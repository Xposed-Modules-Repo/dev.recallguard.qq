package dev.recallguard.qq;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The only component that performs push-cache file I/O. Injected MCS and QQ code use Binder IPC.
 */
public final class PushCacheProvider extends ContentProvider {
    private static final String TAG = "QQRecallGuard";
    private static final String DB_NAME = "qq_recall_guard_pushes.db";
    private static final int DB_VERSION = 1;
    private static final int MAX_CANDIDATES = 200;
    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String MODULE_PACKAGE = "dev.recallguard.qq";

    private final Object lock = new Object();
    private CacheDb helper;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        helper = new CacheDb(context.getApplicationContext());
        try {
            synchronized (lock) {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.beginTransaction();
                try {
                    cleanupIfDue(db, System.currentTimeMillis());
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "push cache initialization failed open", error);
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            if (PushCacheContract.METHOD_PUT.equals(method)) {
                enforceCaller(PushRelayMcs.MCS_PACKAGE, MODULE_PACKAGE);
                return put(extras == null ? Bundle.EMPTY : extras);
            }
            if (PushCacheContract.METHOD_LIST.equals(method)) {
                enforceCaller(QQ_PACKAGE, MODULE_PACKAGE);
                return listPending();
            }
            if (PushCacheContract.METHOD_MARK_RESTORED.equals(method)) {
                enforceCaller(QQ_PACKAGE, MODULE_PACKAGE);
                return markRestored(extras == null ? Bundle.EMPTY : extras);
            }
            if (PushCacheContract.METHOD_STATUS.equals(method)) {
                enforceCaller(QQ_PACKAGE, MODULE_PACKAGE);
                return status();
            }
            throw new IllegalArgumentException("unsupported method");
        } catch (SecurityException error) {
            throw error;
        } catch (Throwable error) {
            Log.e(TAG, "push cache call failed open method=" + method, error);
            return result(false);
        }
    }

    private Bundle put(Bundle value) {
        String stableKey = value.getString(PushCacheContract.KEY_STABLE_KEY, "");
        String text = value.getString(PushCacheContract.KEY_TEXT, "");
        String actionParam = value.getString(PushCacheContract.KEY_ACTION_PARAM, "");
        if (stableKey.isEmpty() || text.trim().isEmpty() || actionParam.isEmpty()) {
            return result(false);
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return withDatabase(db -> {
                db.beginTransaction();
                try {
                    cleanupIfDue(db, now);
                    boolean exists = exists(db, stableKey);
                    if (!exists && count(db) >= MAX_CANDIDATES) {
                        db.delete("candidates", null, null);
                        resetDeadline(db, now);
                    }
                    ContentValues row = candidateValues(value, now);
                    if (exists) {
                        // Preserve restored state on a duplicate notification post.
                        db.update("candidates", row, "stable_key=?",
                                new String[]{stableKey});
                    } else {
                        db.insertOrThrow("candidates", null, row);
                    }
                    db.setTransactionSuccessful();
                    return result(true);
                } finally {
                    db.endTransaction();
                }
            });
        }
    }

    private Bundle listPending() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return withDatabase(db -> {
                ArrayList<Bundle> values = new ArrayList<>();
                db.beginTransaction();
                try {
                    cleanupIfDue(db, now);
                    try (Cursor cursor = db.query("candidates", null, "state=0", null,
                            null, null, "post_time_ms ASC", Integer.toString(MAX_CANDIDATES))) {
                        while (cursor.moveToNext()) values.add(rowToBundle(cursor));
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                Bundle out = result(true);
                out.putParcelableArrayList(PushCacheContract.KEY_CANDIDATES, values);
                return out;
            });
        }
    }

    private Bundle markRestored(Bundle value) {
        String stableKey = value.getString(PushCacheContract.KEY_STABLE_KEY, "");
        String tombstoneKey = value.getString(PushCacheContract.KEY_TOMBSTONE_KEY, "");
        if (stableKey.isEmpty() || tombstoneKey.isEmpty()) return result(false);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return withDatabase(db -> {
                db.beginTransaction();
                try {
                    cleanupIfDue(db, now);
                    if (alreadyRestored(db, stableKey, tombstoneKey)) {
                        db.setTransactionSuccessful();
                        return result(true);
                    }
                    ContentValues update = new ContentValues();
                    update.put("state", 1);
                    update.put("tombstone_key", tombstoneKey);
                    update.put("restored_at_ms", now);
                    int changed = db.update("candidates", update,
                            "stable_key=? AND state=0", new String[]{stableKey});
                    db.setTransactionSuccessful();
                    return result(changed == 1);
                } finally {
                    db.endTransaction();
                }
            });
        }
    }

    private Bundle status() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            return withDatabase(db -> {
                db.beginTransaction();
                try {
                    cleanupIfDue(db, now);
                    Bundle out = result(true);
                    out.putInt("count", count(db));
                    out.putLong("deadline_ms", readMeta(db, "cleanup_deadline_ms", 0L));
                    db.setTransactionSuccessful();
                    return out;
                } finally {
                    db.endTransaction();
                }
            });
        }
    }

    private Bundle withDatabase(DbOperation operation) {
        try {
            return operation.run(helper.getWritableDatabase());
        } catch (SQLiteException first) {
            Log.e(TAG, "push cache database damaged; resetting", first);
            Context context = getContext();
            try {
                if (helper != null) helper.close();
                if (context != null) context.deleteDatabase(DB_NAME);
                helper = new CacheDb(context.getApplicationContext());
                return operation.run(helper.getWritableDatabase());
            } catch (Throwable second) {
                Log.e(TAG, "push cache reset failed open", second);
                return result(false);
            }
        }
    }

    private void enforceCaller(String... expectedPackages) {
        String callingPackage = getCallingPackage();
        int uid = Binder.getCallingUid();
        Context context = getContext();
        PackageManager manager = context == null ? null : context.getPackageManager();
        String[] packages = manager == null ? null : manager.getPackagesForUid(uid);
        boolean accepted = false;
        if (packages != null && callingPackage != null) {
            List<String> owned = Arrays.asList(packages);
            for (String expectedPackage : expectedPackages) {
                if (expectedPackage.equals(callingPackage)
                        && owned.contains(expectedPackage)) {
                    accepted = true;
                    break;
                }
            }
        }
        if (!accepted) {
            throw new SecurityException("unexpected push-cache caller uid=" + uid);
        }
    }

    private static ContentValues candidateValues(Bundle value, long now) {
        ContentValues row = new ContentValues();
        row.put("stable_key", value.getString(PushCacheContract.KEY_STABLE_KEY, ""));
        row.put("push_global_id", value.getString(PushCacheContract.KEY_PUSH_GLOBAL_ID, ""));
        row.put("text", value.getString(PushCacheContract.KEY_TEXT, ""));
        row.put("action_param", value.getString(PushCacheContract.KEY_ACTION_PARAM, ""));
        row.put("jump_type", value.getString(PushCacheContract.KEY_JUMP_TYPE, ""));
        row.put("from_uin", value.getString(PushCacheContract.KEY_FROM_UIN, ""));
        row.put("group_code", value.getString(PushCacheContract.KEY_GROUP_CODE, ""));
        row.put("group_uin", value.getString(PushCacheContract.KEY_GROUP_UIN, ""));
        row.put("peer_uin", value.getString(PushCacheContract.KEY_PEER_UIN, ""));
        row.put("chat_type", value.getInt(PushCacheContract.KEY_CHAT_TYPE, 0));
        row.put("push_msg_seq", value.getLong(PushCacheContract.KEY_PUSH_MSG_SEQ, 0L));
        row.put("post_time_ms", value.getLong(PushCacheContract.KEY_POST_TIME_MS, now));
        row.put("msg_time_ms", value.getLong(PushCacheContract.KEY_MSG_TIME_MS, now));
        row.put("updated_at_ms", now);
        return row;
    }

    private static Bundle rowToBundle(Cursor cursor) {
        Bundle value = new Bundle();
        putString(cursor, value, "stable_key", PushCacheContract.KEY_STABLE_KEY);
        putString(cursor, value, "push_global_id", PushCacheContract.KEY_PUSH_GLOBAL_ID);
        putString(cursor, value, "text", PushCacheContract.KEY_TEXT);
        putString(cursor, value, "action_param", PushCacheContract.KEY_ACTION_PARAM);
        putString(cursor, value, "jump_type", PushCacheContract.KEY_JUMP_TYPE);
        putString(cursor, value, "from_uin", PushCacheContract.KEY_FROM_UIN);
        putString(cursor, value, "group_code", PushCacheContract.KEY_GROUP_CODE);
        putString(cursor, value, "group_uin", PushCacheContract.KEY_GROUP_UIN);
        putString(cursor, value, "peer_uin", PushCacheContract.KEY_PEER_UIN);
        value.putInt(PushCacheContract.KEY_CHAT_TYPE,
                cursor.getInt(cursor.getColumnIndexOrThrow("chat_type")));
        value.putLong(PushCacheContract.KEY_PUSH_MSG_SEQ,
                cursor.getLong(cursor.getColumnIndexOrThrow("push_msg_seq")));
        value.putLong(PushCacheContract.KEY_POST_TIME_MS,
                cursor.getLong(cursor.getColumnIndexOrThrow("post_time_ms")));
        value.putLong(PushCacheContract.KEY_MSG_TIME_MS,
                cursor.getLong(cursor.getColumnIndexOrThrow("msg_time_ms")));
        return value;
    }

    private static void putString(Cursor cursor, Bundle value, String column, String key) {
        value.putString(key, cursor.getString(cursor.getColumnIndexOrThrow(column)));
    }

    private static boolean exists(SQLiteDatabase db, String stableKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM candidates WHERE stable_key=? LIMIT 1",
                new String[]{stableKey})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean alreadyRestored(SQLiteDatabase db, String stableKey,
            String tombstoneKey) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM candidates WHERE stable_key=? AND state=1 "
                        + "AND tombstone_key=? LIMIT 1",
                new String[]{stableKey, tombstoneKey})) {
            return cursor.moveToFirst();
        }
    }

    private static int count(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM candidates", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static void cleanupIfDue(SQLiteDatabase db, long now) {
        long deadline = readMeta(db, "cleanup_deadline_ms", 0L);
        if (deadline <= 0L) {
            resetDeadline(db, now);
        } else if (now >= deadline) {
            db.delete("candidates", null, null);
            resetDeadline(db, now);
        }
    }

    private static void resetDeadline(SQLiteDatabase db, long now) {
        writeMeta(db, "cleanup_baseline_ms", now);
        writeMeta(db, "cleanup_deadline_ms", now + RETENTION_MS);
    }

    private static long readMeta(SQLiteDatabase db, String key, long fallback) {
        try (Cursor cursor = db.rawQuery("SELECT long_value FROM metadata WHERE key=?",
                new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : fallback;
        }
    }

    private static void writeMeta(SQLiteDatabase db, String key, long value) {
        ContentValues row = new ContentValues();
        row.put("key", key);
        row.put("long_value", value);
        db.insertWithOnConflict("metadata", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static Bundle result(boolean ok) {
        Bundle value = new Bundle();
        value.putBoolean(PushCacheContract.KEY_OK, ok);
        return value;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { throw unsupported(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw unsupported(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw unsupported();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("use ContentProvider.call");
    }

    private interface DbOperation { Bundle run(SQLiteDatabase db); }

    private static final class CacheDb extends SQLiteOpenHelper {
        CacheDb(Context context) { super(context, DB_NAME, null, DB_VERSION); }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE candidates ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "stable_key TEXT NOT NULL UNIQUE,"
                    + "push_global_id TEXT NOT NULL DEFAULT '',"
                    + "text TEXT NOT NULL,"
                    + "action_param TEXT NOT NULL,"
                    + "jump_type TEXT NOT NULL DEFAULT '',"
                    + "from_uin TEXT NOT NULL DEFAULT '',"
                    + "group_code TEXT NOT NULL DEFAULT '',"
                    + "group_uin TEXT NOT NULL DEFAULT '',"
                    + "peer_uin TEXT NOT NULL,"
                    + "chat_type INTEGER NOT NULL,"
                    + "push_msg_seq INTEGER NOT NULL DEFAULT 0,"
                    + "post_time_ms INTEGER NOT NULL,"
                    + "msg_time_ms INTEGER NOT NULL,"
                    + "state INTEGER NOT NULL DEFAULT 0,"
                    + "tombstone_key TEXT,"
                    + "restored_at_ms INTEGER,"
                    + "updated_at_ms INTEGER NOT NULL)" );
            db.execSQL("CREATE UNIQUE INDEX one_restore_per_tombstone "
                    + "ON candidates(tombstone_key) WHERE tombstone_key IS NOT NULL");
            db.execSQL("CREATE INDEX pending_by_time "
                    + "ON candidates(state, post_time_ms)");
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, long_value INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("unsupported cache schema upgrade");
        }
    }
}
