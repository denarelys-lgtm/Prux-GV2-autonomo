package com.example.detectcamera;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/**
 * High-performance screen pipeline:
 * MediaProjection -> VirtualDisplay -> encoder Surface -> hardware H.264.
 * No ImageReader, Bitmap or JPEG is used for the screen.
 */
public final class ScreenCaptureController {
    private static final String TAG = "ScreenCapture";
    private static final String DISPLAY_NAME = "Prux-Screen-VirtualDisplay";
    private static final int MAX_LONG_SIDE = 1280;

    private final Context context;
    private final MediaProjection mediaProjection;
    private final WebServer webServer;

    private HandlerThread thread;
    private Handler handler;
    private VirtualDisplay virtualDisplay;
    private H264ScreenEncoder encoder;
    private volatile boolean released;
    private volatile boolean projectionStopped;

    private int width;
    private int height;
    private int densityDpi;

    public ScreenCaptureController(Context context, MediaProjection mediaProjection, WebServer webServer) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (released || projectionStopped || mediaProjection == null || virtualDisplay != null) return;
        try {
            readDisplaySize();
            thread = new HandlerThread("PruxVirtualDisplayEncoder");
            thread.start();
            handler = new Handler(thread.getLooper());

            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    projectionStopped = true;
                    Log.w(TAG, "MediaProjection detenida");
                    releaseAsync();
                }
            }, handler);

            encoder = new H264ScreenEncoder(width, height, new H264ScreenEncoder.Listener() {
                @Override public void onFormat(byte[] config, int w, int h) {
                    if (webServer != null) webServer.actualizarVideoConfig(config, w, h);
                }

                @Override public void onFrame(byte[] data, boolean keyFrame, long ptsUs) {
                    if (webServer != null && !released && !projectionStopped) {
                        webServer.publicarVideoFrame(data, keyFrame, ptsUs);
                    }
                }

                @Override public void onError(Throwable error) {
                    if (!released) Log.e(TAG, "Error del encoder H.264", error);
                }
            });
            encoder.start();

            Surface surface = encoder.getInputSurface();
            if (surface == null || !surface.isValid()) throw new IllegalStateException("Encoder Surface inválida");

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    flags,
                    surface,
                    new VirtualDisplay.Callback() {
                        @Override public void onPaused() { Log.d(TAG, "VirtualDisplay pausado"); }
                        @Override public void onResumed() { Log.d(TAG, "VirtualDisplay reanudado"); }
                        @Override public void onStopped() { Log.w(TAG, "VirtualDisplay detenido"); }
                    },
                    handler
            );

            if (virtualDisplay == null) throw new IllegalStateException("VirtualDisplay == null");
            Log.i(TAG, "Pipeline activo: VirtualDisplay -> Surface -> H.264 " + width + "x" + height + " @60fps");
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo iniciar la captura virtual", t);
            release();
        }
    }

    private void readDisplaySize() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int realW = 0, realH = 0;
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
            realW = b.width(); realH = b.height();
        } else if (wm != null) {
            Display d = wm.getDefaultDisplay();
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p); realW = p.x; realH = p.y;
        }
        if (realW <= 0 || realH <= 0) {
            realW = context.getResources().getDisplayMetrics().widthPixels;
            realH = context.getResources().getDisplayMetrics().heightPixels;
        }
        densityDpi = Math.max(160, context.getResources().getDisplayMetrics().densityDpi);
        float scale = Math.min(1f, MAX_LONG_SIDE / (float)Math.max(realW, realH));
        width = even(Math.max(2, Math.round(realW * scale)));
        height = even(Math.max(2, Math.round(realH * scale)));
    }

    private static int even(int n) { return (n & 1) == 0 ? n : n - 1; }

    public synchronized boolean isRunning() {
        return !released && !projectionStopped && virtualDisplay != null && encoder != null;
    }

    private void releaseAsync() {
        Handler h = handler;
        if (h != null) h.post(this::release);
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        try { if (virtualDisplay != null) { virtualDisplay.setSurface(null); virtualDisplay.release(); } } catch (Throwable ignored) {}
        virtualDisplay = null;
        try { if (encoder != null) encoder.release(); } catch (Throwable ignored) {}
        encoder = null;
        if (webServer != null) webServer.limpiarVideo();
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) {}
        thread = null; handler = null;
        Log.i(TAG, "Captura virtual liberada");
    }
}
