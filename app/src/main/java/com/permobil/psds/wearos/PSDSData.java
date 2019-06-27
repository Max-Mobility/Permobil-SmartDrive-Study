package com.permobil.psds.wearos;

import android.os.Build;
import android.os.SystemClock;

import com.google.api.client.util.Key;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class PSDSData {

    public static class SensorData {
        /**
         * Sensor type as int.
         */
        @Key("s")
        public int s;

        /**
         * Returns the seconds since Epoch
         */
        @Key("t")
        public long t;


        /**
         * Hashmap for storing the sensor data.
         */
        @Key("d")
        public List<Float> d;

        // anything extending GernericJson must have empty constructor
        public SensorData() {
            this.t = System.currentTimeMillis() / 1000;
        }

        public SensorData(int s, long ts, List<Float> d) {
            this.s = s;
            this.t = (new Date()).getTime() + (ts - SystemClock.elapsedRealtimeNanos()) / 1000000L;
            this.d = d;
        }
    }

    @Key("_id")
    public String _id;

    @Key("sensor_data")
    public List<SensorData> sensor_data;


    @Key("device_manufacturer")
    public String device_manufacturer;

    @Key("device_model")
    public String device_model;

    @Key("device_os_version")
    public String device_os_version;

    @Key("device_sdk_version")
    public int device_sdk_version;

    @Key("device_uuid")
    public String device_uuid;

    @Key("user_identifier")
    public String user_identifier;

    @Key("location")
    public PSDSLocation location;

    @Key("locations")
    public List<PSDSLocation> locations;

    public PSDSData() {
        this._id = UUID.randomUUID().toString();
        this.device_manufacturer = Build.MANUFACTURER;
        this.device_model = Build.MODEL;
        this.device_os_version = Build.VERSION.RELEASE;
        this.device_sdk_version = Build.VERSION.SDK_INT;
    }


}
