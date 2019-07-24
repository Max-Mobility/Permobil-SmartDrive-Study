package com.permobil.psds.wearos;

import com.google.api.client.util.Key;

public class PSDSLocation {
    @Key
    public double latitude;

    @Key
    public double longitude;

    @Key
    public long time;

    @Key
    public float speed;

    public PSDSLocation() {
    }

    public PSDSLocation(double latitude, double longitude, long time, float speed) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.time = time;
        this.speed = speed;
    }
}