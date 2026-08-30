package com.example.detectcamera;

import android.content.Context;

/** Compatibility facade kept for the existing CameraService flow. */
public final class MediaProjectionHelper {
    private MediaProjectionHelper() {}

    public static boolean isPruxAdbAvailable(Context context) {
        return PruxAdbEngine.get(context).isConnected();
    }

    public static boolean preparar(Context context) {
        if (!isPruxAdbAvailable(context)) return false;
        PruxPrivilegedBridge.prepareMediaProjection(context);
        return true;
    }

    public static void ejecutarComandoShell(Context context, String command) {
        // Only the existing ProjectionActivity launch is accepted by the bridge.
        PruxAdbEngine.get(context).executeAllowed(command, null);
    }
}
