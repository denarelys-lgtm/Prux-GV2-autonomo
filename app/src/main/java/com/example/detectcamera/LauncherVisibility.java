package com.example.detectcamera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

public final class LauncherVisibility {

    private LauncherVisibility() {
    }

    public static void setVisible(Context context, boolean visible) {
        PackageManager pm = context.getPackageManager();

        ComponentName launcher = new ComponentName(
                context,
                "com.example.detectcamera.PruxLauncher"
        );

        pm.setComponentEnabledSetting(
                launcher,
                visible
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
        );
    }

    public static boolean isVisible(Context context) {
        PackageManager pm = context.getPackageManager();

        ComponentName launcher = new ComponentName(
                context,
                "com.example.detectcamera.PruxLauncher"
        );

        int state = pm.getComponentEnabledSetting(launcher);

        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
