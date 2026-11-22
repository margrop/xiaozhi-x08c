# 协议说明（小智协议 v1）

本客户端实现小智 ESP32 生态的 WebSocket 协议 v1。服务端行为以上游
[xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) 为准。

## 连接与握手

地址：`ws://<SERVER_IP>:8000/xiaozhi/v1/`。
连接头包括 `Authorization`、`Protocol-Version: 1`、`Device-Id: <DEVICE_ID>`
和持久化的 `Client-Id`。握手成功后客户端发送 `hello`，服务端返回 `session_id`
和 TTS 输出采样率。

客户端上行音频为 16 kHz、单声道、60 ms 的 Opus 帧；服务端 hello 中的采样率用于播放解码。

## listen 模式

### auto（当前用户入口）

语音唤醒和“点击说话”都使用 auto：

```json
{ "type": "listen", "state": "start", "mode": "auto", "session_id": "<SESSION_ID>" }
```

客户端持续发送二进制 Opus 帧，由服务端 VAD 判断用户说完并生成 `stt`、`llm`、`tts`。
正常结束由服务端 TTS 状态变化体现；本地首轮 10 秒和跟听 5 秒是客户端超时保护。

### manual（兼容 API / 测试）

客户端仍保留 manual API，测试脚本使用它：

```json
{ "type": "listen", "state": "start", "mode": "manual", "session_id": "<SESSION_ID>" }
```

发送 Opus 帧后由客户端发送 stop。当前可见按钮不是按住说话，而是 auto 点击入口。

> 实现细节：`XiaozhiClient.stopListening()` 当前序列化停止报文时使用
> `mode: "manual"`，包括 auto 会话的本地超时清理；服务端通常按 state/session 处理，
> 文档和测试不要依赖 stop 报文中的 mode 值。

### 文本测试

服务端测试脚本使用 `listen state=detect` 直接注入文本，不代表设备麦克风路径：

```json
{
  "type": "listen", "state": "detect", "text": "你好",
  "source": "text", "session_id": "<SESSION_ID>"
}
```

## abort

播放中唤醒、点击打断或停止按钮会发送：

```json
{
  "type": "abort",
  "session_id": "<SESSION_ID>",
  "reason": "user"
}
```

客户端同时停止本地播放。TTS 状态结束后，`tts.text` 会作为完整助手文本显示；`llm.text`
可能只包含增量或情绪标记。

## 服务端下行消息

| type | 关键字段 | 说明 |
| --- | --- | --- |
| `hello` | `session_id`, `audio_params` | 握手应答 |
| `stt` | `text` | ASR 完整识别结果 |
| `llm` | `text` | LLM 增量文本 |
| `tts` | `state`, `text` | TTS 句子和播放状态 |
| `error` | `message` | 错误信息 |

TTS 音频以二进制 Opus 帧随文本消息并行下发；`tts state=stop` 表示本轮播报结束。

## 时序

```text
客户端                                  服务端
  │ ── hello ─────────────────────────▶ │
  │ ◀──────────────── hello(session) ─ │
  │ ── listen start(auto) ────────────▶ │
  │ ── Opus 帧 × N ───────────────────▶ │
  │ ◀──────────────────── stt(text) ── │
  │ ◀──────────────────── llm(text) ── │
  │ ◀────────────── tts(sentence) ──── │
  │ ◀──────────── TTS Opus 帧 × M ──── │
  │ ◀────────────────── tts(stop) ──── │
```

服务端配置 `close_connection_no_voice_time` 控制长时间无语音时的断连；客户端可按需重连。
参考实现：`app/src/main/java/com/xiaozhi/x08c/XiaozhiClient.java`。
