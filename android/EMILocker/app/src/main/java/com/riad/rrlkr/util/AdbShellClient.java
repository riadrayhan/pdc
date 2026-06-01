package com.riad.rrlkr.util;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal ADB-over-TCP client that connects to the device's own ADB daemon
 * at 127.0.0.1:5555 and executes a shell command.
 *
 * Works on devices where:
 *  - ADB TCP is enabled (Developer Options → ADB over network, or built-in
 *    persist.service.adb.enable=1 in some Chinese ROMs / userdebug builds)
 *  - ADB auth is not required (userdebug / eng builds) OR the device
 *    auto-trusts localhost connections.
 *
 * If the server responds with an AUTH challenge (stock Android), the call
 * fails gracefully with "AUTH required".
 */
public final class AdbShellClient {

    private static final String TAG = "AdbShellClient";

    private static final String HOST    = "127.0.0.1";
    private static final int    PORT    = 5555;
    private static final int    TIMEOUT = 6_000; // ms

    // ADB message command IDs (little-endian uint32)
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private static final int ADB_VERSION  = 0x01000000;
    private static final int MAX_PAYLOAD  = 256 * 1024;
    private static final int LOCAL_ID     = 1;

    public interface Callback {
        void onSuccess(String output);
        void onFailure(String reason);
    }

    private AdbShellClient() {}

    /**
     * Run a shell command on the local ADB daemon. Callback fires on a
     * background thread; switch to main thread before updating UI.
     */
    public static void runCommand(String command, Callback cb) {
        new Thread(() -> {
            try {
                String out = execute(command);
                cb.onSuccess(out);
            } catch (Exception e) {
                Log.w(TAG, "ADB shell failed: " + e.getMessage());
                cb.onFailure(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "adb-local").start();
    }

    /** Blocking execution — call from a background thread. */
    public static String execute(String command) throws Exception {
        Socket socket = new Socket();
        socket.setSoTimeout(TIMEOUT);
        socket.connect(new InetSocketAddress(HOST, PORT), 3_000);

        try {
            DataInputStream in  = new DataInputStream(socket.getInputStream());
            OutputStream    out = socket.getOutputStream();

            // ── Step 1: CNXN handshake ────────────────────────────────────
            send(out, A_CNXN, ADB_VERSION, MAX_PAYLOAD,
                "host::features=cmd,shell_v2");

            // ── Step 2: Read peer's first message ─────────────────────────
            int[] hdr  = readHeader(in);
            byte[] data = readData(in, hdr[3]);

            if (hdr[0] == A_AUTH) {
                throw new Exception("AUTH required — ADB needs key authorization on this ROM");
            }
            if (hdr[0] != A_CNXN) {
                throw new Exception("Expected CNXN, got 0x" + Integer.toHexString(hdr[0]));
            }

            // ── Step 3: OPEN a shell service ──────────────────────────────
            send(out, A_OPEN, LOCAL_ID, 0, "shell:" + command + "\0");

            // ── Step 4: Wait for OKAY ─────────────────────────────────────
            hdr  = readHeader(in);
            data = readData(in, hdr[3]);

            if (hdr[0] == A_CLSE) {
                throw new Exception("Shell service refused (CLSE after OPEN)");
            }
            if (hdr[0] != A_OKAY) {
                throw new Exception("Expected OKAY after OPEN, got 0x"
                    + Integer.toHexString(hdr[0]));
            }

            int remoteId = hdr[1]; // peer's local-id becomes our remote-id

            // Send OKAY to acknowledge
            send(out, A_OKAY, LOCAL_ID, remoteId, "");

            // ── Step 5: Collect WRTE output until CLSE ────────────────────
            StringBuilder sb      = new StringBuilder();
            long          deadline = System.currentTimeMillis() + 5_000;

            while (System.currentTimeMillis() < deadline) {
                hdr  = readHeader(in);
                data = readData(in, hdr[3]);

                if (hdr[0] == A_WRTE) {
                    sb.append(new String(data, "UTF-8"));
                    send(out, A_OKAY, LOCAL_ID, remoteId, ""); // flow-control ack
                } else if (hdr[0] == A_CLSE) {
                    break;
                }
                // A_OKAY = just an ack, skip
            }

            // Graceful close
            try { send(out, A_CLSE, LOCAL_ID, remoteId, ""); } catch (Throwable ignored) {}

            return sb.toString().trim();

        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    // ─── ADB protocol helpers ─────────────────────────────────────────────

    private static void send(OutputStream out, int cmd, int arg0, int arg1,
                              String payload) throws IOException {
        byte[] body = payload.isEmpty() ? new byte[0] : payload.getBytes("UTF-8");
        int checksum = 0;
        for (byte b : body) checksum += (b & 0xFF);

        ByteBuffer buf = ByteBuffer
            .allocate(24 + body.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(body.length);
        buf.putInt(checksum);
        buf.putInt(cmd ^ 0xFFFF_FFFF);
        buf.put(body);
        out.write(buf.array());
        out.flush();
    }

    private static int[] readHeader(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer buf = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{
            buf.getInt(), // [0] command
            buf.getInt(), // [1] arg0
            buf.getInt(), // [2] arg1
            buf.getInt(), // [3] data_length
            buf.getInt(), // [4] checksum (ignored)
            buf.getInt(), // [5] magic   (ignored)
        };
    }

    private static byte[] readData(DataInputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] d = new byte[len];
        in.readFully(d);
        return d;
    }
}
