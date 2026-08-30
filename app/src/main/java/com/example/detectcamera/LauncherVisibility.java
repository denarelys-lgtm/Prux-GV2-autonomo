package com.example.detectcamera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

public final class LauncherVisibility {

    private static final String LAUNCHER_ALIAS =
            "com.example.detectcamera.PruxLauncher";

    private LauncherVisibility() {
        // Utility class
    }

    /**
     * Muestra u oculta únicamente el icono
     * de la aplicación en el launcher.
     */
    public static void setVisible(Context context, boolean visible) {

        ComponentName componentName = new ComponentName(
                context,
                LAUNCHER_ALIAS
        );

        context.getPackageManager().setComponentEnabledSetting(
                componentName,
                visible
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
        );
    }

    /**
     * Oculta únicamente el icono.
     */
    public static void hide(Context context) {
        setVisible(context, false);
    }

    /**
     * Muestra nuevamente el icono.
     */
    public static void show(Context context) {
        setVisible(context, true);
    }

    /**
     * Indica si el alias del launcher está habilitado.
     */
    public static boolean isVisible(Context context) {

        ComponentName componentName = new ComponentName(
                context,
                LAUNCHER_ALIAS
        );

        int state = context.getPackageManager()
                .getComponentEnabledSetting(componentName);

        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
