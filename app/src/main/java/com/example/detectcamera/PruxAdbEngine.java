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

    private static final long FIRST_RECONNECT_DELAY_MS = 1_000L;
    private static final long MAX_RECONNECT_DELAY_MS   = 30_000L;
    private static final long HEALTH_CHECK_MS          = 15_000L;
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

    /** Protege la transición connected <-> disconnected. */
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

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    public void startPersistentMonitoring() {
        if (!monitoring.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "Monitor persistente ADB iniciado");
        monitor.scheduleWithFixedDelay(
                this::monitorConnection,
                0L,
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

    // =====================================================================
    // Monitor
    // =====================================================================

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

    /**
     * Heartbeat ligero: usa host:version (protocolo ADB) en lugar de
     * abrir un shell nuevo cada vez.
     */
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
                    Thread.sleep(20L);
                }
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Heartbeat falló: " + friendly(t));
            return false;
        }
    }

    /**
     * Encola reconexión con backoff exponencial. Si el intento falla, se
     * reprograma automáticamente sin esperar al siguiente tick del monitor.
     * Idempotente: si ya hay uno en curso, esta llamada no hace nada.
     */
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

    // =====================================================================
    // Conexión
    // =====================================================================

    /** @return true si quedó conectado. */
    private boolean doReconnect() {
        try {
            // 1. Asegura que el toggle de wireless debugging esté encendido.
            //    En Android 11+ se apaga en cada reinicio.
            AdbPortResolver.enableWirelessDebugging(context);

            // 2. Intenta puerto legacy (5555) o devuelve -1 para forzar mDNS.
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
                // mDNS encuentra el puerto real de wireless debugging.
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
            Log.w(TAG, "ADB todavía no disponible.");
            return false;

        } catch (AdbPairingRequiredException e) {
            markDisconnected();
            Log.w(TAG, "ADB requiere emparejamiento.");
            return false;
        } catch (Throwable t) {
            markDisconnected();
            Log.e(TAG, "Reconexión fallida", t);
            return false;
        }
    }

    /**
     * Garantiza conexión activa bajo lock para evitar dobles conexiones
     * concurrentes desde varios comandos.
     *
     * La reconexión la dispara el llamador (executeAllowed vía catch) si
     * este método devuelve false, así que aquí no se programa nada.
     */
    private boolean ensureConnected(@NonNull AbsAdbConnectionManager manager)
            throws IOException, InterruptedException {
        synchronized (connectionLock) {
            if (connected) {
                return true;
            }

            // Enciende el toggle si hace falta (por si viene de un cold start).
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
            } catch (AdbPairingRequiredException e) {
                Log.w(TAG, "Requerido emparejamiento manual al intentar conectar en ensureConnected");
            }

            connected = false;
            return false;
        }
    }

    // =====================================================================
    // API pública
    // =====================================================================

    public void pair(@Nullable String host,
                     int port,
                     @Nullable String code,
                     @Nullable Callback callback) {

        executor.execute(() -> {
            boolean success = false;
            String message;

            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    throw new IllegalStateException(
                            "Wireless Debugging requiere Android 11+");
                }
                if (host == null || host.trim().isEmpty()) {
                    throw new IllegalArgumentException("Host inválido");
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Puerto inválido");
                }
                if (code == null || !code.matches("\\d{6}")) {
                    throw new IllegalArgumentException(
                            "El código debe tener 6 dígitos");
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager.getInstance(context);

                Log.i(TAG, "Iniciando pairing " + host + ":" + port);
                success = manager.pair(host, port, code);

                if (!success) {
                    notifyAdbState(false);
                    post(callback, false, "El emparejamiento fue rechazado");
                    return;
                }

                Log.i(TAG, "Pairing completado.");

                int activePort = AdbPortResolver.enableAndGetWirelessPort(context);
                if (activePort > 0) {
                    lastKnownPort = activePort;
                }

                boolean connectedNow = false;
                try {
                    if (activePort > 0) {
                        connectedNow = manager.connect("127.0.0.1", activePort);
                    }
                    if (!connectedNow) {
                        connectedNow = manager.autoConnect(
                                context, AUTOCONNECT_TIMEOUT_MS);
                    }
                } catch (AdbPairingRequiredException e) {
                    Log.w(TAG, "Emparejamiento finalizado pero requiere autenticación adicional");
                }

                synchronized (connectionLock) {
                    connected = connectedNow;
                }

                if (connectedNow) {
                    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                    notifyAdbState(true);
                    message = "Emparejamiento completado y ADB conectado";
                } else {
                    notifyAdbState(false);
                    message = "Emparejamiento completado; esperando conexión ADB";
                }

                // Arranca el monitor y, si la conexión no fue inmediata,
                // dispara un intento ahora sin esperar al primer tick.
                startPersistentMonitoring();
                if (!connectedNow) {
                    requestReconnect();
                }

            } catch (Throwable t) {
                synchronized (connectionLock) {
                    connected = false;
                }
                message = friendly(t);
                Log.e(TAG, "Error durante pairing", t);
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
                // Asegura toggle encendido antes de intentar.
                AdbPortResolver.enableWirelessDebugging(context);

                int port = AdbPortResolver.enableAndGetWirelessPort(context);
                if (port > 0) {
                    lastKnownPort = port;
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager.getInstance(context);

                if (port > 0) {
                    success = manager.connect("127.0.0.1", port);
                }
                if (!success) {
                    success = manager.autoConnect(
                            context, AUTOCONNECT_TIMEOUT_MS);
                }

                if (success) {
                    synchronized (connectionLock) {
                        connected = true;
                    }
                    reconnectDelay = FIRST_RECONNECT_DELAY_MS;
                    message = "ADB conectado";
                    if (port > 0) {
                        message += " en el puerto " + port;
                    }
                    notifyAdbState(true);
                } else {
                    markDisconnected();
                    message = "No se encontró una conexión ADB emparejada";
                    // Fuerza un intento inmediato en lugar de esperar al monitor.
                    requestReconnect();
                }

            } catch (AdbPairingRequiredException e) {
                markDisconnected();
                message = "Se requiere emparejamiento manual";
                requestReconnect();
            } catch (Throwable t) {
                markDisconnected();
                message = friendly(t);
                Log.e(TAG, "reconnect", t);
                requestReconnect();
            }

            post(callback, success, message);
        });
    }

    /**
     * Ejecuta un comando de la lista blanca. La validación se hace fuera del
     * try para que un comando no permitido no desconecte ADB.
     */
    public void executeAllowed(@Nullable String command,
                               @Nullable Callback callback) {

        if (!isAllowed(command)) {
            post(callback, false, "Comando no permitido por Prux");
            return;
        }

        final String cmd = command.trim();

        executor.execute(() -> {
            boolean success = false;
            String output = "";

            try {
                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager.getInstance(context);

                if (!ensureConnected(manager)) {
                    throw new IllegalStateException("ADB no conectado");
                }

                output  = executeShell(manager, cmd);
                success = true;

            } catch (Throwable t) {
                output = friendly(t);
                markDisconnected();
                Log.e(TAG, "executeAllowed: " + cmd, t);
                requestReconnect();
            }

            post(callback, success, output);
        });
    }

    // =====================================================================
    // Shell
    // =====================================================================

    @NonNull
    private String executeShell(@NonNull AbsAdbConnectionManager manager,
                                @NonNull String command) throws Exception {

        try (AdbStream stream = manager.openStream("shell:" + command)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            stream.openInputStream(),
                            StandardCharsets.UTF_8));

            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;

            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    output.append(line).append('\n');
                } else {
                    Thread.sleep(20L);
                }
            }

            return output.toString();
        }
    }

    // =====================================================================
    // Lista blanca
    // =====================================================================

    private static final String PKG = "[A-Za-z0-9._]+";

    private static boolean isAllowed(@Nullable String command) {
        if (command == null) {
            return false;
        }
        String c = command.trim();
        if (c.isEmpty()) {
            return false;
        }

        // appops (con o sin prefijo "cmd" según el dispositivo).
        if (c.matches("(cmd )?appops set " + PKG
                + " (RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|START_FOREGROUND) allow")) {
            return true;
        }
        if (c.matches("(cmd )?appops set " + PKG + " PROJECT_MEDIA allow")) {
            return true;
        }

        // Doze whitelist.
        if (c.matches("dumpsys deviceidle whitelist \\+" + PKG)) {
            return true;
        }

        // Lanzar la Activity de proyección.
        if (c.matches("am start -n " + PKG + "/\\.ProjectionActivity")) {
            return true;
        }

        // Notificaciones: snooze limitado al propio paquete.
        if (c.equals("cmd notification list")) {
            return true;
        }
        if (c.matches("cmd notification snooze --package " + PKG + " .*")) {
            return true;
        }

        return false;
    }

    // =====================================================================
    // Estado y utilidades
    // =====================================================================

    /**
     * Marca como desconectado. Solo emite broadcast cuando realmente había
     * conexión, para no spamear receivers.
     */
    private void markDisconnected() {
        synchronized (connectionLock) {
            if (!connected) {
                return;
            }
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
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo publicar estado ADB");
        }
    }

    @NonNull
    private static String friendly(@NonNull Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }

    private static void post(@Nullable Callback callback,
                             boolean success,
                             @NonNull String message) {
        if (callback != null) {
            callback.onResult(success, message);
        }
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }
}
