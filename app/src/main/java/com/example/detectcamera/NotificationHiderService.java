package com.example.detectcamera;

import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public class NotificationHiderService extends NotificationListenerService {

    private static final String TAG = "PRUX_NOTIFICATION";

    // Claves comunes para extras de notificaciones
    private static final String[] ANDROID_KEYS = {
            "android.title",
            "android.text",
            "android.subText",
            "android.bigText",
            "android.summaryText",
            "android.infoText",
            "android.title.big",
            "android.text.big",
            "android.progressText",
            "android.app.people"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> cancellingKeys = new HashSet<>(); // Evita bucles infinitos si falla la cancelación

    private volatile boolean listenerConnected = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        Log.d(TAG, "========================================");
        Log.d(TAG, "PRUX: NotificationListener CONECTADO");
        Log.d(TAG, "========================================");
        escanearActivas("onListenerConnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        Log.d(TAG, "onNotificationPosted recibido | conectado=" + listenerConnected);
        procesar(sbn, "onNotificationPosted");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        String key = safeKey(sbn);
        // Si la notificación que estábamos intentando cancelar desaparece, la eliminamos del set de pendientes
        if (cancellingKeys.remove(key)) {
            Log.d(TAG, "Notificación cancelada correctamente: " + key);
        }
        Log.d(TAG, "🟢 onNotificationRemoved | package=" + safePackage(sbn) + " | key=" + key);
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
            String ticker = obtenerTicker(sbn);
            String tag = sbn.getTag() != null ? sbn.getTag() : "";

            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, "origen=" + origen);
            Log.d(TAG, "package=" + pkg);
            Log.d(TAG, "key=" + key);
            Log.d(TAG, "id=" + sbn.getId());
            Log.d(TAG, "tag=" + tag);
            Log.d(TAG, "texto=" + texto);
            Log.d(TAG, "ticker=" + ticker);
            Log.d(TAG, "isOngoing=" + sbn.isOngoing());
            Log.d(TAG, "isClearable=" + sbn.isClearable());
            Log.d(TAG, "----------------------------------------");

            // Combinamos todo el texto detectable
            StringBuilder textoCompleto = new StringBuilder();
            textoCompleto.append(texto).append(' ').append(ticker).append(' ').append(tag);

            if (!esDepuracionInalambrica(textoCompleto.toString())) {
                return;
            }

            Log.w(TAG, "🎯 OBJETIVO ENCONTRADO: DEPURACIÓN INALÁMBRICA");
            Log.w(TAG, "🎯 package=" + pkg);
            Log.w(TAG, "🎯 key=" + key);

            if (key == null || key.isEmpty()) {
                Log.e(TAG, "❌ La notificación objetivo no tiene key utilizable");
                return;
            }

            // Evitar múltiples hilos de cancelación para la misma key
            if (cancellingKeys.add(key)) {
                cancelarConReintentos(key);
            }

        } catch (Throwable e) {
            Log.e(TAG, "ERROR procesando notificación", e);
        }
    }

    private void cancelarConReintentos(final String key) {
        // Primera cancelación inmediata
        try {
            Log.w(TAG, "➡️ Cancelando notificación: " + key);
            cancelNotification(key);
        } catch (Throwable e) {
            Log.e(TAG, "❌ cancelNotification() lanzó excepción", e);
            cancellingKeys.remove(key);
            return;
        }

        // Reintentos periódicos hasta que la notificación desaparezca
        Runnable reintento = new Runnable() {
            @Override
            public void run() {
                if (!cancellingKeys.contains(key)) {
                    // Ya no es necesario seguir intentando (probablemente ya se canceló)
                    return;
                }

                boolean sigueActiva = false;
                try {
                    StatusBarNotification[] activas = getActiveNotifications();
                    if (activas != null) {
                        for (StatusBarNotification sbn : activas) {
                            if (sbn != null && key.equals(safeKey(sbn))) {
                                sigueActiva = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Error al verificar notificación " + key, e);
                }

                if (!sigueActiva) {
                    Log.d(TAG, "✅ Notificación " + key + " ya no está activa");
                    cancellingKeys.remove(key);
                    return;
                }

                Log.w(TAG, "⚠️ Reintentando cancelación para " + key);
                try {
                    cancelNotification(key);
                } catch (Throwable e) {
                    Log.e(TAG, "Error reintentando cancelar", e);
                }

                // Programamos el siguiente reintento en 1 segundo
                handler.postDelayed(this, 1000);
            }
        };

        // Primer reintento después de 500 ms
        handler.postDelayed(reintento, 500);
    }

    private String obtenerTicker(StatusBarNotification sbn) {
        try {
            if (sbn.getNotification() == null) return "";
            Notification notif = sbn.getNotification();
            CharSequence ticker = notif.tickerText;
            return ticker != null ? ticker.toString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private String obtenerTexto(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return "";
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) {
                Log.w(TAG, "Notification.extras == null | key=" + safeKey(sbn));
                return "";
            }

            StringBuilder texto = new StringBuilder();
            for (String key : ANDROID_KEYS) {
                agregarExtra(extras, key, texto);
            }

            // Diagnóstico: mostrar todas las claves disponibles
            try {
                StringBuilder keys = new StringBuilder();
                for (String key : extras.keySet()) {
                    if (keys.length() > 0) keys.append(", ");
                    keys.append(key);
                }
                Log.d(TAG, "extras.keys=" + keys);
            } catch (Throwable ignored) {}

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
        if (t.isEmpty()) return false;

        if (t.contains("depuracion inalambrica")) return true;
        if (t.contains("wireless debugging")) return true;

        // Detección más laxa por palabras separadas
        boolean tieneDepuracion = t.contains("depuracion") || t.contains("debugging");
        boolean tieneWireless = t.contains("inalambrica") || t.contains("wireless");

        return tieneDepuracion && tieneWireless;
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
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        cancellingKeys.clear();
        Log.d(TAG, "Servicio destruido");
    }
}
