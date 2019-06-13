package com.permobil.psds.wearos;

import io.reactivex.Observable;
import okhttp3.RequestBody;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.PUT;

public interface KinveyApiService {
    @Headers({
            "Content-Type:application/json"
    })
    @PUT(Constants.API_DATA_ENDPOINT + "/{id}")
    Observable<PSDSData> sendData(@Header("Authorization") String authorization, String id, @Body PSDSData data);

    @PUT(Constants.API_DATA_ENDPOINT + "/{id}")
    Observable<PSDSData> sendData(@Header("Authorization") String authorization, String id, @Body RequestBody data);
}
