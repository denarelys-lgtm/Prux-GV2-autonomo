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

```
private static final String TAG = "ScreenCapture";

/*
 * Nombre del VirtualDisplay.
 *
 * Android lo registra como una pantalla virtual de presentación.
 * El WebServer NO es el display: es el destino de los frames
 * obtenidos desde el ImageReader.
 */
private static final String DISPLAY_NAME = "Prux-WebServer-Display";

/*
 * Límite para no generar frames gigantes.
 */
private static final int MAX_WIDTH = 1280;

/*
 * Calidad JPEG.
 *
 * 55 ofrece una relación bastante buena entre:
 * - fluidez
 * - consumo de CPU
 * - tráfico de red
 * - tamaño del frame
 */
private static final int JPEG_QUALITY = 55;

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

/*
 * Evita procesar simultáneamente varios frames.
 */
private final Object frameLock = new Object();

private volatile boolean processingFrame;

public ScreenCaptureController(
        Context context,
        MediaProjection mediaProjection,
        WebServer webServer
) {
    this.context = context.getApplicationContext();
    this.mediaProjection = mediaProjection;
    this.webServer = webServer;
}

/**
 * Inicia:
 *
 * MediaProjection
 *      ↓
 * VirtualDisplay
 *      ↓
 * ImageReader
 *      ↓
 * JPEG
 *      ↓
 * WebServer
 */
public synchronized void start() {

    if (released) {
        Log.w(TAG, "start() ignorado: controlador liberado.");
        return;
    }

    if (projectionStopped) {
        Log.w(TAG, "start() ignorado: MediaProjection detenida.");
        return;
    }

    if (mediaProjection == null) {
        Log.e(TAG, "MediaProjection es null.");
        return;
    }

    if (virtualDisplay != null) {
        Log.d(TAG, "VirtualDisplay ya está activo.");
        return;
    }

    readDisplayMetrics();

    createCaptureThread();

    registerProjectionCallback();

    createImageReader();

    createVirtualDisplay();
}

/**
 * Obtiene la resolución real disponible para la captura.
 */
private void readDisplayMetrics() {

    DisplayMetrics metrics =
            context.getResources().getDisplayMetrics();

    width = Math.max(1, metrics.widthPixels);
    height = Math.max(1, metrics.heightPixels);
    densityDpi = Math.max(1, metrics.densityDpi);

    /*
     * Limitar únicamente el ancho.
     * Se conserva la relación de aspecto.
     */
    if (width > MAX_WIDTH) {

        int originalWidth = width;

        width = MAX_WIDTH;

        height = Math.max(
                1,
                Math.round(
                        height *
                                (width / (float) originalWidth)
                )
        );
    }

    Log.i(
            TAG,
            "Resolución de captura: "
                    + width
                    + "x"
                    + height
                    + " @"
                    + densityDpi
                    + "dpi"
    );
}

/**
 * Hilo dedicado para captura.
 */
private void createCaptureThread() {

    captureThread =
            new HandlerThread("PruxScreenCapture");

    captureThread.start();

    captureHandler =
            new Handler(captureThread.getLooper());
}

/**
 * Escucha cuándo MediaProjection deja de existir.
 */
private void registerProjectionCallback() {

    mediaProjection.registerCallback(
            new MediaProjection.Callback() {

                @Override
                public void onStop() {

                    projectionStopped = true;

                    Log.w(
                            TAG,
                            "MediaProjection fue detenida por Android."
                    );

                    /*
                     * No hacemos llamadas peligrosas desde el callback.
                     * release() se puede ejecutar posteriormente.
                     */
                }
            },
            captureHandler
    );
}

/**
 * Surface que recibe la salida del VirtualDisplay.
 */
private void createImageReader() {

    imageReader =
            ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    3
            );

    imageReader.setOnImageAvailableListener(
            this::onImageAvailable,
            captureHandler
    );
}

/**
 * Crea la pantalla virtual.
 *
 * La idea es:
 *
 * Display físico
 *       ↓
 * MediaProjection
 *       ↓
 * VirtualDisplay
 *       ↓
 * ImageReader Surface
 */
private void createVirtualDisplay() {

    try {

        int flags =
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                        |
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

        Surface outputSurface =
                imageReader.getSurface();

        if (outputSurface == null ||
                !outputSurface.isValid()) {

            throw new IllegalStateException(
                    "La Surface del ImageReader no es válida."
            );
        }

        virtualDisplay =
                mediaProjection.createVirtualDisplay(
                        DISPLAY_NAME,

                        width,
                        height,
                        densityDpi,

                        flags,

                        outputSurface,

                        new VirtualDisplay.Callback() {

                            @Override
                            public void onPaused() {

                                Log.d(
                                        TAG,
                                        "VirtualDisplay pausado."
                                );
                            }

                            @Override
                            public void onResumed() {

                                Log.d(
                                        TAG,
                                        "VirtualDisplay reanudado."
                                );

                                reaplicarSurface();
                            }

                            @Override
                            public void onStopped() {

                                Log.w(
                                        TAG,
                                        "VirtualDisplay detenido."
                                );
                            }
                        },

                        captureHandler
                );

        if (virtualDisplay == null) {

            throw new IllegalStateException(
                    "createVirtualDisplay() devolvió null."
            );
        }

        Log.i(
                TAG,
                "VirtualDisplay Prux creado correctamente."
        );

        Log.i(
                TAG,
                "MediaProjection → VirtualDisplay → ImageReader → WebServer"
        );

    } catch (Throwable t) {

        Log.e(
                TAG,
                "No se pudo crear el VirtualDisplay.",
                t
        );

        closeReaderAndThread();
    }
}

/**
 * Recibe cada frame producido por el VirtualDisplay.
 */
private void onImageAvailable(ImageReader reader) {

    /*
     * Si ya estamos procesando un frame, descartamos éste.
     *
     * Esto evita que el WebServer acumule frames atrasados.
     */
    synchronized (frameLock) {

        if (processingFrame) {

            Image discarded = null;

            try {
                discarded = reader.acquireLatestImage();
            } catch (Throwable ignored) {
            } finally {

                if (discarded != null) {
                    try {
                        discarded.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            return;
        }

        processingFrame = true;
    }

    Image image = null;
    Bitmap fullBitmap = null;
    Bitmap cleanBitmap = null;

    try {

        if (released || projectionStopped) {
            return;
        }

        /*
         * acquireLatestImage() es importante:
         *
         * No queremos enviar frames antiguos.
         * Siempre interesa el frame más reciente.
         */
        image = reader.acquireLatestImage();

        if (image == null) {
            return;
        }

        Image.Plane[] planes =
                image.getPlanes();

        if (planes == null ||
                planes.length == 0) {

            return;
        }

        Image.Plane plane =
                planes[0];

        ByteBuffer buffer =
                plane.getBuffer();

        if (buffer == null) {
            return;
        }

        int pixelStride =
                plane.getPixelStride();

        int rowStride =
                plane.getRowStride();

        if (pixelStride <= 0 ||
                rowStride <= 0) {

            return;
        }

        /*
         * Android puede agregar padding al final
         * de cada fila.
         */
        int rowPadding =
                Math.max(
                        0,
                        rowStride -
                                pixelStride * width
                );

        int bitmapWidth =
                width +
                        rowPadding /
                                pixelStride;

        /*
         * Seguridad contra dimensiones inválidas.
         */
        if (bitmapWidth < width ||
                bitmapWidth <= 0 ||
                height <= 0) {

            return;
        }

        buffer.rewind();

        fullBitmap =
                Bitmap.createBitmap(
                        bitmapWidth,
                        height,
                        Bitmap.Config.ARGB_8888
                );

        fullBitmap.copyPixelsFromBuffer(buffer);

        /*
         * Eliminamos el padding lateral.
         */
        cleanBitmap =
                Bitmap.createBitmap(
                        fullBitmap,
                        0,
                        0,
                        width,
                        height
                );

        ByteArrayOutputStream baos =
                new ByteArrayOutputStream(
                        Math.max(
                                16 * 1024,
                                width * height / 8
                        )
                );

        boolean compressed =
                cleanBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        JPEG_QUALITY,
                        baos
                );

        if (!compressed) {
            return;
        }

        byte[] jpeg =
                baos.toByteArray();

        if (jpeg.length == 0) {
            return;
        }

        /*
         * Entrega el frame al WebServer.
         */
        if (webServer != null &&
                !released &&
                !projectionStopped) {

            webServer.actualizarFramePantalla(
                    jpeg
            );
        }

    } catch (Throwable t) {

        if (!released) {

            Log.e(
                    TAG,
                    "Error procesando frame.",
                    t
            );
        }

    } finally {

        if (image != null) {

            try {
                image.close();
            } catch (Throwable ignored) {
            }
        }

        if (cleanBitmap != null &&
                !cleanBitmap.isRecycled()) {

            try {
                cleanBitmap.recycle();
            } catch (Throwable ignored) {
            }
        }

        if (fullBitmap != null &&
                !fullBitmap.isRecycled()) {

            try {
                fullBitmap.recycle();
            } catch (Throwable ignored) {
            }
        }

        synchronized (frameLock) {
            processingFrame = false;
        }
    }
}

/**
 * Vuelve a conectar la Surface al VirtualDisplay.
 *
 * Útil después de:
 * - pausa
 * - reanudación
 * - cambios de superficie
 */
private synchronized void reaplicarSurface() {

    if (released ||
            projectionStopped ||
            virtualDisplay == null ||
            imageReader == null) {

        return;
    }

    try {

        Surface surface =
                imageReader.getSurface();

        if (surface == null ||
                !surface.isValid()) {

            Log.w(
                    TAG,
                    "La Surface no es válida."
            );

            return;
        }

        virtualDisplay.setSurface(
                surface
        );

        virtualDisplay.resize(
                width,
                height,
                densityDpi
        );

        Log.d(
                TAG,
                "Surface del WebServer reaplicada al VirtualDisplay."
        );

    } catch (Throwable t) {

        Log.w(
                TAG,
                "No se pudo reaplicar la Surface.",
                t
        );
    }
}

/**
 * Indica si la cadena de captura está funcionando.
 */
public synchronized boolean isRunning() {

    return !released
            && !projectionStopped
            && virtualDisplay != null
            && imageReader != null;
}

/**
 * Libera todos los recursos.
 */
public synchronized void release() {

    if (released) {
        return;
    }

    released = true;

    /*
     * Primero detener el VirtualDisplay.
     */
    try {

        if (virtualDisplay != null) {

            virtualDisplay.setSurface(null);

            virtualDisplay.release();

            virtualDisplay = null;
        }

    } catch (Throwable t) {

        Log.w(
                TAG,
                "Error liberando VirtualDisplay.",
                t
        );
    }

    /*
     * Después ImageReader + hilo.
     */
    closeReaderAndThread();

    /*
     * El WebServer deja de publicar el frame anterior.
     */
    if (webServer != null) {

        try {

            webServer.actualizarFramePantalla(
                    null
            );

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "No se pudo limpiar el frame del WebServer.",
                    t
            );
        }
    }

    Log.i(
            TAG,
            "ScreenCaptureController liberado."
    );
}

/**
 * Cierra ImageReader y el hilo de captura.
 */
private void closeReaderAndThread() {

    try {

        if (imageReader != null) {

            imageReader.setOnImageAvailableListener(
                    null,
                    null
            );

            imageReader.close();

            imageReader = null;
        }

    } catch (Throwable t) {

        Log.w(
                TAG,
                "Error cerrando ImageReader.",
                t
        );
    }

    try {

        if (captureThread != null) {

            captureThread.quitSafely();

            captureThread = null;
            captureHandler = null;
        }

    } catch (Throwable t) {

        Log.w(
                TAG,
                "Error cerrando hilo de captura.",
                t
        );
    }

    synchronized (frameLock) {
        processingFrame = false;
    }
}
```

}
