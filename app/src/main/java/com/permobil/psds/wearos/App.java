package com.permobil.psds.wearos;

import android.app.Application;
import android.util.Log;

import java.io.IOException;

import io.sentry.Sentry;
import io.sentry.android.AndroidSentryClientFactory;
import io.sentry.event.BreadcrumbBuilder;

public class App extends Application {
    private final static String TAG = "App.java";

    @Override
    public void onCreate() {
        super.onCreate();
        // Setup Sentry logging, uses `sentry.properties`
        Sentry.init(new AndroidSentryClientFactory(getApplicationContext()));

          /*
         Record a breadcrumb in the current context which will be sent
         with the next event(s). By default the last 100 breadcrumbs are kept.
         */
        Sentry.getContext().recordBreadcrumb(
                new BreadcrumbBuilder().setMessage("Permobil Study Data Collector app started.").build()
        );


        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable e) {
                        Log.e(TAG, e.getMessage());
                        Sentry.capture(e);
                    }
                });

    }
}
