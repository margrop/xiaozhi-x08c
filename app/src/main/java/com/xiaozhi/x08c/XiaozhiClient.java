package com.xiaozhi.x08c;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Thread-safe client for version 1 of the Xiaozhi WebSocket protocol. */
public final class XiaozhiClient {
    private static final int INPUT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_OUTPUT_SAMPLE_RATE = 24000;
    private static final long RECONNECT_DELAY_SECONDS = 3L;
    private static final int MAX_OUTBOUND_QUEUE = 128;

    /** Manual listen mode: the client sends listen stop when the button is released. */
    public static final String LISTEN_MODE_MANUAL = "manual";
    /**
     * Auto listen mode: the client keeps sending audio and the server VAD
     * decides when the utterance ends (used after the wake word).
     */
    public static final String LISTEN_MODE_AUTO = "auto";

    public interface Listener {
        void onState(String state, boolean ready);

        void onReady(int outputSampleRate);

        void onUserText(String text);

        void onAssistantText(String text);

        void onSpeaking(boolean speaking);

        void onAudio(byte[] data);

        void onError(String message);
    }

    private final SettingsStore settings;
    private final Listener listener;
    private final Object lock = new Object();
    private final ScheduledExecutorService executor;

    private WebSocket webSocket;
    private long generation;
    private boolean connecting;
    private boolean connected;
    private boolean desiredConnected;
    private boolean reconnectAttempted;
    private boolean closed;
    private boolean ready;
    private boolean listening;
    private boolean speaking;
    private String sessionId = "";
    private int outboundQueueDepth;

    public XiaozhiClient(SettingsStore settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "XiaozhiClient");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** Starts a new connection cycle. At most one automatic reconnect is made per cycle. */
    public void connect() {
        long connectGeneration = 0L;
        boolean alreadyClosed;
        synchronized (lock) {
            alreadyClosed = closed;
            if (!closed) {
                if (desiredConnected && (connecting || webSocket != null)) {
                    return;
                }
                desiredConnected = true;
                connecting = true;
                connected = false;
                reconnectAttempted = false;
                resetSessionStateLocked();
                connectGeneration = ++generation;
            }
        }
        if (alreadyClosed) {
            notifyError("Client is closed");
            return;
        }
        notifyState("connecting", false);
        scheduleConnect(connectGeneration, 0L);
    }

    /** Disconnects without closing the client, allowing a later explicit connect(). */
    public void disconnect() {
        WebSocket socket;
        boolean wasSpeaking;
        synchronized (lock) {
            if (closed) {
                return;
            }
            desiredConnected = false;
            connecting = false;
            connected = false;
            ++generation;
            socket = webSocket;
            webSocket = null;
            wasSpeaking = speaking;
            resetSessionStateLocked();
        }
        if (wasSpeaking) {
            notifySpeaking(false);
        }
        disconnectSocket(socket);
        notifyState("disconnected", false);
    }

    public boolean isReady() {
        synchronized (lock) {
            return !closed && ready && webSocket != null;
        }
    }

    public boolean isListening() {
        synchronized (lock) {
            return !closed && listening;
        }
    }

    public boolean isSpeaking() {
        synchronized (lock) {
            return !closed && speaking;
        }
    }

    /** Starts listening in manual mode; kept for first-stage API compatibility. */
    public void startListening() {
        startListening(LISTEN_MODE_MANUAL);
    }

    /**
     * Starts listening in the given mode ({@link #LISTEN_MODE_MANUAL} or
     * {@link #LISTEN_MODE_AUTO}). Unknown values fall back to manual.
     */
    public void startListening(String mode) {
        final String listenMode =
                LISTEN_MODE_AUTO.equals(mode) ? LISTEN_MODE_AUTO : LISTEN_MODE_MANUAL;
        final WebSocket socket;
        final String currentSessionId;
        synchronized (lock) {
            if (!canSendLocked() || listening) {
                return;
            }
            listening = true;
            socket = webSocket;
            currentSessionId = sessionId;
        }
        String message = buildListenMessage("start", currentSessionId, listenMode);
        executeSend(socket, message, null, true);
    }

    public void stopListening() {
        final WebSocket socket;
        final String currentSessionId;
        synchronized (lock) {
            if (!canSendLocked()) {
                listening = false;
                return;
            }
            if (!listening) {
                return;
            }
            listening = false;
            socket = webSocket;
            currentSessionId = sessionId;
        }
        String message = buildListenMessage("stop", currentSessionId, LISTEN_MODE_MANUAL);
        executeSend(socket, message, null, true);
    }

    /** Sends one binary Opus frame while listening (manual or auto) is active. */
    public void sendAudio(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        final WebSocket socket;
        final byte[] audio = data.clone();
        synchronized (lock) {
            if (!canSendLocked() || !listening) {
                return;
            }
            socket = webSocket;
        }
        executeSend(socket, null, audio, true);
    }

    public void abort() {
        final WebSocket socket;
        final String currentSessionId;
        synchronized (lock) {
            if (!canSendLocked()) {
                return;
            }
            socket = webSocket;
            currentSessionId = sessionId;
        }
        final String message;
        try {
            message = new JSONObject()
                    .put("type", "abort")
                    .put("session_id", currentSessionId)
                    .put("reason", "user")
                    .toString();
        } catch (JSONException error) {
            notifyError(errorMessage("Could not create abort message", error));
            return;
        }
        executeSend(socket, message, null, true);
    }

    /** Permanently closes this client and stops all connection and reconnect tasks. */
    public void close() {
        WebSocket socket;
        boolean wasSpeaking;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            desiredConnected = false;
            connecting = false;
            connected = false;
            ++generation;
            socket = webSocket;
            webSocket = null;
            wasSpeaking = speaking;
            resetSessionStateLocked();
        }
        executor.shutdownNow();
        if (wasSpeaking) {
            notifySpeaking(false);
        }
        disconnectSocket(socket);
        notifyState("closed", false);
    }

    private void scheduleConnect(final long connectGeneration, long delaySeconds) {
        try {
            executor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (lock) {
                            if (!isConnectionExpectedLocked(connectGeneration)) {
                                return;
                            }
                        }
                        if (delaySeconds > 0L) {
                            notifyState("connecting", false);
                        }
                        connectOnBackgroundThread(connectGeneration);
                    } catch (Exception error) {
                        handleConnectFailure(null, connectGeneration, error);
                    }
                }
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException error) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            handleConnectFailure(null, connectGeneration, error);
        }
    }

    private void connectOnBackgroundThread(long connectGeneration) {
        WebSocket createdSocket = null;
        try {
            String serverUrl = settings.getServerUrl().trim();
            if (serverUrl.isEmpty()) {
                throw new IllegalStateException("Server URL is empty");
            }

            WebSocket socket = new WebSocketFactory().createSocket(serverUrl);
            createdSocket = socket;
            socket.addHeader("Authorization", "Bearer " + settings.getToken());
            socket.addHeader("Protocol-Version", "1");
            socket.addHeader("Device-Id", settings.getDeviceId());
            socket.addHeader("Client-Id", settings.getClientId());
            socket.addListener(new SocketListener());

            boolean connectionExpected;
            synchronized (lock) {
                connectionExpected = isConnectionExpectedLocked(connectGeneration);
                if (connectionExpected) {
                    webSocket = socket;
                }
            }
            if (!connectionExpected) {
                disconnectSocket(socket);
                return;
            }

            // The blocking connect is confined to our background executor. The
            // listener sends the client hello only after this handshake succeeds.
            socket.connect();
        } catch (Exception error) {
            handleConnectFailure(createdSocket, connectGeneration, error);
        }
    }

    private void handleConnectFailure(
            WebSocket failedSocket, long connectGeneration, Exception error) {
        boolean current = false;
        boolean wasSpeaking = false;
        synchronized (lock) {
            if (connectGeneration == generation
                    && !closed
                    && desiredConnected
                    && (failedSocket == null || webSocket == null || failedSocket == webSocket)) {
                current = true;
                desiredConnected = false;
                connecting = false;
                connected = false;
                webSocket = null;
                wasSpeaking = speaking;
                resetSessionStateLocked();
            }
        }

        if (current) {
            notifyError(errorMessage("Connection failed", error));
            if (wasSpeaking) {
                notifySpeaking(false);
            }
            disconnectSocket(failedSocket);
            notifyState("disconnected", false);
        } else if (failedSocket != null) {
            // A manual disconnect/close won the race with the connection attempt.
            try {
                failedSocket.disconnect();
            } catch (Exception ignored) {
                // The socket is already obsolete; there is no live state to clean.
            }
        }
    }

    private void handleConnected(WebSocket socket) {
        synchronized (lock) {
            if (!isCurrentSocketLocked(socket)) {
                return;
            }
            connecting = false;
            connected = true;
            resetSessionStateLocked();
        }
        notifyState("connected", false);
        try {
            JSONObject audioParams = new JSONObject()
                    .put("format", "opus")
                    .put("sample_rate", INPUT_SAMPLE_RATE)
                    .put("channels", 1)
                    .put("frame_duration", 60);
            JSONObject hello = new JSONObject()
                    .put("type", "hello")
                    .put("version", 1)
                    .put("transport", "websocket")
                    .put("audio_params", audioParams);
            socket.sendText(hello.toString());
        } catch (Exception error) {
            handleSocketError(socket, error, "Could not send client hello");
        }
    }

    private void handleText(WebSocket socket, String text) {
        try {
            synchronized (lock) {
                if (!isCurrentSocketLocked(socket)) {
                    return;
                }
            }
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            if ("hello".equals(type)) {
                handleServerHello(socket, message);
            } else if ("error".equals(type)) {
                String error = message.optString("message", "");
                if (error.isEmpty()) {
                    error = message.optString("error", "Server error");
                }
                notifyError(error);
            } else if ("stt".equals(type)) {
                if (isReadySocket(socket)) {
                    notifyUserText(message.optString("text", ""));
                }
            } else if ("llm".equals(type)) {
                if (isReadySocket(socket)) {
                    notifyAssistantText(message.optString("text", ""));
                }
            } else if ("tts".equals(type)) {
                handleTts(socket, message.optString("state", ""), message.optString("text", ""));
            }
        } catch (Exception error) {
            if (isCurrentSocket(socket)) {
                notifyError(errorMessage("Invalid server message", error));
            }
        }
    }

    private void handleServerHello(WebSocket socket, JSONObject hello) throws JSONException {
        String newSessionId = hello.optString("session_id", "").trim();
        if (newSessionId.isEmpty()) {
            throw new JSONException("Server hello has no session_id");
        }

        int outputSampleRate = DEFAULT_OUTPUT_SAMPLE_RATE;
        JSONObject audioParams = hello.optJSONObject("audio_params");
        if (audioParams != null && audioParams.has("sample_rate")) {
            int serverSampleRate = audioParams.getInt("sample_rate");
            if (serverSampleRate <= 0) {
                throw new JSONException("Invalid output sample_rate");
            }
            outputSampleRate = serverSampleRate;
        }

        synchronized (lock) {
            if (!isCurrentSocketLocked(socket)) {
                return;
            }
            sessionId = newSessionId;
            ready = true;
            listening = false;
        }
        // ready is deliberately not exposed before a valid server hello arrives.
        notifyState("ready", true);
        notifyReady(outputSampleRate);
    }

    private void handleTts(WebSocket socket, String state, String text) {
        if (text != null && !text.trim().isEmpty() && isReadySocket(socket)) {
            // The server sends the spoken sentence in tts.text while llm.text may
            // contain only short emotion markers.  UI transcripts should show the
            // complete reply that is actually synthesized.
            notifyAssistantText(text);
        }
        boolean newSpeaking;
        if ("start".equals(state)) {
            newSpeaking = true;
        } else if ("stop".equals(state)) {
            newSpeaking = false;
        } else {
            return;
        }

        boolean changed = false;
        synchronized (lock) {
            if (isCurrentSocketLocked(socket) && ready && speaking != newSpeaking) {
                speaking = newSpeaking;
                if (newSpeaking) {
                    // The server took over with TTS output. An auto-mode listen
                    // ends server-side without an explicit listen stop, so the
                    // uplink flag must be cleared here to allow the next
                    // listen start and to drop late audio frames.
                    listening = false;
                }
                changed = true;
            }
        }
        if (changed) {
            notifySpeaking(newSpeaking);
        }
    }

    private void handleBinary(WebSocket socket, byte[] data) {
        if (isReadySocket(socket)) {
            notifyAudio(data);
        }
    }

    private void handleDisconnected(WebSocket socket) {
        final long reconnectGeneration;
        final boolean reconnect;
        final boolean wasConnected;
        final boolean wasSpeaking;
        synchronized (lock) {
            if (!isCurrentSocketLocked(socket)) {
                return;
            }
            webSocket = null;
            wasConnected = connected;
            connected = false;
            wasSpeaking = speaking;
            resetSessionStateLocked();

            reconnect = wasConnected && desiredConnected && !closed && !reconnectAttempted;
            if (reconnect) {
                reconnectAttempted = true;
                connecting = true;
                reconnectGeneration = generation;
            } else {
                desiredConnected = false;
                connecting = false;
                reconnectGeneration = generation;
            }
        }

        if (wasSpeaking) {
            notifySpeaking(false);
        }
        notifyState(reconnect ? "reconnecting" : "disconnected", false);
        if (reconnect) {
            scheduleConnect(reconnectGeneration, RECONNECT_DELAY_SECONDS);
        }
    }

    private void handleSocketError(WebSocket socket, Exception error, String prefix) {
        if (!isCurrentSocket(socket)) {
            return;
        }
        notifyError(errorMessage(prefix, error));
        // Clean state immediately; the later nv-websocket disconnect callback
        // will be ignored because this socket is no longer current.
        handleDisconnected(socket);
        disconnectSocket(socket);
    }

    private String buildListenMessage(String state, String currentSessionId, String mode) {
        try {
            return new JSONObject()
                    .put("type", "listen")
                    .put("state", state)
                    .put("mode", mode)
                    .put("session_id", currentSessionId)
                    .toString();
        } catch (JSONException error) {
            // No checked JSON failure is expected for these fixed primitive values.
            notifyError(errorMessage("Could not create listen message", error));
            return "";
        }
    }

    private void executeSend(
            final WebSocket socket, final String text, final byte[] binary,
            final boolean requireReady) {
        if ((text == null || text.isEmpty()) && binary == null) {
            return;
        }
        try {
            if (binary != null) {
                synchronized (lock) {
                    if (outboundQueueDepth >= MAX_OUTBOUND_QUEUE) {
                        notifyError("WebSocket 音频发送队列已满，丢弃音频帧");
                        return;
                    }
                    outboundQueueDepth++;
                }
            }
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (lock) {
                            if (!isCurrentSocketLocked(socket)
                                    || (requireReady && !ready)) {
                                return;
                            }
                            if (binary != null && !listening) {
                                return;
                            }
                        }
                        if (binary != null) {
                            socket.sendBinary(binary);
                        } else {
                            socket.sendText(text);
                        }
                    } catch (Exception error) {
                        handleSocketError(socket, error, "Could not send WebSocket data");
                    } finally {
                        if (binary != null) {
                            synchronized (lock) {
                                if (outboundQueueDepth > 0) {
                                    outboundQueueDepth--;
                                }
                            }
                        }
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            boolean shouldReport;
            synchronized (lock) {
                shouldReport = !closed;
            }
            if (shouldReport) {
                notifyError(errorMessage("Could not queue WebSocket data", error));
            }
        }
    }

    private boolean canSendLocked() {
        return !closed && ready && webSocket != null && !sessionId.isEmpty();
    }

    private boolean isReadySocket(WebSocket socket) {
        synchronized (lock) {
            return isCurrentSocketLocked(socket) && ready;
        }
    }

    private boolean isCurrentSocket(WebSocket socket) {
        synchronized (lock) {
            return isCurrentSocketLocked(socket);
        }
    }

    private boolean isCurrentSocketLocked(WebSocket socket) {
        return !closed && webSocket == socket;
    }

    private boolean isConnectionExpectedLocked(long connectGeneration) {
        return !closed && desiredConnected && connecting && generation == connectGeneration;
    }

    private void resetSessionStateLocked() {
        ready = false;
        listening = false;
        speaking = false;
        sessionId = "";
    }

    private void disconnectSocket(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.disconnect();
        } catch (Exception error) {
            notifyError(errorMessage("Could not close WebSocket", error));
        }
    }

    private static String errorMessage(String prefix, Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }
        return prefix + ": " + detail;
    }

    private void notifyState(String state, boolean isReady) {
        try {
            if (listener != null) {
                listener.onState(state, isReady);
            }
        } catch (Exception ignored) {
            // Listener failures must not kill WebSocket or executor threads.
        }
    }

    private void notifyReady(int outputSampleRate) {
        try {
            if (listener != null) {
                listener.onReady(outputSampleRate);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyUserText(String text) {
        try {
            if (listener != null) {
                listener.onUserText(text);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyAssistantText(String text) {
        try {
            if (listener != null) {
                listener.onAssistantText(text);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifySpeaking(boolean value) {
        try {
            if (listener != null) {
                listener.onSpeaking(value);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyAudio(byte[] data) {
        try {
            if (listener != null) {
                listener.onAudio(data);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyError(String message) {
        try {
            if (listener != null) {
                listener.onError(message != null ? message : "Unknown error");
            }
        } catch (Exception ignored) {
        }
    }

    private final class SocketListener extends WebSocketAdapter {
        @Override
        public void onConnected(
                WebSocket socket, Map<String, List<String>> headers) {
            try {
                handleConnected(socket);
            } catch (Exception error) {
                handleSocketError(socket, error, "Connection setup failed");
            }
        }

        @Override
        public void onTextMessage(WebSocket socket, String text) {
            try {
                handleText(socket, text);
            } catch (Exception error) {
                if (isCurrentSocket(socket)) {
                    notifyError(errorMessage("Message handling failed", error));
                }
            }
        }

        @Override
        public void onBinaryMessage(WebSocket socket, byte[] data) {
            try {
                handleBinary(socket, data);
            } catch (Exception error) {
                if (isCurrentSocket(socket)) {
                    notifyError(errorMessage("Audio handling failed", error));
                }
            }
        }

        @Override
        public void onDisconnected(
                WebSocket socket,
                WebSocketFrame serverCloseFrame,
                WebSocketFrame clientCloseFrame,
                boolean closedByServer) {
            try {
                handleDisconnected(socket);
            } catch (Exception error) {
                notifyError(errorMessage("Disconnect handling failed", error));
            }
        }

        @Override
        public void onError(WebSocket socket, WebSocketException error) {
            try {
                if (isCurrentSocket(socket)) {
                    notifyError(errorMessage("WebSocket error", error));
                }
            } catch (Exception callbackError) {
                notifyError(errorMessage("WebSocket error handling failed", callbackError));
            }
        }
    }
}
