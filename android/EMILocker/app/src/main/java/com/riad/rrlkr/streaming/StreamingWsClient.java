package com.riad.rrlkr.streaming;

import android.util.Log;

import com.riad.rrlkr.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin wrapper around OkHttp's WebSocket that handles the WSS URL derivation
 * from {@link BuildConfig#SERVER_URL} and silent reconnection.
 */
public class StreamingWsClient {

    private static final String TAG = "StreamWS";

    public interface Callback {
        default void onOpen() {}
        default void onClosed(String reason) {}
        default void onFailure(Throwable t) {}
        default void onTextMessage(String text) {}
    }

    private final String path;
    private final Callback callback;
    private final OkHttpClient client;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile boolean connected;
    private int reconnectAttempt = 0;

    public StreamingWsClient(String path, Callback callback) {
        this.path = path;
        this.callback = callback;
        this.client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    }

    public boolean isConnected() { return connected; }

    public void connect() {
        stopped.set(false);
        doConnect();
    }

    private void doConnect() {
        if (stopped.get()) return;
        String base = BuildConfig.SERVER_URL;            // https://.../api/v1
        String wsBase = base.replaceFirst("^https://", "wss://")
                            .replaceFirst("^http://", "ws://");
        if (!wsBase.endsWith("/")) wsBase += "/";
        String url = wsBase + path;
        Log.i(TAG, "Connecting " + url);

        Request req = new Request.Builder().url(url).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                reconnectAttempt = 0;
                Log.i(TAG, "WS open " + path);
                callback.onOpen();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                callback.onTextMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                Log.i(TAG, "WS closed " + code + " " + reason);
                callback.onClosed(reason);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                Log.w(TAG, "WS failure: " + t.getMessage());
                callback.onFailure(t);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (stopped.get()) return;
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = (long) Math.min(30000, Math.pow(2, reconnectAttempt) * 500);
        new Thread(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
            if (!stopped.get()) doConnect();
        }, "ws-reconnect").start();
    }

    /** Drop the current connection and retry now â€” call this when the
     *  network just came back online. */
    public void forceReconnect() {
        if (stopped.get()) return;
        reconnectAttempt = 0;
        WebSocket s = ws;
        if (s != null) {
            try { s.cancel(); } catch (Throwable ignored) {}
        }
        connected = false;
        new Thread(this::doConnect, "ws-force-reconnect").start();
    }

    public boolean sendBinary(byte[] data) {
        WebSocket s = ws;
        if (s == null) return false;
        try { return s.send(ByteString.of(data)); }
        catch (Throwable t) { return false; }
    }

    public boolean sendText(String text) {
        WebSocket s = ws;
        if (s == null) return false;
        try { return s.send(text); } catch (Throwable t) { return false; }
    }

    public void close() {
        stopped.set(true);
        connected = false;
        WebSocket s = ws;
        if (s != null) {
            try { s.close(1000, "client closed"); } catch (Throwable ignored) {}
        }
        ws = null;
    }
}
