package com.example.detectcamera;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    private static final String TAG =
            "PruxAdbEngine";

    private static final long FIRST_RECONNECT_DELAY_MS =
            1000L;

    private static final long MAX_RECONNECT_DELAY_MS =
            30000L;

    private static final long HEALTH_CHECK_MS =
            10000L;

    private static final long COMMAND_TIMEOUT_MS =
            7000L;

    private static volatile PruxAdbEngine instance;

    private final Context context;

    private final ExecutorService executor =
            Executors.newCachedThreadPool();

    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {

                        Thread thread =
                                new Thread(
                                        r,
                                        "Android-ADB-Monitor"
                                );

                        thread.setDaemon(true);

                        return thread;
                    }
            );

    private final AtomicBoolean monitoring =
            new AtomicBoolean(false);

    private final AtomicBoolean reconnectRunning =
            new AtomicBoolean(false);

    private volatile boolean connected;

    private volatile long reconnectDelay =
            FIRST_RECONNECT_DELAY_MS;

    private volatile int lastKnownPort =
            -1;

    private PruxAdbEngine(Context context) {

        this.context =
                context.getApplicationContext();
    }

    public static PruxAdbEngine get(
            Context context
    ) {

        if (instance == null) {

            synchronized (
                    PruxAdbEngine.class
            ) {

                if (instance == null) {

                    instance =
                            new PruxAdbEngine(
                                    context
                            );
                }
            }
        }

        return instance;
    }

    public boolean isConnected() {
        return connected;
    }

    public int getLastKnownPort() {
        return lastKnownPort;
    }

    /**
     * Arranca un único monitor persistente.
     */
    public void startPersistentMonitoring() {

        if (
                !monitoring.compareAndSet(
                        false,
                        true
                )
        ) {
            return;
        }

        Log.i(
                TAG,
                "Monitor persistente ADB iniciado"
        );

        monitor.scheduleWithFixedDelay(
                this::monitorConnection,
                0L,
                HEALTH_CHECK_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void monitorConnection() {

        try {

            if (connected) {

                if (performHeartbeat()) {

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    return;
                }

                Log.w(
                        TAG,
                        "Heartbeat falló."
                );

                markDisconnected();
            }

            requestReconnect();

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "Error en monitor ADB",
                    t
            );

            markDisconnected();

            requestReconnect();
        }
    }

    /**
     * Heartbeat real contra el transporte ADB.
     */
    private boolean performHeartbeat() {

        try {

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager
                            .getInstance(context);

            try (
                    AdbStream stream =
                            manager.openStream(
                                    "shell:echo android"
                            )
            ) {

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        stream.openInputStream(),
                                        StandardCharsets.UTF_8
                                )
                        );

                long deadline =
                        System.currentTimeMillis()
                                + 2000L;

                while (
                        System.currentTimeMillis()
                                < deadline
                ) {

                    if (reader.ready()) {

                        String line =
                                reader.readLine();

                        return line != null;
                    }

                    Thread.sleep(20L);
                }

                return false;
            }

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Heartbeat falló: "
                            + friendly(t)
            );

            return false;
        }
    }

    private void requestReconnect() {

        if (
                !reconnectRunning.compareAndSet(
                        false,
                        true
                )
        ) {
            return;
        }

        final long delay =
                reconnectDelay;

        reconnectDelay =
                Math.min(
                        reconnectDelay * 2L,
                        MAX_RECONNECT_DELAY_MS
                );

        monitor.schedule(
                () -> {

                    try {
                        doReconnect();
                    } finally {
                        reconnectRunning.set(false);
                    }

                },
                delay,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Recupera una conexión ya emparejada.
     */
    private void doReconnect() {

        try {

            int port =
                    AdbPortResolver
                            .enableAndGetWirelessPort();

            if (port > 0) {
                lastKnownPort = port;
            }

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager
                            .getInstance(context);

            boolean ok = false;

            /*
             * Primero utilizamos el puerto detectado.
             */
            if (port > 0) {

                ok =
                        manager.connect(
                                "127.0.0.1",
                                port
                        );
            }

            /*
             * Si no funciona, dejamos que la biblioteca
             * encuentre la conexión emparejada.
             */
            if (!ok) {

                ok =
                        manager.autoConnect(
                                context,
                                5000
                        );
            }

            if (ok) {

                connected = true;

                reconnectDelay =
                        FIRST_RECONNECT_DELAY_MS;

                Log.i(
                        TAG,
                        "ADB conectado. Puerto="
                                + port
                );

                notifyAdbState(true);

            } else {

                markDisconnected();

                Log.w(
                        TAG,
                        "ADB todavía no disponible."
                );
            }

        } catch (
                AdbPairingRequiredException e
        ) {

            markDisconnected();

            Log.w(
                    TAG,
                    "ADB requiere emparejamiento."
            );

        } catch (Throwable t) {

            markDisconnected();

            Log.e(
                    TAG,
                    "Reconexión fallida",
                    t
            );
        }
    }

    /**
     * Emparejamiento inicial.
     */
    public void pair(
            String host,
            int port,
            String code,
            Callback callback
    ) {

        executor.execute(() -> {

            boolean success =
                    false;

            String message;

            try {

                if (
                        Build.VERSION.SDK_INT
                                < Build.VERSION_CODES.R
                ) {

                    throw new IllegalStateException(
                            "Wireless Debugging requiere Android 11+"
                    );
                }

                if (
                        host == null
                                || host.trim().isEmpty()
                ) {

                    throw new IllegalArgumentException(
                            "Host inválido"
                    );
                }

                if (
                        port < 1
                                || port > 65535
                ) {

                    throw new IllegalArgumentException(
                            "Puerto inválido"
                    );
                }

                if (
                        code == null
                                || !code.matches(
                                "\\d{6}"
                        )
                ) {

                    throw new IllegalArgumentException(
                            "El código debe tener 6 dígitos"
                    );
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager
                                .getInstance(context);

                Log.i(
                        TAG,
                        "Iniciando pairing "
                                + host
                                + ":"
                                + port
                );

                success =
                        manager.pair(
                                host,
                                port,
                                code
                        );

                if (!success) {

                    message =
                            "El emparejamiento fue rechazado";

                    notifyAdbState(false);

                    post(
                            callback,
                            false,
                            message
                    );

                    return;
                }

                /*
                 * Pairing completado.
                 */
                Log.i(
                        TAG,
                        "Pairing completado."
                );

                /*
                 * Ahora intentamos conexión inmediata.
                 */
                int activePort =
                        AdbPortResolver
                                .enableAndGetWirelessPort();

                if (activePort > 0) {
                    lastKnownPort = activePort;
                }

                boolean connectedNow =
                        false;

                if (activePort > 0) {

                    connectedNow =
                            manager.connect(
                                    "127.0.0.1",
                                    activePort
                            );
                }

                if (!connectedNow) {

                    connectedNow =
                            manager.autoConnect(
                                    context,
                                    5000
                            );
                }

                if (connectedNow) {

                    connected = true;

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    notifyAdbState(true);

                    message =
                            "Emparejamiento completado y ADB conectado";

                } else {

                    connected = false;

                    notifyAdbState(false);

                    message =
                            "Emparejamiento completado; esperando conexión ADB";
                }

                startPersistentMonitoring();

            } catch (Throwable t) {

                connected = false;

                message =
                        friendly(t);

                Log.e(
                        TAG,
                        "Error durante pairing",
                        t
                );

                notifyAdbState(false);
            }

            post(
                    callback,
                    success,
                    message
            );
        });
    }

    /**
     * Reconexión manual.
     */
    public void reconnect(
            Callback callback
    ) {

        startPersistentMonitoring();

        executor.execute(() -> {

            boolean success =
                    false;

            String message;

            try {

                int port =
                        AdbPortResolver
                                .enableAndGetWirelessPort();

                if (port > 0) {
                    lastKnownPort = port;
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager
                                .getInstance(context);

                if (port > 0) {

                    success =
                            manager.connect(
                                    "127.0.0.1",
                                    port
                            );
                }

                if (!success) {

                    success =
                            manager.autoConnect(
                                    context,
                                    5000
                            );
                }

                if (success) {

                    connected = true;

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    message =
                            "ADB conectado";

                    if (port > 0) {
                        message +=
                                " en el puerto " + port;
                    }

                    notifyAdbState(true);

                } else {

                    markDisconnected();

                    message =
                            "No se encontró una conexión ADB emparejada";
                }

            } catch (
                    AdbPairingRequiredException e
            ) {

                markDisconnected();

                message =
                        "Se requiere emparejamiento manual";

            } catch (Throwable t) {

                markDisconnected();

                message =
                        friendly(t);

                Log.e(
                        TAG,
                        "reconnect",
                        t
                );
            }

            post(
                    callback,
                    success,
                    message
            );
        });
    }

    /**
     * Ejecuta un comando de la lista blanca.
     */
    public void executeAllowed(
            String command,
            Callback callback
    ) {

        executor.execute(() -> {

            boolean success =
                    false;

            String output = "";

            try {

                if (!isAllowed(command)) {

                    throw new SecurityException(
                            "Comando no permitido por Prux"
                    );
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager
                                .getInstance(context);

                /*
                 * Recuperación automática antes del comando.
                 */
                if (!connected) {

                    int port =
                            AdbPortResolver
                                    .enableAndGetWirelessPort();

                    boolean recovered =
                            false;

                    if (port > 0) {

                        lastKnownPort = port;

                        recovered =
                                manager.connect(
                                        "127.0.0.1",
                                        port
                                );
                    }

                    if (!recovered) {

                        recovered =
                                manager.autoConnect(
                                        context,
                                        5000
                                );
                    }

                    if (!recovered) {

                        throw new IllegalStateException(
                                "ADB no conectado"
                        );
                    }

                    connected = true;

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    notifyAdbState(true);
                }

                output =
                        executeShell(
                                manager,
                                command
                        );

                success = true;

            } catch (Throwable t) {

                output =
                        friendly(t);

                markDisconnected();

                Log.e(
                        TAG,
                        "executeAllowed: "
                                + command,
                        t
                );

                requestReconnect();
            }

            post(
                    callback,
                    success,
                    output
            );
        });
    }

    private String executeShell(
            AbsAdbConnectionManager manager,
            String command
    ) throws Exception {

        try (
                AdbStream stream =
                        manager.openStream(
                                "shell:" + command
                        )
        ) {

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    stream.openInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder output =
                    new StringBuilder();

            long deadline =
                    System.currentTimeMillis()
                            + COMMAND_TIMEOUT_MS;

            while (
                    System.currentTimeMillis()
                            < deadline
            ) {

                if (reader.ready()) {

                    String line =
                            reader.readLine();

                    if (line == null) {
                        break;
                    }

                    output
                            .append(line)
                            .append('\n');

                } else {

                    Thread.sleep(20L);
                }
            }

            return output.toString();
        }
    }

    /**
     * Lista blanca.
     */
    private static boolean isAllowed(
            String command
    ) {

        if (
                command == null
                        || command.trim().isEmpty()
        ) {
            return false;
        }

        String c =
                command.trim();

        return c.matches(
                "cmd appops set [A-Za-z0-9._]+ "
                        + "(RUN_IN_BACKGROUND|"
                        + "RUN_ANY_IN_BACKGROUND|"
                        + "START_FOREGROUND) allow"
        )

                || c.matches(
                "dumpsys deviceidle whitelist "
                        + "\\+[A-Za-z0-9._]+"
        )

                || c.matches(
                "appops set [A-Za-z0-9._]+ "
                        + "PROJECT_MEDIA allow"
        )

                || c.matches(
                "pm grant [A-Za-z0-9._]+ "
                        + "android\\.permission\\.PROJECT_MEDIA"
        )

                || c.matches(
                "am start -n [A-Za-z0-9._]+/"
                        + "\\.ProjectionActivity"
        )

                || c.equals(
                "cmd notification list"
        )

                || c.matches(
                "cmd notification snooze .*"
        );
    }

    private void markDisconnected() {

        boolean wasConnected =
                connected;

        connected = false;

        if (wasConnected) {
            notifyAdbState(false);
        } else {
            notifyAdbState(false);
        }
    }

    private void notifyAdbState(
            boolean state
    ) {

        try {

            Intent event =
                    new Intent(
                            "com.example.detectcamera.PRUX_ADB_STATE"
                    );

            event.setPackage(
                    context.getPackageName()
            );

            event.putExtra(
                    "available",
                    state
            );

            context.sendBroadcast(event);

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "No se pudo publicar estado ADB"
            );
        }
    }

    private static String friendly(
            Throwable t
    ) {

        Throwable current =
                t;

        while (
                current.getCause() != null
        ) {

            current =
                    current.getCause();
        }

        String message =
                current.getMessage();

        if (
                message == null
                        || message.trim().isEmpty()
        ) {

            return current
                    .getClass()
                    .getSimpleName();
        }

        return message;
    }

    private static void post(
            Callback callback,
            boolean success,
            String message
    ) {

        if (callback != null) {

            callback.onResult(
                    success,
                    message
            );
        }
    }

    public interface Callback {

        void onResult(
                boolean success,
                String message
        );
    }
}
