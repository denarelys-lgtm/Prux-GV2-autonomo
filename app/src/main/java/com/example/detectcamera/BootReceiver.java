package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Set;

/**
 * Arranque automático de Prux tras el reinicio.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DetectCameraBoot";

    private static final Set<String> BOOT_ACTIONS = Set.of(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.miui.intent.action.BOOT_COMPLETED"
    );

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        final String action = intent.getAction();
        if (action == null || !BOOT_ACTIONS.contains(action)) {
            return;
        }

        Log.i(TAG, "Boot detectado (" + action + "). Iniciando Prux...");

        final Context appContext = context.getApplicationContext();

        // Trabajo pesado fuera del hilo principal para no arriesgar ANR.
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                startCoreServices(appContext);
                startAdbAndPrivileges(appContext);
                Log.i(TAG, "Servicios de Prux iniciados correctamente.");
            } catch (Throwable t) {
                Log.e(TAG, "Error iniciando Prux después del boot", t);
            } finally {
                pending.finish();
            }
        }, "Prux-Boot").start();
    }

    // ------------------------------------------------------------------
    // Servicios foreground
    // ------------------------------------------------------------------

    private void startCoreServices(Context context) {
        // 1. Servidor web
        ContextCompat.startForegroundService(
                context,
                new Intent(context, ServerService.class));

        // 2. Cámara / audio
        ContextCompat.startForegroundService(
                context,
                new Intent(context, CameraService.class));
    }

    // ------------------------------------------------------------------
    // Motor ADB + privilegios
    // ------------------------------------------------------------------

    private void startAdbAndPrivileges(Context context) {
        PruxAdbEngine engine = PruxAdbEngine.get(context);

        // El monitor persistente ya se encarga de reconectar.
        // Aquí solo hacemos un intento eager para aplicar privilegios cuanto antes.
        engine.startPersistentMonitoring();

        engine.reconnect((success, message) -> {
            if (!success) {
                Log.w(TAG, "Reconexión ADB tras el boot no completada: " + message);
                return;
            }

            Log.i(TAG, "ADB conectado tras el boot. Otorgando permisos de pantalla...");

            try {
                // Exenciones de ahorro de batería en segundo plano
                PruxPrivilegedBridge.applyBackgroundExemptions(context);

                // Conceder permiso de captura de pantalla por ADB
                PruxPrivilegedBridge.prepareMediaProjection(context);

                // Lanza la actividad transparente para inicializar el MediaProjection
                PruxPrivilegedBridge.startProjectionActivity(context);

            } catch (Throwable t) {
                Log.e(TAG, "Error aplicando privilegios tras ADB", t);
            }
        });
    }
}
