package com.antigravity.galeritotelegram;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "galeri_telegram.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_UPLOADED = "uploaded_photos";
    public static final String COLUMN_ID = "photo_id";
    public static final String COLUMN_NAME = "photo_name";
    public static final String COLUMN_UPLOADED_AT = "uploaded_at";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_UPLOADED + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY, " +
                COLUMN_NAME + " TEXT, " +
                COLUMN_UPLOADED_AT + " INTEGER" +
                ")";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_UPLOADED);
        onCreate(db);
    }

    public synchronized boolean isUploaded(long photoId) {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE_UPLOADED,
                new String[]{COLUMN_ID},
                COLUMN_ID + " = ?",
                new String[]{String.valueOf(photoId)},
                null, null, null
        )) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    public synchronized void markUploaded(long photoId, String photoName) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_ID, photoId);
        values.put(COLUMN_NAME, photoName);
        values.put(COLUMN_UPLOADED_AT, System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_UPLOADED, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized int getUploadedCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_UPLOADED, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }
}
