package com.example.detectcamera;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class AdbDismissService extends AccessibilityService {

    private static final String TAG = "PRUX_ACCESSIBILITY";

    private static final String[] TARGET_TEXT = {
        "Se conectó la depuración inalámbrica",
        "Se conecto la depuracion inalambrica",
        "Depuración inalámbrica",
        "Depuracion inalambrica",
        "Wireless debugging"
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 1. Filtrar estrictamente: reaccionar SOLO si el evento proviene de la barra/pantalla de notificaciones
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !"com.android.systemui".equals(packageName.toString())) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        AccessibilityNodeInfo targetNode = null;

        try {
            // 2. Buscar si el texto objetivo existe en la ventana activa de SystemUI
            for (String text : TARGET_TEXT) {
                try {
                    List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(text);
                    if (nodes != null && !nodes.isEmpty()) {
                        targetNode = nodes.get(0);
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (targetNode == null) return;

            Log.d(TAG, "🎯 Notificación de depuración inalámbrica detectada en SystemUI");

            // 3. Ejecutar descarte nativo (ACTION_DISMISS) navegando hacia arriba en la jerarquía del nodo
            descartarNotificacionNativa(targetNode);

        } catch (Exception e) {
            Log.e(TAG, "Error procesando evento de accesibilidad", e);
        } finally {
            try {
                if (targetNode != null) targetNode.recycle();
                rootNode.recycle();
            } catch (Exception ignored) {}
        }
    }

    private void descartarNotificacionNativa(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;

        while (current != null) {
            try {
                // Verificar si el contenedor actual soporta el descarte directo del sistema
                if (current.isDismissable()) {
                    boolean exito = current.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                    if (exito) {
                        Log.d(TAG, "✅ Notificación descartada mediante ACTION_DISMISS nativo");
                        return;
                    }
                }
            } catch (Exception ignored) {}

            // Subir al nodo padre dentro del árbol visual
            AccessibilityNodeInfo parent = null;
            try {
                parent = current.getParent();
            } catch (Exception ignored) {}

            current = parent;
        }

        Log.w(TAG, "⚠️ El nodo encontrado no admite la acción ACTION_DISMISS nativa");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AdbDismissService interrumpido");
    }
}
