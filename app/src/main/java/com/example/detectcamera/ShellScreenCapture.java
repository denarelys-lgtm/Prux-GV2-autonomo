package com.example.detectcamera;

/**
 * Legacy compatibility class. Screen streaming in Prux uses MediaProjection.
 * This class deliberately does not bypass FLAG_SECURE or use privileged screencap.
 */
public final class ShellScreenCapture {
    private ShellScreenCapture() {}
    public static byte[] capturarFramePng() { return null; }
}
