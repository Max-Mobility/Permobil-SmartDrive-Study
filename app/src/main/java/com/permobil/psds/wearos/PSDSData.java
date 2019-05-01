package com.permobil.psds.wearos;

import android.hardware.Sensor;
import android.os.Build;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;

import java.util.List;

public class PSDSData extends GenericJson {

    public static class SensorData extends GenericJson {
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
        public List<Float> d;

        // anything extending GernericJson must have empty constructor
        public SensorData() {
            this.t = System.currentTimeMillis() / 1000;
        }

        public SensorData(int s, long ts, List<Float> d) {
            this.s = s;
            this.t = System.currentTimeMillis() / 1000;
            this.ts = ts;
            this.d = d;
        }
    }

    @Key
    public List<Sensor> sensor_list;

    @Key
    public List<SensorData> sensor_data;

    @Key
    public String device_manufacturer;

    @Key
    public String device_model;

    @Key
    public String device_os_version;

    @Key
    public int device_sdk_version;

    @Key
    public String device_uuid;

    @Key
    public String user_identifier;

    @Key
    public PSDSLocation location;

    public PSDSData() {
        this.device_manufacturer = Build.MANUFACTURER;
        this.device_model = Build.MODEL;
        this.device_os_version = Build.VERSION.RELEASE;
        this.device_sdk_version = Build.VERSION.SDK_INT;
    }


}
