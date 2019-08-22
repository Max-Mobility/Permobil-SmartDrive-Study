package com.permobil.psds.wearos;

import android.location.Location;

import com.google.api.client.util.Key;

public class PSDSLocation {
    @Key
    public double latitude;

    @Key
    public double longitude;

    @Key
    public long time;

    @Key
    public long elapsedTimeNs;

    @Key
    public float speed;

    @Key
    public float accuracy;

    @Key
    public float speedAccuracy;

    public PSDSLocation() {
    }

    public PSDSLocation(
                        double latitude,
                        double longitude,
                        long time,
                        long elapsedTimeNanos,
                        float speed,
                        float accuracy,
                        float speedAccuracy
                        ) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.time = time;
        this.elapsedTimeNs = elapsedTimeNanos;
        this.speed = speed;
        this.accuracy = accuracy;
        this.speedAccuracy = speedAccuracy;
    }

    public PSDSLocation(Location loc) {
      this.latitude = loc.getLatitude();
      this.longitude = loc.getLongitude();
      this.speed = loc.getSpeed();
      this.time = loc.getTime();
      this.elapsedTimeNs = loc.getElapsedRealtimeNanos();
      this.accuracy = loc.getAccuracy();
      this.speedAccuracy = loc.getSpeedAccuracyMetersPerSecond();
    }
}
