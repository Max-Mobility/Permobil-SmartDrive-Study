package com.permobil.psds.wearos;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class SensorDbHandler extends SQLiteOpenHelper {

    // Database Version
    private static final int DATABASE_VERSION = 1;

    // Database Name
    private static final String DATABASE_NAME = "StudyDataDb";

    // Sensor Data table name
    private static final String TABLE_SENSORDATA = "sensor_data";

    // Sensor data Table Columns names
    private static final String KEY_ID = "id";
    private static final String KEY_DATA = "data";

    public SensorDbHandler(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE_SENSORDATA = "CREATE TABLE " + TABLE_SENSORDATA + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_DATA + " TEXT)";
        db.execSQL(CREATE_TABLE_SENSORDATA);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop older table if existed
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SENSORDATA);
        // Create tables again
        onCreate(db);
    }

    // Insert values to the table sensordata
    public void addRecord(SensorSqlData contact) {
        SQLiteDatabase db = this.getReadableDatabase();
        ContentValues values = new ContentValues();

        values.put(KEY_DATA, contact.getData());
        db.insert(TABLE_SENSORDATA, null, values);
        db.close();
    }
}
