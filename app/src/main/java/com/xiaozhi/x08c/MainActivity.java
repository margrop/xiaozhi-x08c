package com.xiaozhi.x08c;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * UI only: binds to {@link VoiceAssistantService} and never holds a
 * {@link XiaozhiClient} or {@link AudioEngine} directly, so rotation and
 * backgrounding cannot leak them. Launch configuration extras are forwarded
 * to the service, which persists and applies them.
 */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 12, 17);
    private static final int PANEL = Color.rgb(16, 23, 31);
    private static final int CARD = Color.rgb(20, 29, 38);
    private static final int BORDER = Color.rgb(41, 55, 67);
    private static final int TEXT = Color.rgb(233, 240, 244);
    private static final int MUTED = Color.rgb(132, 151, 164);
    private static final int CYAN = Color.rgb(67, 220, 190);
    private static final int BLUE = Color.rgb(89, 167, 255);
    private static final int RED = Color.rgb(255, 111, 105);
    private static final float TEXT_SCALE = 2.0f;

    private final Typeface displayFace = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface bodyFace = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private String lastTranscriptSource = "";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder ibinder) {
            service = ((VoiceAssistantService.LocalBinder) ibinder).getService();
            service.addListener(serviceListener);
            serviceReady = true;
            runOnUiThread(() -> {
                // Sync the current state: state changes fired between the wake
                // and this activity binding are otherwise lost, which hid the
                // listening indicator for the whole session.
                assistantState = service.getAssistantState();
                updateWakeButton();
                updateStatus();
                updateConnectButton();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) {
                service.removeListener(serviceListener);
            }
            service = null;
            serviceReady = false;
        }
    };

    private final VoiceAssistantService.Listener serviceListener =
            new VoiceAssistantService.Listener() {
                @Override
                public void onConnectionState(String state, boolean ready) {
                    runOnUiThread(() -> {
                        connectionText = describeConnection(state);
                        clientReady = ready;
                        updateStatus();
                        updateConnectButton();
                    });
                }

                @Override
                public void onAssistantState(VoiceAssistantService.State newState) {
                    runOnUiThread(() -> {
                        assistantState = newState;
                        updateStatus();
                        updateTalkButton();
                    });
                }

                @Override
                public void onInfo(String message) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> showError(message));
                }

                @Override
                public void onUserText(String text) {
                    runOnUiThread(() -> appendTranscript("我", text));
                }

                @Override
                public void onAssistantText(String text) {
                    runOnUiThread(() -> appendTranscript("小智", text));
                }
            };

    private SettingsStore settings;
    private VoiceAssistantService service;
    private boolean serviceReady;
    private boolean clientReady;
    private String connectionText = "未连接";
    private VoiceAssistantService.State assistantState = VoiceAssistantService.State.IDLE;

    private TextView statusView;
    private TextView serverView;
    private TextView transcriptView;
    private TextView connectButton;
    private TextView talkButton;
    private TextView wakeButton;
    private LinearLayout micPanel;
    private TextView micIcon;
    private TextView micHint;
    private Animation micPulse;
    private ScrollView transcriptScroll;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        settings = new SettingsStore(this);
        DeviceIdentity.getDeviceId(this);
        setContentView(buildUi());
        updateServerLabel();
        appendTranscript("系统", "说“小智小智”唤醒小智，或按住说话；不会修改原厂小爱。");

        // Keep the foreground service alive regardless of this activity.
        Intent start = new Intent(this, VoiceAssistantService.class);
        start.setAction(VoiceAssistantService.ACTION_START);
        startService(start);
        bindService();
        handleLaunchConfig(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchConfig(intent);
    }

    private void handleLaunchConfig(Intent intent) {
        if (intent == null) return;
        boolean hasUrl = intent.hasExtra(VoiceAssistantService.EXTRA_SERVER_URL);
        boolean hasToken = intent.hasExtra(VoiceAssistantService.EXTRA_SERVER_TOKEN);
        boolean hasDeviceId = intent.hasExtra(VoiceAssistantService.EXTRA_DEVICE_ID);
        if (!hasUrl && !hasToken && !hasDeviceId) return;

        String url = hasUrl ? intent.getStringExtra(VoiceAssistantService.EXTRA_SERVER_URL) : settings.getServerUrl();
        String token = hasToken ? intent.getStringExtra(VoiceAssistantService.EXTRA_SERVER_TOKEN) : settings.getToken();
        String deviceId = hasDeviceId ? intent.getStringExtra(VoiceAssistantService.EXTRA_DEVICE_ID) : settings.getDeviceId();
        settings.save(url, token, deviceId);
        updateServerLabel();

        // Forward the raw extras to the service; it persists and reconnects.
        Intent serviceIntent = new Intent(this, VoiceAssistantService.class);
        serviceIntent.setAction(VoiceAssistantService.ACTION_APPLY_CONFIG);
        if (hasUrl) serviceIntent.putExtra(VoiceAssistantService.EXTRA_SERVER_URL, url);
        if (hasToken) serviceIntent.putExtra(VoiceAssistantService.EXTRA_SERVER_TOKEN, token);
        if (hasDeviceId) serviceIntent.putExtra(VoiceAssistantService.EXTRA_DEVICE_ID, deviceId);
        startService(serviceIntent);
        appendTranscript("系统", "服务器设置已同步到语音助手服务。");
    }

    private void bindService() {
        if (bound) return;
        bound = bindService(new Intent(this, VoiceAssistantService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(18), dp(24), dp(22));
        page.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("小智 X08C", 27, TEXT, displayFace);
        titleBlock.addView(title);
        titleBlock.addView(label("WAKE WORD  ·  AUTO VAD  ·  LOCAL OPUS", 11, MUTED, bodyFace));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));

        statusView = label("未连接", 15, MUTED, displayFace);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(18), dp(12), dp(18), dp(12));
        statusView.setBackground(card(CARD, dp(28), BORDER, 1));
        header.addView(statusView);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(-1, -2);
        toolbarLp.topMargin = dp(14);
        page.addView(toolbar, toolbarLp);

        serverView = label("", 13, MUTED, bodyFace);
        serverView.setSingleLine(true);
        serverView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(serverView, new LinearLayout.LayoutParams(0, -2, 1));
        addActionButton(toolbar, "服务器设置", BLUE, v -> showSettings());
        wakeButton = addActionButton(toolbar, "唤醒", CYAN, v -> toggleWake());
        connectButton = addActionButton(toolbar, "连接", CYAN, v -> toggleConnection());
        addActionButton(toolbar, "音频自测", TEXT, v -> runAudioSelfTest());

        // XiaoAi-style listening indicator: big mic, shown while the assistant
        // is listening after the wake word (or during hold-to-talk).
        micPanel = new LinearLayout(this);
        micPanel.setOrientation(LinearLayout.VERTICAL);
        micPanel.setGravity(Gravity.CENTER);
        micIcon = label("🎤", 64, CYAN, displayFace);
        micIcon.setGravity(Gravity.CENTER);
        micHint = label("我在听，请讲…", 22, CYAN, displayFace);
        micHint.setGravity(Gravity.CENTER);
        micHint.setPadding(0, dp(8), 0, 0);
        micPanel.addView(micIcon, new LinearLayout.LayoutParams(-1, -2));
        micPanel.addView(micHint, new LinearLayout.LayoutParams(-1, -2));
        micPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(-1, -2);
        micLp.topMargin = dp(16);
        page.addView(micPanel, micLp);

        LinearLayout console = new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setPadding(dp(18), dp(12), dp(18), dp(12));
        console.setBackground(card(PANEL, dp(16), BORDER, 1));
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(-1, 0, 1);
        consoleLp.topMargin = dp(14);
        page.addView(console, consoleLp);

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setFillViewport(true);
        transcriptView = label("", 17, TEXT, bodyFace);
        transcriptView.setLineSpacing(dp(8), 1);
        transcriptView.setTextIsSelectable(true);
        transcriptScroll.addView(transcriptView, new ScrollView.LayoutParams(-1, -2));
        console.addView(transcriptScroll, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(-1, dp(120));
        controlsLp.topMargin = dp(14);
        page.addView(controls, controlsLp);

        talkButton = label("点击说话", 24, BG, displayFace);
        talkButton.setGravity(Gravity.CENTER);
        talkButton.setBackground(card(CYAN, dp(18), CYAN, 0));
        LinearLayout.LayoutParams talkLp = new LinearLayout.LayoutParams(0, -1, 3);
        talkLp.rightMargin = dp(12);
        controls.addView(talkButton, talkLp);
        talkButton.setOnClickListener(v -> startTalking());

        TextView stop = label("停止 / 打断", 19, RED, displayFace);
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(card(CARD, dp(18), RED, 1));
        stop.setOnClickListener(v -> {
            if (service != null) {
                service.interruptSession();
            }
        });
        controls.addView(stop, new LinearLayout.LayoutParams(0, -1, 1));
        return page;
    }

    private TextView addActionButton(LinearLayout parent, String text, int color, View.OnClickListener listener) {
        TextView view = label(text, 13, color, displayFace);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setBackground(card(CARD, dp(24), BORDER, 1));
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        parent.addView(view, lp);
        return view;
    }

    private void toggleConnection() {
        if (service == null) {
            showError("语音助手服务尚未就绪");
            return;
        }
        if (clientReady || service.isClientReady()) {
            service.disconnectClient();
            return;
        }
        String url = settings.getServerUrl().trim();
        if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
            showError("请先填写有效的 ws:// 或 wss:// 服务器地址");
            showSettings();
            return;
        }
        connectionText = "正在连接…";
        updateStatus();
        service.connectClient();
    }

    private void toggleWake() {
        if (service == null) {
            showError("语音助手服务尚未就绪");
            return;
        }
        service.setWakeEnabled(!service.isWakeEnabled());
        updateWakeButton();
    }

    private void updateWakeButton() {
        if (wakeButton == null || service == null) return;
        boolean enabled = service.isWakeEnabled();
        wakeButton.setText(enabled ? "唤醒 开" : "唤醒 关");
        wakeButton.setTextColor(enabled ? CYAN : MUTED);
    }

    private void showSettings() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText url = input("ws://服务器IP:端口/路径", settings.getServerUrl());
        EditText token = input("Token（自建服务可使用 test-token）", settings.getToken());
        EditText device = input("Device ID（通常保留默认）", settings.getDeviceId());
        fields.addView(url);
        fields.addView(token);
        fields.addView(device);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("小智服务器")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, which) -> {
                    String newUrl = url.getText().toString().trim();
                    String newToken = token.getText().toString().trim();
                    String newDevice = device.getText().toString().trim();
                    settings.save(newUrl, newToken, newDevice);
                    updateServerLabel();
                    if (service != null) {
                        service.applyConfig(newUrl, newToken, newDevice);
                    }
                    Toast.makeText(this, "服务器配置已保存", Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            scaleText(dialog.getWindow().getDecorView(), TEXT_SCALE);
        }
    }

    private EditText input(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(0, dp(12), 0, dp(12));
        return field;
    }

    private void startTalking() {
        if (service == null) {
            showError("语音助手服务尚未就绪");
            return;
        }
        if (!ensureMicPermission()) return;
        service.activateXiaozhi();
    }

    private void runAudioSelfTest() {
        if (service == null) {
            showError("语音助手服务尚未就绪");
            return;
        }
        if (!ensureMicPermission()) return;
        service.interruptSession();
        service.runAudioSelfTest();
    }

    private boolean ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        Toast.makeText(this, "请允许麦克风权限", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void updateConnectButton() {
        if (connectButton == null) return;
        connectButton.setText(clientReady ? "断开" : "连接");
    }

    private void updateTalkButton() {
        if (talkButton == null) return;
        boolean manual = assistantState == VoiceAssistantService.State.MANUAL_LISTENING;
        talkButton.setText(manual ? "聆听中…" : "点击说话");
    }

    private void updateStatus() {
        if (statusView == null) return;
        // The mic indicator tracks the assistant state, not the connection:
        // it must update even when the client is not ready, otherwise the
        // listening hint never appears during reconnect windows.
        updateMicIndicator();
        if (!clientReady) {
            statusView.setText(connectionText == null ? "未连接" : connectionText);
            statusView.setTextColor(MUTED);
            return;
        }
        String text;
        int color;
        switch (assistantState) {
            case XIAOZHI_LISTENING:
                text = "聆听中（自动）";
                color = CYAN;
                break;
            case MANUAL_LISTENING:
                text = "聆听中（手动）";
                color = CYAN;
                break;
            case SPEAKING:
                text = "小智回复中…";
                color = CYAN;
                break;
            case NATIVE_ACTIVE:
                text = "已交还小爱同学";
                color = MUTED;
                break;
            case IDLE:
            default:
                text = "待机 · 说“小智小智”唤醒";
                color = MUTED;
                break;
        }
        statusView.setText(text);
        statusView.setTextColor(color);
        updateMicIndicator();
    }

    private void updateMicIndicator() {
        if (micPanel == null) {
            return;
        }
        boolean listening = assistantState == VoiceAssistantService.State.XIAOZHI_LISTENING
                || assistantState == VoiceAssistantService.State.MANUAL_LISTENING;
        android.util.Log.i("MainActivity", "updateMicIndicator state=" + assistantState
                + " listening=" + listening + " clientReady=" + clientReady);
        if (listening) {
            if (micPanel.getVisibility() != View.VISIBLE) {
                micPanel.setVisibility(View.VISIBLE);
                micHint.setText(assistantState == VoiceAssistantService.State.MANUAL_LISTENING
                        ? "正在录音…松开发送" : "我在听，请讲…");
                if (micPulse == null) {
                    micPulse = new AlphaAnimation(0.45f, 1.0f);
                    micPulse.setDuration(700L);
                    micPulse.setRepeatMode(Animation.REVERSE);
                    micPulse.setRepeatCount(Animation.INFINITE);
                }
                micIcon.startAnimation(micPulse);
            }
        } else {
            micPanel.setVisibility(View.GONE);
            micIcon.clearAnimation();
        }
    }

    private static String describeConnection(String state) {
        if ("connecting".equals(state) || "reconnecting".equals(state)) {
            return "正在连接…";
        }
        if ("ready".equals(state)) {
            return "已连接";
        }
        if ("closed".equals(state)) {
            return "已关闭";
        }
        return "未连接";
    }

    private void showError(String message) {
        statusView.setText("需要检查");
        statusView.setTextColor(RED);
        appendTranscript("错误", message);
    }

    private void updateServerLabel() {
        String url = settings.getServerUrl();
        serverView.setText(url.isEmpty() ? "未配置服务器" : url);
    }

    private void appendTranscript(String source, String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (transcript.length() > 8000) {
            transcript.delete(0, 4000);
            lastTranscriptSource = "";
        }
        if (!source.equals(lastTranscriptSource)) {
            if (transcript.length() > 0) transcript.append("\n\n");
            transcript.append(source).append("：");
            lastTranscriptSource = source;
        }
        transcript.append(message);
        transcriptView.setText(transcript.toString());
        scrollTranscriptToBottom();
    }

    /** Keep the live transcript pinned to its newest line while TTS streams. */
    private void scrollTranscriptToBottom() {
        if (transcriptScroll == null || transcriptView == null) {
            return;
        }
        // Wait until setText has been measured, then use an immediate scroll
        // rather than fullScroll/smoothScroll, which can fight rapid TTS chunks.
        transcriptView.post(() -> transcriptView.post(() -> {
            int bottom = Math.max(0,
                    transcriptView.getMeasuredHeight() - transcriptScroll.getHeight());
            transcriptScroll.scrollTo(0, bottom);
        }));
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (service != null) {
            service.notifyUserInteraction();
        }
    }

    @Override
    protected void onPause() {
        // Click-to-talk uses the same auto-VAD session as the wake word; do
        // not stop it merely because the activity is temporarily covered.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            service.removeListener(serviceListener);
            service = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    private TextView label(String text, float size, int color, Typeface face) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size * TEXT_SCALE);
        view.setTextColor(color);
        view.setTypeface(face);
        return view;
    }

    private static void scaleText(View view, float factor) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    textView.getTextSize() * factor);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scaleText(group.getChildAt(i), factor);
            }
        }
    }

    private GradientDrawable card(int fill, float radius, int stroke, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
