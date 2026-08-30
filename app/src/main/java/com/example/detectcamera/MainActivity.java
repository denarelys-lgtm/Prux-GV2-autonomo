package com.example.detectcamera;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 101;
    private TextView txtIpStatus, txtAdbStatus, txtPairingPort;
    private EditText edtPairingCode, edtPairingPort;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private final BroadcastReceiver receiverIp = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && "com.example.detectcamera.UPDATE_IP".equals(intent.getAction())) {
                String ip = intent.getStringExtra("IP_ADDRESS");
                if (txtIpStatus != null && ip != null) txtIpStatus.setText("Servidor: http://" + ip);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtIpStatus = findViewById(R.id.txtIpStatus);
        txtAdbStatus = findViewById(R.id.txtAdbStatus);
        txtPairingPort = findViewById(R.id.txtPairingPort);
        edtPairingCode = findViewById(R.id.edtPairingCode);
        edtPairingPort = findViewById(R.id.edtPairingPort);
        Button btnDiscover = findViewById(R.id.btnDiscoverPairing);
        Button btnPair = findViewById(R.id.btnPair);
        Button btnReconnect = findViewById(R.id.btnReconnect);

        btnDiscover.setOnClickListener(v -> discoverPairingPort());
        btnPair.setOnClickListener(v -> pair());
        btnReconnect.setOnClickListener(v -> reconnect());

        verificarYSolicitarPermisos();
        discoverPairingPort();
        reconnect();

        // === ACTIVAR EL CAZADOR DE NOTIFICACIONES ===
        activarHider();
    }

    // ===== NUEVO MÉTODO =====
    private void activarHider() {
        try {
            ComponentName cn = new ComponentName(this, NotificationHiderService.class);
            String flat = cn.flattenToString();
            String current = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");

            if (current == null || !current.contains(flat)) {
                String nuevo = (current == null || current.isEmpty()) ? flat : current + ":" + flat;
                Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", nuevo);
                Toast.makeText(this, "Cazador de notificaciones activado", Toast.LENGTH_SHORT).show();
            } else {
                // Ya estaba activado
            }
        } catch (Exception e) {
            // Si falla, el usuario tendrá que activarlo manualmente en Ajustes
            Toast.makeText(this, "No se pudo auto-activar; ve a Ajustes > Notificaciones > Acceso", Toast.LENGTH_LONG).show();
        }
    }

    private void pair() {
        String portText = edtPairingPort.getText().toString().trim();
        String code = edtPairingCode.getText().toString().trim();
        if (!portText.matches("\\d{1,5}") || !code.matches("\\d{6}")) {
            Toast.makeText(this, "Introduce puerto y código de 6 dígitos", Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Integer.parseInt(portText);
        txtAdbStatus.setText("ADB: emparejando...");
        PruxAdbEngine.get(this).pair("127.0.0.1", port, code, (ok, msg) -> runOnUiThread(() -> {
            txtAdbStatus.setText(ok ? "ADB: conectado" : "ADB: " + msg);
            if (ok) PruxPrivilegedBridge.applyBackgroundExemptions(this);
            edtPairingCode.setText("");
        }));
    }

    private void reconnect() {
        txtAdbStatus.setText("ADB: buscando conexión autorizada...");
        PruxAdbEngine.get(this).reconnect((ok, msg) -> runOnUiThread(() -> {
            txtAdbStatus.setText(ok ? "ADB: conectado" : "ADB: " + msg);
            if (ok) PruxPrivilegedBridge.applyBackgroundExemptions(this);
        }));
    }

    private void discoverPairingPort() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (nsdManager == null) nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsdManager == null) return;
        stopDiscovery();
        txtPairingPort.setText("Buscando puerto de emparejamiento...");
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { stopDiscovery(); }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { stopDiscovery(); }
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType() != null && serviceInfo.getServiceType().contains("_adb-tls-pairing")) {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            runOnUiThread(() -> {
                                if (info.getPort() > 0) {
                                    edtPairingPort.setText(String.valueOf(info.getPort()));
                                    txtPairingPort.setText("Puerto de emparejamiento detectado: " + info.getPort());
                                }
                            });
                            stopDiscovery();
                        }
                    });
                }
            }
        };
        try { nsdManager.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener); }
        catch (Throwable t) { txtPairingPort.setText("No se pudo descubrir el puerto; introdúcelo manualmente."); }
    }

    private void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Throwable ignored) {}
            discoveryListener = null;
        }
    }

    private void verificarYSolicitarPermisos() {
        String[] permisos = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : permisos) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, permisos, PERMISSION_REQUEST_CODE);
        else iniciarServicioCamara();
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) iniciarServicioCamara();
        }
    }

    private void iniciarServicioCamara() {
        try {
            Intent intent = new Intent(this, CameraService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent); else startService(intent);
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("com.example.detectcamera.UPDATE_IP");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiverIp, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiverIp, filter);
        reconnect();
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiverIp); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }
}
