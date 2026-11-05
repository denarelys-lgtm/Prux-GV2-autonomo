package com.example.detectcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import java.net.InetSocketAddress;
import java.net.Socket;

public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";
    private static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final int    LEGACY_ADB_TCP_PORT      = 5555;

    private AdbPortResolver() {}

    public static int enableAndGetWirelessPort(@NonNull Context context) {
        enableWirelessDebugging(context);

        if (canConnect(LEGACY_ADB_TCP_PORT)) {
            Log.i(TAG, "Puerto ADB legacy alcanzable: " + LEGACY_ADB_TCP_PORT);
            return LEGACY_ADB_TCP_PORT;
        }
        return -1;
    }

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

            // Optimización: Reducir la comprobación agresiva a un intervalo seguro de espera pasiva
            int intentos = 0;
            while (intentos < 10) {
                if (Settings.Global.getInt(cr, SETTING_ADB_WIFI_ENABLED, 0) == 1) {
                    Thread.sleep(500L);
                    return true;
                }
                Thread.sleep(300L);
                intentos++;
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
