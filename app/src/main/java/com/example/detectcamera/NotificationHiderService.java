package com.example.detectcamera;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;

public class NotificationHiderService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        // 1. ¿Quién lanzó esto?
        String packageName = sbn.getPackageName();
        
        // 2. Solo nos interesa si lo lanza el Sistema (Android)
        if (packageName.equals("com.android.systemui") || packageName.equals("android")) {
            
            // 3. Extraemos el texto de la notificación
            Bundle extras = sbn.getNotification().extras;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            
            // 4. Si el título o el texto hablan de depuración inalámbrica...
            if ( (title != null && title.toString().contains("inalámbrica")) ||
                 (text != null && text.toString().contains("inalámbrica")) ) {
                 
                // 5. ¡La borramos de la barra de estado!
                cancelNotification(sbn.getKey());
                // (Opcional: También puedes usar snoozeNotification(sbn.getKey(), 30000) para dormirla 30 segundos)
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        // No necesitas hacer nada aquí, pero es obligatorio tenerlo.
    }
}
