package com.example.detectcamera;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationHiderService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        // Envolvemos todo en try-catch para evitar crashes en ROMs exóticas
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null) return;

            // ===== FILTRO UNIVERSAL POR PAQUETE =====
            // Detecta automáticamente SystemUI de TODAS las marcas
            boolean esSystemUI = pkg.equals("android") ||
                    pkg.contains("systemui") ||   // Samsung, Xiaomi, Huawei, Oppo, Vivo, etc.
                    pkg.equals("com.android.systemui");

            if (!esSystemUI) {
                return; // No es notificación del sistema, la ignoramos
            }

            // ===== EXTRAEMOS TEXTO Y TÍTULO =====
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) return;

            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            // Unimos todo en un solo String para buscarlo fácilmente
            String contenidoCompleto = "";
            if (title != null) contenidoCompleto += title.toString().toLowerCase() + " ";
            if (text != null) contenidoCompleto += text.toString().toLowerCase() + " ";
            if (subText != null) contenidoCompleto += subText.toString().toLowerCase() + " ";
            if (bigText != null) contenidoCompleto += bigText.toString().toLowerCase() + " ";

            if (contenidoCompleto.isEmpty()) return;

            // ===== PALABRAS CLAVE EN 4 IDIOMAS (Español, Inglés, Chino, Alemán) =====
            String[] keywords = {
                    "inalámbrica", "inalambrica",    // Español
                    "wireless",                      // Inglés
                    "depuración", "depuracion",      // Español
                    "debugging", "debug",            // Inglés
                    "adb",                           // Universal
                    "emparejamiento", "pairing",     // Emparejamiento
                    "conectado", "connected",        // Conectado
                    "无线调试", "无线",               // Chino simplificado
                    "drahtlos", "debuggen"           // Alemán
            };

            boolean esAdb = false;
            for (String kw : keywords) {
                if (contenidoCompleto.contains(kw)) {
                    esAdb = true;
                    break;
                }
            }

            // ===== SI ES ADB, LA ELIMINAMOS =====
            if (esAdb) {
                // Opción 1: Borrar permanentemente
                cancelNotification(sbn.getKey());

                // Opción 2: Si quieres "silenciarla" sin borrar historial (se oculta pero sigue en el panel si deslizas)
                // snoozeNotification(sbn.getKey(), 3600000); // 1 hora
            }

        } catch (Exception e) {
            // Si algo falla (ej: un OEM rompe el formato), no hacemos nada para no crashear
            e.printStackTrace();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        // No necesario, pero obligatorio
    }
}
