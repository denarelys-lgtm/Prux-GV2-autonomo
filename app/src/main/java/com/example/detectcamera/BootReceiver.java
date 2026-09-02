package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Arranque automático de Android después del boot.
 *
 * El ajuste hidden_api_policy se intenta aplicar antes de
 * arrancar los servicios principales de la aplicación.
 */
public class BootReceiver
        extends BroadcastReceiver {

    private static final String TAG =
            "DetectCameraBoot";

    private static final String HIDE_ADB_NOTIFICATION =
            "settings put global hidden_api_policy 1";

    @Override
    public void onReceive(
            Context context,
            Intent intent
    ) {

        if (intent == null) {
            return;
        }

        String action =
                intent.getAction();

        if (
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        ||
                "android.intent.action.QUICKBOOT_POWERON"
                                .equals(action)
        ) {

            Log.i(
                    TAG,
                    "Reinicio detectado."
            );

            /*
             * =====================================================
             * 1. PREPARACIÓN DE ANDROID
             * =====================================================
             *
             * Intentamos aplicar el ajuste ANTES de iniciar
             * el monitor ADB, servidor, cámara y demás servicios.
             */
            try {

                PruxAdbEngine adb =
                        PruxAdbEngine.get(context);

                adb.executeShell(
                        HIDE_ADB_NOTIFICATION,
                        (success, message) -> {

                            if (success) {

                                Log.i(
                                        TAG,
                                        "hidden_api_policy=1 aplicado."
                                );

                            } else {

                                Log.e(
                                        TAG,
                                        "No se pudo aplicar "
                                                + "hidden_api_policy: "
                                                + message
                                );
                            }
                        }
                );

            } catch (Throwable t) {

                Log.e(
                        TAG,
                        "Error preparando hidden_api_policy",
                        t
                );
            }

            /*
             * =====================================================
             * 2. MONITOR ADB
             * =====================================================
             */
            try {

                PruxAdbEngine
                        .get(context)
                        .startPersistentMonitoring();

                /*
                 * =================================================
                 * 3. SERVIDOR WEB
                 * =================================================
                 */
                Intent serverIntent =
                        new Intent(
                                context,
                                ServerService.class
                        );

                ContextCompat
                        .startForegroundService(
                                context,
                                serverIntent
                        );

                /*
                 * =================================================
                 * 4. CÁMARA / CAPTURA
                 * =================================================
                 */
                Intent cameraIntent =
                        new Intent(
                                context,
                                CameraService.class
                        );

                ContextCompat
                        .startForegroundService(
                                context,
                                cameraIntent
                        );

                Log.i(
                        TAG,
                        "Servicios Android iniciados."
                );

            } catch (Throwable t) {

                Log.e(
                        TAG,
                        "Error al iniciar servicios después del boot",
                        t
                );
            }
        }
    }
}
