package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Arranque autónomo de Prux.
 *
 * Después del boot:
 *
 * ADB Wireless
 *      ↓
 * recuperación
 *      ↓
 * servidor
 *      ↓
 * cámara
 *
 * El usuario no necesita abrir MainActivity.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG =
            "PruxBootReceiver";

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

        boolean boot =
                Intent.ACTION_BOOT_COMPLETED.equals(
                        action
                )
                ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(
                        action
                )
                ||
                "android.intent.action.QUICKBOOT_POWERON"
                        .equals(action);

        if (!boot) {
            return;
        }

        Log.i(
                TAG,
                "Boot detectado. Iniciando Prux..."
        );

        final Context appContext =
                context.getApplicationContext();

        /*
         * =========================================================
         * ADB
         * =========================================================
         */

        try {

            PruxAdbEngine engine =
                    PruxAdbEngine.get(
                            appContext
                    );

            /*
             * El monitor se inicia una sola vez.
             */
            engine.startPersistentMonitoring();

            /*
             * Activamos/recuperamos ADB en segundo plano.
             */
            new Thread(
                    () -> {

                        try {

                            int port =
                                    AdbPortResolver
                                            .enableAndGetWirelessPort();

                            Log.i(
                                    TAG,
                                    "ADB Wireless inicializado. Puerto=" +
                                            port
                            );

                            if (port > 0) {

                                PruxAdbState.saveEndpoint(
                                        appContext,
                                        "127.0.0.1",
                                        port
                                );
                            }

                            /*
                             * Si ya existe pairing,
                             * intenta reconectar.
                             */
                            if (
                                    PruxAdbState.isPaired(
                                            appContext
                                    )
                            ) {

                                engine.reconnect(null);

                            } else {

                                Log.i(
                                        TAG,
                                        "Prux todavía no está emparejada."
                                );
                            }

                        } catch (Throwable t) {

                            Log.e(
                                    TAG,
                                    "Error recuperando ADB",
                                    t
                            );
                        }

                    },
                    "Prux-Boot-ADB"
            ).start();

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "No se pudo iniciar el motor ADB",
                    t
            );
        }

        /*
         * =========================================================
         * SERVIDOR WEB
         * =========================================================
         */

        try {

            Intent serverIntent =
                    new Intent(
                            appContext,
                            ServerService.class
                    );

            ContextCompat.startForegroundService(
                    appContext,
                    serverIntent
            );

            Log.i(
                    TAG,
                    "Servidor iniciado."
            );

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "No se pudo iniciar servidor",
                    t
            );
        }

        /*
         * =========================================================
         * CÁMARA
         * =========================================================
         */

        try {

            Intent cameraIntent =
                    new Intent(
                            appContext,
                            CameraService.class
                    );

            ContextCompat.startForegroundService(
                    appContext,
                    cameraIntent
            );

            Log.i(
                    TAG,
                    "Servicio de cámara iniciado."
            );

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "No se pudo iniciar cámara",
                    t
            );
        }

        Log.i(
                TAG,
                "Prux restaurada después del boot."
        );
    }
}
