package com.permobil.psds.wearos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface KinveyApiService {
    @POST(Constants.API_DATA_ENDPOINT)
    Call<PSDSData> sendData(@Header("Authorization") String authorization, @Body PSDSData data);
}
