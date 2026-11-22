package com.k2fsa.sherpa.onnx;

public class KeywordSpotterConfig {
    public FeatureConfig featConfig = new FeatureConfig();
    public OnlineModelConfig modelConfig = new OnlineModelConfig();
    public int maxActivePaths = 4;
    public String keywordsFile = "kws/keywords.txt";
    public float keywordsScore = 1.5f;
    public float keywordsThreshold = 0.30f;
    public int numTrailingBlanks = 2;
}
