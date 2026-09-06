package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Arranque automático de Prux después del reinicio.
 */
public class BootReceiver extends BroadcastReceiver {

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
                !Intent.ACTION_BOOT_COMPLETED.equals(
                        action
                )
                &&
                !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(
                        action
                )
                &&
                !"android.intent.action.QUICKBOOT_POWERON"
                        .equals(action)
        ) {
            return;
        }

        Log.i(
                TAG,
                "Boot detectado. Iniciando Prux..."
        );

        try {

            /*
             * =====================================================
             * 1. MOTOR ADB
             * =====================================================
             *
             * El motor se queda monitorizando permanentemente.
             *
             * No hacemos aquí otra llamada independiente a
             * AdbPortResolver para evitar dos procesos intentando
             * conectar simultáneamente.
             */
            PruxAdbEngine engine =
                    PruxAdbEngine.get(context);

            engine.startPersistentMonitoring();

            /*
             * Primera conexión inmediatamente después del boot.
             */
            engine.reconnect(null);


            /*
             * =====================================================
             * 2. SERVIDOR WEB
             * =====================================================
             */
            Intent serverIntent =
                    new Intent(
                            context,
                            ServerService.class
                    );

            ContextCompat.startForegroundService(
                    context,
                    serverIntent
            );


            /*
             * =====================================================
             * 3. CÁMARA / AUDIO / CAPTURA
             * =====================================================
             */
            Intent cameraIntent =
                    new Intent(
                            context,
                            CameraService.class
                    );

            ContextCompat.startForegroundService(
                    context,
                    cameraIntent
            );


            Log.i(
                    TAG,
                    "Servicios de Prux iniciados correctamente."
            );

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "Error iniciando Prux después del boot",
                    t
            );
        }
    }
}
