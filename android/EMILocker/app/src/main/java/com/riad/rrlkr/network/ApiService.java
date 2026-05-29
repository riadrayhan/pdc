package com.riad.rrlkr.network;

import com.riad.rrlkr.network.models.CommandAck;
import com.riad.rrlkr.network.models.CommandResponse;
import com.riad.rrlkr.network.models.DeviceEnrollRequest;
import com.riad.rrlkr.network.models.DeviceResponse;
import com.riad.rrlkr.network.models.HeartbeatRequest;
import com.riad.rrlkr.network.models.HeartbeatResponse;
import com.riad.rrlkr.service.DeviceReEnrollService;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * API Service Interface - Defines all API endpoints
 */
public interface ApiService {
    
    /**
     * Enroll a new device (legacy)
     */
    @POST("devices/enroll")
    Call<DeviceResponse> enrollDevice(@Body DeviceEnrollRequest request);
    
    /**
     * Enroll device with full fingerprint (v2)
     */
    @POST("enrollment/enroll")
    Call<DeviceReEnrollService.EnrollResponse> enrollDevice(@Body DeviceEnrollRequestV2 request);
    
    /**
     * Check device status by fingerprint
     * Used to identify device after factory reset
     */
    @POST("enrollment/check-status")
    Call<DeviceReEnrollService.DeviceStatusResponse> checkDeviceStatus(@Body DeviceEnrollRequestV2 request);
    
    /**
     * Send device heartbeat
     */
    @POST("devices/heartbeat")
    Call<HeartbeatResponse> sendHeartbeat(@Body HeartbeatRequest request);
    
    /**
     * Acknowledge command execution
     */
    @POST("commands/ack")
    Call<Void> acknowledgeCommand(@Body CommandAck ack);
    
    /**
     * Get pending commands for device
     */
    @GET("commands/pending/{deviceId}")
    Call<List<CommandResponse>> getPendingCommands(@Path("deviceId") String deviceId);

    /**
     * Report device GPS location to server
     */
    @POST("devices/{deviceId}/report-location")
    Call<Void> reportLocation(@Path("deviceId") String deviceId, @Body Map<String, Object> locationData);

    /**
     * Report device camera photo to server
     */
    @POST("devices/{deviceId}/report-photo")
    Call<Void> reportPhoto(@Path("deviceId") String deviceId, @Body Map<String, Object> photoData);

    /**
     * Report ZTE enrollment progress to server
     */
    @POST("zte/report-progress")
    Call<Map<String, Object>> reportZTEProgress(@Body Map<String, Object> progressData);
}
