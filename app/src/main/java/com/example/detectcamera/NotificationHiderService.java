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

    private static final String[] ANDROID_KEYS = {
            "android.title", "android.text", "android.subText", "android.bigText",
            "android.summaryText", "android.infoText", "android.title.big", "android.text.big"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> cancellingKeys = new HashSet<>();
    private volatile boolean listenerConnected = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        listenerConnected = true;
        escanearActivas("onListenerConnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        procesar(sbn, "onNotificationPosted");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        String key = safeKey(sbn);
        cancellingKeys.remove(key);
    }

    private void escanearActivas(String origen) {
        try {
            StatusBarNotification[] activas = getActiveNotifications();
            if (activas == null) return;
            for (StatusBarNotification sbn : activas) {
                procesar(sbn, "active:" + origen);
            }
        } catch (Throwable ignored) {}
    }

    private void procesar(StatusBarNotification sbn, String origen) {
        try {
            if (sbn == null) return;
            String key = safeKey(sbn);
            String texto = obtenerTexto(sbn);
            String ticker = obtenerTicker(sbn);
            String tag = sbn.getTag() != null ? sbn.getTag() : "";

            StringBuilder textoCompleto = new StringBuilder();
            textoCompleto.append(texto).append(' ').append(ticker).append(' ').append(tag);

            if (!esDepuracionInalambrica(textoCompleto.toString())) return;
            if (key == null || key.isEmpty()) return;

            if (cancellingKeys.add(key)) {
                cancelarConReintentos(key);
            }
        } catch (Throwable ignored) {}
    }

    private void cancelarConReintentos(final String key) {
        try {
            cancelNotification(key);
        } catch (Throwable e) {
            cancellingKeys.remove(key);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!cancellingKeys.contains(key)) return;
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
                } catch (Throwable ignored) {}

                if (!sigueActiva) {
                    cancellingKeys.remove(key);
                    return;
                }

                try {
                    cancelNotification(key);
                } catch (Throwable ignored) {}

                handler.postDelayed(this, 1500); // Espaciar más los reintentos reduce el consumo
            }
        }, 800);
    }

    private String obtenerTicker(StatusBarNotification sbn) {
        try {
            Notification notif = sbn.getNotification();
            return (notif != null && notif.tickerText != null) ? notif.tickerText.toString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private String obtenerTexto(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return "";
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) return "";

            StringBuilder texto = new StringBuilder();
            for (String key : ANDROID_KEYS) {
                CharSequence cs = extras.getCharSequence(key);
                if (cs != null) texto.append(cs).append(' ');
            }
            return normalizar(texto.toString());
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean esDepuracionInalambrica(String texto) {
        String t = normalizar(texto);
        if (t.isEmpty()) return false;
        if (t.contains("depuracion inalambrica") || t.contains("wireless debugging")) return true;
        return (t.contains("depuracion") || t.contains("debugging")) && (t.contains("inalambrica") || t.contains("wireless"));
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
        return texto.toLowerCase()
                .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
                .replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        cancellingKeys.clear();
    }
}
