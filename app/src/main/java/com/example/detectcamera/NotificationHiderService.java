package com.example.detectcamera;

import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Oculta EXCLUSIVAMENTE la notificación de "Depuración inalámbrica".
 *
 * IMPORTANTE:
 * - No elimina canales.
 * - No desactiva Wireless Debugging.
 * - No usa snooze como mecanismo principal.
 * - No presupone qué paquete publica la notificación.
 * - Incluye diagnóstico para saber si el listener realmente está conectado
 *   y si SystemUI vuelve a publicar la notificación después de cancelarla.
 */
public class NotificationHiderService extends NotificationListenerService {

    private static final String TAG = "PRUX_NOTIFICATION";

    private static final String[] ANDROID_KEYS = {
            "android.title",
            "android.text",
            "android.subText",
            "android.bigText",
            "android.summaryText"
    };

    private volatile boolean listenerConnected = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;

        Log.d(TAG, "========================================");
        Log.d(TAG, "PRUX: NotificationListener CONECTADO");
        Log.d(TAG, "PRUX: iniciando escaneo de notificaciones activas");
        Log.d(TAG, "========================================");

        escanearActivas("onListenerConnected");
    }

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (sbn == null) {
            Log.w(TAG, "onNotificationPosted: sbn == null");
            return;
        }

        Log.d(TAG, "onNotificationPosted recibido | conectado=" + listenerConnected);
        procesar(sbn, "onNotificationPosted");
    }

    private void escanearActivas(String origen) {
        try {
            StatusBarNotification[] activas = getActiveNotifications();

            if (activas == null) {
                Log.w(TAG, "getActiveNotifications() devolvió null | origen=" + origen);
                return;
            }

            Log.d(TAG, "Activas=" + activas.length + " | origen=" + origen);

            for (StatusBarNotification sbn : activas) {
                procesar(sbn, "active:" + origen);
            }
        } catch (Throwable e) {
            Log.e(TAG, "ERROR en getActiveNotifications()", e);
        }
    }

    private void procesar(StatusBarNotification sbn, String origen) {
        try {
            if (sbn == null) return;

            String pkg = safePackage(sbn);
            String key = safeKey(sbn);
            String texto = obtenerTexto(sbn);

            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, "origen=" + origen);
            Log.d(TAG, "package=" + pkg);
            Log.d(TAG, "key=" + key);
            Log.d(TAG, "texto=" + texto);
            Log.d(TAG, "----------------------------------------");

            // No filtramos por paquete. La frase objetivo es suficientemente específica.
            if (!esDepuracionInalambrica(texto)) {
                return;
            }

            Log.w(TAG, "🎯 OBJETIVO ENCONTRADO: DEPURACIÓN INALÁMBRICA");
            Log.w(TAG, "🎯 package=" + pkg);
            Log.w(TAG, "🎯 key=" + key);

            if (key == null || key.length() == 0) {
                Log.e(TAG, "❌ La notificación objetivo no tiene key utilizable");
                return;
            }

            cancelarYVerificar(key);

        } catch (Throwable e) {
            Log.e(TAG, "ERROR procesando notificación", e);
        }
    }

    private void cancelarYVerificar(final String key) {
        try {
            Log.w(TAG, "➡️ Ejecutando cancelNotification(" + key + ")");
            cancelNotification(key);
            Log.w(TAG, "⬅️ cancelNotification() regresó sin excepción");
        } catch (Throwable e) {
            Log.e(TAG, "❌ cancelNotification() lanzó excepción", e);
            return;
        }

        // La verificación es importante: si vuelve a aparecer, sabemos que el sistema
        // la está republicando y no que Prux simplemente no la haya encontrado.
        new Thread(() -> verificarCancelacion(key), "Prux-Notification-Verify").start();
    }

    private void verificarCancelacion(String key) {
        final int intentos = 12;

        for (int i = 1; i <= intentos; i++) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                StatusBarNotification[] activas = getActiveNotifications();
                boolean sigue = false;

                if (activas != null) {
                    for (StatusBarNotification sbn : activas) {
                        if (sbn != null && key.equals(safeKey(sbn))) {
                            sigue = true;
                            break;
                        }
                    }
                }

                if (!sigue) {
                    Log.d(TAG, "✅ VERIFICACIÓN: notificación desapareció | intento=" + i);
                    return;
                }

                Log.w(TAG, "⚠️ VERIFICACIÓN: sigue activa | reintento=" + i);
                cancelNotification(key);

            } catch (Throwable e) {
                Log.e(TAG, "ERROR verificando/reintentando cancelación | intento=" + i, e);
            }
        }

        Log.e(TAG, "❌ La notificación sigue activa después de " + intentos + " intentos");
        Log.e(TAG, "❌ Esto indica que Android/SystemUI la está rechazando o republicando.");
    }

    private String obtenerTexto(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) {
                return "";
            }

            Bundle extras = sbn.getNotification().extras;
            if (extras == null) {
                Log.w(TAG, "Notification.extras == null | key=" + safeKey(sbn));
                return "";
            }

            StringBuilder texto = new StringBuilder();

            for (String key : ANDROID_KEYS) {
                agregarExtra(extras, key, texto);
            }

            // Diagnóstico: muestra qué extras conoce realmente Android.
            try {
                StringBuilder keys = new StringBuilder();
                for (String key : extras.keySet()) {
                    if (keys.length() > 0) keys.append(", ");
                    keys.append(key);
                }
                Log.d(TAG, "extras.keys=" + keys);
            } catch (Throwable ignored) {
                Log.w(TAG, "No se pudieron enumerar extras.keys");
            }

            return normalizar(texto.toString());

        } catch (Throwable e) {
            Log.e(TAG, "Error obteniendo texto de notificación", e);
            return "";
        }
    }

    private void agregarExtra(Bundle extras, String key, StringBuilder destino) {
        try {
            CharSequence cs = extras.getCharSequence(key);
            if (cs != null) {
                destino.append(cs).append(' ');
                Log.d(TAG, "extra[" + key + "]=" + cs);
            }
        } catch (Throwable e) {
            Log.w(TAG, "No se pudo leer extra " + key);
        }
    }

    private boolean esDepuracionInalambrica(String texto) {
        String t = normalizar(texto);

        if (t.contains("depuracion inalambrica")) return true;
        if (t.contains("wireless debugging")) return true;

        // Algunas traducciones/intermedios separan las palabras.
        boolean depuracion = t.contains("depuracion") || t.contains("debugging");
        boolean wireless = t.contains("inalambrica") || t.contains("wireless");

        return depuracion && wireless;
    }

    private String safePackage(StatusBarNotification sbn) {
        try {
            String value = sbn.getPackageName();
            return value == null ? "<null>" : value;
        } catch (Throwable e) {
            return "<error>";
        }
    }

    private String safeKey(StatusBarNotification sbn) {
        try {
            String value = sbn.getKey();
            return value == null ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    private String normalizar(String texto) {
        if (texto == null) return "";

        return texto
                .toLowerCase()
                .replace('á', 'a')
                .replace('é', 'e')
                .replace('í', 'i')
                .replace('ó', 'o')
                .replace('ú', 'u')
                .replace('ü', 'u')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Override
    public void onNotificationRemoved(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (sbn == null) {
            Log.d(TAG, "onNotificationRemoved: sbn == null");
            return;
        }

        Log.d(TAG, "🟢 onNotificationRemoved | package="
                + safePackage(sbn)
                + " | key="
                + safeKey(sbn));
    }

}
