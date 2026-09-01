package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Arranque automático de Android después del boot.
 */
public class BootReceiver
        extends BroadcastReceiver {

    private static final String TAG =
            "DetectCameraBoot";

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
                Intent.ACTION_BOOT_COMPLETED
                        .equals(action)
                        ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED
                        .equals(action)
                        ||
                "android.intent.action.QUICKBOOT_POWERON"
                        .equals(action)
        ) {

            Log.i(
                    TAG,
                    "Reinicio detectado. "
                            + "Restaurando Android..."
            );

            try {

                /*
                 * Arrancar inmediatamente el monitor ADB.
                 *
                 * Si ya existe una identidad ADB emparejada,
                 * el motor intentará recuperar la conexión.
                 */
                PruxAdbEngine
                        .get(context)
                        .startPersistentMonitoring();

                /*
                 * Servidor Web.
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
                 * Cámara y captura.
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
                        "Servicios Android "
                                + "iniciados después del boot."
                );

            } catch (Throwable t) {

                Log.e(
                        TAG,
                        "Error al iniciar "
                                + "servicios después del boot",
                        t
                );
            }
        }
    }
}
