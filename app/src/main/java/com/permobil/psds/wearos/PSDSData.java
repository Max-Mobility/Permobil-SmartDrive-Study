package com.permobil.psds.wearos;

import android.hardware.Sensor;
import android.os.Build;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;

import java.util.ArrayList;
import java.util.List;

public class PSDSData extends GenericJson {

    @Key
    public ArrayList<SensorServiceData> sensor_data;

    @Key
    public List<Sensor> sensor_list;

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
