package com.example.detectcamera;

import android.content.Context;

/** Compatibility name retained so the rest of the project does not need a large refactor. */
public final class PruxBackgroundBypass {
    private PruxBackgroundBypass() {}

    public static void aplicarExencionesBackground(Context context) {
        PruxPrivilegedBridge.applyBackgroundExemptions(context);
    }
}
