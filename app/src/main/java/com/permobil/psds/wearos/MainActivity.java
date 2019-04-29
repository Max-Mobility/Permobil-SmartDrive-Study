package com.permobil.psds.wearos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends WearableActivity {

    private TextView mTextView;
    private Button mSubmitBtn;

    private final static int LOCATION_PERMSSION_VALUE = 5425;
    private final static int STORATE_PERMISSION_VALUE = 5426;
    private final static String TAG = "PSDS_MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextView = findViewById(R.id.text);
        mSubmitBtn = findViewById(R.id.submitBtn);

        mSubmitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "button clicked");

                // check the psds identifier the user entered and validate
                // also store in shared preferences so we can check on start later to skip entering it
                String userId = mTextView.getText().toString();
                if (userId != null) {
                    Log.d(TAG, "Evaluating user ID: " + userId);
                    if (userId.matches("/PSDS[0-9]+/gi")) {
                        Log.d(TAG, "User ID is valid!!!");
                    } else {
                        Log.d(TAG, "User ID is invalid!!!");
                    }
                } else {
                    return;
                }
            }
        });

        // Enables Always-on
        setAmbientEnabled();

        // need to make sure we have permission for data study to work properly
        boolean locationPermission = this._requestLocationPermission();
        Log.d(TAG, "Location permission granted? " + locationPermission);
        boolean storagePermission = this._requestStoragePermission();
        Log.d(TAG, "Storage permission granted? " + storagePermission);

        if (locationPermission && storagePermission) {
            Intent i = new Intent(MainActivity.this, SensorService.class);
            startService(i);
            Log.d(TAG, "SensorService has been started successfully.");
        } else {
            Log.d(TAG, "PSDS is missing permission for location and/or storage so we cannot collect data properly.");
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case LOCATION_PERMSSION_VALUE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                return;
            }
            case STORATE_PERMISSION_VALUE: {

            }

            // other 'case' lines to check for other
            // permissions this app might request.
        }
    }

    private boolean _requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            // No explanation needed; request the permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    5425);
            return false;
        } else {
            return true;
        }
    }

    private boolean _requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            // No explanation needed; request the permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    5426);
            return false;
        } else {
            return true;
        }
    }

    private boolean idIsValid(String id) {
        return true;
    }
//    const regex = /PSDS[0-9]+/gi;
//    const testID = 'xxr&dxx';
//        return testID == id || regex.test(id);
//    }
}
