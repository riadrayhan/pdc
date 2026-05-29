package com.riad.rrlkr.network;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Visible WebView host that solves Cloudflare's Turnstile challenge.
 * Launched once when CloudflareInterceptor detects a challenge. Closes
 * itself as soon as cf_clearance cookie is set.
 */
public class CloudflareSolverActivity extends Activity {

    public static final String EXTRA_URL = "url";
    private static final String TAG = "CFSolverAct";
    private static final long TIMEOUT_MS = 45_000L;

    private WebView wv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable poll;
    private long deadline;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) { finishWithResult(false); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Verifying connection with server\u2026");
        title.setTextSize(16f);
        title.setPadding(32, 48, 32, 16);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar pb = new ProgressBar(this);
        FrameLayout pbWrap = new FrameLayout(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        pbWrap.addView(pb, pbLp);
        root.addView(pbWrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("If a checkbox appears below, tap it once.");
        hint.setTextSize(12f);
        hint.setPadding(32, 16, 32, 16);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wv = new WebView(this);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; SM-A075F) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        wv.setWebViewClient(new WebViewClient() {});
        root.addView(wv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        wv.loadUrl(url);

        poll = new Runnable() {
            @Override public void run() {
                String c = CookieManager.getInstance().getCookie(url);
                if (c != null && c.contains("cf_clearance=")) {
                    Log.i(TAG, "cf_clearance acquired");
                    CloudflareSolver.setCookieFromActivity(c);
                    finishWithResult(true);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "Timed out waiting for cf_clearance");
                    finishWithResult(false);
                    return;
                }
                h.postDelayed(this, 700L);
            }
        };
        h.postDelayed(poll, 1500L);
    }

    private void finishWithResult(boolean ok) {
        try { h.removeCallbacks(poll); } catch (Throwable ignored) {}
        try { if (wv != null) { wv.stopLoading(); wv.destroy(); wv = null; } } catch (Throwable ignored) {}
        CloudflareSolver.notifySolveResult(ok);
        finish();
    }

    @Override
    protected void onDestroy() {
        try { h.removeCallbacks(poll); } catch (Throwable ignored) {}
        try { if (wv != null) { wv.destroy(); wv = null; } } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
