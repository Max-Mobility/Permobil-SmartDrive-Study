package com.permobil.psds.wearos;

import android.content.ContentValues;
import android.content.Context;
import android.database.AbstractWindowedCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

public class SensorDbHandler extends SQLiteOpenHelper {
    private static final String TAG = "SensorDbHandler";
    // Database Version
    private static final int DATABASE_VERSION = 1;
    // Database Name
    private static final String DATABASE_NAME = "PermobilStudyDataDb";
    // Table name
    private static final String TABLE_NAME = "SENSOR_DATA";
    // Table Columns names
    private static final String KEY_ID = "id";
    private static final String KEY_DATA = "data";
    private static final String KEY_DATA_ID = "uuid";

    public SensorDbHandler(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE_SENSORDATA = "CREATE TABLE " + TABLE_NAME + "(" + KEY_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT," + KEY_DATA + " TEXT, " + KEY_DATA_ID + " TEXT)";
        db.execSQL(CREATE_TABLE_SENSORDATA);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop older table if existed
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        // Create tables again
        onCreate(db);
    }

    // Insert values to the table
    public void addRecord(PSDSData data) {
        SQLiteDatabase db = this.getReadableDatabase();
        ContentValues values = new ContentValues();

        Gson gson = new Gson();
        String dataAsJson = gson.toJson(data);
        Log.d(TAG, "Saving new RECORD to SQL Table: " + data._id);

        values.put(KEY_DATA_ID, data._id);
        values.put(KEY_DATA, dataAsJson);
        db.insert(TABLE_NAME, null, values);
        db.close();
    }

    public List<PSDSData> getRecords(int numRecords) {
        List recordList = new ArrayList();
        String selectQuery = "SELECT * FROM " + TABLE_NAME + " ORDER BY " + KEY_ID + " ASC";
        if (numRecords > 0) {
            selectQuery += " LIMIT " + numRecords;
        }
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);

        CursorWindow cw = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            cw = new CursorWindow("getRecordsCursor", 10000000);
        } else {
            cw = new CursorWindow("getRecordsCursor");
        }
        AbstractWindowedCursor ac = (AbstractWindowedCursor) cursor;
        ac.setWindow(cw);

        Gson gson = new Gson();

        // if TABLE has rows
        if (cursor.moveToFirst()) {
            // Loop through the table rows
            do {
                int index = cursor.getInt(0);
                PSDSData record = gson.fromJson(cursor.getString(1), PSDSData.class);
                String uuid = cursor.getString(2);
                Log.d(TAG, "record id: " + index + " - " + uuid);
                // Add record to list
                recordList.add(record);
            } while (cursor.moveToNext());
        }

        cursor.close();
        db.close();
        Log.d(TAG, "Returning SQLite RecordList with record count: " + recordList.size());
        return recordList;
    }

    public long getTableRowCount() {
        SQLiteDatabase db = this.getWritableDatabase();
        long count = DatabaseUtils.queryNumEntries(db, TABLE_NAME);
        Log.d(TAG, "Current SQLite Table Row Count: " + count);
        db.close();
        return count;
    }

    public void deleteRecord(String id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, KEY_DATA_ID + "=?", new String[]{id});
        Log.d(TAG, "Deleted record from database with id: " + id);
        db.close();
    }

    public void deleteDatabase_DO_YOU_KNOW_WHAT_YOU_ARE_DOING() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(DATABASE_NAME, null, null);
        Log.d(TAG, "Deleting entire database.");
        db.close();
    }
}
