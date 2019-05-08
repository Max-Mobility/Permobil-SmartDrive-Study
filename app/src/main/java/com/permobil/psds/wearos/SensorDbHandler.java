package com.permobil.psds.wearos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
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

    public SensorDbHandler(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE_SENSORDATA = "CREATE TABLE " + TABLE_NAME + "(" + KEY_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT," + KEY_DATA + " TEXT)";
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
        Log.d(TAG, "Saving new RECORD to SQL Table: " + dataAsJson);

        values.put(KEY_DATA, dataAsJson);
        db.insert(TABLE_NAME, null, values);
        db.close();
    }

    public List<SqlRowResult> getAllRecords() {
        List recordList = new ArrayList();
        String selectQuery = "SELECT * FROM " + TABLE_NAME + " ORDER BY " + KEY_ID + " DESC";
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(selectQuery, null);
        Gson gson = new Gson();

        // if TABLE has rows
        if (cursor.moveToFirst()) {
            // Loop through the table rows
            do {
                PSDSData record = gson.fromJson(cursor.getString(1), PSDSData.class);
                Log.d(TAG, "record id: " + cursor.getInt(0));

                SqlRowResult result = new SqlRowResult(cursor.getInt(0), record);
                // Add record to list
                recordList.add(result);
            } while (cursor.moveToNext());
        }

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
        db.delete(TABLE_NAME, KEY_ID + "=?", new String[]{String.valueOf(id)});
        Log.d(TAG, "Deleted record from database with id: " + id);
        db.close();
    }

    public void deleteDatabase_DO_YOU_KNOW_WHAT_YOU_ARE_DOING() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(DATABASE_NAME, null, null);
        Log.d(TAG, "Deleting entire database.");
        db.close();
    }


    // class to describe the shape of the record with id so we can use the ID in the service when iterating the records to perform deletes from the service
    public class SqlRowResult {
        public int id;
        public PSDSData data;

        public SqlRowResult() {
        }

        public SqlRowResult(int id, PSDSData data) {
            this.id = id;
            this.data = data;
        }

        public PSDSData getPSDSData() {
            return this.data;
        }

        public int getId() {
            return this.id;
        }
    }
}
