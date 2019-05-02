package com.permobil.psds.wearos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.kinvey.android.Client;
import com.kinvey.android.store.DataStore;
import com.kinvey.android.sync.KinveyPushCallback;
import com.kinvey.android.sync.KinveyPushResponse;
import com.kinvey.java.KinveyException;
import com.kinvey.java.core.KinveyClientCallback;
import com.kinvey.java.store.StoreType;

import java.util.ArrayList;

import io.sentry.Sentry;
import io.sentry.event.UserBuilder;

public class SensorService extends Service {

    private static final String TAG = "PermobilSensorService";
    private static final int NOTIFICATION_ID = 543;
    private static final int sensorDelay = 40000; // 40000 us or 40 ms delay
    private static final int maxReportingLatency = 1000000; // 1 seconds between sensor updates

    private DataStore<PSDSData> psdsDataStore;
    private Handler mHandler;
    private WakeLock mWakeLock;
    private String userIdentifier;
    private String deviceUUID;
    private LocationManager mLocationManager;
    private Runnable mSaveTask;
    private Runnable mPushTask;
    private SensorEventListener mListener;

    public boolean isPushing = false;
    public static boolean isServiceRunning = false;
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
        startServiceWithNotification();

        Log.d(TAG, "SensorService onCreate...");
        @SuppressLint("HardwareIds")
        String uuid = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        this.deviceUUID = uuid;
        this.mHandler = new Handler();

        Client mKinveyClient = ((App) getApplication()).getSharedClient();
        Log.d(TAG, "Kinvey Client from App.java found and set in service.");

        // Get the Kinvey Data Collection for storing data
        this.psdsDataStore = DataStore.collection("PSDSData", PSDSData.class, StoreType.SYNC, mKinveyClient);
        Log.d(TAG, "PSDSDataStore: " + this.psdsDataStore.getCollectionName());

        // Get the LocationManager so we can send last known location with the record
        // when saving to Kinvey
        mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        Log.d(TAG, "Location Manager: " + mLocationManager);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // user identifier should always be in sharedPreferences
        userIdentifier = getSharedPreferences(getString(R.string.shared_preference_file_key), Context.MODE_PRIVATE)
                .getString(Constants.SAVED_STUDY_ID, "");

        // Set the user in the current context.
        Sentry.getContext().setUser(new UserBuilder().setId(userIdentifier).build());

        // Handle wake_lock so data collection can continue even when screen turns off
        // without wake_lock the service will stop bc the CPU gives up
        this._handleWakeLockSetup();

        boolean didRegisterSensors = this._registerDeviceSensors(sensorDelay, maxReportingLatency);
        Log.d(TAG, "Did register Sensors: " + didRegisterSensors);

        mSaveTask = new Runnable() {
            @Override
            public void run() {
                _SaveDataToKinveyLocal();
                mHandler.postDelayed(mSaveTask, 10 * 1000);
            }
        };
        mPushTask = new Runnable() {
            @Override
            public void run() {
                _PushDataToKinveyRemote();
                mHandler.postDelayed(mPushTask, 60 * 1000);
            }
        };

        mSaveTask.run();
        mPushTask.run();

        if (intent != null && intent.getAction().equals(Constants.ACTION_START_SERVICE)) {
            startServiceWithNotification();
        } else {
            stopMyService();
        }

        return START_STICKY; // START_STICKY is used for services that are explicitly started and stopped as
        // needed
    }

    private void _PushDataToKinveyRemote() {
        Log.d(TAG, "_PushDataToKinveyRemote()...");
        if (isPushing) {
            Log.d(TAG, "already pushing");
            return;
        }
        if (psdsDataStore.syncCount() == 0) {
            Log.d(TAG, "no data needs pushing - clearing the data store");
            psdsDataStore.clear();
            return;
        }
        isPushing = true;
        // Push data to Kinvey backend.
        psdsDataStore.push(new KinveyPushCallback() {
            @Override
            public void onSuccess(KinveyPushResponse kinveyPushResponse) {
                isPushing = false;
                Log.d(TAG, "Data pushed to Kinvey successfully. Check Kinvey console. Success Count = "
                        + kinveyPushResponse.getSuccessCount());
                sendMessageToActivity("Data service syncing data to backend successfully.");
            }

            @Override
            public void onFailure(Throwable throwable) {
                isPushing = false;
                Log.e(TAG, "Kinvey push failure message" + ": " + throwable.getMessage());
                Log.e(TAG, "Kinvey push failure cause: " + throwable.getCause());
                sendMessageToActivity(throwable.getMessage());
            }

            @Override
            public void onProgress(long current, long all) {
                Log.d(TAG, "Kinvey push progress: " + current + " / " + all);
            }
        });
    }

    private void _SaveDataToKinveyLocal() {
        Log.d(TAG, "_SaveDataToKinveyLocal()...");
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
                }

                @Override
                public void onFailure(Throwable error) {
                    Log.e(TAG, "Failed to save to Kinvey: " + error.getMessage());
                    Sentry.capture(error);
                }
            });
        } catch (KinveyException ke) {
            Log.e(TAG, "Error saving kinvey record for sensor data. " + ke.getReason());
            Sentry.capture(ke);
        }
    }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        super.onDestroy();
        if (this.mWakeLock != null) {
            Log.d(TAG, "Releasing wakelock for SensorService.");
            this.mWakeLock.release();
        }

        // remove handler tasks
        mHandler.removeCallbacks(mSaveTask);
        mHandler.removeCallbacks(mPushTask);
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
                PSDSData.SensorData data = new PSDSData.SensorData(event.sensor.getType(), event.timestamp, dataList);
                SensorService.sensorServiceDataList.add(data);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // TODO Auto-generated method stub
        }

    }

    private boolean _registerDeviceSensors(int delay, int reportingLatency) {
        SensorManager mSensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
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
                mSensorManager.registerListener(mListener, mGravity, delay, reportingLatency);

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


    private void startServiceWithNotification() {
        if (isServiceRunning) return;
        isServiceRunning = true;

        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setAction(Constants.ACTION_START_SERVICE);  // A string containing the action name
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Bitmap icon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_round);

        // create the notification channel
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = Constants.NOTIFICATION_CHANNEL;
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel notificationChannel = new NotificationChannel(channelId, Constants.NOTIFICATION_CHANNEL, importance);
        notificationChannel.enableLights(false);
        notificationChannel.enableVibration(false);
        notificationManager.createNotificationChannel(notificationChannel);

        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setTicker(getResources().getString(R.string.app_name))
                .setContentText("Permobil Sensor Data Study is collecting data.")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setChannelId(channelId)
                .build();
        notification.flags = notification.flags | Notification.FLAG_NO_CLEAR; // NO_CLEAR makes the notification stay when the user performs a "delete all" command
        startForeground(NOTIFICATION_ID, notification);
    }

    private void stopMyService() {
        stopForeground(true);
        stopSelf();
        isServiceRunning = false;
    }

}
