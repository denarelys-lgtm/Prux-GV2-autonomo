package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

/** Replaces the small subset of Shizuku operations Prux actually needs. */
public final class PruxPrivilegedBridge {
    private static final String TAG = "PruxPrivilegedBridge";
    private PruxPrivilegedBridge() {}

    public static boolean isAvailable(Context context) {
        return PruxAdbEngine.get(context).isConnected();
    }

    public static void reconnect(Context context, PruxAdbEngine.Callback callback) {
        PruxAdbEngine.get(context).reconnect(callback);
    }

    public static void applyBackgroundExemptions(Context context) {
        String pkg = context.getPackageName();
        PruxAdbEngine engine = PruxAdbEngine.get(context);
        engine.executeAllowed("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow", null);
        engine.executeAllowed("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", null);
        engine.executeAllowed("cmd appops set " + pkg + " START_FOREGROUND allow", null);
        engine.executeAllowed("dumpsys deviceidle whitelist +" + pkg, null);
    }

    public static void prepareMediaProjection(Context context) {
        String pkg = context.getPackageName();
        PruxAdbEngine engine = PruxAdbEngine.get(context);
        engine.executeAllowed("appops set " + pkg + " PROJECT_MEDIA allow", null);
        engine.executeAllowed("pm grant " + pkg + " android.permission.PROJECT_MEDIA", null);
        Log.i(TAG, "Preparación de MediaProjection solicitada mediante ADB autorizado.");
    }

    public static void startProjectionActivity(Context context) {
        String cmd = "am start -n " + context.getPackageName() + "/.ProjectionActivity";
        PruxAdbEngine.get(context).executeAllowed(cmd, null);
    }
}
