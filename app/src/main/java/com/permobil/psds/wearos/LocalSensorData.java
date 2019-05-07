package com.permobil.psds.wearos;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "psds_data_table")
public class LocalSensorData {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "data")
    private String data;

    public LocalSensorData(String data) {
        this.data = data;
    }

    public String getLocalSensorData() {
        return this.data;
    }
}
