package com.example.detectcamera;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class AdbDismissService extends AccessibilityService {
    private static final String TAG = "KOPLANZA";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Obtenemos la raíz de la ventana activa (panel de notificaciones / barra de estado)
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // Palabras clave para detectar la notificación en Xiaomi / HyperOS / Android en español e inglés
        String[] keywords = {
            "depuración inalámbrica",
            "depuracion inalambrica",
            "se conectó",
            "wireless debugging",
            "inalámbrica"
        };

        AccessibilityNodeInfo targetNode = null;

        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                targetNode = nodes.get(0);
                break;
            }
        }

        // Si encontramos la notificación, subimos por la jerarquía hasta encontrar el nodo descartable
        if (targetNode != null) {
            AccessibilityNodeInfo current = targetNode;
            while (current != null) {
                if (current.isDismissable()) {
                    boolean exito = current.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                    if (exito) {
                        Log.d(TAG, "✅ Notificación ADB descartada con éxito vía AccessibilityService");
                    }
                    break;
                }
                current = current.getParent();
            }
            targetNode.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AdbDismissService interrumpido");
    }
}

