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
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat.Builder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.sentry.Sentry;
import io.sentry.event.UserBuilder;
import okhttp3.RequestBody;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

public class SensorService extends Service {

    private static final String TAG = "PermobilSensorService";
    private static final int NOTIFICATION_ID = 543;
    private static final int NETWORK_CONNECTIVITY_TIMEOUT_MS = 60000;
    private static final int SENSOR_DELAY_DEBUG = 40 * 1000; // microseconds between sensor data
    private static final int SENSOR_DELAY_RELEASE = SensorManager.SENSOR_DELAY_NORMAL; // approx 200 ms between sensor data
    private static final int maxReportingLatency = 3 * 60 * 1000 * 1000; // 3 minutes between sensor updates in microseconds
    private static final int SAVE_TASK_PERIOD_MS = 60 * 1000; // each record will be 1 minute long
    private static final int SEND_TASK_PERIOD_MS = 10 * 1000; // send a record every 10 seconds if possible
    private static final long LOCATION_LISTENER_MIN_TIME_MS = 1 * 60 * 1000;
    private static final float LOCATION_LISTENER_MIN_DISTANCE_M = 25;
    private static final int MAX_SEND_COUNT = 1;
    private static final int MAX_NUM_ENTRIES_PER_RECORD = 5 * 5 * 60 * 5; // 5 sensors * 5 Hz * 60 seconds * 5 minutes = max length of each log

    private String userIdentifier;
    private String deviceUUID;
    private SensorDbHandler db;
    private KinveyApiService mKinveyApiService;
    private String mKinveyAuthorization;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Runnable mSendTask;
    private Runnable mSaveTask;
    private Location mLastKnownLocation;
    private LocationManager mLocationManager;
    private TriggerSensorListener mTriggerListener;
    private SensorEventListener mListener;
    private SensorManager mSensorManager;
    private Sensor mSignificantMotion;
    private Sensor mLinearAcceleration;
    private Sensor mGravity;
    private Sensor mMagneticField;
    private Sensor mRotationVector;
    private Sensor mGameRotationVector;
    private Sensor mGyroscope;
    private Sensor mProximity;
    private Sensor mOffBodyDetect;
    private Sensor mStationaryDetect;
    private Sensor mMotionDetect;
    private ConnectivityManager mConnectivityManager;
    private NetworkCallback mNetworkCallback;

    // activity detection
    public boolean personIsActive = false;
    public boolean watchBeingWorn = false;
    public boolean isServiceRunning = false;

    public boolean isDebuggable = false;

    public long numRecordsPushed = 0;
    public long numRecordsSaved = 0;

    public List<PSDSData.SensorData> sensorServiceDataList = new ArrayList<>();
    public List<PSDSLocation> sensorServiceLocationList = new ArrayList<>();

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

        // set the debuggable flag
        isDebuggable = (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));

        Log.d(TAG, "Create sensor SQLite database...");
        db = new SensorDbHandler(getApplicationContext());
        Log.d(TAG, "SQLite DB created: " + db);

        @SuppressLint("HardwareIds")
        String uuid = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        this.deviceUUID = uuid;

        // start up for service
        this.mHandlerThread = new HandlerThread("com.permobil.psds.wearos.thread");
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper());

        this.mSaveTask = new SaveRunnable();
        this.mSendTask = new SendRunnable();

        // clear the datastore (from previous app runs)
        _PurgeLocalData();

        // Get the LocationManager so we can send last known location with the record when saving to Kinvey
        mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        LocationListener mLocationListener = new LocationListener();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Unable to get device location because LOCATION permission has not been granted.");
        } else {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_LISTENER_MIN_TIME_MS,
                    LOCATION_LISTENER_MIN_DISTANCE_M,
                    mLocationListener
            );
        }

        Log.d(TAG, "gps provider enabled: " + mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
        Log.d(TAG, "network provider enabled: " + mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        Log.d(TAG, "passive provider enabled: " + mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER));
        Log.d(TAG, "providers: " + mLocationManager.getProviders(false));

        // create the retrofit instance
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(Constants.API_BASE)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();

        // create an instance of the KinveyApiService
        mKinveyApiService = retrofit.create(KinveyApiService.class);

        // save the authorization string needed for kinvey
        String authorizationToEncode = "bradwaynemartin@gmail.com:testtest";
        byte[] data = authorizationToEncode.getBytes(StandardCharsets.UTF_8);
        mKinveyAuthorization = Base64.encodeToString(data, Base64.NO_WRAP);
        mKinveyAuthorization = "Basic " + mKinveyAuthorization;

        // Get the ConnectivityManager so we can turn on wifi before saving
        mConnectivityManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        mNetworkCallback = new NetworkCallback();

        isServiceRunning = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand()..." + intent + " - " + flags + " - " + startId);
        Log.d(TAG, "isServiceRunning: " + isServiceRunning);
        if (!isServiceRunning) {
            // user identifier should always be in sharedPreferences
            userIdentifier = getSharedPreferences(getString(R.string.shared_preference_file_key), Context.MODE_PRIVATE)
                    .getString(Constants.SAVED_STUDY_ID, "");
            // Set the user in the current context.
            Sentry.getContext().setUser(new UserBuilder().setId(userIdentifier).build());

            if (intent != null && Objects.requireNonNull(intent.getAction()).equals(Constants.ACTION_START_SERVICE)) {
                startServiceWithNotification();

                Log.d(TAG, "starting service!");

                int sensorDelay = isDebuggable ? SENSOR_DELAY_DEBUG : SENSOR_DELAY_RELEASE;
                boolean didRegisterSensors = this._registerDeviceSensors(sensorDelay, maxReportingLatency);
                Log.d(TAG, "Did register Sensors: " + didRegisterSensors);

                mHandler.removeCallbacksAndMessages(null);
                mHandler.post(mSaveTask);
                mHandler.postDelayed(mSendTask, SEND_TASK_PERIOD_MS);
            } else {
                stopMyService();
            }
        }

        return START_STICKY; // START_STICKY is used for services that are explicitly started and stopped as
        // needed
    }

    private class SaveRunnable implements Runnable {
        @Override
        public void run() {
            try {
                _SaveData();
            } catch (Exception e) {
                Sentry.capture(e);
                Log.e(TAG, "Exception in SaveRunnable: " + e.getMessage());
            }
            mHandler.postDelayed(mSaveTask, SAVE_TASK_PERIOD_MS);
        }
    }

    private class SendRunnable implements Runnable {
        @Override
        public void run() {
            try {
                if (isPlugged()) {
                    _RequestNetworkAndSend();
                }
            } catch (Exception e) {
                Sentry.capture(e);
                Log.e(TAG, "Exception in SendRunnable: " + e.getMessage());
            }
            mHandler.postDelayed(mSendTask, SEND_TASK_PERIOD_MS);
        }
    }

    public boolean isPlugged() {
        Context context = getApplicationContext();
        boolean isPlugged;
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = 0;
        if (intent != null) {
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        }
        isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB;
        isPlugged = isPlugged || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        return isPlugged;
    }

    private void _PurgeLocalData() {
        Log.d(TAG, "_PurgeLocalData()");
        try {
            // TODO: clear local file system
            Log.d(TAG, "Do we want to clear the entire SQLite DB here? Or should we just let it grow and only delete once a record has been sent to Kinvey successfully???");
        } catch (Exception e) {
            Log.e(TAG, "Error purging local data" + e.getMessage());
            sendMessageToActivity("Error trying to purge local data: " + e.getMessage(), Constants.SENSOR_SERVICE_MESSAGE);
            Sentry.capture(e);
        }
    }

    private void _PushDataToKinvey() {
        Log.d(TAG, "_PushDataToKinvey()...");
        // Check if the SQLite table has any records pending to be pushed
        long tableRowCount = db.getTableRowCount();
        Log.d(TAG, "Database size: " + db.getTableSizeBytes() + " bytes");
        if (tableRowCount == 0) {
            Log.d(TAG, "No unsent data, clearing the storage.");
            _PurgeLocalData();
            unregisterNetwork();
        } else {
            long pushCount = Math.min(tableRowCount, MAX_SEND_COUNT);
            Log.d(TAG, "Pushing to kinvey: " + pushCount);
            sendMessageToActivity("Sending " + pushCount + " records to backend", Constants.SENSOR_SERVICE_MESSAGE);
            try {
                SensorDbHandler.Record r = db.getRecord();
                Observable.just(RequestBody.create(okhttp3.MediaType.parse("application/json; charset=utf-8"), r.data))
                        .flatMap(x -> mKinveyApiService.sendData(mKinveyAuthorization, x, r.id))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .unsubscribeOn(Schedulers.io())
                        .subscribe(
                                item -> {
                                    Log.d(TAG, "item sent: " + item._id);
                                    numRecordsPushed++;
                                    db.deleteRecord(item._id);
                                    // Send the Table row count to the UI to keep user informed on how many records are local and need to be pushed
                                    sendMessageToActivity("Local Database Records: " + db.getTableRowCount(), Constants.SENSOR_SERVICE_LOCAL_DB_RECORD_COUNT);
                                },
                                error -> {
                                    Log.e(TAG, "send data onError(): " + error);
                                    Sentry.capture(error);
                                    unregisterNetwork();
                                },
                                () -> {
                                    Log.d(TAG, "onCompleted()");
                                    sendMessageToActivity("Sent " + pushCount + " records to backend successfully.", Constants.SENSOR_SERVICE_MESSAGE);
                                    // Send the Table row count to the UI to keep user informed on how many records are local and need to be pushed
                                    sendMessageToActivity("Local Database Records: " + db.getTableRowCount(), Constants.SENSOR_SERVICE_LOCAL_DB_RECORD_COUNT);
                                    unregisterNetwork();
                                });
            } catch (Exception e) {
                Log.e(TAG, "Exception pushing to kinvey:" + e.getMessage());
                sendMessageToActivity("Error sending to database: " + e.getMessage(), Constants.SENSOR_SERVICE_MESSAGE);
                Sentry.capture(e);
                unregisterNetwork();
            }
        }
    }

    private void _SaveData() {
        Log.d(TAG, "_SaveData()...");
        // adding an empty check to avoid pushing the initial service starting records with no sensor_data since the intervals haven't clocked at that time
        if (sensorServiceDataList.isEmpty()) {
            Log.d(TAG, "Sensor data list is empty, so will not save/push this record.");
        } else {
            Log.d(TAG, "Database size: " + db.getTableSizeBytes() + " bytes");
            int numRecordsToSave = sensorServiceDataList.size();
            while (numRecordsToSave > 0) {
                PSDSData data = new PSDSData();
                data.user_identifier = this.userIdentifier;
                data.device_uuid = this.deviceUUID;

                // manage how many records we make per save operation - ensure that no record is too long (memory / storage)
                int numToCopy = Math.min(numRecordsToSave, MAX_NUM_ENTRIES_PER_RECORD);
                int numRemaining = numRecordsToSave - numToCopy;
                synchronized (sensorServiceDataList) {
                    if (numRemaining > 0) {
                        data.sensor_data = new ArrayList<>(sensorServiceDataList.subList(0, numToCopy));
                        sensorServiceDataList.subList(0, numToCopy).clear();
                    } else {
                        data.sensor_data = sensorServiceDataList;
                        // reset the sensor data list for new values to be pushed into
                        sensorServiceDataList = new ArrayList<>();
                    }
                }
                numRecordsToSave = sensorServiceDataList.size();

                // copy the locations over
                synchronized (sensorServiceLocationList) {
                    // TODO: will we need to split this up like we do the sensor data? - we shouldn't since there is far less data...
                    int numLocations = sensorServiceLocationList.size();
                    data.locations = new ArrayList<>(sensorServiceLocationList.subList(0, numLocations));
                    sensorServiceLocationList.subList(0, numLocations);
                }

                // if we have location permission write the location to record, if not, just print WARNING to LogCat, not sure on best handling for UX right now.
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Unable to get device location because LOCATION permission has not been granted.");
                    data.location = null;
                } else if (mLastKnownLocation != null) {
                  data.location = new PSDSLocation(mLastKnownLocation);
                } else {
                  Location loc;
                  loc = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                  if (loc == null) {
                    loc = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (loc == null) {
                      loc = mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                    }
                  }
                  if (loc != null) {
                    data.location = new PSDSLocation(loc);
                  } else {
                    data.location = null;
                  }
                }
                Log.d(TAG, "Data location: " + data.location);

                try {
                    db.addRecord(data);
                    numRecordsSaved++;
                    // Send the Table row count to the UI to keep user informed on how many records are local and need to be pushed
                    sendMessageToActivity("Local Database Records: " + db.getTableRowCount(), Constants.SENSOR_SERVICE_LOCAL_DB_RECORD_COUNT);
                } catch (Exception e) {
                    Log.e(TAG, "Exception:" + e.getMessage());
                    sendMessageToActivity("Error saving: " + e.getMessage(), Constants.SENSOR_SERVICE_MESSAGE);
                    Sentry.capture(e);
                    unregisterNetwork();
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()...");
        super.onDestroy();

        // unregister network
        unregisterNetwork();

        // remove sensor listeners
        _unregisterDeviceSensors();

        // remove handler tasks
        mHandler.removeCallbacksAndMessages(null);
        mHandlerThread.quitSafely();

        isServiceRunning = false;
    }

    private class LocationListener implements android.location.LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            Log.d(TAG, "Got location: " + location);
            mLastKnownLocation = location;
            // TODO: do we need to check if the user has been active here?
            PSDSLocation loc = new PSDSLocation(location);
            synchronized (sensorServiceLocationList) {
                sensorServiceLocationList.add(loc);
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "onStatusChanged(): " + provider + " - " + status);
        }
    }

    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        boolean isRegistered = false;

        @Override
        public void onAvailable(Network network) {
            try {
                if (mConnectivityManager.bindProcessToNetwork(network)) {
                    NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null) {
                        int bandwidth = capabilities.getLinkDownstreamBandwidthKbps();
                        Log.d(TAG, "Bandwidth for network: " + bandwidth);
                        // we can use this network, so push to remote
                        _PushDataToKinvey();
                    } else {
                        Log.d(TAG, "No capabilities for network!");
                        unregisterNetwork();
                    }
                } else {
                    Log.w(TAG, "Couldn't bind process to network!");
                    // app doesn't have android.permission.INTERNET permission
                    unregisterNetwork();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "ConnectivityManager.NetworkCallback.onAvailable: ", e);
                Sentry.capture(e);
                unregisterNetwork();
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            int bandwidth = networkCapabilities.getLinkDownstreamBandwidthKbps();
            Log.d(TAG, "Network Capabilities changed to " + bandwidth + " Kbps");
        }

        @Override
        public void onLost(Network network) {
            Log.d(TAG, "Lost network!");
        }

        @Override
        public void onUnavailable() {
            isRegistered = false;
        }
    }

    private void unregisterNetwork() {
        Log.d(TAG, "unregisterNetwork()...");
        mConnectivityManager.bindProcessToNetwork(null);
        if (this.mNetworkCallback.isRegistered) {
            mConnectivityManager.unregisterNetworkCallback(this.mNetworkCallback);
        }
        this.mNetworkCallback.isRegistered = false;
    }

    private void requestNetwork(int[] capabilities, int[] transportTypes) {
        Log.d(TAG, "requestNetwork()...");
        if (this.mNetworkCallback.isRegistered) {
            Log.d(TAG, "already registered");
            return;
        }
        NetworkRequest.Builder request = new NetworkRequest.Builder();
        // add capabilities
        for (int cap : capabilities) {
            request.addCapability(cap);
        }
        // add transport types
        for (int trans : transportTypes) {
            request.addTransportType(trans);
        }
        this.mNetworkCallback.isRegistered = true;
        mConnectivityManager.requestNetwork(request.build(), this.mNetworkCallback, NETWORK_CONNECTIVITY_TIMEOUT_MS);
    }

    public void _RequestNetworkAndSend() {
        // Add any NetworkCapabilities.NET_CAPABILITY_
        int[] capabilities = new int[]{NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED};

        // Add any NetworkCapabilities.TRANSPORT_
        int[] transportTypes = new int[]{NetworkCapabilities.TRANSPORT_WIFI};

        requestNetwork(capabilities, transportTypes);
    }

    public class TriggerSensorListener extends TriggerEventListener {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_SIGNIFICANT_MOTION) {
                personIsActive = true;
                if (isServiceRunning) {
                    mSensorManager.requestTriggerSensor(this, mSignificantMotion);
                }
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
                    synchronized (sensorServiceDataList) {
                        sensorServiceDataList.add(data);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // TODO Auto-generated method stub
        }

        void updateActivity(SensorEvent event) {
            // check if the watch is moving
            if (event.sensor.getType() == Sensor.TYPE_MOTION_DETECT) {
                personIsActive = true;
            } else if (event.sensor.getType() == Sensor.TYPE_STATIONARY_DETECT) {
                personIsActive = false;
            }
            // check if the user is wearing the watch
            if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                // 1.0 => device is on body, 0.0 => device is off body
                watchBeingWorn = (event.values[0] != 0.0);
            }
        }

        boolean hasBeenActive() {
            //Log.d(TAG, "PersonIsActive: " + personIsActive + "; watchBeingWorn: " + watchBeingWorn);
            return watchBeingWorn;
        }

    }

    private void _unregisterDeviceSensors() {
        // make sure we have the sensor manager for the device
        if (mSensorManager != null && mListener != null && mTriggerListener != null) {
            if (mLinearAcceleration != null)
                mSensorManager.unregisterListener(mListener, mLinearAcceleration);
            if (mGravity != null)
                mSensorManager.unregisterListener(mListener, mGravity);
            if (mMagneticField != null)
                mSensorManager.unregisterListener(mListener, mMagneticField);
            if (mRotationVector != null)
                mSensorManager.unregisterListener(mListener, mRotationVector);
            if (mGameRotationVector != null)
                mSensorManager.unregisterListener(mListener, mGameRotationVector);
            if (mGyroscope != null)
                mSensorManager.unregisterListener(mListener, mGyroscope);
            if (mProximity != null)
                mSensorManager.unregisterListener(mListener, mProximity);
            if (mOffBodyDetect != null)
                mSensorManager.unregisterListener(mListener, mOffBodyDetect);
            if (mStationaryDetect != null)
                mSensorManager.unregisterListener(mListener, mStationaryDetect);
            if (mSignificantMotion != null)
                mSensorManager.cancelTriggerSensor(mTriggerListener, mSignificantMotion);
            if (mMotionDetect != null)
                mSensorManager.unregisterListener(mListener, mMotionDetect);
        } else {
            Log.e(TAG, "Sensor Manager was not found, so sensor service is unable to unregister sensor listener events.");
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
            mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (mLinearAcceleration != null)
                mSensorManager.registerListener(mListener, mLinearAcceleration, delay, reportingLatency);

            mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravity != null)
                mSensorManager.registerListener(mListener, mGravity, delay, reportingLatency);

            mMagneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mMagneticField != null)
                mSensorManager.registerListener(mListener, mMagneticField, delay, reportingLatency);

            mRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (mRotationVector != null)
                mSensorManager.registerListener(mListener, mRotationVector, delay, reportingLatency);

            mGameRotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (mGameRotationVector != null)
                mSensorManager.registerListener(mListener, mGameRotationVector, delay, reportingLatency);

            mGyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (mGyroscope != null)
                mSensorManager.registerListener(mListener, mGyroscope, delay, reportingLatency);

            mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximity != null) {
                mSensorManager.registerListener(mListener, mProximity, delay, reportingLatency);
                //Log.d(TAG, "Have TYPE_PROXIMITY");
            }

            mOffBodyDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
            if (mOffBodyDetect != null)
                mSensorManager.registerListener(mListener, mOffBodyDetect, delay, reportingLatency);

            //Log.d(TAG, "Checking TYPE_STATIONARY_DETECT");
            mStationaryDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
            if (mStationaryDetect != null) {
                mSensorManager.registerListener(mListener, mStationaryDetect, delay, reportingLatency);
                //Log.d(TAG, "Have TYPE_STATIONARY_DETECT");
            }

            //Log.d(TAG, "Checking TYPE_SIGNIFICANT_MOTION");
            mSignificantMotion = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            if (mSignificantMotion != null) {
                mSensorManager.requestTriggerSensor(mTriggerListener, mSignificantMotion);
                //Log.d(TAG, "Have TYPE_SIGNIFICANT_MOTION");
            }

            //Log.d(TAG, "Checking TYPE_MOTION_DETECT");
            mMotionDetect = mSensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT);
            if (mMotionDetect != null) {
                mSensorManager.registerListener(mListener, mMotionDetect, delay, reportingLatency);
                //Log.d(TAG, "Have TYPE_MOTION_DETECT");
            }
        } else {
            Log.e(TAG, "Sensor Manager was not found, so sensor service is unable to register sensor listener events.");
        }

        return true;
    }

    private void sendMessageToActivity(String msg, String extraKey) {
        Intent intent = new Intent(Constants.SENSOR_SERVICE_MESSAGE_INTENT_KEY);
        // You can also include some extra data.
        intent.putExtra(extraKey, msg);
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
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(notificationChannel);
        } else {
            Sentry.capture("NotificationManager was null. Unable to create the NotificationChannel to start the service with the notification.");
        }

        // create the notification builder
        Builder notificationBuilder = new Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setTicker("Permobil")
                .setContentText("Permobil Sensor Data Study is collecting data.")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setChannelId(channelId);

        // create the notification
        Notification notification = notificationBuilder.build();
        notification.flags = notification.flags | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR; // NO_CLEAR makes the notification stay when the user performs a "delete all" command
        startForeground(NOTIFICATION_ID, notification);
    }

    private void stopMyService() {
        stopForeground(true);
        stopSelf();
        isServiceRunning = false;
    }

}
