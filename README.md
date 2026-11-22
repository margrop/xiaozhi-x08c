# xiaozhi-x08c

在小米小爱音箱 X08C（Android 9、ARM32）上运行的小智语音客户端，配合
[xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server) Docker 部署使用。
当前应用版本为 **0.2.0**，目标 ABI 为 `armeabi-v7a`。发布号、Android `versionCode`、Git tag 和 GitHub Release 的对应规则见 [docs/release.md](docs/release.md)。

- 客户端：原生 Android 应用（Java + JNI），提供点击说话、自动 VAD、多唤醒词、TTS 播放与打断。
- 服务端：复用上游 `xiaozhi-esp32-server` 官方镜像，本仓库提供部署模板、配置渲染和测试脚本。
- 原厂“小爱同学”：保留并存，不修改原厂服务。

## 核心能力

- **点击说话**：点击“点击说话”后进入与语音唤醒相同的 auto/VAD 会话，不需要按住按钮。
- **本地唤醒词**：Sherpa-ONNX KWS 离线监听：
  - “小智小智” → 小智 auto 会话；
  - “小屁小屁” → 小智 auto 会话；
  - “小爱同学” → 不接管，交给原厂小爱。
- **自动对话**：唤醒后播放本地随机应答语（“我在”/“干嘛呀”/“诶”，均小于 1 秒），随后显示“我在听，请讲…”，服务端 VAD 自动判断说完。
- **连续对话**：每次 TTS 完整结束后保留约 5 秒免唤醒跟听窗口；超时回到待机。
- **打断**：TTS 播放时再次说任一小智唤醒词，立即停止本地播放并发送 `abort`，等待新问题。
- **停止命令**：激活聆听状态下，识别结果完整等于“关闭”“停止”“结束”“退出”“不用了”或“算了”时退出小智并回到时钟；“关闭灯”等更长指令不会触发退出。
- **界面**：唤醒时显示大号麦克风提示和完整 TTS 文本；空闲约 2 分钟自动回系统时钟屏保。
- **Home Assistant**：可选 function call 集成，默认开放安全常用域，并按策略额外纳入面包机实体；摄像头等高风险域排除。

首轮唤醒后 10 秒没有语音会取消本轮会话。首次安装必须授予麦克风权限；如果服务在授权前已启动，请重开应用或重启服务。

## 项目结构

```text
xiaozhi-x08c/
├── app/                    # Android 客户端（ARM32 / armeabi-v7a）
│   └── src/main/
│       ├── java/com/xiaozhi/x08c/   # Activity、客户端、前台语音服务、KWS
│       ├── assets/kws/               # Sherpa-ONNX 模型、词表和许可证说明
│       ├── jniLibs/armeabi-v7a/      # Sherpa-ONNX / ONNX Runtime 原生库
│       └── native/                   # Opus JNI；Opus 源码静态编译进 JNI 库
├── server/                 # 服务端部署配置和端到端测试
├── tools/
│   ├── device/             # 设备辅助脚本及 HA 设备清单生成器
│   └── check-public-tree.py         # 公开树私网地址/密钥/工件扫描
├── docs/                   # 架构、设备、协议、服务端、测试和 HA 文档
├── wiki/                   # 可直接发布到 GitHub Wiki 的页面草稿
├── third_party/            # Sherpa-ONNX Java API 与 Opus 源码
├── licenses/               # 第三方许可证
└── .github/workflows/      # CI：公开树检查 + APK 构建
```

## 快速开始

前置条件：

1. 可通过 `adb` 连接的 X08C（Android 9、ARM32）；
2. 一台运行 Docker 的服务端主机，地址记为 `<SERVER_IP>`；
3. MiniMax LLM/TTS 所需的 API Key、Group ID、模型和音色；
4. 如启用天气或 Home Assistant，再准备对应的私有配置。

步骤概览：

1. 按 [docs/server.md](docs/server.md) 在 `<SERVER_IP>` 启动服务端；
2. 构建并安装 APK；
3. 在应用设置中填入 `ws://<SERVER_IP>:8000/xiaozhi/v1/`、Token 和设备 ID；
4. 授予麦克风权限，点击“点击说话”或直接说“小智小智”/“小屁小屁”；
5. 先运行应用内“音频自测”，再按 [docs/testing.md](docs/testing.md) 联调。

## 构建

构建需要 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）、NDK
`26.3.11579264` 和 CMake `3.22.1`。CI 也使用这些版本。

```bash
./gradlew :app:assembleDebug
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`，仅包含 `armeabi-v7a`。
应用参数为 `minSdk 24`、`targetSdk 28`、`versionCode 20000`、`versionName 0.2.0`。版本号规则和发布流程见 [docs/release.md](docs/release.md)。

## 安装与配置

```bash
adb connect <DEVICE_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以通过 Intent 注入配置：

```bash
adb shell am start -n com.xiaozhi.x08c/.MainActivity \
  --es server_url "ws://<SERVER_IP>:8000/xiaozhi/v1/" \
  --es server_token "<ACCESS_TOKEN>" \
  --es device_id "<DEVICE_ID>"
```

## 传输安全

可信局域网示例使用 `ws://`；Token 会随 WebSocket 请求发送。跨网段或公网部署必须使用 TLS 反向代理和 `wss://`，不要在不可信网络中使用明文 WebSocket。

## 服务端默认组件

| 组件 | 实现 | 说明 |
| --- | --- | --- |
| ASR | `SherpaParaformerASR` | 本地 sherpa-onnx Paraformer 中文模型 |
| LLM | `MiniMaxLLM` | OpenAI 兼容接口 |
| TTS | `MinimaxTTSHTTPStream` | HTTP 流式 PCM 24 kHz |

ASR、LLM、TTS 可以独立替换。模板中保留 SenseVoiceSmall 作为 ASR 备选。
QWeather 和 Home Assistant 是可选插件，配置方式见 [docs/server.md](docs/server.md) 和
[docs/home-assistant.md](docs/home-assistant.md)。

## 测试与 CI

- 设备端：应用内“音频自测”、唤醒词、点击说话、TTS 打断、连续对话和 HA 人工测试；
- 服务端：`server/test-text-dialog.py`、`server/test-asr-loopback.py`；
- CI：`tools/check-public-tree.py` + Gradle APK 编译和 artifact 上传。

CI 不具备 X08C 麦克风、原厂小爱、真实服务端或 HA 环境，因此不会替代实机联调。
详见 [docs/testing.md](docs/testing.md)。

## 许可证与安全

- 本项目代码许可证：见 [LICENSE](LICENSE)；
- 第三方组件：见 [NOTICE.md](NOTICE.md) 与 `licenses/`；
- 仓库只使用 `<SERVER_IP>`、`<DEVICE_IP>`、`<DEVICE_MAC>` 等占位符；真实密钥和地址只保存在本地部署配置；
- 提交前运行 `python3 tools/check-public-tree.py`；
- 修改设备分区前，先按 [tools/backup-verify/README.md](tools/backup-verify/README.md) 完整备份。

## Wiki 页面草稿

仓库 `wiki/` 目录包含可直接发布到 GitHub Wiki 的 Home、入门、唤醒词、服务端/HA 和排障页面。
发布 Wiki 时只保留 `<SERVER_IP>`、`<DEVICE_IP>`、`<DEVICE_MAC>` 等占位符，不要放入真实配置。

## 上游项目

- [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)
- [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)
