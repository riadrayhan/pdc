package com.riad.rrlkr.network;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.webkit.CookieManager;

/**
 * Cloudflare "Just a moment..." (Turnstile) challenge solver.
 *
 * Architecture:
 * - CloudflareInterceptor catches challenge responses from OkHttp and calls solve(url).
 * - solve() launches CloudflareSolverActivity (visible) and blocks the OkHttp thread
 *   until the activity reports a result.
 * - Cookie is cached for re-use until expiry. cf_clearance typically lasts ~30 days.
 */
public final class CloudflareSolver {

    private static final String TAG = "CFSolver";
    private static final long SOLVE_TIMEOUT_MS = 60_000L;
    private static final long MIN_REFRESH_MS = 5_000L;

    private static volatile Context appContext;
    private static volatile String cachedCookieHeader;
    private static volatile long lastSolveMs;
    private static final Object LOCK = new Object();
    private static final Object SOLVE_DONE = new Object();
    private static volatile boolean solveFinished;
    private static volatile boolean solveSuccess;

    private CloudflareSolver() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static String getCookieHeader(String url) {
        try {
            String cm = CookieManager.getInstance().getCookie(url);
            if (cm != null && cm.contains("cf_clearance=")) return cm;
        } catch (Throwable ignored) {}
        return cachedCookieHeader;
    }

    /** Called from solver activity once a cookie is acquired. */
    static void setCookieFromActivity(String cookie) {
        cachedCookieHeader = cookie;
        lastSolveMs = System.currentTimeMillis();
    }

    /** Called from solver activity when it finishes (success or timeout). */
    static void notifySolveResult(boolean ok) {
        synchronized (SOLVE_DONE) {
            solveSuccess = ok;
            solveFinished = true;
            SOLVE_DONE.notifyAll();
        }
    }

    /**
     * Trigger a solve. Blocks calling thread up to SOLVE_TIMEOUT_MS.
     * Returns the cookie string or null on failure.
     */
    public static String solve(String url) {
        if (appContext == null) {
            Log.e(TAG, "CloudflareSolver not initialised");
            return null;
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cachedCookieHeader != null && (now - lastSolveMs) < MIN_REFRESH_MS) {
                return cachedCookieHeader;
            }

            solveFinished = false;
            solveSuccess = false;

            try {
                Intent i = new Intent(appContext, CloudflareSolverActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                i.putExtra(CloudflareSolverActivity.EXTRA_URL, url);
                appContext.startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to launch solver activity", t);
                return null;
            }

            synchronized (SOLVE_DONE) {
                long deadline = System.currentTimeMillis() + SOLVE_TIMEOUT_MS;
                while (!solveFinished) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try { SOLVE_DONE.wait(remaining); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (solveSuccess) {
                Log.i(TAG, "Cloudflare challenge solved");
                return cachedCookieHeader;
            }
            Log.w(TAG, "Cloudflare solve failed/timeout");
            return null;
        }
    }
}
