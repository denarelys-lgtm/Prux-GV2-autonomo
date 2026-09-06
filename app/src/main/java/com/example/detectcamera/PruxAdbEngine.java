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
 * Núcleo ADB de Prux.
 *
 * Arquitectura:
 *
 * ACTIVAR
 *   ↓
 * DESCUBRIR
 *   ↓
 * PAIRING
 *   ↓
 * IDENTIDAD PERSISTENTE
 *   ↓
 * CONEXIÓN
 *   ↓
 * HEARTBEAT
 *   ↓
 * RECUPERACIÓN AUTOMÁTICA
 */
public final class PruxAdbEngine {

    private static final String TAG =
            "PruxAdbEngine";

    private static final long FIRST_RECONNECT_DELAY_MS =
            1000L;

    private static final long MAX_RECONNECT_DELAY_MS =
            30000L;

    private static final long HEALTH_CHECK_MS =
            10000L;

    private static volatile PruxAdbEngine instance;

    private final Context context;

    private final ExecutorService executor =
            Executors.newCachedThreadPool();

    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {

                        Thread thread =
                                new Thread(
                                        runnable,
                                        "Prux-ADB-Monitor"
                                );

                        thread.setDaemon(true);

                        return thread;
                    }
            );

    private final AtomicBoolean monitoring =
            new AtomicBoolean(false);

    private final AtomicBoolean reconnectRunning =
            new AtomicBoolean(false);

    private volatile boolean connected =
            false;

    private volatile long reconnectDelay =
            FIRST_RECONNECT_DELAY_MS;

    private PruxAdbEngine(
            Context context
    ) {

        this.context =
                context.getApplicationContext();
    }

    public static PruxAdbEngine get(
            Context context
    ) {

        if (instance == null) {

            synchronized (PruxAdbEngine.class) {

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

    /*
     * =============================================================
     * MONITOR
     * =============================================================
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
                "Monitor ADB iniciado."
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

                connected =
                        false;

                notifyAdbState(false);
            }

            requestReconnect();

        } catch (Throwable t) {

            connected =
                    false;

            Log.e(
                    TAG,
                    "Error en monitor ADB",
                    t
            );

            notifyAdbState(false);

            requestReconnect();
        }
    }

    private boolean performHeartbeat() {

        try {

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager
                            .getInstance(
                                    context
                            );

            try (
                    AdbStream stream =
                            manager.openStream(
                                    "shell:echo PRUX_OK"
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
                                + 2500L;

                while (
                        System.currentTimeMillis()
                                < deadline
                ) {

                    if (reader.ready()) {

                        String line =
                                reader.readLine();

                        return line != null
                                && line.contains(
                                "PRUX_OK"
                        );
                    }

                    Thread.sleep(20L);
                }

            }

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Heartbeat no disponible: " +
                            friendly(t)
            );
        }

        return false;
    }

    /*
     * =============================================================
     * RECUPERACIÓN
     * =============================================================
     */

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

                        reconnectRunning.set(
                                false
                        );
                    }

                },
                delay,
                TimeUnit.MILLISECONDS
        );
    }

    private void doReconnect() {

        try {

            Log.i(
                    TAG,
                    "Intentando recuperar ADB..."
            );

            int port =
                    AdbPortResolver
                            .enableAndGetWirelessPort();

            AbsAdbConnectionManager manager =
                    PruxAdbConnectionManager
                            .getInstance(
                                    context
                            );

            boolean ok =
                    connectBest(
                            manager,
                            port
                    );

            connected =
                    ok;

            if (ok) {

                reconnectDelay =
                        FIRST_RECONNECT_DELAY_MS;

                if (port > 0) {

                    PruxAdbState.saveEndpoint(
                            context,
                            "127.0.0.1",
                            port
                    );
                }

                Log.i(
                        TAG,
                        "ADB recuperado."
                );

                notifyAdbState(true);

            } else {

                Log.w(
                        TAG,
                        "ADB todavía no disponible."
                );

                notifyAdbState(false);
            }

        } catch (
                AdbPairingRequiredException e
        ) {

            connected =
                    false;

            Log.w(
                    TAG,
                    "ADB requiere pairing."
            );

            notifyAdbState(false);

        } catch (Throwable t) {

            connected =
                    false;

            Log.e(
                    TAG,
                    "Recuperación ADB fallida",
                    t
            );

            notifyAdbState(false);
        }
    }

    /**
     * Intenta primero el último endpoint conocido,
     * después el puerto recién detectado y finalmente
     * autodiscovery.
     */
    private boolean connectBest(
            AbsAdbConnectionManager manager,
            int detectedPort
    ) {

        /*
         * ---------------------------------------------------------
         * Último endpoint guardado
         * ---------------------------------------------------------
         */

        try {

            String lastHost =
                    PruxAdbState.getLastHost(
                            context
                    );

            int lastPort =
                    PruxAdbState.getLastPort(
                            context
                    );

            if (
                    lastHost != null
                            &&
                            lastPort > 0
            ) {

                Log.i(
                        TAG,
                        "Probando último endpoint: " +
                                lastHost +
                                ":" +
                                lastPort
                );

                if (
                        manager.connect(
                                lastHost,
                                lastPort
                        )
                ) {

                    return true;
                }
            }

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Último endpoint no disponible."
            );
        }

        /*
         * ---------------------------------------------------------
         * Puerto detectado
         * ---------------------------------------------------------
         */

        if (detectedPort > 0) {

            try {

                if (
                        manager.connect(
                                "127.0.0.1",
                                detectedPort
                        )
                ) {

                    PruxAdbState.saveEndpoint(
                            context,
                            "127.0.0.1",
                            detectedPort
                    );

                    return true;
                }

            } catch (Throwable t) {

                Log.w(
                        TAG,
                        "Conexión local fallida."
                );
            }
        }

        /*
         * ---------------------------------------------------------
         * Autodiscovery
         * ---------------------------------------------------------
         */

        try {

            if (
                    manager.autoConnect(
                            context,
                            5000
                    )
            ) {

                return true;
            }

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Autodiscovery falló: " +
                            friendly(t)
            );
        }

        return false;
    }

    /*
     * =============================================================
     * PAIRING
     * =============================================================
     */

    public void pair(
            String host,
            int port,
            String code,
            Callback callback
    ) {

        executor.execute(
                () -> {

                    boolean ok =
                            false;

                    String message;

                    try {

                        if (
                                Build.VERSION.SDK_INT
                                        < Build.VERSION_CODES.R
                        ) {

                            throw new IllegalStateException(
                                    "Wireless Debugging requiere Android 11+."
                            );
                        }

                        if (
                                host == null
                                        ||
                                        host.trim().isEmpty()
                        ) {

                            host =
                                    "127.0.0.1";
                        }

                        if (port <= 0) {

                            throw new IllegalArgumentException(
                                    "Puerto de pairing inválido."
                            );
                        }

                        if (
                                code == null
                                        ||
                                        !code.matches(
                                                "\\d{6}"
                                        )
                        ) {

                            throw new IllegalArgumentException(
                                    "El código debe contener 6 dígitos."
                            );
                        }

                        AbsAdbConnectionManager manager =
                                PruxAdbConnectionManager
                                        .getInstance(
                                                context
                                        );

                        Log.i(
                                TAG,
                                "Iniciando pairing en " +
                                        host +
                                        ":" +
                                        port
                        );

                        ok =
                                manager.pair(
                                        host,
                                        port,
                                        code
                                );

                        if (!ok) {

                            message =
                                    "El emparejamiento fue rechazado.";

                        } else {

                            /*
                             * El pairing fue exitoso.
                             * Guardamos el estado.
                             */
                            PruxAdbState.markPaired(
                                    context,
                                    true
                            );

                            PruxAdbState.saveEndpoint(
                                    context,
                                    host,
                                    port
                            );

                            message =
                                    "Emparejamiento ADB completado.";

                            /*
                             * Ahora buscamos el puerto real
                             * de conexión.
                             */
                            int activePort =
                                    AdbPortResolver
                                            .getWirelessPort();

                            boolean connectedNow =
                                    connectBest(
                                            manager,
                                            activePort
                                    );

                            connected =
                                    connectedNow;

                            if (connectedNow) {

                                reconnectDelay =
                                        FIRST_RECONNECT_DELAY_MS;

                                if (
                                        activePort > 0
                                ) {

                                    PruxAdbState
                                            .saveEndpoint(
                                                    context,
                                                    "127.0.0.1",
                                                    activePort
                                            );
                                }

                                message +=
                                        " · ADB conectado.";

                                notifyAdbState(
                                        true
                                );

                            } else {

                                message +=
                                        " · Emparejado; esperando conexión.";

                                notifyAdbState(
                                        false
                                );
                            }

                            startPersistentMonitoring();
                        }

                    } catch (Throwable t) {

                        message =
                                friendly(t);

                        Log.e(
                                TAG,
                                "Error en pairing",
                                t
                        );
                    }

                    post(
                            callback,
                            ok,
                            message
                    );
                }
        );
    }

    /*
     * =============================================================
     * RECONNECT MANUAL
     * =============================================================
     */

    public void reconnect(
            Callback callback
    ) {

        startPersistentMonitoring();

        executor.execute(
                () -> {

                    boolean ok =
                            false;

                    String message;

                    try {

                        int port =
                                AdbPortResolver
                                        .enableAndGetWirelessPort();

                        AbsAdbConnectionManager manager =
                                PruxAdbConnectionManager
                                        .getInstance(
                                                context
                                        );

                        ok =
                                connectBest(
                                        manager,
                                        port
                                );

                        connected =
                                ok;

                        if (ok) {

                            reconnectDelay =
                                    FIRST_RECONNECT_DELAY_MS;

                            if (port > 0) {

                                PruxAdbState
                                        .saveEndpoint(
                                                context,
                                                "127.0.0.1",
                                                port
                                        );
                            }

                            message =
                                    "ADB conectado.";

                            notifyAdbState(
                                    true
                            );

                        } else {

                            message =
                                    PruxAdbState
                                            .isPaired(
                                                    context
                                            )
                                            ?
                                            "Emparejado pero ADB no está disponible."
                                            :
                                            "No existe un pairing ADB.";

                            notifyAdbState(
                                    false
                            );
                        }

                    } catch (
                            AdbPairingRequiredException e
                    ) {

                        connected =
                                false;

                        message =
                                "Se requiere emparejamiento manual.";

                        notifyAdbState(
                                false
                        );

                    } catch (Throwable t) {

                        connected =
                                false;

                        message =
                                friendly(t);

                        Log.e(
                                TAG,
                                "Error reconectando",
                                t
                        );

                        notifyAdbState(
                                false
                        );
                    }

                    post(
                            callback,
                            ok,
                            message
                    );
                }
        );
    }

    /*
     * =============================================================
     * EJECUCIÓN PRIVILEGIADA
     * =============================================================
     */

    public void executeAllowed(
            String command,
            Callback callback
    ) {

        executor.execute(
                () -> {

                    boolean ok =
                            false;

                    String output =
                            "";

                    try {

                        if (
                                command == null
                                        ||
                                        !isAllowed(
                                                command
                                        )
                        ) {

                            throw new SecurityException(
                                    "Comando no permitido por Prux."
                            );
                        }

                        AbsAdbConnectionManager manager =
                                PruxAdbConnectionManager
                                        .getInstance(
                                                context
                                        );

                        /*
                         * Si no hay conexión,
                         * Prux intenta recuperarla.
                         */
                        if (!connected) {

                            int port =
                                    AdbPortResolver
                                            .enableAndGetWirelessPort();

                            boolean recovered =
                                    connectBest(
                                            manager,
                                            port
                                    );

                            connected =
                                    recovered;

                            if (!recovered) {

                                throw new IllegalStateException(
                                        "ADB no conectado."
                                );
                            }

                            notifyAdbState(
                                    true
                            );
                        }

                        try (
                                AdbStream stream =
                                        manager.openStream(
                                                "shell:" +
                                                        command
                                        )
                        ) {

                            BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    stream.openInputStream(),
                                                    StandardCharsets.UTF_8
                                            )
                                    );

                            StringBuilder result =
                                    new StringBuilder();

                            long deadline =
                                    System.currentTimeMillis()
                                            + 10000L;

                            while (
                                    System.currentTimeMillis()
                                            < deadline
                            ) {

                                if (
                                        reader.ready()
                                ) {

                                    String line =
                                            reader.readLine();

                                    if (
                                            line == null
                                    ) {
                                        break;
                                    }

                                    result
                                            .append(line)
                                            .append('\n');

                                } else {

                                    Thread.sleep(
                                            20L
                                    );
                                }
                            }

                            output =
                                    result.toString();

                            ok =
                                    true;
                        }

                    } catch (Throwable t) {

                        connected =
                                false;

                        output =
                                friendly(t);

                        Log.e(
                                TAG,
                                "executeAllowed: " +
                                        command,
                                t
                        );

                        notifyAdbState(
                                false
                        );

                        requestReconnect();
                    }

                    post(
                            callback,
                            ok,
                            output
                    );
                }
        );
    }

    /*
     * =============================================================
     * LISTA BLANCA
     * =============================================================
     */

    private static boolean isAllowed(
            String command
    ) {

        if (
                command == null ||
                command.trim().isEmpty()
        ) {

            return false;
        }

        String c =
                command.trim();

        /*
         * AppOps.
         */
        if (
                c.matches(
                        "cmd appops set [A-Za-z0-9._]+ " +
                                "(RUN_IN_BACKGROUND|" +
                                "RUN_ANY_IN_BACKGROUND|" +
                                "START_FOREGROUND) allow"
                )
        ) {

            return true;
        }

        /*
         * Doze whitelist.
         */
        if (
                c.matches(
                        "dumpsys deviceidle whitelist " +
                                "\\+[A-Za-z0-9._]+"
                )
        ) {

            return true;
        }

        /*
         * MediaProjection.
         */
        if (
                c.matches(
                        "appops set [A-Za-z0-9._]+ " +
                                "PROJECT_MEDIA allow"
                )
        ) {

            return true;
        }

        if (
                c.matches(
                        "pm grant [A-Za-z0-9._]+ " +
                                "android\\.permission\\.PROJECT_MEDIA"
                )
        ) {

            return true;
        }

        /*
         * ProjectionActivity.
         */
        if (
                c.matches(
                        "am start -n [A-Za-z0-9._]+/" +
                                "\\.ProjectionActivity"
                )
        ) {

            return true;
        }

        /*
         * Notificaciones.
         */
        if (
                c.equals(
                        "cmd notification list"
                )
        ) {

            return true;
        }

        if (
                c.startsWith(
                        "cmd notification snooze"
                )
        ) {

            return true;
        }

        return false;
    }

    /*
     * =============================================================
     * ESTADO
     * =============================================================
     */

    private void notifyAdbState(
            boolean state
    ) {

        try {

            Intent intent =
                    new Intent(
                            "com.example.detectcamera.PRUX_ADB_STATE"
                    );

            intent.setPackage(
                    context.getPackageName()
            );

            intent.putExtra(
                    "available",
                    state
            );

            context.sendBroadcast(
                    intent
            );

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "No se pudo publicar estado ADB."
            );
        }
    }

    /*
     * =============================================================
     * UTILIDADES
     * =============================================================
     */

    private static String friendly(
            Throwable throwable
    ) {

        Throwable current =
                throwable;

        while (
                current.getCause()
                        != null
        ) {

            current =
                    current.getCause();
        }

        String message =
                current.getMessage();

        if (
                message == null
                        ||
                        message.trim().isEmpty()
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
