# xiaozhi-x08c Wiki

xiaozhi-x08c 是运行在小米小爱音箱 X08C（Android 9、ARM32）上的小智客户端。

## 快速导航

- [Getting Started](Getting-Started.md)
- [Wake Words and Conversation](Wake-Words-and-Conversation.md)
- [Server and Home Assistant](Server-and-Home-Assistant.md)
- [Testing and Troubleshooting](Testing-and-Troubleshooting.md)

## 能力概览

- 点击说话和本地 auto/VAD 对话；
- “小智小智”“小屁小屁”唤醒小智；“小爱同学”保留原厂处理；
- 本地随机唤醒应答语、TTS 播放中唤醒打断；
- TTS 结束后约 5 秒免唤醒连续对话；
- MiniMax LLM/TTS、Paraformer ASR、QWeather 和可选 Home Assistant；
- ARM32 `armeabi-v7a` APK，适合本地侧载，不是 Google Play 包。

所有示例都使用 `<SERVER_IP>`、`<DEVICE_IP>`、`<DEVICE_MAC>` 和令牌占位符，禁止把真实配置提交到公开仓库。
