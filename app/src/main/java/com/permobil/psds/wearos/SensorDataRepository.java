package com.permobil.psds.wearos;

import android.app.Application;
import android.os.AsyncTask;

import androidx.lifecycle.LiveData;

import java.util.List;

public class SensorDataRepository {
    private SensorDataDao mSensorDataDao;
    private LiveData<List<LocalSensorData>> mAllRecords;

    SensorDataRepository(Application application) {
        SensorDataDatabase db = SensorDataDatabase.getDatabase(application);
        mSensorDataDao = db.sensorDataDao();
        mAllRecords = mSensorDataDao.getAllRecords();
    }

    LiveData<List<LocalSensorData>> getAllRecords() {
        return mAllRecords;
    }

    public void insert(LocalSensorData data) {
        new insertAsyncTask(mSensorDataDao).execute(data);
    }


    private static class insertAsyncTask extends AsyncTask<LocalSensorData, Void, Void> {

        private SensorDataDao mAsyncTaskDao;

        insertAsyncTask(SensorDataDao dao) {
            mAsyncTaskDao = dao;
        }

        @Override
        protected Void doInBackground(final LocalSensorData... params) {
            mAsyncTaskDao.insert(params[0]);
            return null;
        }
    }
}
