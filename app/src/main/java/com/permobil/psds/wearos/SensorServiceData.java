package com.permobil.psds.wearos;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;


public class SensorServiceData extends GenericJson {

    /**
     * Sensor type as int.
     */
    @Key
    public int s;


    /**
     * Sensor event timestamp.
     */
    @Key
    public long ts;


    /**
     * Returns the seconds since Epoch
     */
    @Key
    public long t;


    /**
     * Hashmap for storing the sensor data.
     */
    @Key
    public GenericJson d;

    // Empty constructor
    public SensorServiceData() {
        this.t = System.currentTimeMillis() / 1000;
    }

    public SensorServiceData(int s, long ts, GenericJson d) {
        this.s = s;
        this.ts = ts;
        this.d = d.clone();
        this.t = System.currentTimeMillis() / 1000;
    }

}
