package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class KeywordSpotter {
    private long ptr;

    public KeywordSpotter(AssetManager assetManager, KeywordSpotterConfig config) {
        ptr = newFromAsset(assetManager, config);
        if (ptr == 0) {
            throw new IllegalArgumentException("Invalid KeywordSpotterConfig");
        }
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

    public OnlineStream createStream(String keywords) {
        return new OnlineStream(createStream(ptr, keywords));
    }

    public void decode(OnlineStream stream) {
        decode(ptr, stream.ptr);
    }

    public void reset(OnlineStream stream) {
        reset(ptr, stream.ptr);
    }

    public boolean isReady(OnlineStream stream) {
        return isReady(ptr, stream.ptr);
    }

    public KeywordSpotterResult getResult(OnlineStream stream) {
        return getResult(ptr, stream.ptr);
    }

    private native void delete(long ptr);
    private native long newFromAsset(AssetManager assetManager, KeywordSpotterConfig config);
    private native long createStream(long ptr, String keywords);
    private native boolean isReady(long ptr, long streamPtr);
    private native void decode(long ptr, long streamPtr);
    private native void reset(long ptr, long streamPtr);
    private native KeywordSpotterResult getResult(long ptr, long streamPtr);

    static {
        System.loadLibrary("sherpa-onnx-jni");
    }
}
