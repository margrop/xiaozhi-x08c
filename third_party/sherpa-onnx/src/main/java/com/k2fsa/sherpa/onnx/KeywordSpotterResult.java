package com.k2fsa.sherpa.onnx;

public class KeywordSpotterResult {
    public String keyword = "";
    public String[] tokens = new String[0];
    public float[] timestamps = new float[0];

    public KeywordSpotterResult() {
    }

    public KeywordSpotterResult(String keyword, String[] tokens, float[] timestamps) {
        this.keyword = keyword == null ? "" : keyword;
        this.tokens = tokens == null ? new String[0] : tokens;
        this.timestamps = timestamps == null ? new float[0] : timestamps;
    }

    @Override
    public String toString() {
        StringBuilder tokenText = new StringBuilder();
        for (String token : tokens) {
            if (tokenText.length() > 0) tokenText.append(", ");
            tokenText.append(token);
        }
        return "Keyword: " + keyword + "\nTokens: [" + tokenText + "]";
    }
}
