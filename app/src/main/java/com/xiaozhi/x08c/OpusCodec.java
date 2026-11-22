package com.xiaozhi.x08c;

import java.util.Arrays;

/**
 * Opus encoder/decoder backed by the statically bundled ARM32 Opus library.
 *
 * <p>The encoder always accepts 60 ms, 16 kHz, mono PCM frames. The decoder can
 * output at any sample rate supported by Opus.</p>
 */
public final class OpusCodec implements AutoCloseable {
    public static final int INPUT_SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 960;
    public static final int DEFAULT_OUTPUT_SAMPLE_RATE = 48000;

    private static final int[] SUPPORTED_SAMPLE_RATES = {
            8000, 12000, 16000, 24000, 48000
    };

    static {
        System.loadLibrary("x08copus");
    }

    private long encoderHandle;
    private long decoderHandle;
    private int outputSampleRate;
    private boolean closed;

    public OpusCodec() {
        this(DEFAULT_OUTPUT_SAMPLE_RATE);
    }

    public OpusCodec(int outputSampleRate) {
        checkSampleRate(outputSampleRate);
        this.outputSampleRate = outputSampleRate;

        long encoder = nativeCreateEncoder(INPUT_SAMPLE_RATE, 1);
        if (encoder == 0) {
            throw new IllegalStateException("Opus 编码器创建失败");
        }

        try {
            long decoder = nativeCreateDecoder(outputSampleRate, 1);
            if (decoder == 0) {
                throw new IllegalStateException("Opus 解码器创建失败");
            }
            decoderHandle = decoder;
            encoderHandle = encoder;
        } catch (RuntimeException | Error error) {
            nativeDestroyEncoder(encoder);
            throw error;
        }
    }

    public synchronized byte[] encode(short[] pcm) {
        ensureOpen();
        if (pcm == null || pcm.length != FRAME_SAMPLES) {
            throw new IllegalArgumentException("PCM 帧必须包含 960 个 short 样本");
        }
        return nativeEncode(encoderHandle, pcm);
    }

    public synchronized short[] decode(byte[] data) {
        ensureOpen();
        if (data == null) {
            throw new IllegalArgumentException("Opus 数据不能为 null");
        }
        if (data.length == 0) {
            return new short[0];
        }
        return nativeDecode(decoderHandle, data);
    }

    public synchronized void setOutputSampleRate(int sampleRate) {
        ensureOpen();
        checkSampleRate(sampleRate);
        if (sampleRate == outputSampleRate) {
            return;
        }

        // Create the replacement first so a failure leaves the old decoder usable.
        long replacement = nativeCreateDecoder(sampleRate, 1);
        if (replacement == 0) {
            throw new IllegalStateException("Opus 解码器创建失败");
        }
        long previous = decoderHandle;
        decoderHandle = replacement;
        outputSampleRate = sampleRate;
        nativeDestroyDecoder(previous);
    }

    public synchronized int getOutputSampleRate() {
        ensureOpen();
        return outputSampleRate;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        long encoder = encoderHandle;
        long decoder = decoderHandle;
        encoderHandle = 0;
        decoderHandle = 0;
        if (encoder != 0) {
            nativeDestroyEncoder(encoder);
        }
        if (decoder != 0) {
            nativeDestroyDecoder(decoder);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OpusCodec 已关闭");
        }
    }

    public static boolean isSupportedSampleRate(int sampleRate) {
        for (int supported : SUPPORTED_SAMPLE_RATES) {
            if (supported == sampleRate) {
                return true;
            }
        }
        return false;
    }

    private static void checkSampleRate(int sampleRate) {
        for (int supported : SUPPORTED_SAMPLE_RATES) {
            if (supported == sampleRate) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Opus 输出采样率仅支持 " + Arrays.toString(SUPPORTED_SAMPLE_RATES));
    }

    private static native long nativeCreateEncoder(int sampleRate, int channels);

    private static native long nativeCreateDecoder(int sampleRate, int channels);

    private static native byte[] nativeEncode(long encoderHandle, short[] pcm);

    private static native short[] nativeDecode(long decoderHandle, byte[] data);

    private static native void nativeDestroyEncoder(long encoderHandle);

    private static native void nativeDestroyDecoder(long decoderHandle);
}
