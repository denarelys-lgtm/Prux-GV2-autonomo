package com.example.detectcamera;

import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationHiderService
        extends NotificationListenerService {

    private static final String TAG =
            "PRUX_NOTIFICATION";

    private volatile boolean listenerConnected = false;

    /*
     * Android conserva la estructura compatible con Android.
     */
    private static final String[] EXTRA_KEYS = {
            "android.title",
            "android.text",
            "android.subText",
            "android.bigText",
            "android.summary"
    };

    /*
     * Solamente queremos atacar Wireless Debugging.
     */
    private static final String[] TARGET_PHRASES = {
            "depuración inalámbrica",
            "depuracion inalambrica",
            "wireless debugging"
    };

    // ============================================================
    // LISTENER CONECTADO
    // ============================================================

    @Override
    public void onListenerConnected() {

        super.onListenerConnected();

        listenerConnected = true;

        Log.d(
                TAG,
                "🔥 PRUX NotificationListener conectado"
        );

        /*
         * MUY IMPORTANTE:
         *
         * La notificación puede existir ANTES de que Prux
         * termine de conectarse como listener.
         *
         * Por eso revisamos las notificaciones que ya están
         * presentes.
         */
        escanearNotificacionesActivas();
    }

    // ============================================================
    // NOTIFICACIÓN NUEVA
    // ============================================================

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (!listenerConnected) {
            return;
        }

        procesarNotificacion(sbn);
    }

    // ============================================================
    // ESCANEAR NOTIFICACIONES YA EXISTENTES
    // ============================================================

    private void escanearNotificacionesActivas() {

        try {

            StatusBarNotification[] activas =
                    getActiveNotifications();

            if (activas == null) {
                return;
            }

            Log.d(
                    TAG,
                    "Escaneando " +
                    activas.length +
                    " notificaciones activas"
            );

            for (StatusBarNotification sbn :
                    activas) {

                procesarNotificacion(sbn);
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error escaneando notificaciones activas",
                    e
            );
        }
    }

    // ============================================================
    // PROCESAR
    // ============================================================

    private void procesarNotificacion(
            StatusBarNotification sbn) {

        if (sbn == null) {
            return;
        }

        try {

            String pkg =
                    sbn.getPackageName();

            if (pkg == null) {
                return;
            }

            /*
             * La captura muestra que la notificación pertenece
             * a la interfaz/sistema de Android.
             */
            if (!esSistemaAndroid(pkg)) {
                return;
            }

            if (sbn.getNotification() == null) {
                return;
            }

            Bundle extras =
                    sbn.getNotification().extras;

            if (extras == null) {
                return;
            }

            StringBuilder contenido =
                    new StringBuilder();

            for (String key : EXTRA_KEYS) {

                try {

                    CharSequence value =
                            extras.getCharSequence(key);

                    if (value != null) {

                        contenido
                                .append(value)
                                .append(' ');
                    }

                } catch (Exception ignored) {
                }
            }

            String texto =
                    normalizar(
                            contenido.toString()
                    );

            Log.d(
                    TAG,
                    "Notificación: " +
                    pkg +
                    " | " +
                    texto
            );

            /*
             * No usamos "adb", "conectado" o
             * "inalámbrica" individualmente.
             */
            if (!esWirelessDebugging(texto)) {
                return;
            }

            String key =
                    sbn.getKey();

            Log.d(
                    TAG,
                    "🎯 WIRELESS DEBUGGING ENCONTRADO"
            );

            Log.d(
                    TAG,
                    "Key = " + key
            );

            ocultarNotificacion(key);

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error procesando notificación",
                    e
            );
        }
    }

    // ============================================================
    // DETECCIÓN
    // ============================================================

    private boolean esWirelessDebugging(
            String texto) {

        for (String frase :
                TARGET_PHRASES) {

            if (texto.contains(
                    normalizar(frase))) {

                return true;
            }
        }

        /*
         * Compatibilidad adicional por si Android
         * separa el título y el texto.
         */
        boolean depuracion =
                texto.contains("depuración") ||
                texto.contains("depuracion") ||
                texto.contains("debugging");

        boolean wireless =
                texto.contains("inalámbrica") ||
                texto.contains("inalambrica") ||
                texto.contains("wireless");

        return depuracion && wireless;
    }

    // ============================================================
    // OCULTAR
    // ============================================================

    private void ocultarNotificacion(
            String key) {

        if (key == null ||
                key.trim().isEmpty()) {

            return;
        }

        /*
         * Primer intento:
         * cancelación directa mediante NotificationListener.
         */
        try {

            cancelNotification(key);

            Log.d(
                    TAG,
                    "✅ cancelNotification() ejecutado"
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Falló cancelNotification()",
                    e
            );
        }

        /*
         * Segundo intento:
         *
         * Si Android vuelve a publicar inmediatamente
         * la notificación, esperamos un instante y volvemos
         * a comprobar las activas.
         */
        reintentar(key);
    }

    // ============================================================
    // REINTENTO
    // ============================================================

    private void reintentar(
            final String key) {

        new Thread(
                () -> {

                    /*
                     * Varias pasadas cortas.
                     *
                     * Esto permite atacar una notificación
                     * que SystemUI vuelva a publicar.
                     */
                    for (int i = 0; i < 10; i++) {

                        try {

                            Thread.sleep(150);

                        } catch (InterruptedException e) {

                            Thread.currentThread()
                                    .interrupt();

                            return;
                        }

                        try {

                            StatusBarNotification[] activas =
                                    getActiveNotifications();

                            if (activas == null) {
                                continue;
                            }

                            for (
                                    StatusBarNotification sbn :
                                    activas
                            ) {

                                if (sbn == null) {
                                    continue;
                                }

                                if (key.equals(
                                        sbn.getKey()
                                )) {

                                    if (esWirelessDebugging(
                                            obtenerTexto(
                                                    sbn
                                            )
                                    )) {

                                        Log.d(
                                                TAG,
                                                "🔁 Reintentando ocultar WDB"
                                        );

                                        cancelNotification(
                                                key
                                        );
                                    }
                                }
                            }

                        } catch (Exception e) {

                            Log.e(
                                    TAG,
                                    "Error en reintento",
                                    e
                            );
                        }
                    }

                },
                "Prux-Notification-Killer"
        ).start();
    }

    // ============================================================
    // OBTENER TEXTO
    // ============================================================

    private String obtenerTexto(
            StatusBarNotification sbn) {

        try {

            if (sbn == null ||
                    sbn.getNotification() == null) {

                return "";
            }

            Bundle extras =
                    sbn.getNotification().extras;

            if (extras == null) {
                return "";
            }

            StringBuilder resultado =
                    new StringBuilder();

            for (String key :
                    EXTRA_KEYS) {

                try {

                    CharSequence value =
                            extras.getCharSequence(key);

                    if (value != null) {

                        resultado
                                .append(value)
                                .append(' ');
                    }

                } catch (Exception ignored) {
                }
            }

            return normalizar(
                    resultado.toString()
            );

        } catch (Exception e) {

            return "";
        }
    }

    // ============================================================
    // SISTEMA ANDROID
    // ============================================================

    private boolean esSistemaAndroid(
            String packageName) {

        String pkg =
                packageName.toLowerCase();

        return
                pkg.contains("systemui") ||
                pkg.equals("android") ||
                pkg.contains(
                        "android.systemui"
                );
    }

    // ============================================================
    // NORMALIZACIÓN
    // ============================================================

    private String normalizar(
            String texto) {

        if (texto == null) {
            return "";
        }

        return texto
                .toLowerCase()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll(
                        "\\s+",
                        " "
                )
                .trim();
    }

    // ============================================================
    // NOTIFICACIÓN REMOVIDA
    // ============================================================

    @Override
    public void onNotificationRemoved(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (sbn != null) {

            Log.d(
                    TAG,
                    "Notificación removida: " +
                    sbn.getKey()
            );
        }
    }
}
