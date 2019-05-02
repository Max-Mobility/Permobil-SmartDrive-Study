package com.permobil.psds.wearos;

import android.app.Application;
import android.util.Log;

import com.kinvey.android.Client;
import com.kinvey.android.model.User;
import com.kinvey.android.store.UserStore;
import com.kinvey.java.core.KinveyClientCallback;

import java.io.IOException;

import io.sentry.Sentry;
import io.sentry.android.AndroidSentryClientFactory;
import io.sentry.event.BreadcrumbBuilder;

public class App extends Application {
    private Client sharedClient;

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

        sharedClient = new Client.Builder(this).build();
//        sharedClient.enableDebugLogging();

        // check the kinvey client has active user
        boolean isLoggedIn = sharedClient.isUserLoggedIn();
        if (!isLoggedIn) {
            try {
                UserStore.login("bradwaynemartin@gmail.com", "testtest", sharedClient, new KinveyClientCallback<User>() {
                    @Override
                    public void onSuccess(User user) {
                        Log.d(TAG, "Login success.");
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        Log.e(TAG, "Login failed.");
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable e) {
                        Log.e(TAG, e.getMessage());
                        Sentry.capture(e);
                    }
                });

    }

    public Client getSharedClient() {
        return sharedClient;
    }

    public User getActiveUser() {
        return sharedClient.getActiveUser();
    }


}
