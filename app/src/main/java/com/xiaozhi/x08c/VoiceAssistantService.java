package com.xiaozhi.x08c;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaPlayer;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service hosting the always-on audio path: one 16 kHz mono capture
 * stream feeds both the Sherpa wake word engine (raw PCM tap) and the Xiaozhi
 * protocol (Opus frames). Owns the {@link XiaozhiClient} and the
 * {@link AudioEngine} so activities never hold them directly.
 *
 * <p>State machine:</p>
 * <ul>
 *   <li>{@code IDLE} – capture running, wake words armed, no session uplink.</li>
 *   <li>{@code XIAOZHI_LISTENING} – “小智小智” heard; listen start mode=auto,
 *       server VAD ends the utterance.</li>
 *   <li>{@code SPEAKING} – server TTS in flight; uplink stopped, audio played.</li>
 *   <li>{@code NATIVE_ACTIVE} – “小爱同学” heard; playback/uplink yielded to the
 *       stock assistant for a short cooldown; capture keeps running.</li>
 *   <li>{@code MANUAL_LISTENING} – compatibility API; the visible UI uses click-to-talk auto mode.</li>
 * </ul>
 *
 * The stock “小爱同学” service is never stopped or disabled.
 */
public class VoiceAssistantService extends Service {
    private static final String TAG = "VoiceAssistantService";
    public static final String ACTION_START = "com.xiaozhi.x08c.action.START";
    public static final String ACTION_APPLY_CONFIG = "com.xiaozhi.x08c.action.APPLY_CONFIG";
    public static final String EXTRA_SHOW_IDLE_SCREEN = "show_idle_screen";
    public static final String EXTRA_SERVER_URL = "server_url";
    public static final String EXTRA_SERVER_TOKEN = "server_token";
    public static final String EXTRA_DEVICE_ID = "device_id";

    private static final String CHANNEL_ID = "x08c_voice_assistant";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NATIVE_COOLDOWN_MS = 2000L;
    private static final long IDLE_RETURN_DELAY_MS = 120000L;

    /**
     * Pre-roll ring of encoded uplink frames (60 ms each). Speech that follows
     * the wake word immediately would otherwise be clipped while the state
     * machine switches to {@code XIAOZHI_LISTENING}; on wake we replay the
     * ring (which was cleared at detection, so it only holds post-wake
     * speech) and then the live stream continues.
     */
    private static final int PRE_ROLL_CAPACITY = 30; // ~1.8 s
    /** Ignore KWS re-detections of the same utterance for this long. */
    private static final long WAKE_REDETECT_DEBOUNCE_MS = 2500L;
    /** Keep the microphone uplink disabled while the local ack is audible. */
    private static final long WAKE_ACK_GUARD_MS = 1100L;
    /** RMS below this counts as silence when gating the post-wake listen start. */
    private static final int WAKE_SILENCE_RMS_THRESHOLD = 150;
    /** Consecutive silent 60 ms frames required before the mic counts as quiet. */
    private static final int WAKE_SILENCE_CONFIRM_FRAMES = 7; // ~420 ms
    /** The post-wake silence gate never waits longer than this. */
    private static final long WAKE_SILENCE_MAX_WAIT_MS = 3000L;

    /**
     * Auto-listen window: after “小智小智” the user has this long to start
     * speaking; otherwise the session is dropped and the wake word is required
     * again (matches the stock “小爱同学” behaviour).
     */
    private static final long AUTO_LISTEN_SILENCE_TIMEOUT_MS = 10000L;
    /** 5-second follow-up window after TTS, without requiring the wake word. */
    private static final long FOLLOW_UP_LISTEN_TIMEOUT_MS = 5000L;
    /** 16-bit PCM RMS above this counts as speech for the timeout. */
    private static final int SPEECH_ENERGY_THRESHOLD = 500;

    public enum State {
        IDLE,
        XIAOZHI_LISTENING,
        SPEAKING,
        NATIVE_ACTIVE,
        MANUAL_LISTENING
    }

    /** UI-facing callbacks; always invoked on the main thread. */
    public interface Listener {
        void onConnectionState(String state, boolean ready);

        void onAssistantState(State state);

        void onInfo(String message);

        void onError(String message);

        void onUserText(String text);

        void onAssistantText(String text);
    }

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final IBinder binder = new LocalBinder();

    private SettingsStore settings;
    private AudioEngine audioEngine;
    private volatile WakeWordEngine wakeEngine;
    private String wakeEngineError;
    private XiaozhiClient client;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean destroyed;
    private State state = State.IDLE;
    private boolean pendingAutoListen;
    private boolean selfTestPending;
    private boolean selfTestRunning;
    private final Object preRollLock = new Object();
    private final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
    /** Set once real speech energy is seen during an auto listen window. */
    private boolean autoListenHeardSpeech;
    private boolean followUpListening;
    private MediaPlayer wakeAckPlayer;
    private volatile boolean wakeAckPlaying;
    /** Prevents multiple KWS callbacks from queueing duplicate wake handling. */
    private boolean wakeDispatchPending;
    /** True only after the auto listen start message has reached the client. */
    private volatile boolean autoListenReady;
    private final java.util.Random ackRandom = new java.util.Random();
    private long lastAcceptedWakeElapsedMs = -WAKE_REDETECT_DEBOUNCE_MS;

    private AudioManager audioManager;
    private boolean focusHeld;
    /** Gate that delays the post-wake listen until the mic is quiet (echo tail). */
    private volatile boolean wakeSilenceGating;
    /** Set once the environment has been silent for a confirm window. */
    private volatile boolean silenceOnsetReached;
    /** Consecutive silent frames; only touched on the capture thread. */
    private int consecutiveSilentFrames;
    /** Frames captured after the environment went quiet; safe to replay. */
    private final ArrayDeque<byte[]> cleanRing = new ArrayDeque<>();
    private long wakeGateDeadlineMs;

    private final AudioEngine.FrameConsumer uplinkConsumer = new AudioEngine.FrameConsumer() {
        @Override
        public void onFrame(byte[] data) {
            synchronized (preRollLock) {
                if (wakeSilenceGating) {
                    // During the post-wake silence gate only frames captured
                    // after the environment went quiet are usable uplink audio;
                    // everything else is the tail of an interrupted reply.
                    if (silenceOnsetReached) {
                        cleanRing.addLast(data);
                        while (cleanRing.size() > PRE_ROLL_CAPACITY) {
                            cleanRing.pollFirst();
                        }
                    }
                } else {
                    boolean capturePreRoll = !wakeAckPlaying
                            && (!isAutoListening() || autoListenReady);
                    if (capturePreRoll) {
                        preRoll.addLast(data);
                        while (preRoll.size() > PRE_ROLL_CAPACITY) {
                            preRoll.pollFirst();
                        }
                    }
                }
            }
            XiaozhiClient current = client;
            if (current == null || wakeAckPlaying
                    || (isAutoListening() && !autoListenReady)) {
                return;
            }
            synchronized (lock) {
                if (state != State.XIAOZHI_LISTENING && state != State.MANUAL_LISTENING) {
                    return;
                }
            }
            current.sendAudio(data);
        }
    };

    private final AudioEngine.PcmFrameConsumer pcmTap = new AudioEngine.PcmFrameConsumer() {
        @Override
        public void onPcmFrame(short[] samples) {
            double rms = computeRms(samples);
            trackAutoListenSpeech(rms);
            updateWakeSilenceGate(rms);
            diagPcmStats(samples);
            WakeWordEngine engine = wakeEngine;
            boolean xiaozhiArmed;
            boolean xiaoaiArmed;
            synchronized (lock) {
                // Xiaozhi phrases may start a session from IDLE or barge in
                // while SPEAKING; the XiaoAi phrase stays armed in every other
                // state (including XIAOZHI_LISTENING) so the stock assistant
                // always wins immediately and the running session yields.
                xiaozhiArmed = state == State.IDLE || state == State.SPEAKING;
                xiaoaiArmed = xiaozhiArmed || state == State.XIAOZHI_LISTENING;
            }
            if (engine == null || !settings.isWakeEnabled() || wakeAckPlaying
                    || !xiaoaiArmed) {
                return;
            }
            String keyword;
            try {
                keyword = engine.acceptWaveform(samples, samples.length);
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                notifyError("唤醒引擎处理失败: " + errorMessage(error));
                return;
            }
            if (keyword == null) {
                return;
            }
            try {
                engine.reset();
            } catch (RuntimeException ignored) {
                // Reset is best effort; the next frame starts from clean state.
            }
            // The transducer KWS can report the same keyword on consecutive
            // frames; ignore re-detections right after an accepted wake.
            long now = SystemClock.elapsedRealtime();
            if (now - lastAcceptedWakeElapsedMs < WAKE_REDETECT_DEBOUNCE_MS) {
                return;
            }
            // The pre-roll ring must only contain speech captured AFTER the
            // wake keyword; replaying the keyword tail made the server answer
            // the wake word itself ("在啊") on every wake.
            synchronized (preRollLock) {
                preRoll.clear();
            }
            lastAcceptedWakeElapsedMs = now;
            if (WakeWordEngine.KEYWORD_XIAOZHI.equals(keyword)) {
                if (!xiaozhiArmed) {
                    // Already inside a Xiaozhi session; a repeated phrase while
                    // SPEAKING is handled by the barge-in path, not here.
                    return;
                }
                synchronized (lock) {
                    if (wakeDispatchPending) {
                        return;
                    }
                    wakeDispatchPending = true;
                }
                mainHandler.post(wakeXiaozhiRunnable);
            } else if (WakeWordEngine.KEYWORD_XIAOAI.equals(keyword)) {
                synchronized (lock) {
                    if (wakeDispatchPending) {
                        return;
                    }
                    wakeDispatchPending = true;
                }
                mainHandler.post(wakeXiaoaiRunnable);
            }
        }

        private double computeRms(short[] samples) {
            long sum = 0;
            for (short s : samples) {
                sum += (long) s * s;
            }
            return Math.sqrt(sum / (double) samples.length);
        }

        private void trackAutoListenSpeech(double rms) {
            if (rms < SPEECH_ENERGY_THRESHOLD) {
                return;
            }
            synchronized (lock) {
                if (state != State.XIAOZHI_LISTENING || autoListenHeardSpeech) {
                    return;
                }
                autoListenHeardSpeech = true;
            }
            if (followUpListening) {
                mainHandler.removeCallbacks(followUpTimeoutRunnable);
            }
            mainHandler.removeCallbacks(autoListenTimeoutRunnable);
        }

        /** Capture thread: silence detection for the post-wake listen gate. */
        private void updateWakeSilenceGate(double rms) {
            if (!wakeSilenceGating) {
                return;
            }
            if (rms < WAKE_SILENCE_RMS_THRESHOLD) {
                consecutiveSilentFrames++;
                if (!silenceOnsetReached
                        && consecutiveSilentFrames >= WAKE_SILENCE_CONFIRM_FRAMES) {
                    silenceOnsetReached = true;
                    mainHandler.post(wakeGateCompleteRunnable);
                }
            } else {
                consecutiveSilentFrames = 0;
            }
        }

        // Temporary diagnostics: log mic energy once per second so we can tell
        // whether the capture stream carries the user's voice after a wake.
        private int diagFrames;
        private int diagSilent;
        private double diagSum;
        private double diagMax;

        private void diagPcmStats(short[] samples) {
            double sum = 0;
            double max = 0;
            for (short s : samples) {
                sum += (double) s * s;
                double a = Math.abs((double) s);
                if (a > max) {
                    max = a;
                }
            }
            double rms = Math.sqrt(sum / samples.length);
            diagFrames++;
            diagSum += rms;
            if (max > diagMax) {
                diagMax = max;
            }
            if (rms < 100) {
                diagSilent++;
            }
            if (diagFrames >= 17) { // ~1 second
                Log.i(TAG, String.format(
                        "pcm_stats avgRms=%.0f maxAmp=%.0f silentFrames=%d/17",
                        diagSum / diagFrames, diagMax, diagSilent));
                diagFrames = 0;
                diagSilent = 0;
                diagSum = 0;
                diagMax = 0;
            }
        }
    };

    private final Runnable wakeXiaozhiRunnable = new Runnable() {
        @Override
        public void run() {
            handleXiaozhiWake();
        }
    };

    private final Runnable wakeXiaoaiRunnable = new Runnable() {
        @Override
        public void run() {
            handleXiaoaiWake();
        }
    };

    private final Runnable exitNativeRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (state == State.NATIVE_ACTIVE) {
                    setStateLocked(State.IDLE);
                }
            }
        }
    };

    private final Runnable autoListenTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            handleAutoListenTimeout();
        }
    };

    private final Runnable followUpTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            handleFollowUpTimeout();
        }
    };

    private final Runnable wakeGateTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!wakeSilenceGating) {
                return;
            }
            if (SystemClock.elapsedRealtime() >= wakeGateDeadlineMs) {
                completeWakeSilenceGate();
                return;
            }
            mainHandler.postDelayed(this, 100L);
        }
    };

    private final Runnable wakeGateCompleteRunnable = new Runnable() {
        @Override
        public void run() {
            completeWakeSilenceGate();
        }
    };

    private final Runnable delayedAutoListenRunnable = new Runnable() {
        @Override
        public void run() {
            beginAutoListen(null);
        }
    };

    private final Runnable returnToIdleScreenRunnable = new Runnable() {
        @Override
        public void run() {
            showIdleScreen();
        }
    };

    private final AudioEngine.Callback audioCallback = new AudioEngine.Callback() {
        @Override
        public void onRecordingChanged(boolean recording) {
            if (!recording) {
                mainHandler.post(handleRecordingStoppedRunnable);
            }
        }

        @Override
        public void onInfo(String message) {
            notifyInfo(message);
        }

        @Override
        public void onError(String message) {
            notifyError(message);
        }
    };

    private final Runnable handleRecordingStoppedRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            boolean pending;
            boolean running;
            synchronized (lock) {
                pending = selfTestPending;
                running = selfTestRunning;
                selfTestPending = false;
            }
            if (running) {
                // The loopback self test finished; resume the always-on capture.
                synchronized (lock) {
                    selfTestRunning = false;
                }
                ensureRecording();
            } else if (pending) {
                startLoopbackNow();
            }
        }
    };

    private void handleClientState(String connectionState, boolean ready) {
        notifyConnectionState(connectionState, ready);
        if (!ready) {
            boolean keepWakeSession;
            synchronized (lock) {
                keepWakeSession = pendingAutoListen
                        || state == State.XIAOZHI_LISTENING
                        || wakeAckPlaying;
                if (!keepWakeSession) {
                    pendingAutoListen = false;
                }
            }
            if (keepWakeSession) {
                return;
            }
            audioEngine.stopPlayback();
            synchronized (lock) {
                if (state != State.NATIVE_ACTIVE) {
                    setStateLocked(State.IDLE);
                }
            }
            scheduleIdleReturn();
        }
    }

    private void handleClientReady(int outputSampleRate) {
        if (!OpusCodec.isSupportedSampleRate(outputSampleRate)) {
            notifyError("服务端返回不支持的音频采样率: " + outputSampleRate);
            if (client != null) {
                client.disconnect();
            }
            return;
        }
        audioEngine.setOutputSampleRate(outputSampleRate);
        boolean beginAuto;
        synchronized (lock) {
            beginAuto = pendingAutoListen;
            pendingAutoListen = false;
        }
        if (beginAuto) {
            mainHandler.removeCallbacks(delayedAutoListenRunnable);
            mainHandler.postDelayed(delayedAutoListenRunnable, WAKE_ACK_GUARD_MS);
        }
    }

    private void handleClientUserText(String text) {
        State current;
        synchronized (lock) {
            current = state;
        }
        if (current == State.XIAOZHI_LISTENING || current == State.MANUAL_LISTENING) {
            String normalized = text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
            if (isStandaloneStopCommand(normalized)) {
                handleStopCommand();
                return;
            }
        }
        notifyUserText(text);
    }

    private void handleClientSpeaking(boolean speaking) {
        if (speaking) {
            autoListenReady = false;
            followUpListening = false;
            mainHandler.removeCallbacks(followUpTimeoutRunnable);
            stopWakeAck();
            synchronized (lock) {
                if (state == State.NATIVE_ACTIVE) {
                    return;
                }
                setStateLocked(State.SPEAKING);
            }
            // If the stock assistant (or media) already owns the speaker the
            // moment our reply starts, yield instead of talking over it.
            foreignSpeakerDetected();
            startSpeakerPoll();
        } else {
            stopSpeakerPoll();
            // tts.stop can arrive before the final queued Opus packets have
            // drained. Do not flush playback here; the next listen starts only
            // after the short guard, while AudioEngine finishes queued audio.
            synchronized (lock) {
                if (state == State.SPEAKING) {
                    autoListenReady = false;
                    setStateLocked(State.IDLE);
                    startFollowUpListen();
                }
            }
        }
    }

    private final XiaozhiClient.Listener clientListener = new XiaozhiClient.Listener() {
        private void postToMain(Runnable action) {
            mainHandler.post(action);
        }

        @Override
        public void onState(String connectionState, boolean ready) {
            postToMain(() -> handleClientState(connectionState, ready));
        }

        @Override
        public void onReady(int outputSampleRate) {
            postToMain(() -> handleClientReady(outputSampleRate));
        }

        @Override
        public void onUserText(String text) {
            postToMain(() -> handleClientUserText(text));
        }

        @Override
        public void onAssistantText(String text) {
            postToMain(() -> notifyAssistantText(text));
        }

        @Override
        public void onSpeaking(boolean speaking) {
            postToMain(() -> handleClientSpeaking(speaking));
        }

        @Override
        public void onAudio(byte[] data) {
            // Only the active reply may produce sound: binary frames that race
            // with an abort/barge-in must never restart playback, otherwise
            // the interrupted TTS keeps talking (each playOpus call would
            // spin up a fresh playback session after stopPlayback).
            State current;
            synchronized (lock) {
                current = state;
            }
            if (current != State.SPEAKING) {
                return;
            }
            audioEngine.playOpus(data);
        }

        @Override
        public void onError(String message) {
            postToMain(() -> notifyError(message));
        }
    };

    public class LocalBinder extends Binder {
        public VoiceAssistantService getService() {
            return VoiceAssistantService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsStore(this);
        DeviceIdentity.getDeviceId(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("语音助手运行中"));

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "x08c:voice_assistant");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        audioEngine = new AudioEngine(this, audioCallback);
        client = new XiaozhiClient(settings, clientListener);
        // Model loading blocks for several seconds on this device; run it off
        // the main thread so the foreground service and UI come up immediately.
        new Thread(new Runnable() {
            @Override
            public void run() {
                createWakeEngine();
            }
        }, "x08c-kws-init").start();
        ensureRecording();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_APPLY_CONFIG.equals(intent.getAction())) {
            String url = intent.hasExtra(EXTRA_SERVER_URL)
                    ? intent.getStringExtra(EXTRA_SERVER_URL) : settings.getServerUrl();
            String token = intent.hasExtra(EXTRA_SERVER_TOKEN)
                    ? intent.getStringExtra(EXTRA_SERVER_TOKEN) : settings.getToken();
            String deviceId = intent.hasExtra(EXTRA_DEVICE_ID)
                    ? intent.getStringExtra(EXTRA_DEVICE_ID) : settings.getDeviceId();
            applyConfig(url, token, deviceId);
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_IDLE_SCREEN, false)) {
            mainHandler.postDelayed(returnToIdleScreenRunnable, 800L);
        }
        ensureRecording();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(delayedAutoListenRunnable);
        mainHandler.removeCallbacks(followUpTimeoutRunnable);
        mainHandler.removeCallbacks(autoListenTimeoutRunnable);
        synchronized (lock) {
            pendingAutoListen = false;
            autoListenReady = false;
            followUpListening = false;
        }
        stopWakeAck();
        setState(State.IDLE);
        if (client != null) {
            client.close();
        }
        if (wakeEngine != null) {
            try {
                wakeEngine.close();
            } catch (RuntimeException ignored) {
                // Release is best effort during teardown.
            }
            wakeEngine = null;
        }
        if (audioEngine != null) {
            audioEngine.close();
        }
        abandonXiaozhiFocus();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ API

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
            String startupError = wakeEngineError;
            if (startupError != null) {
                try {
                    listener.onError(startupError);
                } catch (RuntimeException ignored) {
                    // Listener failures must not kill the service.
                }
            }
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State getAssistantState() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean isClientReady() {
        return client != null && client.isReady();
    }

    public boolean isRecording() {
        return audioEngine != null && audioEngine.isRecording();
    }

    public boolean isWakeEnabled() {
        return settings.isWakeEnabled();
    }

    public void setWakeEnabled(boolean enabled) {
        settings.setWakeEnabled(enabled);
        if (wakeEngine != null) {
            try {
                wakeEngine.reset();
            } catch (RuntimeException ignored) {
                // Reset is best effort.
            }
        }
        notifyInfo(enabled ? "唤醒词监听已开启" : "唤醒词监听已关闭");
    }

    /** Called by the UI on any user interaction; resets the idle-screen timer. */
    public void notifyUserInteraction() {
        synchronized (lock) {
            if (state == State.IDLE) {
                scheduleIdleReturn();
            } else {
                cancelIdleReturn();
            }
        }
    }

    public void connectClient() {
        if (client != null) {
            client.connect();
        }
    }

    private void cancelAutoSession() {
        mainHandler.removeCallbacks(delayedAutoListenRunnable);
        mainHandler.removeCallbacks(followUpTimeoutRunnable);
        mainHandler.removeCallbacks(autoListenTimeoutRunnable);
        mainHandler.removeCallbacks(wakeGateTickRunnable);
        mainHandler.removeCallbacks(wakeGateCompleteRunnable);
        wakeSilenceGating = false;
        synchronized (lock) {
            pendingAutoListen = false;
            autoListenReady = false;
            followUpListening = false;
        }
    }

    // ------------------------------------------------------------ audio focus

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        focusHeld = false;
                        mainHandler.post(externalFocusLossRunnable);
                    }
                }
            };

    private final Runnable externalFocusLossRunnable = new Runnable() {
        @Override
        public void run() {
            handleExternalFocusLoss();
        }
    };

    /**
     * Take the speaker away from the stock assistant / media while Xiaozhi is
     * answering; a stock XiaoAi TTS in progress is paused by the system and
     * its session is cancelled, so it cannot answer a Xiaozhi command.
     */
    private void requestXiaozhiFocus() {
        if (focusHeld || audioManager == null) {
            return;
        }
        int result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonXiaozhiFocus() {
        if (!focusHeld || audioManager == null) {
            return;
        }
        audioManager.abandonAudioFocus(focusChangeListener);
        focusHeld = false;
    }

    // ------------------------------------------------------- playback monitor

    /**
     * The stock XiaoAi service can be woken by our own TTS (a reply that
     * mentions it, or loud speech) and may answer without requesting audio
     * focus, which the focus listener alone cannot catch. Poll the music
     * stream while we are answering: if audio is active but our own output
     * has gone silent, someone else owns the speaker and we must yield.
     */
    private static final long SPEAKER_POLL_MS = 400L;
    private final Runnable speakerPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!foreignSpeakerDetected()) {
                mainHandler.postDelayed(this, SPEAKER_POLL_MS);
            }
        }
    };

    /**
     * @return true when another component is playing audio while our output
     *     is silent; the caller yields the speaker in that case.
     */
    private boolean foreignSpeakerDetected() {
        boolean musicActive = audioManager != null && audioManager.isMusicActive();
        boolean oursActive = audioEngine != null && audioEngine.isOutputActive();
        if (musicActive && !oursActive) {
            Log.i(TAG, "foreign playback detected while our output is silent");
            handleForeignPlaybackStarted();
            return true;
        }
        return false;
    }

    private void startSpeakerPoll() {
        mainHandler.removeCallbacks(speakerPollRunnable);
        mainHandler.postDelayed(speakerPollRunnable, SPEAKER_POLL_MS);
    }

    private void stopSpeakerPoll() {
        mainHandler.removeCallbacks(speakerPollRunnable);
    }

    /** Main thread: someone else started playing while we were answering. */
    private void handleForeignPlaybackStarted() {
        if (destroyed) {
            return;
        }
        State current;
        synchronized (lock) {
            current = state;
        }
        if (current != State.SPEAKING) {
            return;
        }
        Log.i(TAG, "foreign playback started during SPEAKING; yielding the speaker");
        stopSpeakerPoll();
        cancelAutoSession();
        stopWakeAck();
        audioEngine.stopPlayback();
        if (client != null) {
            client.stopListening();
            if (client.isReady()) {
                client.abort();
            }
        }
        abandonXiaozhiFocus();
        setState(State.IDLE);
        scheduleIdleReturn();
    }

    /** Another component (stock XiaoAi, music) took the speaker: stop our turn. */
    private void handleExternalFocusLoss() {
        if (destroyed) {
            return;
        }
        State current;
        synchronized (lock) {
            current = state;
        }
        if (current != State.SPEAKING && current != State.XIAOZHI_LISTENING
                && current != State.MANUAL_LISTENING) {
            return;
        }
        Log.i(TAG, "audio focus lost; stopping the active Xiaozhi turn");
        stopSpeakerPoll();
        cancelAutoSession();
        stopWakeAck();
        audioEngine.stopPlayback();
        if (client != null) {
            client.stopListening();
            if (client.isReady()) {
                client.abort();
            }
        }
        setState(State.IDLE);
        scheduleIdleReturn();
    }

    public void disconnectClient() {
        cancelAutoSession();
        if (client != null) {
            client.disconnect();
        }
        setState(State.IDLE);
    }

    public void applyConfig(String url, String token, String deviceId) {
        settings.save(url, token, deviceId);
        reconnect();
        notifyInfo("服务器配置已保存");
    }

    public void reconnect() {
        cancelAutoSession();
        if (client == null) {
            return;
        }
        client.disconnect();
        String serverUrl = settings.getServerUrl().trim();
        if (serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")) {
            client.connect();
        }
    }

    /** UI equivalent of saying “小智小智”: show the assistant UI and enter auto VAD. */
    public void activateXiaozhi() {
        mainHandler.post(wakeXiaozhiRunnable);
    }

    /** Compatibility API for callers that still need a manual listen session. */
    public boolean beginManualListening() {
        synchronized (lock) {
            if (state == State.NATIVE_ACTIVE) {
                return false;
            }
        }
        cancelIdleReturn();
        showAssistantUi();
        if (client != null && client.isSpeaking()) {
            audioEngine.stopPlayback();
            client.abort();
        }
        if (client == null || !client.isReady()) {
            if (client != null) {
                client.connect();
            }
            notifyInfo("正在连接小智服务器…");
            return false;
        }
        client.startListening(XiaozhiClient.LISTEN_MODE_MANUAL);
        setState(State.MANUAL_LISTENING);
        return true;
    }

    /** Stops the manual uplink; the server then finishes the turn. */
    public void endManualListening() {
        if (client != null) {
            client.stopListening();
        }
        synchronized (lock) {
            if (state == State.MANUAL_LISTENING) {
                setStateLocked(State.IDLE);
                scheduleIdleReturn();
            }
        }
    }

    /** Local interrupt: stop playback and any in-flight listen/turn. */
    public void interruptSession() {
        cancelIdleReturn();
        cancelAutoSession();
        abandonXiaozhiFocus();
        audioEngine.stopPlayback();
        if (client != null) {
            client.stopListening();
            if (client.isReady()) {
                client.abort();
            }
        }
        setState(State.IDLE);
        scheduleIdleReturn();
    }

    /**
     * Local microphone loopback self test. Temporarily stops the always-on
     * capture, runs the bounded loopback, then resumes capture automatically.
     */
    public void runAudioSelfTest() {
        if (audioEngine == null) {
            return;
        }
        if (audioEngine.isRecording()) {
            synchronized (lock) {
                selfTestPending = true;
            }
            audioEngine.stopRecording();
        } else {
            startLoopbackNow();
        }
    }

    // ------------------------------------------------------------ internals

    /** Builds the wake word engine; recording still works if this fails. */
    private void createWakeEngine() {
        try {
            WakeWordEngine engine = new SherpaWakeWordEngine(this);
            synchronized (lock) {
                if (destroyed) {
                    try {
                        engine.close();
                    } catch (RuntimeException ignored) {
                        // Release is best effort during teardown.
                    }
                    return;
                }
                wakeEngine = engine;
                wakeEngineError = null;
            }
            Log.i(TAG, "Wake word engine initialized");
        } catch (RuntimeException | LinkageError error) {
            wakeEngine = null;
            wakeEngineError = "唤醒引擎初始化失败: " + errorMessage(error);
            Log.e(TAG, "Wake word engine initialization failed", error);
            notifyError(wakeEngineError);
        }
    }

    private void ensureRecording() {
        if (destroyed || audioEngine == null || audioEngine.isRecording()) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            notifyError("缺少麦克风权限，无法启动录音");
            return;
        }
        audioEngine.startRecording(uplinkConsumer, pcmTap);
    }

    private void startLoopbackNow() {
        audioEngine.setOutputSampleRate(16000);
        if (audioEngine.startLoopbackTest()) {
            synchronized (lock) {
                selfTestRunning = true;
            }
            notifyInfo("本地回环测试开始，约 2 秒后自动停止采集");
        } else {
            notifyError("音频自测已在运行或当前音频设备不可用");
        }
    }

    /** Called on the main thread after “小智小智” is detected. */
    private void handleXiaozhiWake() {
        cancelIdleReturn();
        showAssistantUi();
        // Pause the stock assistant (its TTS and listening session) before
        // starting our own, regardless of which side was speaking.
        requestXiaozhiFocus();
        synchronized (lock) {
            if (state == State.NATIVE_ACTIVE || state == State.MANUAL_LISTENING
                    || state == State.XIAOZHI_LISTENING) {
                wakeDispatchPending = false;
                return;
            }
            boolean interruptSpeaking = state == State.SPEAKING;
            // Enter listening state before the acknowledgment starts. This
            // keeps the mic UI visible immediately and arms the KWS gate so the
            // speaker cannot wake itself while saying “我在/干嘛呀/诶”.
            setStateLocked(State.XIAOZHI_LISTENING);
            wakeDispatchPending = false;
            if (interruptSpeaking) {
                autoListenReady = false;
            }
        }
        audioEngine.stopPlayback();
        if (client != null && client.isSpeaking()) {
            // Barge-in: abort the current reply and start a fresh auto listen.
            client.abort();
        }
        playWakeAck();
        if (client != null && client.isReady()) {
            // Delay the listen start until the microphone is quiet: neither
            // our own acknowledgment echo nor the tail of an interrupted
            // XiaoAi answer may reach the ASR.
            startWakeSilenceGate();
        } else if (client != null) {
            synchronized (lock) {
                pendingAutoListen = true;
            }
            notifyInfo("正在连接小智服务器…");
            client.connect();
        }
    }

    private boolean isAutoListening() {
        synchronized (lock) {
            return state == State.XIAOZHI_LISTENING;
        }
    }

    /**
     * Starts an auto (server-VAD) listen session.
     *
     * @param flushFrames optional audio captured after the environment went
     *     quiet; replayed right after the listen start so the first words are
     *     not clipped. Null when nothing clean was captured (gate timeout or
     *     a reconnect, where buffered audio would be stale).
     */
    private void beginAutoListen(ArrayDeque<byte[]> flushFrames) {
        synchronized (lock) {
            if (state != State.XIAOZHI_LISTENING || destroyed) {
                return;
            }
        }
        if (client == null || !client.isReady()) {
            synchronized (lock) {
                pendingAutoListen = true;
            }
            return;
        }
        wakeAckPlaying = false;
        autoListenReady = false;
        client.startListening(XiaozhiClient.LISTEN_MODE_AUTO);
        autoListenReady = true;
        if (flushFrames != null && !flushFrames.isEmpty()) {
            XiaozhiClient current = client;
            for (byte[] frame : flushFrames) {
                current.sendAudio(frame);
            }
        }
        synchronized (lock) {
            autoListenHeardSpeech = false;
        }
        mainHandler.removeCallbacks(autoListenTimeoutRunnable);
        mainHandler.postDelayed(autoListenTimeoutRunnable, AUTO_LISTEN_SILENCE_TIMEOUT_MS);
        setState(State.XIAOZHI_LISTENING);
    }

    /**
     * Arms the post-wake silence gate: the listen session starts only after
     * the microphone has been quiet for a short window, so neither our own
     * acknowledgment echo nor the tail of an interrupted reply reaches the
     * server ASR.
     */
    private void startWakeSilenceGate() {
        synchronized (preRollLock) {
            preRoll.clear();
            cleanRing.clear();
        }
        silenceOnsetReached = false;
        consecutiveSilentFrames = 0;
        wakeSilenceGating = true;
        wakeGateDeadlineMs = SystemClock.elapsedRealtime() + WAKE_SILENCE_MAX_WAIT_MS;
        mainHandler.removeCallbacks(wakeGateCompleteRunnable);
        mainHandler.removeCallbacks(wakeGateTickRunnable);
        mainHandler.postDelayed(wakeGateTickRunnable, 100L);
        Log.i(TAG, "wake silence gate armed");
    }

    /** Main thread: the environment is quiet (or the gate timed out) — start listening. */
    private void completeWakeSilenceGate() {
        if (!wakeSilenceGating) {
            return;
        }
        wakeSilenceGating = false;
        mainHandler.removeCallbacks(wakeGateTickRunnable);
        ArrayDeque<byte[]> clean;
        synchronized (preRollLock) {
            clean = new ArrayDeque<>(cleanRing);
            cleanRing.clear();
            preRoll.clear();
        }
        Log.i(TAG, "wake silence gate completed, cleanFrames=" + clean.size());
        beginAutoListen(clean.isEmpty() ? null : clean);
    }

    private void startFollowUpListen() {
        if (client == null || !client.isReady()) {
            scheduleIdleReturn();
            return;
        }
        // No playback happens during the follow-up window; release the speaker
        // so the stock assistant can answer immediately if called.
        abandonXiaozhiFocus();
        followUpListening = true;
        autoListenReady = false;
        client.startListening(XiaozhiClient.LISTEN_MODE_AUTO);
        autoListenReady = true;
        synchronized (lock) {
            autoListenHeardSpeech = false;
            setStateLocked(State.XIAOZHI_LISTENING);
        }
        mainHandler.removeCallbacks(followUpTimeoutRunnable);
        mainHandler.postDelayed(followUpTimeoutRunnable, FOLLOW_UP_LISTEN_TIMEOUT_MS);
        Log.i(TAG, "follow-up listen window started (5s)");
    }

    private void handleFollowUpTimeout() {
        if (!followUpListening) {
            return;
        }
        followUpListening = false;
        autoListenReady = false;
        abandonXiaozhiFocus();
        if (client != null) {
            client.stopListening();
        }
        setState(State.IDLE);
        scheduleIdleReturn();
        Log.i(TAG, "follow-up listen window expired");
    }

    private void handleStopCommand() {
        followUpListening = false;
        autoListenReady = false;
        mainHandler.removeCallbacks(followUpTimeoutRunnable);
        abandonXiaozhiFocus();
        if (client != null) {
            client.stopListening();
            if (client.isReady()) {
                client.abort();
            }
        }
        audioEngine.stopPlayback();
        setState(State.IDLE);
        showIdleScreen();
    }

    private boolean isStandaloneStopCommand(String normalized) {
        return "关闭".equals(normalized)
                || "停止".equals(normalized)
                || "结束".equals(normalized)
                || "退出".equals(normalized)
                || "不用了".equals(normalized)
                || "算了".equals(normalized);
    }

    /**
     * Plays a bundled acknowledgment (“我在” / “干嘛呀” / “诶”, picked at
     * random) so the user knows they can start speaking. Local playback, no
     * network round trip; stopped as soon as the server TTS starts streaming.
     */
    private static final String[] WAKE_ACK_ASSETS = {
            "wake_ack_wozai.wav", "wake_ack_ganma.wav", "wake_ack_ei.wav",
    };

    private void playWakeAck() {
        if (wakeAckPlaying) {
            Log.i(TAG, "Ignoring duplicate wake acknowledgment");
            return;
        }
        stopWakeAck();
        String asset = WAKE_ACK_ASSETS[ackRandom.nextInt(WAKE_ACK_ASSETS.length)];
        try {
            android.content.res.AssetFileDescriptor afd =
                    getAssets().openFd(asset);
            MediaPlayer player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setOnCompletionListener(mp -> stopWakeAck());
            player.prepare();
            wakeAckPlaying = true;
            player.start();
            wakeAckPlayer = player;
        } catch (Exception error) {
            Log.w(TAG, "Unable to play wake ack", error);
            stopWakeAck();
        }
    }

    private void stopWakeAck() {
        wakeAckPlaying = false;
        MediaPlayer player = wakeAckPlayer;
        wakeAckPlayer = null;
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (RuntimeException ignored) {
                // Already stopped or released.
            }
            try {
                player.release();
            } catch (RuntimeException ignored) {
                // Release is best effort.
            }
        }
    }

    /** Main thread: the 10 s auto-listen window expired with no speech heard. */
    private void handleAutoListenTimeout() {
        synchronized (lock) {
            if (state != State.XIAOZHI_LISTENING || autoListenHeardSpeech) {
                return;
            }
        }
        abandonXiaozhiFocus();
        if (client != null) {
            client.stopListening();
        }
        setState(State.IDLE);
        scheduleIdleReturn();
        notifyInfo("没有听到讲话，已返回待机；请说“小智小智”唤醒");
    }

    /** Called on the main thread after “小爱同学” is detected. */
    private void handleXiaoaiWake() {
        cancelIdleReturn();
        showIdleScreen();
        stopWakeAck();
        synchronized (lock) {
            wakeDispatchPending = false;
            pendingAutoListen = false;
        }
        // Cancel a pending post-wake listen gate: the session that was about
        // to start must not begin after yielding to the stock assistant.
        wakeSilenceGating = false;
        mainHandler.removeCallbacks(wakeGateTickRunnable);
        mainHandler.removeCallbacks(wakeGateCompleteRunnable);
        // Hand the speaker back so the stock assistant can answer immediately.
        abandonXiaozhiFocus();
        // Yield to the stock assistant: stop our playback and uplink, abort the
        // server turn, but keep the shared capture stream running. This runs
        // from any Xiaozhi state (IDLE / SPEAKING / XIAOZHI_LISTENING) so a
        // running session never keeps answering once its command belongs to
        // the other assistant.
        audioEngine.stopPlayback();
        if (client != null) {
            client.stopListening();
            if (client.isReady()) {
                client.abort();
            }
        }
        synchronized (lock) {
            if (state == State.NATIVE_ACTIVE) {
                return;
            }
        }
        mainHandler.removeCallbacks(exitNativeRunnable);
        setState(State.NATIVE_ACTIVE);
        mainHandler.postDelayed(exitNativeRunnable, NATIVE_COOLDOWN_MS);
    }

    private void scheduleIdleReturn() {
        mainHandler.removeCallbacks(returnToIdleScreenRunnable);
        mainHandler.postDelayed(returnToIdleScreenRunnable, IDLE_RETURN_DELAY_MS);
    }

    private void cancelIdleReturn() {
        mainHandler.removeCallbacks(returnToIdleScreenRunnable);
    }

    private void showAssistantUi() {
        cancelIdleReturn();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to show assistant UI", error);
        }
    }

    private void showIdleScreen() {
        Intent intent = new Intent("com.xiaomi.mico.screensaver.lock");
        intent.addCategory("com.xiaomi.mico.category.LOCKSCREEN");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
            return;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to show stock clock screen", error);
        }

        // Fall back to the stock Xiaomi home if the clock activity is unavailable.
        Intent home = getPackageManager().getLaunchIntentForPackage("com.xiaomi.micolauncher");
        if (home != null) {
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(home);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to show stock home", error);
            }
        }
    }

    private void setState(State next) {
        synchronized (lock) {
            setStateLocked(next);
        }
    }

    private void setStateLocked(State next) {
        if (state == next) {
            return;
        }
        state = next;
        Log.i(TAG, "state -> " + next);
        if (next != State.XIAOZHI_LISTENING) {
            mainHandler.removeCallbacks(autoListenTimeoutRunnable);
        }
        updateNotification(describeState(next));
        for (Listener listener : listeners) {
            try {
                listener.onAssistantState(next);
            } catch (RuntimeException ignored) {
                // Listener failures must not kill the service.
            }
        }
    }

    private static String describeState(State state) {
        switch (state) {
            case XIAOZHI_LISTENING:
                return "聆听中（自动唤醒）";
            case SPEAKING:
                return "小智回复中";
            case NATIVE_ACTIVE:
                return "已交还小爱同学";
            case MANUAL_LISTENING:
                return "聆听中（手动）";
            case IDLE:
            default:
                return "待机，说“小智小智”唤醒";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("小智 X08C 常驻语音助手");
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("小智 X08C")
                .setContentText(text)
                .setOngoing(true);
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        return builder.build();
    }

    private void updateNotification(String text) {
        startForeground(NOTIFICATION_ID, buildNotification(text));
    }

    private void notifyConnectionState(String connectionState, boolean ready) {
        for (Listener listener : listeners) {
            try {
                listener.onConnectionState(connectionState, ready);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyUserText(String text) {
        for (Listener listener : listeners) {
            try {
                listener.onUserText(text);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyAssistantText(String text) {
        for (Listener listener : listeners) {
            try {
                listener.onAssistantText(text);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyInfo(String message) {
        Log.i(TAG, message);
        for (Listener listener : listeners) {
            try {
                listener.onInfo(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyError(String message) {
        Log.e(TAG, message);
        for (Listener listener : listeners) {
            try {
                listener.onError(message);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
