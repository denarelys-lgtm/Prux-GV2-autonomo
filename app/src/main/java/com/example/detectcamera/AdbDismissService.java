package com.example.detectcamera;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
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

        // 1. Filtrar para reaccionar SOLO si el evento ocurre en SystemUI (barra de notificaciones)
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !"com.android.systemui".equals(packageName.toString())) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        AccessibilityNodeInfo targetNode = null;

        try {
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

            Log.d(TAG, "🎯 Notificación Wireless Debugging encontrada en SystemUI");

            // Intento 1: Descartar subiendo por la jerarquía
            boolean descartado = intentarDescartarNodo(targetNode);

            // Intento 2: Si ACTION_DISMISS falló, deslizar EXACTAMENTE sobre la ubicación de la notificación
            if (!descartado) {
                Log.w(TAG, "⚠️ ACTION_DISMISS falló. Ejecutando swipe sobre las coordenadas de la notificación...");
                deslizarSobreNodo(targetNode);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error procesando Accessibility", e);
        } finally {
            try {
                if (targetNode != null) targetNode.recycle();
                rootNode.recycle();
            } catch (Exception ignored) {}
        }
    }

    private boolean intentarDescartarNodo(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isDismissable()) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                    Log.d(TAG, "✅ Notificación descartada mediante ACTION_DISMISS");
                    return true;
                }
            }
            
            for (int i = 0; i < current.getChildCount(); i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child != null) {
                    CharSequence desc = child.getContentDescription();
                    CharSequence text = child.getText();
                    if ((desc != null && desc.toString().toLowerCase().contains("descartar")) ||
                        (text != null && text.toString().toLowerCase().contains("descartar"))) {
                        if (child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d(TAG, "✅ Notificación descartada haciendo clic en botón de descarte");
                            return true;
                        }
                    }
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private void deslizarSobreNodo(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // Si el nodo no tiene dimensiones válidas en pantalla, abortar para evitar toques accidentales
        if (bounds.isEmpty() || bounds.centerY() <= 0) {
            Log.w(TAG, "⚠️ No se pudieron obtener coordenadas válidas para el nodo.");
            return;
        }

        int startX = bounds.left + 50;
        int endX = bounds.right - 50;
        int centerY = bounds.centerY();

        Path swipePath = new Path();
        swipePath.moveTo(startX, centerY);
        swipePath.lineTo(endX, centerY);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 250));

        dispatchGesture(gestureBuilder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "✅ Gesto de swipe delimitado completado");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "❌ Gesto de swipe cancelado por el sistema");
            }
        }, null);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AdbDismissService interrumpido");
    }
}
