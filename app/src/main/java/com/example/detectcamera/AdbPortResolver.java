package com.example.detectcamera;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdbPortResolver {

    private static final String TAG = "AdbPortResolver";
    private static final Pattern PARCEL_PATTERN = Pattern.compile("Result:\\s*Parcel\\(\\s*[0-9a-fA-F]+\\s+([0-9a-fA-F]+)");

    private AdbPortResolver() {}

    /**
     * Habilita el servicio ADB Wi-Fi en el sistema y retorna el puerto activo.
     */
    public static int enableAndGetWirelessPort() {
        try {
            // 1. Activar el switch global de ADB por Wi-Fi
            executeShellCommand("settings put global adb_wifi_enabled 1");

            // 2. Invocar la transacción 4 (allowWirelessDebugging)
            executeShellCommand("service call adb 4 i32 1 s16 \"\"");

            // Dar tiempo al servicio adbd para inicializar el socket
            Thread.sleep(600);

            // 3. Consultar la transacción 10 (getAdbWirelessPort)
            String parcelResult = executeShellCommand("service call adb 10");
            
            return parsePortFromParcel(parcelResult);

        } catch (Throwable t) {
            Log.e(TAG, "Error al resolver el puerto de ADB Inalámbrico", t);
            return -1;
        }
    }

    private static String executeShellCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando local: " + command, e);
        }
        return output.toString().trim();
    }

    private static int parsePortFromParcel(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) return -1;
        
        Matcher matcher = PARCEL_PATTERN.matcher(rawOutput);
        if (matcher.find()) {
            String hexPort = matcher.group(1);
            try {
                long parsedHex = Long.parseLong(hexPort, 16);
                int port = (int) parsedHex;
                return port == -1 ? -1 : port;
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parseando puerto hexadecimal: " + hexPort, e);
            }
        }
        return -1;
    }
}
