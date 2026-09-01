package com.example.detectcamera;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class AdbDismissService extends AccessibilityService {

    private static final String TAG =
            "PRUX_ACCESSIBILITY";

    private static final String[] TARGET_TEXT = {

        "Se conectó la depuración inalámbrica",
        "Se conecto la depuracion inalambrica",

        "Depuración inalámbrica",
        "Depuracion inalambrica",

        "Wireless debugging"
    };

    @Override
    public void onAccessibilityEvent(
            AccessibilityEvent event) {

        if (event == null) {
            return;
        }

        AccessibilityNodeInfo rootNode =
                getRootInActiveWindow();

        if (rootNode == null) {
            return;
        }

        AccessibilityNodeInfo targetNode = null;

        try {

            /*
             * Buscamos la notificación completa o su título.
             */
            for (String text : TARGET_TEXT) {

                try {

                    List<AccessibilityNodeInfo> nodes =
                            rootNode
                                    .findAccessibilityNodeInfosByText(
                                            text
                                    );

                    if (nodes != null &&
                            !nodes.isEmpty()) {

                        targetNode = nodes.get(0);
                        break;
                    }

                } catch (Exception ignored) {
                }
            }

            if (targetNode == null) {
                return;
            }

            Log.d(
                    TAG,
                    "🎯 Notificación Wireless Debugging encontrada"
            );

            /*
             * El nodo encontrado normalmente no es el nodo
             * que realmente puede descartarse.
             *
             * Subimos por la jerarquía hasta encontrarlo.
             */
            AccessibilityNodeInfo current =
                    targetNode;

            while (current != null) {

                try {

                    if (current.isDismissable()) {

                        boolean resultado =
                                current.performAction(
                                        AccessibilityNodeInfo.ACTION_DISMISS
                                );

                        if (resultado) {

                            Log.d(
                                    TAG,
                                    "✅ Notificación descartada mediante Accessibility"
                            );
                        }

                        break;
                    }

                } catch (Exception ignored) {
                }

                AccessibilityNodeInfo parent =
                        null;

                try {
                    parent = current.getParent();
                } catch (Exception ignored) {
                }

                current = parent;
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error procesando Accessibility",
                    e
            );

        } finally {

            try {

                if (targetNode != null) {
                    targetNode.recycle();
                }

            } catch (Exception ignored) {
            }

            try {
                rootNode.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onInterrupt() {

        Log.w(
                TAG,
                "AdbDismissService interrumpido"
        );
    }
}
