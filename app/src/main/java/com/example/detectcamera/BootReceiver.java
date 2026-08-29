package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DetectCameraBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.i(TAG, "Evento de reinicio detectado. Iniciando servicios automáticamente...");

            try {
                Intent serverIntent = new Intent(context, ServerService.class);
                ContextCompat.startForegroundService(context, serverIntent);
                Log.i(TAG, "ServerService iniciado tras el boot.");
            } catch (Throwable t) {
                Log.e(TAG, "Error al iniciar los servicios tras el boot", t);
            }
        }
    }
}
