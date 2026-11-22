package com.xiaozhi.x08c;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

/** Sherpa-ONNX keyword spotter configured for X08C's 16 kHz mono tap. */
public final class SherpaWakeWordEngine implements WakeWordEngine {
    private static final String TAG = "SherpaWakeWordEngine";
    private static final String MODEL_DIR = "kws";

    private final KeywordSpotter spotter;
    private final OnlineStream stream;
    private final float[] floatFrame;

    public SherpaWakeWordEngine(Context context) {
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.keywordsFile = MODEL_DIR + "/keywords.txt";
        config.keywordsScore = 1.5f;
        config.keywordsThreshold = 0.30f;
        config.numTrailingBlanks = 2;

        OnlineModelConfig model = config.modelConfig;
        model.transducer.encoder = MODEL_DIR + "/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
        model.transducer.decoder = MODEL_DIR + "/decoder-epoch-13-avg-2-chunk-16-left-64.onnx";
        model.transducer.joiner = MODEL_DIR + "/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx";
        model.tokens = MODEL_DIR + "/tokens.txt";
        model.numThreads = 1;
        model.provider = "cpu";
        model.modelType = "zipformer2";

        spotter = new KeywordSpotter(context.getAssets(), config);
        stream = spotter.createStream("");
        floatFrame = new float[OpusCodec.FRAME_SAMPLES];
        Log.i(TAG, "KeywordSpotter initialized with zh-en KWS model");
    }

    @Override
    public String acceptWaveform(short[] samples, int length) {
        if (samples == null || length <= 0) return null;
        int count = Math.min(length, Math.min(samples.length, floatFrame.length));
        for (int i = 0; i < count; i++) {
            floatFrame[i] = samples[i] / 32768.0f;
        }
        stream.acceptWaveform(floatFrame, OpusCodec.INPUT_SAMPLE_RATE);
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            KeywordSpotterResult result = spotter.getResult(stream);
            if (result == null || result.keyword == null || result.keyword.trim().isEmpty()) {
                continue;
            }
            String keyword = result.keyword.trim();
            Log.i(TAG, "Keyword detected: " + keyword);
            if (keyword.contains("小智") || keyword.contains("小屁")) {
                return KEYWORD_XIAOZHI;
            }
            if (keyword.contains("小爱")) {
                return KEYWORD_XIAOAI;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        spotter.reset(stream);
    }

    @Override
    public void close() {
        stream.release();
        spotter.release();
    }
}
