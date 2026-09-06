package com.example.detectcamera;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";

    /*
     * Formatos posibles:
     *
     * Result: Parcel(00000000 0000xxxx ...)
     * tcp:5555
     * 5555
     */
    private static final Pattern PARCEL_HEX_PATTERN =
            Pattern.compile(
                    "Result:\\s*Parcel\\(\\s*[0-9a-fA-F]+\\s+([0-9a-fA-F]+)",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern TCP_PATTERN =
            Pattern.compile(
                    "(?:tcp:)?(\\d{2,5})",
                    Pattern.CASE_INSENSITIVE
            );

    private AdbPortResolver() {
    }

    /**
     * Intenta activar ADB inalámbrico utilizando las interfaces
     * compatibles disponibles en Android.
     *
     * Devuelve el puerto TCP detectado o -1.
     */
    public static int enableAndGetWirelessPort() {

        /*
         * Primero intentamos las rutas conocidas.
         *
         * No damos por hecho que una sola implementación de ADB
         * sea idéntica en todas las versiones de Android.
         */
        tryEnableWirelessDebugging();

        /*
         * Esperamos un poco para que adbd abra el socket.
         */
        sleep(700);

        /*
         * Intentamos varias fuentes de información.
         */
        int port;

        port = getPortFromAdbService();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB obtenido mediante servicio adb: " + port);
            return port;
        }

        port = getPortFromProperty();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB obtenido mediante propiedad: " + port);
            return port;
        }

        port = getPortFromSettings();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB obtenido mediante settings: " + port);
            return port;
        }

        port = getPortFromSockets();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB obtenido mediante sockets: " + port);
            return port;
        }

        /*
         * Último recurso compatible con instalaciones que utilizan
         * el puerto ADB TCP clásico.
         */
        port = testCommonPorts();

        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB detectado mediante puertos conocidos: " + port);
            return port;
        }

        Log.w(TAG, "No fue posible determinar el puerto ADB inalámbrico.");

        return -1;
    }

    /**
     * Intenta activar Wireless Debugging.
     *
     * Las llamadas se hacen independientemente; si una no existe
     * en una versión determinada, se continúa con la siguiente.
     */
    private static void tryEnableWirelessDebugging() {

        /*
         * Ruta utilizada por algunas implementaciones Android-like.
         */
        runSilently(
                "settings put global adb_wifi_enabled 1"
        );

        /*
         * Algunas implementaciones exponen el control a través
         * del servicio adb.
         */
        runSilently(
                "service call adb 4 i32 1 s16 \"\""
        );

        /*
         * Android puede exponer el comando cmd adb.
         *
         * Si la versión no lo implementa, simplemente fallará
         * y continuaremos con las demás rutas.
         */
        runSilently(
                "cmd adb enable-wifi"
        );

        runSilently(
                "cmd adb enable"
        );
    }

    /**
     * Intenta obtener el puerto utilizando la transacción existente
     * que ya utilizaba la versión original de Prux.
     */
    private static int getPortFromAdbService() {

        String result =
                executeShellCommand(
                        "service call adb 10"
                );

        if (result == null || result.isEmpty()) {
            return -1;
        }

        Matcher matcher =
                PARCEL_HEX_PATTERN.matcher(result);

        if (matcher.find()) {

            String value =
                    matcher.group(1);

            try {

                long parsed =
                        Long.parseLong(value, 16);

                int port =
                        (int) parsed;

                if (isValidPort(port)) {
                    return port;
                }

            } catch (NumberFormatException e) {

                Log.w(
                        TAG,
                        "No se pudo convertir puerto Parcel: " + value
                );
            }
        }

        /*
         * Algunas variantes pueden devolver directamente tcp:PORT.
         */
        return extractPort(result);
    }

    /**
     * Consulta las propiedades habituales de adbd.
     */
    private static int getPortFromProperty() {

        String[] commands = {
                "getprop service.adb.tcp.port",
                "getprop persist.adb.tcp.port",
                "getprop ro.adb.tcp.port"
        };

        for (String command : commands) {

            String result =
                    executeShellCommand(command);

            int port =
                    extractPort(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /**
     * Consulta settings relacionados con ADB.
     */
    private static int getPortFromSettings() {

        String[] keys = {
                "adb_port",
                "adb_wifi_port",
                "adb_wifi_tcp_port",
                "adb_tcp_port"
        };

        for (String key : keys) {

            String result =
                    executeShellCommand(
                            "settings get global " + key
                    );

            int port =
                    extractPort(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /**
     * Busca sockets TCP abiertos por adbd.
     */
    private static int getPortFromSockets() {

        String[] commands = {
                "cat /proc/net/tcp",
                "ss -ltn",
                "netstat -ltn"
        };

        for (String command : commands) {

            String result =
                    executeShellCommand(command);

            int port =
                    parseSocketTable(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /**
     * Busca puertos TCP LISTEN en tablas del sistema.
     *
     * Se priorizan puertos habituales de ADB.
     */
    private static int parseSocketTable(String output) {

        if (output == null || output.isEmpty()) {
            return -1;
        }

        /*
         * Primero buscamos puertos conocidos de ADB.
         */
        int[] preferred = {
                5555,
                5556,
                5557,
                5558,
                5559
        };

        for (int port : preferred) {

            if (containsListeningPort(output, port)) {
                return port;
            }
        }

        /*
         * Formato /proc/net/tcp:
         *
         * 0100007F:15B3
         *
         * 15B3 hexadecimal = 5555.
         */
        Pattern procPattern =
                Pattern.compile(
                        "0100007F:([0-9A-Fa-f]{4})"
                );

        Matcher matcher =
                procPattern.matcher(output);

        while (matcher.find()) {

            try {

                int port =
                        Integer.parseInt(
                                matcher.group(1),
                                16
                        );

                if (isValidPort(port)) {
                    return port;
                }

            } catch (NumberFormatException ignored) {
            }
        }

        return -1;
    }

    private static boolean containsListeningPort(
            String output,
            int port
    ) {

        String decimal =
                ":" + port;

        if (output.contains(decimal)) {
            return true;
        }

        String hex =
                String.format(
                        "%04X",
                        port
                );

        return output.matches(
                "(?s).*:[0-9A-Fa-f]*" + hex + ".*"
        );
    }

    /**
     * Último recurso.
     */
    private static int testCommonPorts() {

        int[] ports = {
                5555,
                5556,
                5557,
                5558,
                5559
        };

        for (int port : ports) {

            if (canOpenLocalPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /**
     * Comprueba si localhost:puerto acepta una conexión TCP.
     */
    private static boolean canOpenLocalPort(int port) {

        java.net.Socket socket = null;

        try {

            socket =
                    new java.net.Socket();

            socket.connect(
                    new java.net.InetSocketAddress(
                            "127.0.0.1",
                            port
                    ),
                    300
            );

            return true;

        } catch (Throwable ignored) {

            return false;

        } finally {

            if (socket != null) {

                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int extractPort(String value) {

        if (value == null || value.isEmpty()) {
            return -1;
        }

        Matcher matcher =
                TCP_PATTERN.matcher(value);

        while (matcher.find()) {

            try {

                int port =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                if (isValidPort(port)) {
                    return port;
                }

            } catch (NumberFormatException ignored) {
            }
        }

        return -1;
    }

    private static boolean isValidPort(int port) {

        return port >= 1 && port <= 65535;
    }

    private static void sleep(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }

    private static void runSilently(String command) {

        try {
            executeShellCommand(command);
        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Ruta no disponible: " + command
            );
        }
    }

    private static String executeShellCommand(
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

            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream()
                                    )
                            )
            ) {

                String line;

                while (
                        (line = reader.readLine())
                                != null
                ) {

                    output
                            .append(line)
                            .append('\n');
                }
            }

            process.waitFor();

        } catch (Throwable t) {

            Log.w(
                    TAG,
                    "Error ejecutando: " + command
            );

        } finally {

            if (process != null) {

                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }

        return output
                .toString()
                .trim();
    }
}
