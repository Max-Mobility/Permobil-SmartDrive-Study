package com.permobil.psds.wearos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.kinvey.android.Client;
import com.kinvey.android.store.DataStore;
import com.kinvey.android.sync.KinveyPushCallback;
import com.kinvey.android.sync.KinveyPushResponse;
import com.kinvey.java.KinveyException;
import com.kinvey.java.core.KinveyClientCallback;
import com.kinvey.java.store.StoreType;

import java.util.ArrayList;


public class SensorService extends Service {

    private static final String TAG = "PermobilSensorService";

    private DataStore<PSDSData> psdsDataStore;
    private Handler mHandler;
    private WakeLock mWakeLock;
    private String userIdentifier;
    private String deviceUUID;
    private LocationManager mLocationManager;
    private Runnable mHandlerTask;
    private SensorEventListener mListener;

    public static ArrayList<PSDSData.SensorData> sensorServiceDataList = new ArrayList<>();

    public SensorService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SensorService onCreate...");
        @SuppressLint("HardwareIds")
        String uuid = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );
        this.deviceUUID = uuid;
        this.mHandler = new Handler();

        Client mKinveyClient = ((App) getApplication()).getSharedClient();
        Log.d(TAG, "Kinvey Client from App.java found and set in service.");

        // Get the Kinvey Data Collection for storing data
        this.psdsDataStore = DataStore.collection("PSDSData", PSDSData.class, StoreType.SYNC, mKinveyClient);
        Log.d(TAG, "PSDSDataStore: " + this.psdsDataStore.getCollectionName());

        // Get the LocationManager so we can send last known location with the record when saving to Kinvey
        mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        Log.d(TAG, "Location Manager: " + mLocationManager);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int sensorDelay;
        int maxReportingLatency;
        // the intent that starts the service can pass the sensor delay and Max Reporting Latency
        Bundle extras = intent.getExtras();
        if (extras != null) {
            // check for sensor delay from intent
            int delay = extras.getInt(Constants.SENSOR_DELAY, 0);
            sensorDelay = delay != 0 ? delay : 40000; // 40000 us or 40 ms delay
            // check for reporting delay
            int reportingDelay = extras.getInt(Constants.MAX_REPORTING_DELAY, 0);
            maxReportingLatency = reportingDelay != 0 ? reportingDelay : 10000000; // 10 seconds default between sensor updates
            userIdentifier = getSharedPreferences(getString(R.string.shared_preference_file_key), Context.MODE_PRIVATE).getString(Constants.SAVED_STUDY_ID, "");
        } else {
            sensorDelay = 40000; // 40000 us or 40 ms delay
            maxReportingLatency = 10000000; // 10 seconds between sensor updates
            userIdentifier = null;
        }

        // Handle wake_lock so data collection can continue even when screen turns off
        // without wake_lock the service will stop bc the CPU gives up
        this._handleWakeLockSetup();

        boolean didRegisterSensors = this._registerDeviceSensors(sensorDelay, maxReportingLatency);
        Log.d(TAG, "Did register Sensors: " + didRegisterSensors);

        mHandlerTask = new Runnable() {
            @Override
            public void run() {
                _UploadDataToKinvey();
                mHandler.postDelayed(mHandlerTask, 60 * 1000);
            }
        };

        mHandlerTask.run();

        return START_STICKY; // START_STICKY is used for services that are explicitly started and stopped as needed
    }

    private void _UploadDataToKinvey() {
        Log.d(TAG, "_UploadDataToKinvey()...");
        // adding an empty check to avoid pushing the initial service starting records with no sensor_data since the intervals haven't clocked at that time
        if (sensorServiceDataList.isEmpty()) {
            Log.d(TAG, "Sensor data list is empty, so will not save/push this record.");
            return;
        }
        PSDSData data = new PSDSData();
        data.user_identifier = this.userIdentifier;
        data.device_uuid = this.deviceUUID;
        data.sensor_data = sensorServiceDataList;
        // reset the sensor data list for new values to be pushed into
        SensorService.sensorServiceDataList = new ArrayList<>();

        // if we have location permission write the location to record, if not, just print WARNING to LogCat, not sure on best handling for UX right now.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Unable to get device location because LOCATION permission has not been granted.");
            data.location = null;
        } else {
            Location loc = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) {
                PSDSLocation psdsloc = new PSDSLocation(loc.getLatitude(), loc.getLongitude(), loc.getTime());
                data.location = psdsloc;
            } else {
                data.location = null;
            }
            Log.d(TAG, "Data location: " + data.location);
        }

        try {
            psdsDataStore.save(data, new KinveyClientCallback<PSDSData>() {
                @Override
                public void onSuccess(PSDSData result) {
                    Log.d(TAG, "Entity saved to local Kinvey client");
                    // Push data to Kinvey backend.
                    psdsDataStore.push(new KinveyPushCallback() {
                        @Override
                        public void onSuccess(KinveyPushResponse kinveyPushResponse) {
                            Log.d(TAG, "Data pushed to Kinvey successfully. Check Kinvey console. Success Count = " + kinveyPushResponse.getSuccessCount());
                            sendMessageToActivity("Data service syncing data to backend successfully.");
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            Log.e(TAG, "Kinvey push failure message" +
                                    ": " + throwable.getMessage());
                            Log.e(TAG, "Kinvey push failure cause: " + throwable.getCause());
                            sendMessageToActivity(throwable.getMessage());
                        }

                        @Override
                        public void onProgress(long current, long all) {
                            Log.d(TAG, "Kinvey push progress: " + current + " / " + all);
                        }
                    });
                }

                @Override
                public void onFailure(Throwable error) {
                    Log.e(TAG, "Failed to save to Kinvey: " + error.getMessage());
                }
            });
        } catch (KinveyException ke) {
            Log.e(TAG, "Error saving kinvey record for sensor data. " + ke.getReason());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (this.mWakeLock != null) {
            Log.d(TAG, "Releasing wakelock for SensorService.");
            this.mWakeLock.release();
        }

        // remove handler tasks
        mHandler.removeCallbacks(mHandlerTask);
    }

    public class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mListener != null) {
                ArrayList<Float> dataList = new ArrayList<>();
                for (float f : event.values) {
                    dataList.add(Float.valueOf(f));
                }
                // create new SensorServiceData
                PSDSData.SensorData data = new PSDSData.SensorData(
                        event.sensor.getType(),
                        event.timestamp,
                        dataList
                );
                SensorService.sensorServiceDataList.add(data);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // TODO Auto-generated method stub
        }

    }

    private boolean _registerDeviceSensors(int delay, int reportingLatency) {
        SensorManager mSensorManager = (SensorManager) getApplicationContext()
                .getSystemService(SENSOR_SERVICE);
        // make sure we have the sensor manager for the device
        if (mSensorManager != null) {
            Log.d(TAG, "Creating sensor listener...");
            mListener = new SensorListener();

            // register all the sensors we want to track data for
            Sensor mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (mLinearAcceleration != null)
                mSensorManager.registerListener(mListener, mLinearAcceleration, delay, reportingLatency);

            Sensor mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravity != null)
                mSensorManager.registerListener(mListener, mGravity, delay, 50000000);

            Sensor mMagneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mMagneticField != null)
                mSensorManager.registerListener(mListener, mMagneticField, delay, reportingLatency);

            Sensor mRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (mRotationVector != null)
                mSensorManager.registerListener(mListener, mRotationVector, delay, reportingLatency);

            Sensor mGameRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (mGameRotationVector != null)
                mSensorManager.registerListener(mListener, mGameRotationVector, delay, reportingLatency);

            Sensor mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (mGyroscope != null)
                mSensorManager.registerListener(mListener, mGyroscope, delay, reportingLatency);

            Sensor mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximity != null)
                mSensorManager.registerListener(mListener, mProximity, delay, reportingLatency);

            Sensor mOffBodyDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
            if (mOffBodyDetect != null)
                mSensorManager.registerListener(mListener, mOffBodyDetect, delay, reportingLatency);
        } else {
            Log.e(TAG, "Sensor Manager was not found, so sensor service is unable to register sensor listener events.");
        }

        return true;
    }

    private void _handleWakeLockSetup() {
        PowerManager mgr = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PermobilWear:SensorServiceWakeLock");
        mWakeLock.acquire();
        Log.d(TAG, "PermobilWear:SensorServiceWakeLock has been acquired.");
    }

    private void sendMessageToActivity(String msg) {
        Intent intent = new Intent(Constants.SENSOR_SERVICE_MESSAGE_INTENT_KEY);
        // You can also include some extra data.
        intent.putExtra(Constants.SENSOR_SERVICE_MESSAGE, msg);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

}


