package com.example.detectcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Hardware H.264 encoder using Surface input. No Bitmap/JPEG conversion. */
public final class H264ScreenEncoder {
    public interface Listener {
        void onFormat(byte[] annexBConfig, int width, int height);
        void onFrame(byte[] annexB, boolean keyFrame, long presentationTimeUs);
        void onError(Throwable error);
    }

    private static final String TAG = "H264ScreenEncoder";
    private static final int FPS = 60;
    private static final int BITRATE = 4_000_000;
    private static final int I_FRAME_INTERVAL = 1;

    private final int width;
    private final int height;
    private final Listener listener;
    private MediaCodec codec;
    private Surface inputSurface;
    private volatile byte[] codecConfig;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public H264ScreenEncoder(int width, int height, Listener listener) {
        this.width = width;
        this.height = height;
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (started.get()) return;

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        try {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        } catch (Throwable ignored) {}
        try {
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        } catch (Throwable ignored) {}

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec c, int index) {}

            @Override public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
                try {
                    ByteBuffer buffer = c.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buffer.get(data);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            byte[] cfg = toAnnexB(data);
                            codecConfig = cfg;
                            listener.onFormat(cfg, width, height);
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            byte[] frame = toAnnexB(data);
                            if (key && codecConfig != null && codecConfig.length > 0 && !startsWithConfig(frame, codecConfig)) {
                                frame = concat(codecConfig, frame);
                            }
                            listener.onFrame(frame, key, info.presentationTimeUs);
                        }
                    }
                } catch (Throwable t) {
                    listener.onError(t);
                } finally {
                    try { c.releaseOutputBuffer(index, false); } catch (Throwable ignored) {}
                }
            }

            @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat newFormat) {
                try {
                    ByteBuffer sps = newFormat.getByteBuffer("csd-0");
                    ByteBuffer pps = newFormat.getByteBuffer("csd-1");
                    byte[] config = joinAnnexB(sps, pps);
                    if (config.length > 0) { codecConfig = config; listener.onFormat(config, width, height); }
                } catch (Throwable t) {
                    listener.onError(t);
                }
            }

            @Override public void onError(MediaCodec c, MediaCodec.CodecException e) {
                listener.onError(e);
            }
        });
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
        started.set(true);
        Log.i(TAG, "H.264 Surface encoder iniciado: " + width + "x" + height + " @ " + FPS + "fps");
    }

    public Surface getInputSurface() { return inputSurface; }

    public synchronized void requestKeyFrame() {
        if (codec == null || !started.get()) return;
        try {
            BundleCompat.setRequestSyncFrame(codec);
        } catch (Throwable ignored) {}
    }

    public synchronized void release() {
        started.set(false);
        codecConfig = null;
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Throwable ignored) {}
            inputSurface = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            codec = null;
        }
    }

    private static byte[] toAnnexB(byte[] data) {
        if (hasStartCode(data)) return data;
        byte[] out = new byte[data.length + 4];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1;
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    private static boolean hasStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 &&
                ((data[2] == 1) || (data[2] == 0 && data[3] == 1));
    }

    private static byte[] joinAnnexB(ByteBuffer a, ByteBuffer b) {
        byte[] aa = copy(a), bb = copy(b);
        if (aa.length == 0 && bb.length == 0) return new byte[0];
        byte[] out = new byte[(aa.length > 0 ? aa.length + 4 : 0) +
                (bb.length > 0 ? bb.length + 4 : 0)];
        int p = 0;
        if (aa.length > 0) { p = startCode(out, p); System.arraycopy(aa, 0, out, p, aa.length); p += aa.length; }
        if (bb.length > 0) { p = startCode(out, p); System.arraycopy(bb, 0, out, p, bb.length); }
        return out;
    }

    private static int startCode(byte[] out, int p) {
        out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 1; return p;
    }

    private static byte[] copy(ByteBuffer b) {
        if (b == null) return new byte[0];
        ByteBuffer x = b.duplicate();
        byte[] out = new byte[x.remaining()]; x.get(out); return out;
    }

    private static boolean startsWithConfig(byte[] frame, byte[] config) {
        if (frame.length < config.length) return false;
        for (int i = 0; i < config.length; i++) if (frame[i] != config[i]) return false;
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Avoid compile-time dependency on newer MediaCodec constants. */
    private static final class BundleCompat {
        static void setRequestSyncFrame(MediaCodec codec) throws Exception {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt("request-sync", 0);
            codec.setParameters(b);
        }
    }
}
