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
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat.Builder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.kinvey.android.Client;
import com.kinvey.android.store.DataStore;
import com.kinvey.android.sync.KinveyPushCallback;
import com.kinvey.android.sync.KinveyPushResponse;
import com.kinvey.java.KinveyException;
import com.kinvey.java.core.KinveyClientCallback;
import com.kinvey.java.store.StoreType;

import java.util.ArrayList;
import java.util.Objects;

import io.sentry.Sentry;
import io.sentry.event.UserBuilder;

public class SensorService extends Service {

    private static final String TAG = "PermobilSensorService";
    private static final int NOTIFICATION_ID = 543;
    private static final int sensorDelay = 100000; // 100000 us or 100 ms delay
    private static final int maxReportingLatency = 10000000; // 10 seconds between sensor updates

    private Builder notificationBuilder;
    private NotificationManager notificationManager;
    private Notification notification;
    private DataStore<PSDSData> psdsDataStore;
    private Handler mHandler;
    private WakeLock mWakeLock;
    private String userIdentifier;
    private String deviceUUID;
    private LocationManager mLocationManager;
    private Runnable mKinveyTask;
    private TriggerSensorListener mTriggerListener;
    private SensorEventListener mListener;
    private SensorManager mSensorManager;
    private WifiManager mWifiManager;

    private Sensor mSignificantMotion;

    // activity detection
    public boolean personIsActive = false;
    public boolean watchBeingWorn = false;

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
        Log.d(TAG, "SensorService onCreate...");
        startServiceWithNotification();

        @SuppressLint("HardwareIds")
        String uuid = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        this.deviceUUID = uuid;
        this.mHandler = new Handler();

        Client mKinveyClient = ((App) getApplication()).getSharedClient();

        // Get the Kinvey Data Collection for storing data
        this.psdsDataStore = DataStore.collection("PSDSData", PSDSData.class, StoreType.SYNC, mKinveyClient);
        // Get the LocationManager so we can send last known location with the record when saving to Kinvey
        mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        // Get the WifiManager so we can turn on wifi before saving
        mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // user identifier should always be in sharedPreferences
        userIdentifier = getSharedPreferences(getString(R.string.shared_preference_file_key), Context.MODE_PRIVATE)
                .getString(Constants.SAVED_STUDY_ID, "");
        // Set the user in the current context.
        Sentry.getContext().setUser(new UserBuilder().setId(userIdentifier).build());

        if (intent != null && Objects.requireNonNull(intent.getAction()).equals(Constants.ACTION_START_SERVICE)) {
            startServiceWithNotification();
        } else {
            stopMyService();
        }

        // Handle wake_lock so data collection can continue even when screen turns off
        // without wake_lock the service will stop bc the CPU gives up
        this._handleWakeLockSetup();

        boolean didRegisterSensors = this._registerDeviceSensors(sensorDelay, maxReportingLatency);
        Log.d(TAG, "Did register Sensors: " + didRegisterSensors);


        mKinveyTask = new Runnable() {
            @Override
            public void run() {
                _SaveDataToKinvey();
                mHandler.postDelayed(mKinveyTask, 5 * 60 * 1000); // save every 5 minutes
            }
        };

        mKinveyTask.run();

        return START_STICKY; // START_STICKY is used for services that are explicitly started and stopped as
        // needed
    }

    private void _SaveDataToKinvey() {
        Log.d(TAG, "_SaveDataToKinvey()...");
        // adding an empty check to avoid pushing the initial service starting records with no sensor_data since the intervals haven't clocked at that time
        if (sensorServiceDataList.isEmpty()) {
            Log.d(TAG, "Sensor data list is empty, so will not save/push this record.");
            return;
        }
        if (psdsDataStore.syncCount() == 0) {
            Log.d(TAG, "No unsent data, clearing the storage.");
            psdsDataStore.clear(); // we have nothing unsent, clear the storage
        }
        mWifiManager.setWifiEnabled(true);
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
                data.location = new PSDSLocation(loc.getLatitude(), loc.getLongitude(), loc.getTime());
            } else {
                data.location = null;
            }
            Log.d(TAG, "Data location: " + data.location);
        }

        try {
            psdsDataStore.save(data, new KinveyClientCallback<PSDSData>() {
                @Override
                public void onSuccess(PSDSData result) {
                    // Push data to Kinvey backend.
                    psdsDataStore.push(new KinveyPushCallback() {
                        @Override
                        public void onSuccess(KinveyPushResponse kinveyPushResponse) {
                            Log.d(TAG, "Data pushed to Kinvey successfully. Success Count = "
                                    + kinveyPushResponse.getSuccessCount());
                            sendMessageToActivity("Data service syncing data to backend successfully.");
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            Log.e(TAG, "Kinvey push failure message" + ": " + throwable.getMessage());
                            Log.e(TAG, "Kinvey push failure cause: " + throwable.getCause());
                            sendMessageToActivity(throwable.getMessage());
                            Sentry.capture(throwable);
                        }

                        @Override
                        public void onProgress(long current, long all) {
                            Log.d(TAG, "Kinvey push progress: " + current + " / " + all);
                        }
                    });

                }

                @Override
                public void onFailure(Throwable throwable) {
                    Log.e(TAG, "Kinvey SAVE() Failure: " + throwable.getMessage());
                    sendMessageToActivity("Error saving data: " + throwable.getMessage());
                    Sentry.capture(throwable);
                }
            });

        } catch (KinveyException ke) {
            Log.e(TAG, "Error saving kinvey record for sensor data. " + ke.getReason());
            sendMessageToActivity("Error trying to save data to backend: " + ke.getExplanation());
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
        mHandler.removeCallbacks(mKinveyTask);
    }

    public class TriggerSensorListener extends TriggerEventListener {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_SIGNIFICANT_MOTION) {
                //Log.d(TAG, "Significant motion detected!");
                //sendMessageToActivity("Significant Motion Detected!");
                personIsActive = true;
                mSensorManager.requestTriggerSensor(this, mSignificantMotion);
            }
        }
    }

    public class SensorListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mListener != null) {
                ArrayList<Float> dataList = new ArrayList<>();
                for (float f : event.values) {
                    dataList.add(f);
                }
                updateActivity(event);
                if (hasBeenActive()) {
                    // create new SensorServiceData
                    PSDSData.SensorData data = new PSDSData.SensorData(event.sensor.getType(), event.timestamp, dataList);
                    SensorService.sensorServiceDataList.add(data);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // TODO Auto-generated method stub
        }

        public void updateActivity(SensorEvent event) {
            // check if the watch is moving
            if (event.sensor.getType() == Sensor.TYPE_MOTION_DETECT) {
                personIsActive = true;
            } else if (event.sensor.getType() == Sensor.TYPE_STATIONARY_DETECT) {
                personIsActive = false;
            }
            // check if the user is wearing the watch
            if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                watchBeingWorn = (event.values[0] != 0.0); // 1.0 => device is on body, 0.0 => device is off body
                //sendMessageToActivity("OFF_BODY Change: "+watchBeingWorn);
            }
        }

        public boolean hasBeenActive() {
            //Log.d(TAG, "PersonIsActive: " + personIsActive + "; watchBeingWorn: " + watchBeingWorn);
            return personIsActive && watchBeingWorn;
        }

    }

    private boolean _registerDeviceSensors(int delay, int reportingLatency) {
        mSensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
        // make sure we have the sensor manager for the device
        if (mSensorManager != null) {
            Log.d(TAG, "Creating sensor listener...");
            mListener = new SensorListener();
            mTriggerListener = new TriggerSensorListener();

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
            if (mProximity != null) {
                mSensorManager.registerListener(mListener, mProximity, delay, reportingLatency);
                //Log.d(TAG, "Have TYPE_PROXIMITY");
            }

            Sensor mOffBodyDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
            if (mOffBodyDetect != null)
                mSensorManager.registerListener(mListener, mOffBodyDetect, delay, reportingLatency);

            //Log.d(TAG, "Checking TYPE_STATIONARY_DETECT");
            Sensor mStationaryDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
            if (mStationaryDetect != null) {
                mSensorManager.registerListener(mListener, mStationaryDetect, delay, reportingLatency);
                Log.d(TAG, "Have TYPE_STATIONARY_DETECT");
            }

            //Log.d(TAG, "Checking TYPE_SIGNIFICANT_MOTION");
            mSignificantMotion = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            if (mSignificantMotion != null) {
                mSensorManager.requestTriggerSensor(mTriggerListener, mSignificantMotion);
                Log.d(TAG, "Have TYPE_SIGNIFICANT_MOTION");
            }

            //Log.d(TAG, "Checking TYPE_MOTION_DETECT");
            Sensor mMotionDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT);
            if (mMotionDetect != null) {
                mSensorManager.registerListener(mListener, mMotionDetect, delay, reportingLatency);
                //Log.d(TAG, "Have TYPE_MOTION_DETECT");
            }
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
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = Constants.NOTIFICATION_CHANNEL;
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel notificationChannel = new NotificationChannel(channelId, Constants.NOTIFICATION_CHANNEL, importance);
        notificationChannel.enableLights(false);
        notificationChannel.enableVibration(false);
        notificationManager.createNotificationChannel(notificationChannel);

        // create the notification builder
        notificationBuilder = new Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setTicker(getResources().getString(R.string.app_name))
                .setContentText("Permobil Sensor Data Study is collecting data.")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setChannelId(channelId);

        // create the notification
        notification = notificationBuilder.build();
        notification.flags = notification.flags | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR; // NO_CLEAR makes the notification stay when the user performs a "delete all" command
        startForeground(NOTIFICATION_ID, notification);
    }

    private void stopMyService() {
        stopForeground(true);
        stopSelf();
        isServiceRunning = false;
    }

}
