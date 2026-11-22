# 测试说明

测试分为设备音频、服务端协议、设备联调、Home Assistant 和 CI 五层。
服务端地址统一使用 `ws://<SERVER_IP>:8000/xiaozhi/v1/`。

## 1. 设备音频自测

1. 安装应用并授予麦克风权限；
2. 点击“音频自测”；
3. 采集约 2 秒并本地回放，确认能听到录音。

首次授权后若前台服务没有开始录音，重开应用。

## 2. 服务端协议测试

安装依赖：

```bash
pip install websockets
```

脚本中的 Token、设备 ID 和服务器地址均为占位值，不要写入真实凭据。

### 文本对话

```bash
python3 server/test-text-dialog.py ws://<SERVER_IP>:8000/xiaozhi/v1/
```

覆盖 hello、文本 detect、stt/llm/tts 和二进制音频。

### ASR 回环

```bash
python3 server/test-asr-loopback.py ws://<SERVER_IP>:8000/xiaozhi/v1/
```

脚本先生成 TTS，再以 manual 会话回送 Opus 验证 Paraformer 识别；它不覆盖设备的 KWS/auto 路径。

## 3. 设备联调矩阵

| 场景 | 操作 | 预期 |
| --- | --- | --- |
| 点击入口 | 点击“点击说话” | 显示麦克风提示并进入 auto/VAD |
| 小智唤醒 | 说“小智小智” | 小智界面、随机本地应答、自动聆听 |
| 第二关键词 | 说“小屁小屁” | 与小智小智相同 |
| 原厂入口 | 说“小爱同学” | 不接管，原厂小爱响应 |
| 首轮超时 | 唤醒后 10 秒不说话 | 取消本轮并回待机 |
| 正常对话 | 说完停顿 | 出现完整 `stt`，随后播放 TTS |
| 连续对话 | TTS 结束后 5 秒内直接说 | 无需唤醒词进入下一轮 |
| 连续超时 | TTS 结束后 5 秒不说 | 回时钟/待机 |
| TTS 打断 | 播放时说小智关键词 | 停止播放、发送 abort、等待新问题 |
| 停止命令 | 激活状态下单独说“停止” | 立即回时钟 |
| 普通指令 | 激活状态下说“关闭灯” | 不退出，正常处理指令 |
| 停止按钮 | 点击“停止/打断” | 停止当前播放/会话 |
| 开机启动 | 重启且此前已授权麦克风 | 前台服务恢复 |
| 麦克风争用 | 原厂小爱同时工作 | 记录是否有抢占/全零录音提示 |
| HA | 查询或控制安全设备、面包机 | 仅清单内实体可用 |

## 4. Home Assistant

按 [home-assistant.md](home-assistant.md) 配置，先验证服务端能访问 `/api/states`，
再人工验证查询和控制。不要把长期令牌提交到仓库。

## 5. CI 范围

`.github/workflows/android.yml` 当前只执行：

- `tools/check-public-tree.py` 公开树扫描；
- Android SDK/NDK/CMake 安装；
- `:app:assembleDebug` 和 APK artifact 上传。

GitHub runner 不具备 X08C 麦克风、原厂小爱、已配置的 MiniMax/HA，因此 CI 不验证真实设备、
KWS 命中率、服务端集成或 Home Assistant 行为。唤醒、打断和连续对话需要实机人工验证。
