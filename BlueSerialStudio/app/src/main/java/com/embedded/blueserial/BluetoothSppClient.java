package com.embedded.blueserial;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe Classic Bluetooth SPP client. Never blocks Android's main thread. */
public final class BluetoothSppClient {
    public interface Listener {
        void onStatus(String label, boolean connected);
        void onReceived(byte[] data);
        void onSent(int count);
        void onError(String message);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writeQueue = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<byte[]> latestDrive = new AtomicReference<>();
    private final AtomicBoolean drivePumpQueued = new AtomicBoolean(false);
    private final Object socketLock = new Object();
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile boolean connected;

    public BluetoothSppClient(Listener listener) { this.listener = listener; }
    public boolean isConnected() { return connected; }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothAdapter adapter, BluetoothDevice device, String uuidText) {
        final UUID uuid;
        try {
            uuid = uuidText.trim().isEmpty() ? SPP_UUID : UUID.fromString(uuidText.trim());
        } catch (IllegalArgumentException exception) {
            emitError("UUID 无效，请检查设置中的服务 UUID");
            return;
        }
        disconnect(false);
        final int id = generation.get();
        status("连接中…", false);
        new Thread(() -> {
            BluetoothSocket candidate = null;
            try {
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) { }
                // Try authenticated RFCOMM first.
                candidate = device.createRfcommSocketToServiceRecord(uuid);
                if (!track(candidate, id)) return;
                try {
                    candidate.connect();
                } catch (IOException firstFailure) {
                    safeClose(candidate);
                    if (id != generation.get()) return;
                    // Some older serial modules do not support authenticated pairing.
                    candidate = device.createInsecureRfcommSocketToServiceRecord(uuid);
                    if (!track(candidate, id)) return;
                    candidate.connect();
                }
                if (id != generation.get()) { safeClose(candidate); return; }
                connected = true;
                String name = device.getName();
                status("已连接 · " + (name == null ? device.getAddress() : name), true);
                InputStream stream = candidate.getInputStream();
                byte[] buffer = new byte[2048];
                int count;
                while (generation.get() == id && (count = stream.read(buffer)) >= 0) {
                    if (count > 0) {
                        byte[] bytes = Arrays.copyOf(buffer, count);
                        ui.post(() -> { if (generation.get() == id) listener.onReceived(bytes); });
                    }
                }
            } catch (IOException | SecurityException exception) {
                if (generation.get() == id) emitError("连接/通信失败：" + exception.getMessage());
            } finally {
                safeClose(candidate);
                synchronized (socketLock) {
                    if (socket == candidate && generation.get() == id) socket = null;
                }
                if (generation.get() == id) {
                    connected = false;
                    status("未连接", false);
                }
            }
        }, "SPP-reader-" + id).start();
    }

    private boolean track(BluetoothSocket candidate, int id) {
        synchronized (socketLock) {
            if (generation.get() != id) { safeClose(candidate); return false; }
            socket = candidate;
            return true;
        }
    }

    /** Stop/neutral packets supersede any unsent motion/control backlog. */
    public void sendUrgent(byte[] bytes) {
        if (!connected || bytes.length == 0) return;
        latestDrive.set(null);
        writeQueue.getQueue().clear();
        send(bytes);
    }

    /** Coalesce continuously changing joystick/button states: never accumulate stale motion packets. */
    public void sendDrive(byte[] bytes) {
        if (!connected || bytes.length == 0) return;
        latestDrive.set(Arrays.copyOf(bytes, bytes.length));
        scheduleDrivePump();
    }

    private void scheduleDrivePump() {
        if (!drivePumpQueued.compareAndSet(false, true)) return;
        final int id = generation.get();
        writeQueue.execute(() -> {
            try {
                byte[] packet;
                while (connected && generation.get() == id
                        && (packet = latestDrive.getAndSet(null)) != null) {
                    writePacket(packet, id);
                }
            } finally {
                drivePumpQueued.set(false);
                if (connected && latestDrive.get() != null) scheduleDrivePump();
            }
        });
    }

    public void send(byte[] bytes) {
        if (bytes.length == 0) return;
        if (!connected) { emitError("请先连接蓝牙设备"); return; }
        final int id = generation.get();
        final byte[] packet = Arrays.copyOf(bytes, bytes.length);
        writeQueue.execute(() -> writePacket(packet, id));
    }

    private void writePacket(byte[] packet, int id) {
        if (!connected || generation.get() != id) return;
        BluetoothSocket connection = socket;
        if (connection == null) return;
        try {
            OutputStream output = connection.getOutputStream();
            output.write(packet);
            ui.post(() -> { if (generation.get() == id) listener.onSent(packet.length); });
        } catch (IOException exception) {
            if (generation.get() == id) {
                emitError("发送失败：" + exception.getMessage());
                disconnect(true);
            }
        }
    }

    public void disconnect() { disconnect(true); }

    private void disconnect(boolean notify) {
        generation.incrementAndGet();
        latestDrive.set(null);
        writeQueue.getQueue().clear();
        connected = false;
        synchronized (socketLock) {
            safeClose(socket);
            socket = null;
        }
        if (notify) status("未连接", false);
    }

    private static void safeClose(BluetoothSocket connection) {
        if (connection != null) try { connection.close(); } catch (IOException ignored) { }
    }

    private void status(String s, boolean active) {
        ui.post(() -> listener.onStatus(s, active));
    }
    private void emitError(String s) { ui.post(() -> listener.onError(s)); }
    public void shutdown() { disconnect(); writeQueue.shutdownNow(); }
}
