package com.xiaozhi.x08c;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Audio capture, Opus playback, and a bounded local microphone loopback test. */
public final class AudioEngine implements AutoCloseable {
    public interface Callback {
        void onRecordingChanged(boolean recording);

        void onInfo(String message);

        void onError(String message);
    }

    public interface FrameConsumer {
        void onFrame(byte[] data);
    }

    /** Receives a copy of each raw mono PCM16 capture frame (16 kHz, 60 ms). */
    public interface PcmFrameConsumer {
        void onPcmFrame(short[] samples);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 960; // 60 ms at 16 kHz.
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int LOOPBACK_FRAMES = 33; // About 1.98 seconds.
    private static final int ZERO_FRAME_ERROR_THRESHOLD = 50; // 3 seconds.
    private static final int PLAYBACK_QUEUE_CAPACITY = 64;
    private static final long THREAD_JOIN_MS = 1500;

    private final Object stateLock = new Object();
    private final Context appContext;
    private final Callback callback;

    private volatile boolean closed;
    private volatile boolean recording;
    private int outputSampleRate = OpusCodec.DEFAULT_OUTPUT_SAMPLE_RATE;
    private OpusCodec codec;
    private RecordingSession recordingSession;
    private PlaybackSession playbackSession;

    public AudioEngine(Context context, Callback callback) {
        if (context == null) {
            throw new NullPointerException("context == null");
        }
        if (callback == null) {
            throw new NullPointerException("callback == null");
        }
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext != null ? applicationContext : context;
        this.callback = callback;
    }

    public boolean startRecording(FrameConsumer consumer) {
        return startRecording(consumer, null);
    }

    /**
     * Starts one shared capture stream feeding both the encoded Opus frames
     * and, when {@code pcmConsumer} is non-null, a raw PCM tap for the wake
     * word engine. Only one AudioRecord is ever opened.
     */
    public boolean startRecording(FrameConsumer consumer, PcmFrameConsumer pcmConsumer) {
        if (consumer == null) {
            safeError("录音帧消费者不能为 null");
            return false;
        }

        final RecordingSession session;
        final int audioSource;
        synchronized (stateLock) {
            if (closed) {
                safeError("AudioEngine 已关闭");
                return false;
            }
            if (recordingSession != null) {
                safeError("录音已在进行");
                return false;
            }

            try {
                ensureCodecLocked();
                int bufferSize = recordingBufferSize();
                OpenedRecord opened = openAudioRecord(bufferSize);
                if (opened.record == null) {
                    safeError("麦克风初始化失败"
                            + (opened.error == null ? "" : ": " + opened.error.getMessage()));
                    return false;
                }

                session = new RecordingSession(opened.record, consumer, pcmConsumer);
                audioSource = opened.audioSource;
                recordingSession = session;
                recording = true;
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                safeError("无法启动录音: " + usefulMessage(error));
                return false;
            }
        }

        // Send the start notification before the capture thread can send a stop.
        safeRecordingChanged(true);
        safeInfo("录音已开始: 16 kHz 单声道，60 ms 帧，音源 "
                + (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION
                ? "VOICE_RECOGNITION" : "MIC"));

        synchronized (stateLock) {
            if (closed || !session.running || recordingSession != session) {
                finishRecording(session);
                return false;
            }
            try {
                session.thread.start();
            } catch (RuntimeException error) {
                session.running = false;
                safeError("录音线程启动失败: " + usefulMessage(error));
                finishRecording(session);
                return false;
            }
        }
        return true;
    }

    public void stopRecording() {
        final RecordingSession session;
        synchronized (stateLock) {
            session = recordingSession;
            if (session == null) {
                recording = false;
                return;
            }
            session.running = false;
            recording = false;
        }

        stopAudioRecord(session.audioRecord);
        Thread thread = session.thread;
        if (thread != null && thread != Thread.currentThread()) {
            joinThread(thread, THREAD_JOIN_MS);
            if (thread.isAlive()) {
                // A released AudioRecord also unblocks a native read that did not
                // return after stop(). The release is performed only once.
                releaseRecordingResources(session);
                thread.interrupt();
                joinThread(thread, 500);
            }
        }
        if (thread != null && thread.isAlive()) {
            safeError("录音线程未能在关闭前退出");
        }
        finishRecording(session);
    }

    public boolean isRecording() {
        return recording;
    }

    public void setOutputSampleRate(int sampleRate) {
        if (!isSupportedOutputSampleRate(sampleRate)) {
            safeError("不支持的 Opus 输出采样率: " + sampleRate);
            return;
        }

        synchronized (stateLock) {
            if (closed || sampleRate == outputSampleRate) {
                return;
            }
        }

        // AudioTrack's rate is fixed at construction, so playback must be rebuilt.
        stopPlayback();
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            try {
                if (codec != null) {
                    codec.setOutputSampleRate(sampleRate);
                }
                outputSampleRate = sampleRate;
                safeInfo("Opus 播放采样率已切换为 " + sampleRate + " Hz");
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                safeError("切换 Opus 输出采样率失败: " + usefulMessage(error));
            }
        }
    }

    public void playOpus(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        try {
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                ensureCodecLocked();
                PlaybackSession session = playbackSession;
                if (session == null || !session.running) {
                    session = new PlaybackSession();
                    playbackSession = session;
                    session.thread.start();
                }
                // Own the queued bytes even if the caller reuses its network buffer.
                if (!session.queue.offer(Arrays.copyOf(data, data.length))) {
                    safeError("播放队列已满，丢弃过量音频帧");
                    session.running = false;
                    session.queue.clear();
                }
            }
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            safeError("启动 Opus 播放失败: " + usefulMessage(error));
            stopPlayback();
        }
    }

    public void stopPlayback() {
        final PlaybackSession session;
        synchronized (stateLock) {
            session = playbackSession;
            if (session == null) {
                return;
            }
            session.running = false;
            session.queue.clear();
        }

        AudioTrack track;
        synchronized (stateLock) {
            track = session.audioTrack;
        }
        flushAndStop(track);

        Thread thread = session.thread;
        if (thread != null && thread != Thread.currentThread()) {
            joinThread(thread, THREAD_JOIN_MS);
            if (thread.isAlive()) {
                releasePlaybackResources(session);
                thread.interrupt();
                joinThread(thread, 500);
            }
        }
        finishPlayback(session);
    }

    public boolean startLoopbackTest() {
        synchronized (stateLock) {
            if (closed) {
                safeError("AudioEngine 已关闭");
                return false;
            }
            if (recordingSession != null) {
                safeError("请先停止当前录音再开始回环测试");
                return false;
            }
        }

        stopPlayback();
        final int[] receivedFrames = {0};
        boolean started = startRecording(new FrameConsumer() {
            @Override
            public void onFrame(byte[] data) {
                // startRecording supplies encoded Opus frames, so this exercises
                // microphone -> encoder -> decoder -> speaker without a server.
                playOpus(data);
                receivedFrames[0]++;
                if (receivedFrames[0] >= LOOPBACK_FRAMES) {
                    safeInfo("约 2 秒回环采集结束，播放完剩余音频后自动空闲");
                    stopRecording();
                }
            }
        });
        if (started) {
            safeInfo("本地回环测试开始，约 2 秒后自动停止采集");
        }
        return started;
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        stopRecording();
        stopPlayback();

        OpusCodec codecToClose;
        synchronized (stateLock) {
            codecToClose = codec;
            codec = null;
        }
        if (codecToClose != null) {
            try {
                codecToClose.close();
            } catch (RuntimeException error) {
                safeError("释放 Opus 资源失败: " + usefulMessage(error));
            }
        }
    }

    private void ensureCodecLocked() {
        if (codec == null) {
            codec = new OpusCodec(outputSampleRate);
        }
    }

    private void recordingLoop(RecordingSession session) {
        short[] pcm = new short[FRAME_SAMPLES];
        int zeroFrames = 0;
        boolean zeroErrorReported = false;

        try {
            while (session.running && !closed) {
                int offset = 0;
                while (session.running && offset < FRAME_SAMPLES) {
                    int count = session.audioRecord.read(
                            pcm, offset, FRAME_SAMPLES - offset);
                    if (count > 0) {
                        offset += count;
                    } else if (count < 0) {
                        if (session.running && !closed) {
                            safeError("AudioRecord 读取失败，错误码 " + count);
                        }
                        return;
                    }
                }
                if (!session.running || closed) {
                    break;
                }

                if (isAllZero(pcm)) {
                    zeroFrames++;
                    if (zeroFrames >= ZERO_FRAME_ERROR_THRESHOLD && !zeroErrorReported) {
                        zeroErrorReported = true;
                        safeError("持续检测到全零音频，麦克风可能被小爱占用");
                    }
                } else {
                    zeroFrames = 0;
                    zeroErrorReported = false;
                }

                byte[] encoded;
                synchronized (stateLock) {
                    if (closed || codec == null) {
                        break;
                    }
                    encoded = codec.encode(pcm);
                }
                // The wake word engine receives its own copy because the capture
                // buffer is reused on the next read.
                PcmFrameConsumer tap = session.pcmConsumer;
                if (tap != null) {
                    tap.onPcmFrame(Arrays.copyOf(pcm, FRAME_SAMPLES));
                }
                session.consumer.onFrame(encoded);
            }
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (session.running && !closed) {
                safeError("录音或 Opus 编码失败: " + usefulMessage(error));
            }
        } finally {
            finishRecording(session);
        }
    }

    private void playbackLoop(PlaybackSession session) {
        try {
            while (session.running && !closed) {
                byte[] packet = session.queue.poll(200, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }

                short[] pcm;
                AudioTrack track;
                synchronized (stateLock) {
                    if (closed || !session.running || codec == null) {
                        break;
                    }
                    pcm = codec.decode(packet);
                    if (pcm.length == 0) {
                        continue;
                    }
                    track = ensureAudioTrackLocked(session);
                }

                int offset = 0;
                while (session.running && !closed && offset < pcm.length) {
                    int count = track.write(pcm, offset, pcm.length - offset);
                    if (count > 0) {
                        offset += count;
                    } else if (count == 0) {
                        Thread.yield();
                    } else {
                        throw new IllegalStateException("AudioTrack 写入失败，错误码 " + count);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            if (session.running && !closed) {
                safeError("Opus 解码或播放失败: " + usefulMessage(error));
            }
        } finally {
            session.running = false;
            session.queue.clear();
            finishPlayback(session);
        }
    }

    private AudioTrack ensureAudioTrackLocked(PlaybackSession session) {
        if (session.audioTrack != null) {
            return session.audioTrack;
        }

        int minimum = AudioTrack.getMinBufferSize(
                outputSampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int fallback = Math.max(FRAME_SAMPLES * BYTES_PER_SAMPLE,
                outputSampleRate * BYTES_PER_SAMPLE / 10);
        int bufferSize = minimum > 0 ? Math.max(minimum, fallback) : fallback;

        AudioTrack track = null;
        try {
            track = new AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    outputSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack 初始化失败");
            }
            track.play();
            session.audioTrack = track;
            return track;
        } catch (RuntimeException error) {
            if (track != null) {
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                    // Keep the original initialization error.
                }
            }
            throw error;
        }
    }

    private void finishRecording(RecordingSession session) {
        boolean notifyStopped = false;
        synchronized (stateLock) {
            session.running = false;
            if (recordingSession == session) {
                recordingSession = null;
                recording = false;
            }
            if (session.notifyStopped) {
                session.notifyStopped = false;
                notifyStopped = true;
            }
        }
        releaseRecordingResources(session);
        if (notifyStopped) {
            safeRecordingChanged(false);
        }
    }

    private void finishPlayback(PlaybackSession session) {
        synchronized (stateLock) {
            session.running = false;
            if (playbackSession == session) {
                playbackSession = null;
            }
        }
        releasePlaybackResources(session);
    }

    private void releaseRecordingResources(RecordingSession session) {
        synchronized (stateLock) {
            if (session.resourcesReleased) {
                return;
            }
            session.resourcesReleased = true;
        }
        try {
            session.audioRecord.release();
        } catch (RuntimeException ignored) {
            // Release is best effort and must remain safe during repeated close().
        }
    }

    private void releasePlaybackResources(PlaybackSession session) {
        AudioTrack track;
        synchronized (stateLock) {
            if (session.resourcesReleased) {
                return;
            }
            session.resourcesReleased = true;
            track = session.audioTrack;
            session.audioTrack = null;
        }
        if (track != null) {
            try {
                track.release();
            } catch (RuntimeException ignored) {
                // Release is best effort and must remain safe during repeated close().
            }
        }
    }

    private OpenedRecord openAudioRecord(int bufferSize) {
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        RuntimeException lastError = null;
        for (int source : sources) {
            AudioRecord record = null;
            try {
                record = new AudioRecord(
                        source,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    record.release();
                    record = null;
                    continue;
                }
                record.startRecording();
                if (record.getRecordingState()
                        != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("AudioRecord 未进入录音状态");
                }
                return new OpenedRecord(record, source, null);
            } catch (RuntimeException error) {
                lastError = error;
                if (record != null) {
                    try {
                        record.release();
                    } catch (RuntimeException ignored) {
                        // Keep the original initialization error.
                    }
                }
            }
        }
        return new OpenedRecord(null, sources[sources.length - 1], lastError);
    }

    private static int recordingBufferSize() {
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int frameBytes = FRAME_SAMPLES * BYTES_PER_SAMPLE;
        return minimum > 0 ? Math.max(minimum, frameBytes * 2) : frameBytes * 2;
    }

    private static void stopAudioRecord(AudioRecord record) {
        try {
            record.stop();
        } catch (RuntimeException ignored) {
            // It may already have stopped or failed during initialization.
        }
    }

    private static void flushAndStop(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.flush();
        } catch (RuntimeException ignored) {
            // Continue to stop even if the track is already in a stopped state.
        }
        try {
            track.stop();
        } catch (RuntimeException ignored) {
            // stopPlayback() and close() are intentionally idempotent.
        }
    }

    private static boolean isAllZero(short[] pcm) {
        for (short sample : pcm) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedOutputSampleRate(int sampleRate) {
        return sampleRate == 8000 || sampleRate == 12000 || sampleRate == 16000
                || sampleRate == 24000 || sampleRate == 48000;
    }

    private static void joinThread(Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String usefulMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void safeRecordingChanged(boolean value) {
        try {
            callback.onRecordingChanged(value);
        } catch (RuntimeException ignored) {
            // Client callbacks must not terminate an audio worker.
        }
    }

    private void safeInfo(String message) {
        try {
            callback.onInfo(message);
        } catch (RuntimeException ignored) {
            // Client callbacks must not terminate an audio worker.
        }
    }

    private void safeError(String message) {
        try {
            callback.onError(message);
        } catch (RuntimeException ignored) {
            // Client callbacks must not terminate an audio worker.
        }
    }

    private static final class OpenedRecord {
        final AudioRecord record;
        final int audioSource;
        final RuntimeException error;

        OpenedRecord(AudioRecord record, int audioSource, RuntimeException error) {
            this.record = record;
            this.audioSource = audioSource;
            this.error = error;
        }
    }

    private final class RecordingSession {
        final AudioRecord audioRecord;
        final FrameConsumer consumer;
        final PcmFrameConsumer pcmConsumer;
        final Thread thread;
        volatile boolean running = true;
        boolean notifyStopped = true;
        boolean resourcesReleased;

        RecordingSession(
                AudioRecord audioRecord, FrameConsumer consumer,
                PcmFrameConsumer pcmConsumer) {
            this.audioRecord = audioRecord;
            this.consumer = consumer;
            this.pcmConsumer = pcmConsumer;
            this.thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    recordingLoop(RecordingSession.this);
                }
            }, "x08c-record");
        }
    }

    private final class PlaybackSession {
        final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(PLAYBACK_QUEUE_CAPACITY);
        final Thread thread;
        AudioTrack audioTrack;
        volatile boolean running = true;
        boolean resourcesReleased;

        PlaybackSession() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    playbackLoop(PlaybackSession.this);
                }
            }, "x08c-playback");
        }
    }
}
