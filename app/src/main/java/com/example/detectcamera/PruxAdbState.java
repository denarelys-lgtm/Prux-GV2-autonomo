package com.example.detectcamera;

import android.content.Context;

public final class PruxAdbState {

    private static final String PREFS =
            "prux_adb_state";

    private static final String KEY_PAIRED =
            "paired";

    private static final String KEY_LAST_PORT =
            "last_port";

    private static final String KEY_LAST_HOST =
            "last_host";

    private PruxAdbState() {
    }

    private static android.content.SharedPreferences getPrefs(
            Context context
    ) {

        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public static void markPaired(
            Context context,
            boolean value
    ) {

        getPrefs(context)
                .edit()
                .putBoolean(
                        KEY_PAIRED,
                        value
                )
                .apply();
    }

    public static boolean isPaired(
            Context context
    ) {

        return getPrefs(context)
                .getBoolean(
                        KEY_PAIRED,
                        false
                );
    }

    public static void saveEndpoint(
            Context context,
            String host,
            int port
    ) {

        android.content.SharedPreferences.Editor editor =
                getPrefs(context).edit();

        if (host != null) {

            editor.putString(
                    KEY_LAST_HOST,
                    host
            );
        }

        if (port > 0) {

            editor.putInt(
                    KEY_LAST_PORT,
                    port
            );
        }

        editor.apply();
    }

    public static String getLastHost(
            Context context
    ) {

        return getPrefs(context)
                .getString(
                        KEY_LAST_HOST,
                        "127.0.0.1"
                );
    }

    public static int getLastPort(
            Context context
    ) {

        return getPrefs(context)
                .getInt(
                        KEY_LAST_PORT,
                        -1
                );
    }

    public static void clear(
            Context context
    ) {

        getPrefs(context)
                .edit()
                .clear()
                .apply();
    }
}
