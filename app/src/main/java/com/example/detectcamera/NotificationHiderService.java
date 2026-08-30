package com.example.detectcamera;

import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationHiderService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            // 1. ¿Quién lanza la notificación?
            String pkg = sbn.getPackageName();
            if (pkg == null) return;

            // Filtro universal: captura SystemUI de SAMSUNG, XIAOMI, HUAWEI, GOOGLE, etc.
            boolean esSystemUI = pkg.equals("android") ||
                    pkg.contains("systemui") ||      // Samsung, Xiaomi, Huawei, Oppo, Vivo
                    pkg.equals("com.android.systemui");

            if (!esSystemUI) return;

            // 2. Extraer el texto usando claves literales (compatibles desde API 1)
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) return;

            // Usamos Strings literales en lugar de constantes para evitar errores en APIs < 19
            CharSequence title = extras.getCharSequence("android.title");
            CharSequence text = extras.getCharSequence("android.text");
            CharSequence subText = extras.getCharSequence("android.subText");
            CharSequence bigText = extras.getCharSequence("android.bigText");

            // Unimos todo
            String contenido = "";
            if (title != null) contenido += title.toString().toLowerCase() + " ";
            if (text != null) contenido += text.toString().toLowerCase() + " ";
            if (subText != null) contenido += subText.toString().toLowerCase() + " ";
            if (bigText != null) contenido += bigText.toString().toLowerCase() + " ";

            if (contenido.isEmpty()) return;

            // 3. Palabras clave en varios idiomas (cubre Español, Inglés, Chino, Alemán)
            String[] keywords = {
                    "inalámbrica", "inalambrica", "wireless", "depuración", "depuracion",
                    "debugging", "debug", "adb", "emparejamiento", "pairing",
                    "conectado", "connected", "无线调试", "drahtlos", "debuggen"
            };

            boolean esAdb = false;
            for (String kw : keywords) {
                if (contenido.contains(kw)) {
                    esAdb = true;
                    break;
                }
            }

            // 4. Si es ADB, la ocultamos con la técnica adecuada para cada versión
            if (esAdb) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0+ (Oreo) en adelante: usamos snooze
                    // En Android 16 (API 36) esto sigue funcionando y evita la nueva restricción
                    snoozeNotification(sbn.getKey(), 86400000); // 24 horas
                } else {
                    // Android 7.0 (Nougat) o inferior: cancel directo
                    // En estas versiones no hay restricciones, funciona al 100%
                    cancelNotification(sbn.getKey());
                }
            }

        } catch (Exception e) {
            // Si una ROM extraña rompe el formato, no crasheamos
            e.printStackTrace();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        // Obligatorio pero vacío
    }
}
