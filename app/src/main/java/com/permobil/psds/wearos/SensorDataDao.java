package com.permobil.psds.wearos;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SensorDataDao {

    @Insert
    void insert(LocalSensorData data);

    @Query("DELETE FROM psds_data_table")
    void deleteAll();

    @Query("SELECT * from psds_data_table ORDER BY id ASC")
    LiveData<List<LocalSensorData>> getAllRecords();
}
