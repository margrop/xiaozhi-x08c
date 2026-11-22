# 总体架构

## 系统全景

```text
┌────────────────────────── 设备侧（X08C）──────────────────────────┐
│ 小爱音箱 X08C（Android 9, ARM32）                                │
│                                                                  │
│ com.xiaozhi.x08c                                                 │
│  ├─ MainActivity       设置、点击说话、状态/文本界面、音频自测    │
│  ├─ VoiceAssistantService 前台服务、共享录音、会话状态机          │
│  ├─ SherpaWakeWordEngine 本地 KWS：小智/小屁/小爱                │
│  ├─ XiaozhiClient     WebSocket 协议 v1、auto/manual、abort       │
│  ├─ AudioEngine       16 kHz 单声道 60 ms、Opus、播放             │
│  ├─ BootReceiver      开机启动前台服务                             │
│  └─ Opus JNI + Sherpa/ONNX 原生库（armeabi-v7a）                  │
│                                                                  │
│ 原厂“小爱同学”（未修改，继续保留）                               │
└─────────────────────────────┬────────────────────────────────────┘
                              │ WebSocket + Opus 音频帧
                              │ ws://<SERVER_IP>:8000/xiaozhi/v1/
┌─────────────────────────────┴────────────────────────────────────┐
│ 服务端（Docker，<SERVER_IP>）                                    │
│ xiaozhi-esp32-server（上游镜像，本仓库提供部署配置）              │
│  ├─ ASR SherpaParaformerASR：本地中文 Paraformer                 │
│  ├─ LLM MiniMaxLLM：OpenAI 兼容                                  │
│  ├─ TTS MinimaxTTSHTTPStream：流式 PCM                            │
│  └─ 可选 get_weather / Home Assistant function call               │
└───────────────────────────────────────────────────────────────────┘
```

## 客户端分层

| 模块 | 职责 |
| --- | --- |
| `MainActivity` | 设置、点击说话、麦克风提示、完整文本、停止/打断按钮 |
| `VoiceAssistantService` | 前台常驻、共享录音、KWS/Opus 分流、auto/manual 状态、超时和打断 |
| `WakeWordEngine` / `SherpaWakeWordEngine` | 离线识别“小智小智”“小屁小屁”“小爱同学” |
| `BootReceiver` | 开机启动前台服务；需要此前已授予麦克风权限 |
| `XiaozhiClient` | hello、listen auto/manual、TTS 音频、abort 和连接状态 |
| `AudioEngine` | 16 kHz/单声道/60 ms 录音、Opus 编解码、播放和回环测试 |
| `OpusCodec` + JNI | Opus 静态编译与 JNI 封装，构建入口在 `app/src/main/native/` |
| `SettingsStore` / `DeviceIdentity` | 服务器、Token、设备身份的本地保存 |

关键事实：

- 目标架构为 ARM32（`armeabi-v7a`），Android 9 设备可用；
- 点击“点击说话”和两个小智唤醒词都进入 `listen mode=auto`，由服务端 VAD 断句；
- 小爱唤醒词不接管，交给原厂服务；原厂服务和本服务并存时可能发生麦克风争用；
- 首轮 auto 聆听无语音 10 秒超时；每次 TTS 结束后提供约 5 秒免唤醒跟听；
- TTS 播放中再次命中小智唤醒词会停止播放并发送 `abort`；
- 完整的 `tts.text` 会显示在客户端文本区，文本区固定跟随最新内容；
- 识别完整等于停止命令时回系统时钟；更长的控制指令不会被误判为退出。

## 服务端组成

服务端运行上游镜像，暴露 `8000` WebSocket 和 `8003` 视觉 MCP 端口。
默认组件为 `SherpaParaformerASR`、`MiniMaxLLM` 和 `MinimaxTTSHTTPStream`；
QWeather、Home Assistant 为可选插件，三个核心组件可以独立替换。

## 已实现能力

| 能力 | 状态 |
| --- | --- |
| 服务器配置 / Intent 注入 | 已实现 |
| 点击说话 auto/VAD | 已实现 |
| 本地三关键词 KWS 和开机前台服务 | 已实现 |
| 本地随机唤醒应答语 | 已实现 |
| TTS 文本/音频、播放中唤醒打断 | 已实现 |
| 10 秒首轮超时、5 秒连续对话窗口 | 已实现 |
| 独立停止/打断按钮和精确停止命令 | 已实现 |
| Home Assistant / 天气插件 | 服务端可选配置 |
| 视觉/摄像头能力 | 未实现，仅服务端端口预留 |

## 数据流

### 唤醒或点击说话

1. KWS 检测“小智小智”或“小屁小屁”，或用户点击“点击说话”；
2. 服务显示小智界面并播放本地“我在/干嘛呀/诶”之一；
3. 应答播放期间不上传音频，随后发送 `listen start, mode=auto`；
4. 录音线程发送 Opus 帧，服务端 VAD 判定用户结束；
5. 服务端发送 `stt`、`llm`、`tts` 和二进制音频；
6. TTS 完成后进入 5 秒跟听，超时回到时钟。

### TTS 打断

TTS 播放期间 KWS 仍监听小智唤醒词。命中后停止本地播放，向服务端发送 `abort`，
播放一次本地应答并等待新问题。点击停止/打断按钮或独立停止命令也会结束当前会话。

协议细节见 [protocol.md](protocol.md)，部署见 [server.md](server.md)，
设备说明见 [device.md](device.md)。
