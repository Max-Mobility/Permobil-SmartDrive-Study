package com.permobil.psds.wearos;

public class SensorSqlData {
    String _data;

    // Empty constructor
    public SensorSqlData() {
    }

    // constructor
    public SensorSqlData(String data) {
        this._data = data;
    }

    public String getData() {
        return this._data;
    }

    public void setData(String data) {
        this._data = data;
    }

}
