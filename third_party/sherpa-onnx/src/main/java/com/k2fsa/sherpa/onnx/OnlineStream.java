package com.k2fsa.sherpa.onnx;

public class OnlineStream {
    public long ptr;

    public OnlineStream(long ptr) {
        if (ptr == 0) {
            throw new IllegalArgumentException("Failed to create native OnlineStream");
        }
        this.ptr = ptr;
    }

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(ptr, samples, sampleRate);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    @Override
    protected void finalize() {
        release();
    }

    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);
    private native void delete(long ptr);

    static {
        System.loadLibrary("sherpa-onnx-jni");
    }
}
