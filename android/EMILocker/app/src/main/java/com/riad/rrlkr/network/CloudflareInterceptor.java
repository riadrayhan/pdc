package com.riad.rrlkr.network;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp interceptor that detects Cloudflare challenge responses (typical on
 * cloud hosting free tier) and transparently solves them via WebView, then
 * retries the original request with the harvested cf_clearance cookie.
 */
public class CloudflareInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        Request withCookie = attachCookie(original);
        Response response = chain.proceed(withCookie);

        if (!isChallenge(response)) {
            return response;
        }

        // Peek body without consuming the stream we already have
        String snippet = peek(response);
        if (snippet != null && (snippet.contains("Just a moment") || snippet.contains("cf-mitigated")
                || snippet.contains("challenges.cloudflare.com"))) {
            response.close();
            HttpUrl url = original.url();
            String origin = url.scheme() + "://" + url.host() + "/";
            String cookie = CloudflareSolver.solve(origin);
            if (cookie != null) {
                Request retry = original.newBuilder()
                    .header("Cookie", cookie)
                    .build();
                return chain.proceed(retry);
            }
        }
        return response;
    }

    private Request attachCookie(Request original) {
        try {
            HttpUrl url = original.url();
            String origin = url.scheme() + "://" + url.host() + "/";
            String cookie = CloudflareSolver.getCookieHeader(origin);
            if (cookie != null && original.header("Cookie") == null) {
                return original.newBuilder().header("Cookie", cookie).build();
            }
        } catch (Throwable ignored) {}
        return original;
    }

    private boolean isChallenge(Response r) {
        int c = r.code();
        if (c != 403 && c != 503 && c != 429) return false;
        String server = r.header("Server", "");
        return server != null && server.toLowerCase().contains("cloudflare");
    }

    private String peek(Response r) {
        try {
            ResponseBody body = r.peekBody(2048);
            return body.string();
        } catch (Throwable t) {
            return null;
        }
    }
}
