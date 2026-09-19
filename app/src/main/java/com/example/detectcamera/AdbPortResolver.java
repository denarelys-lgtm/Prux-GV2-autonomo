package com.example.detectcamera;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";

    /*
     * Formatos de salida que intentamos reconocer:
     *
     *   Result: Parcel(00000000 00001b39 ...)   -> hex
     *   tcp:5555
     *   5555
     */
    private static final Pattern PARCEL_HEX_PATTERN =
            Pattern.compile(
                    "Result:\\s*Parcel\\(\\s*[0-9a-fA-F]+\\s+([0-9a-fA-F]+)",
                    Pattern.CASE_INSENSITIVE
            );

    /*
     * Regex estricta:
     *   - opcional "tcp:"
     *   - puerto válido (4 o 5 dígitos, 1024..65535)
     *   - límites de palabra para no capturar "android-34"
     */
    private static final Pattern TCP_PATTERN =
            Pattern.compile(
                    "(?:tcp:)?\\b([1-9][0-9]{3,4})\\b",
                    Pattern.CASE_INSENSITIVE
            );

    /*
     * Puerto en /proc/net/tcp (formato 0100007F:15B3 en hex).
     * 0100007F = 127.0.0.1 en little-endian.
     */
    private static final Pattern PROC_TCP_HEX_PATTERN =
            Pattern.compile(
                    "0100007F:([0-9A-Fa-f]{4})"
            );

    private AdbPortResolver() {
    }

    /* ------------------------------------------------------------------ */
    /*  API pública                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Intenta activar ADB inalámbrico y devuelve el puerto TCP detectado.
     * Devuelve -1 si no se pudo resolver.
     */
    public static int enableAndGetWirelessPort() {

        try {

            Log.i(TAG, "Solicitando activación de ADB inalámbrico...");

            tryEnableWirelessDebugging();

            /*
             * Damos tiempo a adbd y reintentamos varias veces:
             * el socket puede tardar en aparecer.
             */
            for (int i = 0; i < 12; i++) {

                int port = getWirelessPort();

                if (isValidPort(port)) {
                    Log.i(TAG, "Puerto ADB inalámbrico detectado: " + port);
                    return port;
                }

                sleep(250);
            }

        } catch (Throwable t) {
            Log.e(TAG, "Error activando ADB inalámbrico", t);
        }

        Log.w(TAG, "No fue posible determinar el puerto ADB inalámbrico.");
        return -1;
    }

    /**
     * Consulta el puerto ADB actualmente disponible probando todas
     * las fuentes conocidas.
     */
    public static int getWirelessPort() {

        int port;

        port = getPortFromAdbService();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB por servicio adb: " + port);
            return port;
        }

        port = getPortFromProperty();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB por propiedad: " + port);
            return port;
        }

        port = getPortFromSettings();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB por settings: " + port);
            return port;
        }

        port = getPortFromSockets();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB por sockets: " + port);
            return port;
        }

        port = testCommonPorts();
        if (isValidPort(port)) {
            Log.i(TAG, "Puerto ADB por puertos conocidos: " + port);
            return port;
        }

        return -1;
    }

    /* ------------------------------------------------------------------ */
    /*  Activación de Wireless Debugging                                  */
    /* ------------------------------------------------------------------ */

    private static void tryEnableWirelessDebugging() {

        /*
         * Cada ruta es independiente; si una no existe en la versión
         * de Android en uso, se ignora y se continúa con la siguiente.
         */
        runSilently("settings put global adb_wifi_enabled 1");

        runSilently("service call adb 4 i32 1 s16 \"\"");
        runSilently("service call adb 4 i32 1");

        runSilently("cmd adb enable-wifi");
        runSilently("cmd adb enable");
    }

    /* ------------------------------------------------------------------ */
    /*  Fuente 1: servicio adb vía Binder                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Prueba varias transacciones Binder porque el índice cambia según
     * versión de Android y OEM. Lee stdout y stderr (algunos ROMs
     * escriben el Parcel en stderr).
     */
    private static int getPortFromAdbService() {

        String[] transactions = { "10", "11", "12" };

        for (String tx : transactions) {

            String result = executeShellCommand("service call adb " + tx);

            if (result == null || result.isEmpty()) {
                continue;
            }

            int port = parseParcelPort(result);

            if (!isValidPort(port)) {
                port = extractPort(result);
            }

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /* ------------------------------------------------------------------ */
    /*  Fuente 2: propiedades del sistema                                 */
    /* ------------------------------------------------------------------ */

    private static int getPortFromProperty() {

        String[] commands = {
                "getprop service.adb.tcp.port",
                "getprop persist.adb.tcp.port",
                "getprop ro.adb.tcp.port",
                "getprop service.adb.port"
        };

        for (String command : commands) {

            String result = executeShellCommand(command);

            int port = extractPort(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /* ------------------------------------------------------------------ */
    /*  Fuente 3: settings globales                                       */
    /* ------------------------------------------------------------------ */

    private static int getPortFromSettings() {

        String[] keys = {
                "adb_port",
                "adb_wifi_port",
                "adb_wifi_tcp_port",
                "adb_tcp_port"
        };

        for (String key : keys) {

            String result = executeShellCommand("settings get global " + key);

            int port = extractPort(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /* ------------------------------------------------------------------ */
    /*  Fuente 4: tabla de sockets                                        */
    /* ------------------------------------------------------------------ */

    private static int getPortFromSockets() {

        String[] commands = {
                "cat /proc/net/tcp",
                "cat /proc/net/tcp6",
                "ss -ltn",
                "netstat -ltn"
        };

        for (String command : commands) {

            String result = executeShellCommand(command);

            int port = parseSocketTable(result);

            if (isValidPort(port)) {
                return port;
            }
        }

        return -1;
    }

    /**
     * Intenta localizar un puerto ADB en la salida de /proc/net/tcp
     * o de ss/netstat.
     */
    private static int parseSocketTable(String output) {

        if (output == null || output.isEmpty()) {
            return -1;
        }

        /*
         * 1. Parseo determinista de /proc/net/tcp (hex).
         */
        Matcher hexMatcher = PROC_TCP_HEX_PATTERN.matcher(output);

        while (hexMatcher.find()) {

            try {

                int port = Integer.parseInt(hexMatcher.group(1), 16);

                if (isValidPort(port)) {
                    return port;
                }

            } catch (NumberFormatException ignored) {
            }
        }

        /*
         * 2. Puertos conocidos de ADB primero.
         */
        int[] preferred = { 5555, 5556, 5557, 5558, 5559 };

        for (int port : preferred) {
            if (containsListeningPort(output, port)) {
                return port;
            }
        }

        return -1;
    }

    private static boolean containsListeningPort(String output, int port) {

        if (output.contains(":" + port)) {
            return true;
        }

        String hex = String.format("%04X", port);

        return output.matches(
                "(?s).*\\b" + hex + "\\b.*"
        );
    }

    /* ------------------------------------------------------------------ */
    /*  Fuente 5: sondeo TCP local                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Último recurso: comprueba si localhost acepta conexión en los
     * puertos clásicos de ADB.
     */
    private static int testCommonPorts() {

        int[] ports = { 5555, 5556, 5557, 5558, 5559 };

        for (int port : ports) {

            if (canOpenLocalPort(port)) {
                return port;
            }
        }

        return -1;
    }

    private static boolean canOpenLocalPort(int port) {

        Socket socket = null;

        try {

            socket = new Socket();

            socket.connect(
                    new InetSocketAddress("127.0.0.1", port),
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

    /* ------------------------------------------------------------------ */
    /*  Parseo                                                            */
    /* ------------------------------------------------------------------ */

    private static int parseParcelPort(String raw) {

        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }

        Matcher matcher = PARCEL_HEX_PATTERN.matcher(raw);

        if (matcher.find()) {

            try {

                long value = Long.parseLong(matcher.group(1), 16);
                int port = (int) value;

                if (isValidPort(port)) {
                    return port;
                }

            } catch (Throwable t) {
                Log.w(TAG, "No se pudo interpretar Parcel: " + matcher.group(1));
            }
        }

        return -1;
    }

    private static int extractPort(String value) {

        if (value == null || value.isEmpty()) {
            return -1;
        }

        Matcher matcher = TCP_PATTERN.matcher(value);

        while (matcher.find()) {

            try {

                int port = Integer.parseInt(matcher.group(1));

                if (isValidPort(port)) {
                    return port;
                }

            } catch (NumberFormatException ignored) {
            }
        }

        return -1;
    }

    /**
     * Los puertos < 1024 son privilegiados: adbd no puede bindearlos.
     * Filtrarlos evita falsos positivos como "android-34 -> 34".
     */
    private static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    /* ------------------------------------------------------------------ */
    /*  Utilidades                                                        */
    /* ------------------------------------------------------------------ */

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
            Log.w(TAG, "Ruta no disponible: " + command);
        }
    }

    /**
     * Ejecuta un comando de shell y devuelve stdout + stderr concatenados.
     * Es importante leer ambos porque algunas ROMs escriben el resultado
     * del Parcel en stderr.
     */
    private static String executeShellCommand(String command) {

        StringBuilder output = new StringBuilder();

        Process process = null;

        try {

            process = Runtime.getRuntime().exec(
                    new String[]{ "sh", "-c", command }
            );

            /*
             * Leemos stdout y stderr. Para evitar bloqueos, se leen
             * en el mismo hilo secuencialmente; los comandos que
             * usamos son de salida corta.
             */
            drain(process.getInputStream(), output);
            drain(process.getErrorStream(), output);

            process.waitFor();

        } catch (Throwable t) {

            Log.w(TAG, "Error ejecutando: " + command);

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

    private static void drain(InputStream in, StringBuilder out) {

        if (in == null) {
            return;
        }

        BufferedReader reader = null;

        try {

            reader = new BufferedReader(new InputStreamReader(in));

            String line;

            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }

        } catch (Throwable ignored) {

        } finally {

            if (reader != null) {

                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
