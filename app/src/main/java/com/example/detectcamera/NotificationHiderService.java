package com.example.detectcamera;

import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationHiderService extends NotificationListenerService {
    private static final String TAG = "KOPLANZA";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null) return;

            // Filtro para SystemUI
            if (!pkg.contains("systemui") && !pkg.equals("android") && !pkg.equals("com.android.systemui")) {
                return;
            }

            Bundle extras = sbn.getNotification().extras;
            if (extras == null) return;

            StringBuilder textoCompleto = new StringBuilder();
            String[] claves = {"android.title", "android.text", "android.subText", "android.bigText", "android.summary"};
            for (String clave : claves) {
                CharSequence cs = extras.getCharSequence(clave);
                if (cs != null) {
                    textoCompleto.append(cs.toString().toLowerCase()).append(" ");
                }
            }

            String texto = textoCompleto.toString();
            Log.d(TAG, "Notif de: " + pkg + " | Texto: " + texto);

            String[] keywords = {
                "inalámbrica", "inalambrica", "wireless",
                "depuración", "depuracion", "debugging", "debug",
                "adb", "conectado", "connected", "se conectó"
            };

            boolean esAdb = false;
            for (String kw : keywords) {
                if (texto.contains(kw)) {
                    esAdb = true;
                    break;
                }
            }

            if (esAdb) {
                Log.d(TAG, "🔴 ¡ADB detectada en Listener! Intentando cancelar...");

                // Estrategia 1: Cancelar por Key
                cancelNotification(sbn.getKey());

                // Estrategia 2: Snooze 24 horas
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    snoozeNotification(sbn.getKey(), 86400000);
                }

                // Estrategia 3: Eliminar canal si es posible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    String channelId = extras.getString("android.channelId");
                    if (channelId != null) {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) {
                            nm.deleteNotificationChannel(channelId);
                        }
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error en NotificationHiderService", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        // No necesario
    }
}
