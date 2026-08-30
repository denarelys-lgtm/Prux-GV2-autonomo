package com.example.detectcamera;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** Own ADB client for Prux. Pairing is always explicit; reconnect is automatic after pairing. */
public final class PruxAdbEngine {
    private static final String TAG = "PruxAdbEngine";
    private static volatile PruxAdbEngine instance;
    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean connected;

    private PruxAdbEngine(Context context) { this.context = context.getApplicationContext(); }

    public static PruxAdbEngine get(Context context) {
        if (instance == null) synchronized (PruxAdbEngine.class) {
            if (instance == null) instance = new PruxAdbEngine(context);
        }
        return instance;
    }

    public boolean isConnected() { return connected; }

    public void pair(String host, int port, String code, Callback callback) {
        executor.execute(() -> {
            boolean ok = false;
            String message;
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw new IllegalStateException("Wireless Debugging requiere Android 11+");
                if (!code.matches("\\d{6}")) throw new IllegalArgumentException("El código debe tener 6 dígitos");
                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                ok = manager.pair(host, port, code);
                message = ok ? "Emparejamiento ADB completado" : "El emparejamiento fue rechazado";
                if (ok) {
                    boolean connectedNow = manager.autoConnect(context, 5000);
                    connected = connectedNow;
                    message += connectedNow ? " · ADB conectado" : " · Emparejado, esperando conexión";
                }
            } catch (Throwable t) {
                message = friendly(t);
                Log.e(TAG, "pair", t);
            }
            post(callback, ok, message);
        });
    }

    public void reconnect(Callback callback) {
        executor.execute(() -> {
            boolean ok = false;
            String message;
            try {
                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                ok = manager.autoConnect(context, 5000);
                connected = ok;
                message = ok ? "ADB conectado" : "No se encontró una conexión ADB emparejada";
            } catch (AdbPairingRequiredException e) {
                connected = false;
                message = "Se requiere emparejamiento manual";
            } catch (Throwable t) {
                connected = false;
                message = friendly(t);
                Log.e(TAG, "reconnect", t);
            }
            post(callback, ok, message);
        });
    }

    /** Executes only commands explicitly approved by Prux; arbitrary shell is intentionally not exposed. */
    public void executeAllowed(String command, Callback callback) {
        executor.execute(() -> {
            boolean ok = false;
            String output = "";
            try {
                if (!isAllowed(command)) throw new SecurityException("Comando no permitido por Prux");
                AbsAdbConnectionManager manager = PruxAdbConnectionManager.getInstance(context);
                if (!connected && !manager.autoConnect(context, 3000)) throw new IllegalStateException("ADB no conectado");
                connected = true;
                try (AdbStream stream = manager.openStream("shell:" + command)) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8));
                    long deadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < deadline) {
                        if (reader.ready()) {
                            String line = reader.readLine();
                            if (line == null) break;
                            sb.append(line).append('\n');
                        } else Thread.sleep(20);
                    }
                    output = sb.toString();
                    try (OutputStream ignored = stream.openOutputStream()) { }
                    ok = true;
                }
            } catch (Throwable t) {
                connected = false;
                output = friendly(t);
                Log.e(TAG, "executeAllowed: " + command, t);
            }
            post(callback, ok, output);
        });
    }

    private static boolean isAllowed(String command) {
        String c = command.trim();
        return c.matches("cmd appops set [A-Za-z0-9._]+ (RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|START_FOREGROUND) allow")
                || c.matches("dumpsys deviceidle whitelist \\+[A-Za-z0-9._]+")
                || c.matches("appops set [A-Za-z0-9._]+ PROJECT_MEDIA allow")
                || c.matches("pm grant [A-Za-z0-9._]+ android\\.permission\\.PROJECT_MEDIA")
                || c.matches("am start -n [A-Za-z0-9._]+/\\.ProjectionActivity");
    }

    private static String friendly(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private static void post(Callback callback, boolean ok, String message) {
        if (callback != null) callback.onResult(ok, message);
    }

    public interface Callback { void onResult(boolean success, String message); }
}
