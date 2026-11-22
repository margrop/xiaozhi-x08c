# Getting Started

## 构建

需要 Android SDK 35、Build Tools 35.0.0、NDK 26.3.11579264 和 CMake 3.22.1：

```bash
./gradlew :app:assembleDebug
```

## 安装

```bash
adb connect <DEVICE_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次打开后授予麦克风权限。如果服务在授权前已经启动，重新打开应用。

## 配置服务端

在应用中填入：

```text
ws://<SERVER_IP>:8000/xiaozhi/v1/
```

以及服务端要求的 Token 和设备 ID。也可以使用 Intent 注入，详见仓库 README。

## 使用

- 点击“点击说话”进入自动聆听；
- 或直接说“小智小智”/“小屁小屁”；
- 看到“我在听，请讲…”后直接说问题；
- 使用“停止/打断”按钮结束当前会话。

完整配置见仓库的 `docs/server.md`、`docs/device.md` 和 `docs/testing.md`。
