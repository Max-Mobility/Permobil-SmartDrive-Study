package com.permobil.psds.wearos;


import io.reactivex.Observable;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

public interface KinveyApiService {
    @Headers({
            "Content-Type:application/json"
    })
    @POST(Constants.API_DATA_ENDPOINT)
    Observable<SensorDbHandler.SqlRowResult> sendData(@Header("Authorization") String authorization, @Body PSDSData data);
}
