package com.permobil.psds.wearos;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
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
    private Button mSubmitBtn;
    private Button mPermissionsButton;
    private TextView mServiceStatusText;
    private SharedPreferences sharedPref;
    private BroadcastReceiver mMessageReceiver;
    private boolean isServiceRunning;
    private boolean hasStoragePermission;
    private boolean hasLocationPermission;

    private final static String TAG = "PSDS_MainActivity";
    private final static int LOCATION_PERMSSION_VALUE = 5425;
    private final static int STORAGE_PERMISSION_VALUE = 5426;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Get extra data included in the Intent
                String message = intent.getStringExtra(Constants.SENSOR_SERVICE_MESSAGE);
                mServiceStatusText.setText(message);
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
        mSubmitBtn = findViewById(R.id.submitBtn);
        mSubmitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // check the psds identifier the user entered and validate
                // also store in shared preferences so we can check on start later to skip entering it
                String studyId = mTextView.getText().toString().toLowerCase();
                if (!studyId.equals("")) {
                    Pattern p = Pattern.compile("PSDS[0-9]+", Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(studyId);
                    boolean b = m.matches();

                    if (b || studyId.equals("xxr&dxx")) {
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
            }
        });
        mPermissionsButton = findViewById(R.id.permissionBtn);
        mPermissionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // need to see if permissions have been granted if so we can move past this step
                boolean b = hasPermissionsForService();
                if (b) {
                    recreate();
                    return;
                }
                requestStoragePermission();
                requestLocationPermission();
            }
        });

        // check if user already has entered study ID
        String savedStudyId = sharedPref.getString(Constants.SAVED_STUDY_ID, "");
        Log.d(TAG, "Saved study id: " + savedStudyId);

        // we have a study id for the device so just start the service to collect data
        if (!savedStudyId.equals("")) {
            boolean canStartService = hasPermissionsForService();
            if (canStartService) {
                startSensorService(savedStudyId);
            } else {
                // cant start service because permissions so show permission button
                mPermissionsButton.setVisibility(View.VISIBLE);
                mSubmitBtn.setVisibility(View.GONE);
                mTextView.setVisibility(View.GONE);
                mServiceStatusText.setVisibility(View.GONE);
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
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    hasStoragePermission = true;
                } else {
                    hasStoragePermission = false;
                }
            }
            case LOCATION_PERMSSION_VALUE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    hasLocationPermission = true;
                } else {
                    hasLocationPermission = false;
                }
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
                    LOCATION_PERMSSION_VALUE);
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

    private void startSensorService(String studyId) {
        Intent i = new Intent(MainActivity.this, SensorService.class);
        i.putExtra(Constants.SENSOR_DELAY, 40000); // 40000 us or 40 ms delay
        i.putExtra(Constants.MAX_REPORTING_DELAY, 1000000); // 1000000 us or 1 s max delay
        startService(i);
        Log.d(TAG, "SensorService has been started successfully with study ID: " + studyId);
        isServiceRunning = true;
        mTextView.setVisibility(View.GONE);
        mSubmitBtn.setVisibility(View.GONE);
        mPermissionsButton.setVisibility(View.GONE);
        mServiceStatusText.setVisibility(View.VISIBLE);
        mServiceStatusText.setText("Data collection service is running normally.");
    }

}
