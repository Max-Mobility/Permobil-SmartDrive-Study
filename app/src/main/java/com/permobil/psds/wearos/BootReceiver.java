package com.permobil.psds.wearos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AutoStart_BroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "AutoStart onReceive...");

        context.startService(new Intent(context, SensorService.class));

//        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
//            SensorService.enqueueWork(context, new Intent());
//        }


//        Intent i = new Intent(context, SensorService.class);
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            context.startForegroundService(i);
//        } else {
//            context.startService(i);
//        }
//        Log.i(TAG, "AutoStart onReceive...");
    }
}
