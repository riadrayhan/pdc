package com.riad.rrlkr.network;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Retries idempotent GET requests on transient upstream errors typical of
 * Render free-tier cold starts (502/503/504, plus IO timeouts after wakeup).
 * Uses exponential backoff up to 3 attempts total.
 */
public class RetryInterceptor implements Interceptor {

    private static final String TAG = "RetryInterceptor";
    private static final int MAX_ATTEMPTS = 3;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request req = chain.request();
        IOException lastIo = null;
        Response response = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (response != null) {
                try { response.close(); } catch (Throwable ignored) {}
                response = null;
            }
            try {
                response = chain.proceed(req);
            } catch (IOException io) {
                lastIo = io;
                Log.w(TAG, "IO error attempt " + attempt + " for " + req.url() + ": " + io.getMessage());
                if (attempt == MAX_ATTEMPTS) throw io;
                sleep(attempt);
                continue;
            }

            int code = response.code();
            boolean transientUpstream = code == 502 || code == 503 || code == 504;
            if (!transientUpstream || attempt == MAX_ATTEMPTS) {
                return response;
            }
            Log.w(TAG, "HTTP " + code + " attempt " + attempt + " for " + req.url() + " - retrying");
            sleep(attempt);
        }

        if (response != null) return response;
        throw lastIo != null ? lastIo : new IOException("Retry exhausted");
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(1500L * attempt); // 1.5s, 3s
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
