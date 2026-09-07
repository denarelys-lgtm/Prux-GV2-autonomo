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

    private static final String TAG = "DetectCameraBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.i(TAG, "Boot detectado. Iniciando Prux...");

        try {
            /*
             * =====================================================
             * 1. SERVIDOR WEB
             * =====================================================
             */
            Intent serverIntent = new Intent(context, ServerService.class);
            ContextCompat.startForegroundService(context, serverIntent);

            /*
             * =====================================================
             * 2. CÁMARA / AUDIO
             * =====================================================
             */
            Intent cameraIntent = new Intent(context, CameraService.class);
            ContextCompat.startForegroundService(context, cameraIntent);

            /*
             * =====================================================
             * 3. MOTOR ADB Y AUTORIZACIÓN DE PANTALLA
             * =====================================================
             */
            PruxAdbEngine engine = PruxAdbEngine.get(context);
            engine.startPersistentMonitoring();

            engine.reconnect((success, message) -> {
                if (success) {
                    Log.i(TAG, "ADB conectado tras el boot. Otorgando permisos de pantalla...");

                    // Exenciones de ahorro de batería en segundo plano
                    PruxPrivilegedBridge.applyBackgroundExemptions(context);

                    // Conceder permiso de captura de pantalla por ADB
                    PruxPrivilegedBridge.prepareMediaProjection(context);

                    // Lanza la actividad transparente para inicializar el MediaProjection
                    PruxPrivilegedBridge.startProjectionActivity(context);
                } else {
                    Log.w(TAG, "Reconexión ADB tras el boot no completada: " + message);
                }
            });

            Log.i(TAG, "Servicios de Prux iniciados correctamente.");

        } catch (Throwable t) {
            Log.e(TAG, "Error iniciando Prux después del boot", t);
        }
    }
}
