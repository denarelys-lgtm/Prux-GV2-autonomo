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

/**
 * Motor ADB inalámbrico de Android.
 *
 * Funciones:
 * - Emparejamiento explícito.
 * - Reconexión automática con resolución de puerto por Binder.
 * - Monitor persistente.
 * - Detección de pérdida de conexión.
 * - Backoff progresivo para evitar conexiones simultáneas.
 * - Recuperación automática cuando vuelve la conexión.
 */
public final class PruxAdbEngine {

    private static final String TAG = "PruxAdbEngine";

    private static final long FIRST_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 30000L;
    private static final long HEALTH_CHECK_MS = 10000L;

    private static volatile PruxAdbEngine instance;

    private final Context context;

    private final ExecutorService executor =
            Executors.newCachedThreadPool();

    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread =
                        new Thread(
                                r,
                                "Android-ADB-Monitor"
                        );

                thread.setDaemon(true);

                return thread;
            });

    private final AtomicBoolean monitoring =
            new AtomicBoolean(false);

    private final AtomicBoolean reconnectRunning =
            new AtomicBoolean(false);

    private volatile boolean connected = false;

    private volatile long reconnectDelay =
            FIRST_RECONNECT_DELAY_MS;

    private PruxAdbEngine(Context context) {
        this.context =
                context.getApplicationContext();
    }

    public static PruxAdbEngine get(Context context) {

        if (instance == null) {

            synchronized (PruxAdbEngine.class) {

                if (instance == null) {
                    instance =
                            new PruxAdbEngine(context);
                }
            }
        }

        return instance;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Inicia el monitor persistente de ADB.
     *
     * Puede llamarse varias veces sin crear varios monitores.
     */
    public void startPersistentMonitoring() {

        if (!monitoring.compareAndSet(false, true)) {
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

    /**
     * Comprueba periódicamente el estado real de ADB.
     */
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
                        "Heartbeat ADB falló. Conexión perdida."
                );

                connected = false;

                notifyAdbState(false);
            }

            requestReconnect();

        } catch (Throwable t) {

            connected = false;

            Log.e(
                    TAG,
                    "Error en monitor ADB",
                    t
            );

            notifyAdbState(false);

            requestReconnect();
        }
    }

    /**
     * Comprueba que el transporte ADB sigue funcionando.
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
                        System.currentTimeMillis() + 2000L;

                boolean received = false;

                while (
                        System.currentTimeMillis()
                                < deadline
                ) {

                    if (reader.ready()) {

                        String line =
                                reader.readLine();

                        if (line != null) {
                            received = true;
                        }

                        break;
                    }

                    Thread.sleep(20L);
                }

                try (
                        OutputStream ignored =
                                stream.openOutputStream()
                ) {
                    // Cierre limpio del canal.
                }

                return received;
            }

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Heartbeat ADB no disponible: "
                            + friendly(t)
            );

            return false;
        }
    }

    /**
     * Programa una única reconexión.
     */
    private void requestReconnect() {

        if (!reconnectRunning.compareAndSet(
                false,
                true
        )) {
            return;
        }

        final long delay =
                reconnectDelay;

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

        reconnectDelay =
                Math.min(
                        reconnectDelay * 2L,
                        MAX_RECONNECT_DELAY_MS
                );
    }

    /**
     * Intenta recuperar una conexión ADB previamente emparejada consultando Binder.
     */
    private void doReconnect() {

        try {

            int activePort = AdbPortResolver.enableAndGetWirelessPort();
            Log.i(TAG, "Puerto ADB detectado desde Binder: " + activePort);

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager
                            .getInstance(context);

            boolean ok = false;

            if (activePort > 0) {
                ok = manager.connect("127.0.0.1", activePort);
            }

            if (!ok) {
                ok = manager.autoConnect(
                        context,
                        5000
                );
            }

            connected = ok;

            if (ok) {

                reconnectDelay =
                        FIRST_RECONNECT_DELAY_MS;

                Log.i(
                        TAG,
                        "ADB inalámbrico reconectado en puerto " + activePort
                );

                notifyAdbState(true);

            } else {

                Log.w(
                        TAG,
                        "ADB todavía no disponible en el puerto " + activePort
                );

                notifyAdbState(false);
            }

        } catch (AdbPairingRequiredException e) {

            connected = false;

            Log.w(
                    TAG,
                    "ADB requiere emparejamiento manual"
            );

            notifyAdbState(false);

        } catch (Throwable t) {

            connected = false;

            Log.e(
                    TAG,
                    "Reconexión ADB fallida",
                    t
            );

            notifyAdbState(false);
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

            boolean ok = false;
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
                        code == null
                                || !code.matches("\\d{6}")
                ) {

                    throw new IllegalArgumentException(
                            "El código debe tener 6 dígitos"
                    );
                }

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager
                                .getInstance(context);

                ok =
                        manager.pair(
                                host,
                                port,
                                code
                        );

                message =
                        ok
                                ? "Emparejamiento ADB completado"
                                : "El emparejamiento fue rechazado";

                if (ok) {

                    int activePort = AdbPortResolver.enableAndGetWirelessPort();
                    boolean connectedNow = false;

                    if (activePort > 0) {
                        connectedNow = manager.connect("127.0.0.1", activePort);
                    }

                    if (!connectedNow) {
                        connectedNow = manager.autoConnect(context, 5000);
                    }

                    connected =
                            connectedNow;

                    if (connectedNow) {

                        reconnectDelay =
                                FIRST_RECONNECT_DELAY_MS;

                        message +=
                                " · ADB conectado";

                        notifyAdbState(true);

                    } else {

                        message +=
                                " · Emparejado, esperando conexión";

                        notifyAdbState(false);
                    }

                    startPersistentMonitoring();
                }

            } catch (Throwable t) {

                message =
                        friendly(t);

                Log.e(
                        TAG,
                        "pair",
                        t
                );
            }

            post(
                    callback,
                    ok,
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

            boolean ok = false;
            String message;

            try {

                int activePort = AdbPortResolver.enableAndGetWirelessPort();

                AbsAdbConnectionManager manager =
                        PruxAdbConnectionManager
                                .getInstance(context);

                if (activePort > 0) {
                    ok = manager.connect("127.0.0.1", activePort);
                }

                if (!ok) {
                    ok = manager.autoConnect(
                            context,
                            5000
                    );
                }

                connected =
                        ok;

                if (ok) {

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    message =
                            "ADB conectado en el puerto " + activePort;

                    notifyAdbState(true);

                } else {

                    message =
                            "No se encontró una conexión ADB emparejada";

                    notifyAdbState(false);
                }

            } catch (
                    AdbPairingRequiredException e
            ) {

                connected = false;

                message =
                        "Se requiere emparejamiento manual";

                notifyAdbState(false);

            } catch (Throwable t) {

                connected = false;

                message =
                        friendly(t);

                Log.e(
                        TAG,
                        "reconnect",
                        t
                );

                notifyAdbState(false);
            }

            post(
                    callback,
                    ok,
                    message
            );
        });
    }

    /**
     * Ejecuta solamente comandos aprobados por Android.
     */
    public void executeAllowed(
            String command,
            Callback callback
    ) {

        executor.execute(() -> {

            boolean ok = false;
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

                if (!connected) {

                    int activePort = AdbPortResolver.enableAndGetWirelessPort();
                    boolean recovered = false;

                    if (activePort > 0) {
                        recovered = manager.connect("127.0.0.1", activePort);
                    }

                    if (!recovered) {
                        recovered = manager.autoConnect(context, 5000);
                    }

                    connected =
                            recovered;

                    if (!recovered) {

                        throw new IllegalStateException(
                                "ADB no conectado"
                        );
                    }

                    reconnectDelay =
                            FIRST_RECONNECT_DELAY_MS;

                    notifyAdbState(true);
                }

                try (
                        AdbStream stream =
                                manager.openStream(
                                        "shell:" + command
                                )
                ) {

                    StringBuilder sb =
                            new StringBuilder();

                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            stream.openInputStream(),
                                            StandardCharsets.UTF_8
                                    )
                            );

                    long deadline =
                            System.currentTimeMillis()
                                    + 5000L;

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

                            sb.append(line)
                                    .append('\n');

                        } else {

                            Thread.sleep(20L);
                        }
                    }

                    output =
                            sb.toString();

                    try (
                            OutputStream ignored =
                                    stream.openOutputStream()
                    ) {
                        // Cierre limpio.
                    }

                    ok = true;
                }

            } catch (Throwable t) {

                connected = false;

                output =
                        friendly(t);

                Log.e(
                        TAG,
                        "executeAllowed: "
                                + command,
                        t
                );

                notifyAdbState(false);

                requestReconnect();
            }

            post(
                    callback,
                    ok,
                    output
            );
        });
    }

    /**
     * Lista blanca de comandos aprobados.
     */
    private static boolean isAllowed(
            String command
    ) {

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
                || c.startsWith(
                "cmd notification snooze"
        );
    }

    /**
     * Publica el estado actual de ADB.
     */
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

        Throwable current = t;

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
            boolean ok,
            String message
    ) {

        if (callback != null) {

            callback.onResult(
                    ok,
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
