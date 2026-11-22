# Testing and Troubleshooting

## 检查顺序

1. 应用内运行“音频自测”；
2. 确认服务端 WebSocket 地址和 Token；
3. 运行 `server/test-text-dialog.py` 和 `server/test-asr-loopback.py`；
4. 实测三个关键词、点击说话、10 秒超时、5 秒连续对话和 TTS 打断；
5. 实测原厂“小爱同学”与小智同时监听时是否出现麦克风争用。

## 常见现象

- 没有 KWS 响应：确认麦克风权限、前台服务和“唤醒 开”开关；
- 听到自己的应答语后重复回答：检查应用是否为最新 APK，并查看 `VoiceAssistantService` 日志；
- 识别文字不是逐字出现：当前本地 ASR 在服务端 VAD 断句后返回完整文本；
- 录音全零：可能是原厂小爱占用麦克风；
- GitHub CI：只检查公开树和 APK 编译，不具备真实 X08C、MiniMax 或 HA 环境。

不要在日志、Issue 或 Wiki 中粘贴真实 Token、IP、MAC 或完整设备身份。
