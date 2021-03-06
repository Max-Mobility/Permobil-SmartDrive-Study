package com.permobil.psds.wearos;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends WearableActivity {

    private TextView mTextView;
    private TextView mAppVersion;
    private TextView mLocalDbRecordsCount;
    private Button mSubmitBtn;
    private Button mPermissionsButton;
    private TextView mServiceStatusText;
    private TextView mStudyIdText;
    private SharedPreferences sharedPref;
    private boolean isServiceRunning;
    private boolean hasStoragePermission;
    private boolean hasLocationPermission;

    private final static String TAG = "PSDS_MainActivity";
    private final static int LOCATION_PERMISSION_VALUE = 5425;
    private final static int STORAGE_PERMISSION_VALUE = 5426;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get extra data included in the Intent
        BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                // Get extra data included in the Intent
                String message = intent.getStringExtra(Constants.SENSOR_SERVICE_MESSAGE);
                if (message != null && !message.equals("")) {
                    mServiceStatusText.setText(message);
                }

                String dbRecordCount = intent.getStringExtra(Constants.SENSOR_SERVICE_LOCAL_DB_RECORD_COUNT);
                if (dbRecordCount != null && !dbRecordCount.equals("")) {
                    mLocalDbRecordsCount.setText(dbRecordCount);
                    mLocalDbRecordsCount.setVisibility(View.VISIBLE);
                }
            }
        };

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(Constants.SENSOR_SERVICE_MESSAGE_INTENT_KEY));

        // Enables Always-on
        setAmbientEnabled();

        isServiceRunning = false;
        sharedPref = getSharedPreferences(getString(R.string.shared_preference_file_key), Context.MODE_PRIVATE);
        mServiceStatusText = findViewById(R.id.serviceStatusText);
        mTextView = findViewById(R.id.studyId);
        mLocalDbRecordsCount = findViewById(R.id.localDbRecordCountTextView);
        mLocalDbRecordsCount.setVisibility(View.GONE); // hide this initially
        // Handle app version textview setting
        mAppVersion = findViewById(R.id.appVersionTextView);
        PackageInfo pInfo;
        try {
            pInfo = getApplicationContext().getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            int versionCode = pInfo.versionCode;
            mAppVersion.setText(String.format("App Version: %s - %s", version, versionCode));
            mAppVersion.setVisibility(View.VISIBLE);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            mAppVersion.setVisibility(View.GONE);
        }

        mStudyIdText = findViewById(R.id.studyIdTxt);
        mSubmitBtn = findViewById(R.id.submitBtn);
        mSubmitBtn.setOnClickListener(v -> {
            // check the psds identifier the user entered and validate
            // also store in shared preferences so we can check on start later to skip entering it
            String studyId = mTextView.getText().toString().toLowerCase();
            if (!studyId.equals("")) {

                boolean isDebuggable = (0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
                Pattern p = Pattern.compile("PSDS[0-9]+", Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(studyId);
                boolean b = m.matches();

                if (b || studyId.equals("xxr&dxx") || isDebuggable) {
                    Log.d(TAG, "User ID is valid.");
                    // save to sharedPref
                    sharedPref.edit().putString(Constants.SAVED_STUDY_ID, studyId).apply();
                    // can start the service now
                    startSensorService(studyId);
                } else {
                    mServiceStatusText.setVisibility(View.VISIBLE);
                    mServiceStatusText.setText("Study ID is invalid. Try again.");
                }
            } else {
                mServiceStatusText.setVisibility(View.VISIBLE);
                mServiceStatusText.setText("Study ID is required.");
            }
        });
        mPermissionsButton = findViewById(R.id.permissionBtn);
        mPermissionsButton.setOnClickListener(v -> {
            // need to see if permissions have been granted if so we can move past this step
            boolean b = hasPermissionsForService();
            if (b) {
                recreate();
                return;
            }
            requestStoragePermission();
            requestLocationPermission();
        });

        // check if user already has entered study ID
        String savedStudyId = sharedPref.getString(Constants.SAVED_STUDY_ID, "");
        Log.d(TAG, "Saved study id: " + savedStudyId);
        // prompt the user if their GPS is not enabled for data study
        boolean isGpsEnabled = isGpsEnabled();
        if (!isGpsEnabled) {
            buildAlertMessageNoGps();
        }

        // we have a study id for the device so just start the service to collect data
        if (!savedStudyId.equals("")) {
            mStudyIdText.setText(String.format("Study ID: %s", savedStudyId)); // set the study id for text view to show user their current study ID
            boolean canStartService = hasPermissionsForService();
            if (canStartService) {
                startSensorService(savedStudyId);
            } else {
                // cant start service because permissions so show permission button
                mPermissionsButton.setVisibility(View.VISIBLE);
                mSubmitBtn.setVisibility(View.GONE);
                mTextView.setVisibility(View.GONE);
                mServiceStatusText.setVisibility(View.GONE);
                mStudyIdText.setVisibility(View.GONE);
            }
        } else {
            // lets handle permissions first then allow for study ID to be input
            // need to make sure we have permission for data study to work properly
            boolean hasPermissions = this.hasPermissionsForService();
            Log.d(TAG, "Storage and Location permission granted? " + hasPermissions);
            if (hasPermissions) {
                // let user input the study ID here
                mPermissionsButton.setVisibility(View.GONE);
                mServiceStatusText.setVisibility(View.GONE);
                mStudyIdText.setVisibility(View.GONE);
                mTextView.setVisibility(View.VISIBLE);
                mSubmitBtn.setVisibility(View.VISIBLE);
            } else {
                Log.d(TAG, "PSDS is missing permission for location and/or storage so we cannot collect data properly.");
                mSubmitBtn.setVisibility(View.GONE);
                mTextView.setVisibility(View.GONE);
                mServiceStatusText.setVisibility(View.VISIBLE);
                mServiceStatusText.setText("Device storage and location permission are required for data study.");
                mPermissionsButton.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case STORAGE_PERMISSION_VALUE: {
                // If request is cancelled, the result arrays are empty.
                hasStoragePermission = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            }
            case LOCATION_PERMISSION_VALUE: {
                // If request is cancelled, the result arrays are empty.
                hasLocationPermission = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            }
        }
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            // No explanation needed; request the permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission
                            .ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_VALUE);
            hasStoragePermission = false;
        } else {
            hasStoragePermission = true;
        }
    }

    private void requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            // No explanation needed; request the permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission
                            .WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_VALUE);
            hasStoragePermission = false;
        } else {
            hasStoragePermission = true;
        }
    }

    private boolean hasPermissionsForService() {
        // check write storage permission
        // Permission is not granted
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isGpsEnabled() {
        final LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean providerEnabled = manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        Log.d(TAG, "GPS is enabled:" + providerEnabled);
        return providerEnabled;
    }

    private void buildAlertMessageNoGps() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your GPS seems to be disabled, do you want to enable it?")
                .setCancelable(false)
                .setPositiveButton("Yes", (dialog, id) -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("No", (dialog, id) -> dialog.cancel());
        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void startSensorService(String studyId) {
        Intent i = new Intent(MainActivity.this, SensorService.class);
        i.setAction(Constants.ACTION_START_SERVICE);

        // startForegroundService(i);
        startService(i);
        Log.d(TAG, "SensorService has been started successfully with study ID: " + studyId);
        isServiceRunning = true;
        mTextView.setVisibility(View.GONE);
        mSubmitBtn.setVisibility(View.GONE);
        mPermissionsButton.setVisibility(View.GONE);
        mServiceStatusText.setVisibility(View.VISIBLE);
        mServiceStatusText.setText("Data collection service is running normally.");
        mStudyIdText.setVisibility(View.VISIBLE);
        mStudyIdText.setText(String.format("Study ID: %s", sharedPref.getString(Constants.SAVED_STUDY_ID, "")));
    }

}
