package com.example.detectcamera;

import android.content.Context;
import android.util.Log;
import java.io.IOException;

public final class WebServerManager {
    private static final String TAG = "WebServerManager";
    private static final int PORT = 8080;
    private static final int SCREEN_WS_PORT = 8081;
    private static WebServer server;
    private static ScreenWebSocketServer screenWebSocketServer;

    private WebServerManager() {}

    public static synchronized WebServer start(Context context) throws IOException {
        if (server == null) {
            server = new WebServer(PORT);
            server.start(10000, false);
            Log.i(TAG, "WebServer iniciado en puerto " + PORT);
        }
        if (screenWebSocketServer == null) {
            screenWebSocketServer = new ScreenWebSocketServer(SCREEN_WS_PORT);
            try {
                screenWebSocketServer.start();
            } catch (IOException e) {
                screenWebSocketServer = null;
                Log.e(TAG, "No se pudo iniciar WebSocket de pantalla en " + SCREEN_WS_PORT, e);
                throw e;
            }
            Log.i(TAG, "Screen WebSocket H.264 iniciado en puerto " + SCREEN_WS_PORT);
        }
        return server;
    }

    public static synchronized WebServer get() { return server; }
    public static synchronized WebServer getOrStart(Context context) throws IOException { return start(context); }
    public static synchronized ScreenWebSocketServer getScreenWebSocketServer() { return screenWebSocketServer; }
    public static synchronized String getScreenWsToken() { return screenWebSocketServer == null ? "" : screenWebSocketServer.getToken(); }

    public static synchronized void attachCameraService(CameraService service) {
        if (server != null) server.setCameraService(service);
    }
    public static synchronized void detachCameraService(CameraService service) {
        if (server != null) server.clearCameraService(service);
    }

    public static synchronized void stop() {
        if (screenWebSocketServer != null) {
            try { screenWebSocketServer.stop(); } catch (Throwable e) { Log.w(TAG, "Error deteniendo WebSocket", e); }
            screenWebSocketServer = null;
        }
        if (server != null) {
            try { server.detenerAudio(); server.stop(); } catch (Exception e) { Log.w(TAG, "Error deteniendo WebServer", e); }
            server = null;
        }
    }
}
