package com.example.detectcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Activa la depuración inalámbrica (Android 11+) y, como fallback,
 * sondea el puerto legacy 5555.
 *
 * El puerto real de wireless debugging es ALEATORIO y solo se anuncia por
 * mDNS. No existe API pública para leerlo desde una app. Por eso este
 * resolver devuelve -1 la mayor parte del tiempo, señalando al llamador
 * que debe usar {@code AbsAdbConnectionManager.autoConnect()} (mDNS).
 *
 * El único camino real es:
 *   1) Encender el toggle vía Settings.Global (requiere WRITE_SECURE_SETTINGS)
 *   2) Dejar que autoConnect() descubra el puerto por mDNS.
 */
public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";

    private static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final int    LEGACY_ADB_TCP_PORT      = 5555;

    private AdbPortResolver() {}

    /**
     * Intenta habilitar wireless debugging y devuelve el puerto legacy 5555
     * si está alcanzable. En cualquier otro caso devuelve -1 para forzar
     * el descubrimiento por mDNS en el llamador.
     */
    public static int enableAndGetWirelessPort(@NonNull Context context) {
        enableWirelessDebugging(context);

        if (canConnect(LEGACY_ADB_TCP_PORT)) {
            Log.i(TAG, "Puerto ADB legacy alcanzable: " + LEGACY_ADB_TCP_PORT);
            return LEGACY_ADB_TCP_PORT;
        }

        // Wireless debugging usa puerto aleatorio: no se puede sondear.
        // El llamador debe usar autoConnect (mDNS).
        return -1;
    }

    /**
     * Escribe {@code adb_wifi_enabled = 1} en Settings.Global.
     * Requiere WRITE_SECURE_SETTINGS concedido por ADB:
     * <pre>
     *   adb shell pm grant &lt;pkg&gt; android.permission.WRITE_SECURE_SETTINGS
     * </pre>
     *
     * Algunas ROMs (MIUI, ColorOS, EMUI) ignoran este cambio y exigen que
     * el usuario active el toggle manualmente.
     *
     * @return true si el setting queda en 1.
     */
    public static boolean enableWirelessDebugging(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Wireless debugging requiere Android 11+");
            return false;
        }

        try {
            ContentResolver cr = context.getContentResolver();

            if (Settings.Global.getInt(cr, SETTING_ADB_WIFI_ENABLED, 0) == 1) {
                return true;
            }

            Settings.Global.putInt(cr, SETTING_ADB_WIFI_ENABLED, 1);
            Log.i(TAG, "Solicitado adb_wifi_enabled = 1");

            // adbd tarda en abrir el listener TCP tras el cambio.
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline) {
                if (Settings.Global.getInt(cr, SETTING_ADB_WIFI_ENABLED, 0) == 1) {
                    Thread.sleep(1_500L);
                    return true;
                }
                Thread.sleep(200L);
            }

            Log.w(TAG, "El toggle no se activó (ROM restrictiva?)");
            return false;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo activar wireless debugging: " + t);
            return false;
        }
    }

    private static boolean canConnect(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
