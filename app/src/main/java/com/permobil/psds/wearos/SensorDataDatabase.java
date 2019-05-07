package com.permobil.psds.wearos;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {LocalSensorData.class}, version = 1)
public abstract class SensorDataDatabase extends RoomDatabase {
    public abstract SensorDataDao sensorDataDao();

    private static volatile SensorDataDatabase INSTANCE;

    static SensorDataDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (SensorDataDatabase.class) {
                if (INSTANCE == null) {
                    // Create database here
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            SensorDataDatabase.class, "psds_data_table")
                            .build();
                }
            }
        }
        return INSTANCE;
    }

}
