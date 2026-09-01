package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

/**
 * Servicio persistente del servidor Android.
 *
 * El servidor y el monitor ADB son independientes:
 * una caída de ADB no detiene el servidor.
 */
public class ServerService extends Service {

    private static final String TAG =
            "ServerService";

    private static final String CHANNEL_ID =
            "ServerServiceChannel";

    private static final int NOTIFICATION_ID =
            7001;

    private volatile boolean running;

    @Override
    public void onCreate() {

        super.onCreate();

        createNotificationChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "Inicializando servidor..."
                )
        );

        try {

            WebServerManager.start(this);

            running = true;

            updateNotification();

            Log.i(
                    TAG,
                    "Servidor Android iniciado"
            );

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "No se pudo iniciar WebServer",
                    e
            );
        }

        /*
         * A partir de aquí el motor ADB se encarga
         * de vigilar y recuperar la depuración inalámbrica.
         */
        PruxAdbEngine
                .get(this)
                .startPersistentMonitoring();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        /*
         * Solicita al sistema que intente mantener
         * el servicio disponible si el proceso es destruido.
         */
        return START_STICKY;
    }

    private Notification buildNotification(
            String text
    ) {

        String ip =
                "Servidor local";

        WebServer server =
                WebServerManager.get();

        if (server != null) {

            ip =
                    "Servidor activo en :8080";
        }

        return new NotificationCompat.Builder(
                this,
                CHANNEL_ID
        )
                .setContentTitle(
                        "DetectCamera"
                )
                .setContentText(
                        text + " · " + ip
                )
                .setSmallIcon(
                        android.R.drawable
                                .ic_menu_camera
                )
                .setPriority(
                        NotificationCompat
                                .PRIORITY_LOW
                )
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (nm != null) {

            nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                            "Servidor activo"
                    )
            );
        }
    }

    private void createNotificationChannel() {

        if (
                Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.O
        ) {

            NotificationManager nm =
                    getSystemService(
                            NotificationManager.class
                    );

            if (nm != null) {

                nm.createNotificationChannel(
                        new NotificationChannel(
                                CHANNEL_ID,
                                "Servidor DetectCamera",
                                NotificationManager
                                        .IMPORTANCE_LOW
                        )
                );
            }
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        /*
         * No necesitamos detener el monitor ADB aquí.
         * Su recuperación pertenece al motor ADB y al
         * ciclo de vida general de Android.
         */
        WebServerManager.stop();

        Log.w(
                TAG,
                "ServerService destruido"
        );

        super.onDestroy();
    }

    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;
    }
}
