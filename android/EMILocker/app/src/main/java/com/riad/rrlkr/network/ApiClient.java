package com.riad.rrlkr.network;

import android.content.Context;

import com.riad.rrlkr.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * API Client - Singleton for Retrofit instance
 */
public class ApiClient {
    
    private static final String TAG = "ApiClient";
    
    private static Retrofit retrofit;
    private static ApiService apiService;
    private static String baseUrl = BuildConfig.SERVER_URL;
    
    /**
     * Initialize the API client
     */
    public static void init(Context context) {
        CloudflareSolver.init(context);
        createRetrofit();
        warmUp();
    }

    /**
     * Fire-and-forget hit to /health to wake Render's free-tier cold start
     * and pre-solve Cloudflare so the user's first real request is instant.
     */
    private static void warmUp() {
        new Thread(() -> {
            try {
                String base = baseUrl;
                String origin = base;
                int idx = base.indexOf("/", base.indexOf("://") + 3);
                if (idx > 0) origin = base.substring(0, idx);
                okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(origin + "/health")
                    .get()
                    .build();
                retrofit2.Call<?> ignored = null;
                // Reuse the Retrofit OkHttp client
                okhttp3.OkHttpClient client = (okhttp3.OkHttpClient) retrofit.callFactory();
                client.newCall(req).execute().close();
                android.util.Log.i(TAG, "Warm-up complete: " + origin + "/health");
            } catch (Throwable t) {
                android.util.Log.w(TAG, "Warm-up failed (will retry on demand): " + t.getMessage());
            }
        }, "ApiWarmUp").start();
    }
    
    /**
     * Set a new base URL (for dynamic server configuration)
     */
    public static void setBaseUrl(String url) {
        baseUrl = url;
        createRetrofit();
    }
    
    private static void createRetrofit() {
        // Logging interceptor
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(
            BuildConfig.DEBUG ? 
            HttpLoggingInterceptor.Level.BODY : 
            HttpLoggingInterceptor.Level.NONE
        );
        
        // Browser-like User-Agent to avoid Cloudflare bot-fight false positives on okhttp/*
        okhttp3.Interceptor uaInterceptor = chain -> chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; SM-A075F) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
        );

        // OkHttp client - generous timeouts because Render free-tier wakes up to ~60s
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(uaInterceptor)
            .addInterceptor(new RetryInterceptor())
            .addInterceptor(new CloudflareInterceptor())
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
        
        // Retrofit instance
        retrofit = new Retrofit.Builder()
            .baseUrl(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build();
        
        // Create API service
        apiService = retrofit.create(ApiService.class);
    }
    
    /**
     * Get the API service instance
     */
    public static ApiService getApiService() {
        if (apiService == null) {
            createRetrofit();
        }
        return apiService;
    }
    
    /**
     * Get the Retrofit instance
     */
    public static Retrofit getRetrofit() {
        if (retrofit == null) {
            createRetrofit();
        }
        return retrofit;
    }

    /**
     * Alias for getRetrofit() - for backward compatibility
     */
    public static Retrofit getClient() {
        return getRetrofit();
    }
}
