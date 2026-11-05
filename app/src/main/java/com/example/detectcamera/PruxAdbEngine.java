package com.example.detectcamera;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

public final class PruxAdbEngine {

    private static final String TAG = "PruxAdbEngine";

    private static final long FIRST_RECONNECT_DELAY_MS = 2_000L;
    private static final long MAX_RECONNECT_DELAY_MS   = 60_000L;
    private static final long HEALTH_CHECK_MS          = 30_000L; // Optimizado: menos frecuencia para ahorrar batería/CPU
    private static final long HEARTBEAT_TIMEOUT_MS     = 2_000L;
    private static final long COMMAND_TIMEOUT_MS       = 7_000L;
    private static final long AUTOCONNECT_TIMEOUT_MS   = 5_000L;

    private static final String ACTION_ADB_STATE =
            "com.example.detectcamera.PRUX_ADB_STATE";
    private static final String EXTRA_AVAILABLE = "available";

    private static volatile PruxAdbEngine instance;

    private final Context context;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "PruxAdb-Worker");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PruxAdb-Monitor");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean monitoring       = new AtomicBoolean(false);
    private final AtomicBoolean reconnectRunning = new AtomicBoolean(false);

    private final Object connectionLock = new Object();

    private volatile boolean connected;
    private volatile long    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
    private volatile int     lastKnownPort  = -1;

    private PruxAdbEngine(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    public static PruxAdbEngine get(@NonNull Context context) {
        if (instance == null) {
            synchronized (PruxAdbEngine.class) {
                if (instance == null) {
                    instance = new PruxAdbEngine(context);
                }
            }
        }
        return instance;
    }

    public boolean isConnected() { return connected; }
    public int getLastKnownPort() { return lastKnownPort; }

    public void startPersistentMonitoring() {
        if (!monitoring.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "Monitor persistente ADB iniciado");
        monitor.scheduleWithFixedDelay(
                this::monitorConnection,
                5_000L,
                HEALTH_CHECK_MS,
                TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        monitoring.set(false);
        monitor.shutdownNow();
        executor.shutdownNow();
        synchronized (connectionLock) {
            connected = false;
        }
    }

    private void monitorConnection() {
        try {
            if (connected) {
                if (performHeartbeat()) {
                    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                    return;
                }
                Log.w(TAG, "Heartbeat falló.");
                markDisconnected();
            }
            requestReconnect();
        } catch (Throwable t) {
            Log.e(TAG, "Error en monitor ADB", t);
            markDisconnected();
            requestReconnect();
        }
    }

    private boolean performHeartbeat() {
        try {
            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager.getInstance(context);

            try (AdbStream stream = manager.openStream("host:version")) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                stream.openInputStream(),
                                StandardCharsets.UTF_8));

                long deadline = System.currentTimeMillis() + HEARTBEAT_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        return reader.readLine() != null;
                    }
                    Thread.sleep(50L); // Pausa optimizada para liberar CPU
                }
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Heartbeat falló: " + friendly(t));
            return false;
        }
    }

    private void requestReconnect() {
        if (!reconnectRunning.compareAndSet(false, true)) {
            return;
        }

        final long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2L, MAX_RECONNECT_DELAY_MS);

        monitor.schedule(() -> {
            boolean ok = false;
            try {
                ok = doReconnect();
            } catch (Throwable t) {
                Log.e(TAG, "doReconnect lanzó excepción", t);
            } finally {
                reconnectRunning.set(false);
            }
            if (!ok && monitoring.get()) {
                requestReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean doReconnect() {
        try {
            AdbPortResolver.enableWirelessDebugging(context);
            int port = AdbPortResolver.enableAndGetWirelessPort(context);
            if (port > 0) {
                lastKnownPort = port;
            }

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager.getInstance(context);

            boolean ok = false;
            if (port > 0) {
                ok = manager.connect("127.0.0.1", port);
            }
            if (!ok) {
                ok = manager.autoConnect(context, AUTOCONNECT_TIMEOUT_MS);
            }

            if (ok) {
                synchronized (connectionLock) {
                    connected = true;
                }
                reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                Log.i(TAG, "ADB conectado. Puerto=" + port);
                notifyAdbState(true);
                return true;
            }

            markDisconnected();
            return false;

        } catch (AdbPairingRequiredException e) {
            markDisconnected();
            return false;
        } catch (Throwable t) {
            markDisconnected();
            return false;
        }
    }

    private boolean ensureConnected(@NonNull AbsAdbConnectionManager manager)
            throws IOException, InterruptedException {
        synchronized (connectionLock) {
            if (connected) {
                return true;
            }

            AdbPortResolver.enableWirelessDebugging(context);

            try {
                int port = AdbPortResolver.enableAndGetWirelessPort(context);
                if (port > 0) {
                    lastKnownPort = port;
                    if (manager.connect("127.0.0.1", port)) {
                        connected = true;
                        reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                        notifyAdbState(true);
                        return true;
                    }
                }

                if (manager.autoConnect(context, AUTOCONNECT_TIMEOUT_MS)) {
                    connected = true;
                    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                    notifyAdbState(true);
                    return true;
                }
            } catch (AdbPairingRequiredException ignored) {}

            connected = false;
            return false;
        }
    }

    public void pair(@Nullable String host, int port, @Nullable String code, @Nullable Callback callback) {
        executor.execute(() -> {
            boolean success = false;
            String message;

            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    throw new IllegalStateException("Wireless Debugging requiere Android 11+");
                }
                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                success = manager.pair(host, port, code);

                if (!success) {
                    notifyAdbState(false);
                    post(callback, false, "Emparejamiento rechazado");
                    return;
                }

                int activePort = AdbPortResolver.enableAndGetWirelessPort(context);
                if (activePort > 0) lastKnownPort = activePort;

                boolean connectedNow = false;
                try {
                    if (activePort > 0) connectedNow = manager.connect("127.0.0.1", activePort);
                    if (!connectedNow) connectedNow = manager.autoConnect(context, AUTOCONNECT_TIMEOUT_MS);
                } catch (AdbPairingRequiredException ignored) {}

                synchronized (connectionLock) {
                    connected = connectedNow;
                }

                message = connectedNow ? "Emparejado y conectado" : "Emparejado; esperando conexión";
                startPersistentMonitoring();
                if (!connectedNow) requestReconnect();

            } catch (Throwable t) {
                synchronized (connectionLock) { connected = false; }
                message = friendly(t);
                notifyAdbState(false);
            }
            post(callback, success, message);
        });
    }

    public void reconnect(@Nullable Callback callback) {
        startPersistentMonitoring();
        executor.execute(() -> {
            boolean success = false;
            String message;
            try {
                AdbPortResolver.enableWirelessDebugging(context);
                int port = AdbPortResolver.enableAndGetWirelessPort(context);
                if (port > 0) lastKnownPort = port;

                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                if (port > 0) success = manager.connect("127.0.0.1", port);
                if (!success) success = manager.autoConnect(context, AUTOCONNECT_TIMEOUT_MS);

                if (success) {
                    synchronized (connectionLock) { connected = true; }
                    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                    message = "ADB conectado";
                    notifyAdbState(true);
                } else {
                    markDisconnected();
                    message = "ADB no encontrado";
                    requestReconnect();
                }
            } catch (Throwable t) {
                markDisconnected();
                message = friendly(t);
                requestReconnect();
            }
            post(callback, success, message);
        });
    }

    public void executeAllowed(@Nullable String command, @Nullable Callback callback) {
        if (!isAllowed(command)) {
            post(callback, false, "Comando no permitido");
            return;
        }

        final String cmd = command.trim();
        executor.execute(() -> {
            boolean success = false;
            String output = "";
            try {
                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                if (!ensureConnected(manager)) {
                    throw new IllegalStateException("ADB no conectado");
                }
                output = executeShell(manager, cmd);
                success = true;
            } catch (Throwable t) {
                output = friendly(t);
                markDisconnected();
                requestReconnect();
            }
            post(callback, success, output);
        });
    }

    @NonNull
    private String executeShell(@NonNull AbsAdbConnectionManager manager, @NonNull String command) throws Exception {
        try (AdbStream stream = manager.openStream("shell:" + command)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8));

            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;

            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    output.append(line).append('\n');
                } else {
                    Thread.sleep(30L); // Pausa pasiva optimizada
                }
            }
            return output.toString();
        }
    }

    private static final String PKG = "[A-Za-z0-9._]+";

    private static boolean isAllowed(@Nullable String command) {
        if (command == null) return false;
        String c = command.trim();
        if (c.matches("(cmd )?appops set " + PKG + " (RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|START_FOREGROUND) allow")) return true;
        if (c.matches("(cmd )?appops set " + PKG + " PROJECT_MEDIA allow")) return true;
        if (c.matches("dumpsys deviceidle whitelist \\+" + PKG)) return true;
        if (c.matches("am start -n " + PKG + "/\\.ProjectionActivity")) return true;
        if (c.equals("cmd notification list")) return true;
        if (c.matches("cmd notification snooze --package " + PKG + " .*")) return true;
        return false;
    }

    private void markDisconnected() {
        synchronized (connectionLock) {
            if (!connected) return;
            connected = false;
        }
        notifyAdbState(false);
    }

    private void notifyAdbState(boolean state) {
        try {
            Intent event = new Intent(ACTION_ADB_STATE);
            event.setPackage(context.getPackageName());
            event.putExtra(EXTRA_AVAILABLE, state);
            context.sendBroadcast(event);
        } catch (Throwable ignored) {}
    }

    @NonNull
    private static String friendly(@NonNull Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return (message == null || message.trim().isEmpty()) ? current.getClass().getSimpleName() : message;
    }

    private static void post(@Nullable Callback callback, boolean success, @NonNull String message) {
        if (callback != null) callback.onResult(success, message);
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }
}
