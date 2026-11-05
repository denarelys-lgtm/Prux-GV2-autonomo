package com.example.detectcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class ScreenCaptureController {

    private static final String TAG = "ScreenCapture";
    private static final String DISPLAY_NAME = "Prux-WebServer-Display";
    private static final int MAX_WIDTH = 1280;
    private static final int JPEG_QUALITY = 50;

    private final Context context;
    private final WebServer webServer;
    private final MediaProjection mediaProjection;

    private HandlerThread captureThread;
    private Handler captureHandler;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int width;
    private int height;
    private int densityDpi;

    private volatile boolean released;
    private volatile boolean projectionStopped;

    private final Object frameLock = new Object();
    private volatile boolean processingFrame;

    public ScreenCaptureController(Context context, MediaProjection mediaProjection, WebServer webServer) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (released || projectionStopped || mediaProjection == null || virtualDisplay != null) return;
        readDisplayMetrics();
        createCaptureThread();
        registerProjectionCallback();
        createImageReader();
        createVirtualDisplay();
    }

    private void readDisplayMetrics() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = Math.max(1, metrics.widthPixels);
        height = Math.max(1, metrics.heightPixels);
        densityDpi = Math.max(1, metrics.densityDpi);

        if (width > MAX_WIDTH) {
            int originalWidth = width;
            width = MAX_WIDTH;
            height = Math.max(1, Math.round(height * (width / (float) originalWidth)));
        }
    }

    private void createCaptureThread() {
        captureThread = new HandlerThread("PruxScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    private void registerProjectionCallback() {
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                projectionStopped = true;
            }
        }, captureHandler);
    }

    private void createImageReader() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
    }

    private void createVirtualDisplay() {
        try {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            Surface outputSurface = imageReader.getSurface();
            if (outputSurface == null || !outputSurface.isValid()) return;

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME, width, height, densityDpi, flags, outputSurface, null, captureHandler);
        } catch (Throwable t) {
            closeReaderAndThread();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        synchronized (frameLock) {
            if (processingFrame) {
                try (Image discarded = reader.acquireLatestImage()) {} catch (Throwable ignored) {}
                return;
            }
            processingFrame = true;
        }

        Image image = null;
        Bitmap fullBitmap = null;
        Bitmap cleanBitmap = null;

        try {
            if (released || projectionStopped) return;
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            if (buffer == null) return;

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride <= 0 || rowStride <= 0) return;

            int rowPadding = Math.max(0, rowStride - pixelStride * width);
            int bitmapWidth = width + rowPadding / pixelStride;

            buffer.rewind();
            fullBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            fullBitmap.copyPixelsFromBuffer(buffer);

            cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(16 * 1024);

            if (cleanBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
                byte[] jpeg = baos.toByteArray();
                if (jpeg.length > 0 && webServer != null && !released && !projectionStopped) {
                    webServer.actualizarFramePantalla(jpeg);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) try { image.close(); } catch (Throwable ignored) {}
            if (cleanBitmap != null) try { cleanBitmap.recycle(); } catch (Throwable ignored) {}
            if (fullBitmap != null) try { fullBitmap.recycle(); } catch (Throwable ignored) {}
            synchronized (frameLock) { processingFrame = false; }
        }
    }

    public synchronized boolean isRunning() {
        return !released && !projectionStopped && virtualDisplay != null && imageReader != null;
    }

    public synchronized void release() {
        if (released) return;
        released = true;
        try {
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(null);
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Throwable ignored) {}
        closeReaderAndThread();
    }

    private void closeReaderAndThread() {
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (captureThread != null) {
                captureThread.quitSafely();
                captureThread = null;
                captureHandler = null;
            }
        } catch (Throwable ignored) {}

        synchronized (frameLock) { processingFrame = false; }
    }
}
