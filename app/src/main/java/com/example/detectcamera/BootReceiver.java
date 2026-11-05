package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Set;

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

        Log.i(TAG, "Boot detectado (" + action + "). Programando inicio escalonado de Prux...");

        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();

        // Lanzamiento escalonado para no saturar la CPU justo después del arranque del sistema
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            new Thread(() -> {
                try {
                    startCoreServices(appContext);
                    
                    // Espera corta entre servicios para estabilizar memoria
                    Thread.sleep(1500L);
                    
                    startAdbAndPrivileges(appContext);
                    Log.i(TAG, "Servicios de Prux iniciados correctamente de forma escalonada.");
                } catch (Throwable t) {
                    Log.e(TAG, "Error iniciando Prux después del boot", t);
                } finally {
                    pending.finish();
                }
            }, "Prux-Boot").start();
        }, 3000L); // 3 segundos de gracia para que el launcher cargue fluido
    }

    private void startCoreServices(Context context) {
        ContextCompat.startForegroundService(
                context,
                new Intent(context, ServerService.class));

        ContextCompat.startForegroundService(
                context,
                new Intent(context, CameraService.class));
    }

    private void startAdbAndPrivileges(Context context) {
        PruxAdbEngine engine = PruxAdbEngine.get(context);
        engine.startPersistentMonitoring();

        engine.reconnect((success, message) -> {
            if (!success) {
                Log.w(TAG, "Reconexión ADB tras el boot no completada: " + message);
                return;
            }

            Log.i(TAG, "ADB conectado tras el boot. Otorgando permisos de pantalla...");

            try {
                PruxPrivilegedBridge.applyBackgroundExemptions(context);
                PruxPrivilegedBridge.prepareMediaProjection(context);
                PruxPrivilegedBridge.startProjectionActivity(context);
            } catch (Throwable t) {
                Log.e(TAG, "Error aplicando privilegios tras ADB", t);
            }
        });
    }
}
