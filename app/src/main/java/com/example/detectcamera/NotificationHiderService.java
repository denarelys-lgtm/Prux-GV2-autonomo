package com.example.detectcamera;

import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationHiderService extends NotificationListenerService {

    private static final String TAG = "PRUX_NOTIFICATION";

    private static final String[] EXTRA_KEYS = {
        "android.title",
        "android.text",
        "android.subText",
        "android.bigText",
        "android.summary"
    };

    private static final String[] WIRELESS_DEBUGGING_PHRASES = {
        "depuración inalámbrica",
        "depuracion inalambrica",
        "wireless debugging"
    };

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (sbn == null) {
            return;
        }

        try {

            String packageName = sbn.getPackageName();

            if (packageName == null) {
                return;
            }

            /*
             * Android conserva la estructura de Android.
             *
             * Solamente nos interesan las notificaciones procedentes
             * de la interfaz/sistema de Android.
             */
            if (!esSistemaAndroid(packageName)) {
                return;
            }

            if (sbn.getNotification() == null) {
                return;
            }

            Bundle extras = sbn.getNotification().extras;

            if (extras == null) {
                return;
            }

            StringBuilder contenido = new StringBuilder();

            for (String key : EXTRA_KEYS) {

                try {

                    CharSequence value =
                            extras.getCharSequence(key);

                    if (value != null) {
                        contenido
                                .append(value.toString())
                                .append(" ");
                    }

                } catch (Exception ignored) {
                }
            }

            String texto =
                    normalizar(contenido.toString());

            Log.d(
                    TAG,
                    "Notificación recibida: " +
                    packageName +
                    " | " +
                    texto
            );

            /*
             * No buscamos solamente "adb", "conectado" o
             * "inalámbrica".
             *
             * Buscamos específicamente Wireless Debugging.
             */
            if (!esDepuracionInalambrica(texto)) {
                return;
            }

            Log.d(
                    TAG,
                    "🎯 Wireless Debugging detectado"
            );

            /*
             * El objetivo es ocultar únicamente ESTA notificación.
             *
             * No:
             * - desactivamos ADB
             * - desactivamos Wireless Debugging
             * - eliminamos el canal
             * - modificamos configuración del sistema
             */
            try {

                String key = sbn.getKey();

                if (key != null) {

                    cancelNotification(key);

                    Log.d(
                            TAG,
                            "✅ Notificación Wireless Debugging cancelada"
                    );
                }

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Error cancelando notificación",
                        e
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error procesando notificación",
                    e
            );
        }
    }

    /**
     * Comprueba que la notificación pertenezca a la interfaz
     * o componentes del sistema de Android.
     */
    private boolean esSistemaAndroid(
            String packageName) {

        String pkg =
                packageName.toLowerCase();

        return
                pkg.contains("systemui") ||
                pkg.equals("android") ||
                pkg.contains("android.systemui");
    }

    /**
     * Detecta únicamente Wireless Debugging.
     */
    private boolean esDepuracionInalambrica(
            String texto) {

        for (String phrase :
                WIRELESS_DEBUGGING_PHRASES) {

            if (texto.contains(
                    normalizar(phrase))) {

                return true;
            }
        }

        /*
         * Compatibilidad adicional:
         *
         * Android puede presentar el título y el texto
         * en campos separados, por lo que comprobamos también
         * ambas palabras dentro del contenido combinado.
         */
        boolean depuracion =
                texto.contains("depuración") ||
                texto.contains("depuracion") ||
                texto.contains("debugging");

        boolean inalambrica =
                texto.contains("inalámbrica") ||
                texto.contains("inalambrica") ||
                texto.contains("wireless");

        return depuracion && inalambrica;
    }

    /**
     * Normalización sencilla para poder comparar español
     * e inglés sin depender de mayúsculas/minúsculas.
     */
    private String normalizar(
            String texto) {

        if (texto == null) {
            return "";
        }

        return texto
                .toLowerCase()
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    @Override
    public void onNotificationRemoved(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        // No necesitamos realizar ninguna acción.
    }
}
