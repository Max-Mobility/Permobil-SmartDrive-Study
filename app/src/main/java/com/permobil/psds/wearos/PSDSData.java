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
    public String device_manufacturer = Build.MANUFACTURER;

    @Key
    public String device_model = Build.MODEL;

    @Key
    public String device_os_version = Build.VERSION.RELEASE;

    @Key
    public int device_sdk_version = Build.VERSION.SDK_INT;

    @Key
    public String device_uuid;

    @Key
    public String user_identifier;

    @Key
    public PSDSLocation location;

    public PSDSData() {
    }

}
