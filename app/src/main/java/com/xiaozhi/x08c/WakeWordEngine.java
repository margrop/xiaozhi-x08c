package com.xiaozhi.x08c;

/** Minimal wake-word detector interface used by VoiceAssistantService. */
public interface WakeWordEngine extends AutoCloseable {
    String KEYWORD_XIAOZHI = "xiaozhi";
    String KEYWORD_XIAOPI = "xiaopi";
    String KEYWORD_XIAOAI = "xiaoai";

    /**
     * Processes one mono PCM16 frame.
     *
     * @return one of the KEYWORD_* constants, or null when no keyword is detected.
     */
    String acceptWaveform(short[] samples, int length);

    /** Resets internal decoder state after a keyword or aborted session. */
    void reset();

    @Override
    void close();
}
