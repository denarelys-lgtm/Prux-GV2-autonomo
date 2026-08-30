package com.example.detectcamera;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationHiderService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        // Solo nos interesan las notificaciones del sistema
        String pkg = sbn.getPackageName();
        if (!pkg.equals("com.android.systemui") && !pkg.equals("android")) {
            return;
        }

        // Extraemos título y texto
        Bundle extras = sbn.getNotification().extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);

        boolean esAdb = false;
        if (title != null && title.toString().toLowerCase().contains("inalámbrica")) {
            esAdb = true;
        }
        if (text != null && text.toString().toLowerCase().contains("inalámbrica")) {
            esAdb = true;
        }
        // También puedes buscar "depuración" o "wireless" si quieres
        if (esAdb) {
            // Cancela la notificación (desaparece de la barra)
            cancelNotification(sbn.getKey());
            // Si prefieres posponerla 30 segundos (sigue invisible pero no borrada del todo):
            // snoozeNotification(sbn.getKey(), 30000);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        // No es necesario hacer nada
    }
}
