package com.permobil.psds.wearos;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;

public class PSDSLocation extends GenericJson {
    @Key
    public double latitude;

    @Key
    public double longitude;

    @Key
    public long time;

    public PSDSLocation() {
    }

    public PSDSLocation(double latitude, double longitude, long time) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.time = time;
    }
}