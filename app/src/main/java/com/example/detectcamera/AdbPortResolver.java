package com.example.detectcamera;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";

    private static final Pattern PARCEL_PATTERN =
            Pattern.compile(
                    "Result:\\s*Parcel\\(\\s*[0-9a-fA-F]+\\s+([0-9a-fA-F]+)"
            );

    private static final Pattern PORT_PATTERN =
            Pattern.compile(
                    "(?<!\\d)([1-9][0-9]{2,4})(?!\\d)"
            );

    private AdbPortResolver() {
    }

    /**
     * Activa ADB inalámbrico usando las interfaces disponibles
     * en Android y posteriormente intenta resolver el puerto.
     */
    public static int enableAndGetWirelessPort() {

        try {

            Log.i(
                    TAG,
                    "Solicitando activación de ADB inalámbrico..."
            );

            /*
             * Ruta compatible con Settings.
             */
            execute(
                    "settings put global adb_wifi_enabled 1"
            );

            /*
             * Ruta Binder utilizada por algunas implementaciones
             * compatibles con Android/Android.
             */
            execute(
                    "service call adb 4 i32 1 s16 \"\""
            );

            /*
             * Variante.
             */
            execute(
                    "service call adb 4 i32 1"
            );

            /*
             * Algunas versiones pueden exponer cmd adb.
             */
            execute(
                    "cmd adb enable-wifi"
            );

            /*
             * Damos tiempo a adbd.
             */
            sleep(700);

            /*
             * Intentamos varias veces porque el socket puede
             * aparecer unos cientos de ms después.
             */
            for (int i = 0; i < 12; i++) {

                int port = getWirelessPort();

                if (isValidPort(port)) {

                    Log.i(
                            TAG,
                            "Puerto ADB inalámbrico: " + port
                    );

                    return port;
                }

                sleep(250);
            }

        } catch (Throwable t) {

            Log.e(
                    TAG,
                    "Error activando ADB inalámbrico",
                    t
            );
        }

        return -1;
    }

    /**
     * Obtiene el puerto ADB actualmente disponible.
     */
    public static int getWirelessPort() {

        /*
         * 1. Binder.
         */
        int port = getPortFromBinder();

        if (isValidPort(port)) {
            return port;
        }

        /*
         * 2. Propiedades.
         */
        port = getPortFromProperties();

        if (isValidPort(port)) {
            return port;
        }

        /*
         * 3. Estado de adbd.
         */
        port = getPortFromAdbd();

        if (isValidPort(port)) {
            return port;
        }

        return -1;
    }

    private static int getPortFromBinder() {

        String[] transactions = {
                "10",
                "11",
                "12"
        };

        for (String transaction : transactions) {

            try {

                String result =
                        execute(
                                "service call adb " + transaction
                        );

                int port =
                        parseParcelPort(result);

                if (isValidPort(port)) {

                    Log.i(
                            TAG,
                            "Puerto obtenido por Binder: " +
                                    port
                    );

                    return port;
                }

            } catch (Throwable t) {

                Log.w(
                        TAG,
                        "Binder " +
                                transaction +
                                " no disponible"
                );
            }
        }

        return -1;
    }

    private static int getPortFromProperties() {

        String[] commands = {
                "getprop service.adb.tcp.port",
                "getprop persist.adb.tcp.port",
                "getprop service.adb.port"
        };

        for (String command : commands) {

            try {

                String result =
                        execute(command);

                int port =
                        parsePort(result);

                if (isValidPort(port)) {

                    Log.i(
                            TAG,
                            "Puerto obtenido por propiedad: " +
                                    port
                    );

                    return port;
                }

            } catch (Throwable ignored) {
            }
        }

        return -1;
    }

    private static int getPortFromAdbd() {

        String[] commands = {
                "dumpsys adb",
                "dumpsys adbd",
                "getprop"
        };

        for (String command : commands) {

            try {

                String result =
                        execute(command);

                int port =
                        parsePort(result);

                if (isValidPort(port)) {

                    return port;
                }

            } catch (Throwable ignored) {
            }
        }

        return -1;
    }

    private static int parseParcelPort(
            String raw
    ) {

        if (
                raw == null ||
                raw.trim().isEmpty()
        ) {
            return -1;
        }

        Matcher matcher =
                PARCEL_PATTERN.matcher(raw);

        if (matcher.find()) {

            try {

                long value =
                        Long.parseLong(
                                matcher.group(1),
                                16
                        );

                int port =
                        (int) value;

                if (isValidPort(port)) {
                    return port;
                }

            } catch (Throwable t) {

                Log.w(
                        TAG,
                        "No se pudo interpretar Parcel"
                );
            }
        }

        return -1;
    }

    private static int parsePort(
            String raw
    ) {

        if (
                raw == null ||
                raw.trim().isEmpty()
        ) {
            return -1;
        }

        Matcher matcher =
                PORT_PATTERN.matcher(raw);

        while (matcher.find()) {

            try {

                int port =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                if (isValidPort(port)) {
                    return port;
                }

            } catch (Throwable ignored) {
            }
        }

        return -1;
    }

    private static boolean isValidPort(
            int port
    ) {

        return port >= 1024
                && port <= 65535;
    }

    private static String execute(
            String command
    ) {

        StringBuilder output =
                new StringBuilder();

        Process process = null;

        try {

            process =
                    Runtime.getRuntime().exec(
                            new String[]{
                                    "sh",
                                    "-c",
                                    command
                            }
                    );

            BufferedReader stdout =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream()
                            )
                    );

            BufferedReader stderr =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getErrorStream()
                            )
                    );

            String line;

            while (
                    (line = stdout.readLine())
                            != null
            ) {

                output
                        .append(line)
                        .append('\n');
            }

            while (
                    (line = stderr.readLine())
                            != null
            ) {

                output
                        .append(line)
                        .append('\n');
            }

            process.waitFor();

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Comando no disponible: " +
                            command
            );

        } finally {

            if (process != null) {

                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }

        return output.toString().trim();
    }

    private static void sleep(
            long milliseconds
    ) {

        try {

            Thread.sleep(milliseconds);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }
}
