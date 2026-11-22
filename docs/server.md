# 服务端部署说明

服务端直接运行上游 [xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)
官方镜像，本仓库仅提供 `server/` 下的部署配置。下文以部署主机局域网地址 `<SERVER_IP>` 表示。

## 传输安全

示例使用 `ws://` 便于可信局域网调试；Token 会通过 WebSocket `Authorization` 头发送。
跨网段、共享网络或公网部署必须在反向代理后启用 `wss://`/TLS，并在客户端使用对应的 `wss://` 地址。
当前 Android 应用为了兼容 X08C 局域网环境开启了明文流量，不能把 `ws://` 视为安全传输。

## 端口与入口

| 端口 | 用途 |
| --- | --- |
| 8000 | 小智协议 WebSocket：`ws://<SERVER_IP>:8000/xiaozhi/v1/` |
| 8003 | 视觉说明 MCP（`vision_explain`；当前客户端未使用） |

## 组件配置（当前选用）

`server/config.template.yaml` 中的默认组件：

| 组件 | 配置名 | 类型 | 说明 |
| --- | --- | --- | --- |
| ASR | `SherpaParaformerASR` | `sherpa_onnx_local` | 本地 sherpa-onnx Paraformer 中文模型，模型目录 `models/sherpa-onnx-paraformer-zh-small-2024-03-09` |
| LLM | `MiniMaxLLM` | `openai` | OpenAI 兼容，Base URL `https://api.minimaxi.com/v1` |
| TTS | `MinimaxTTSHTTPStream` | `minimax_httpstream` | HTTP 流式，PCM 24 kHz / 128 kbps / 单声道 |

模板中同时保留 `SherpaASR`（SenseVoiceSmall int8，`model.int8.onnx` 约 239MB）配置段作为备选；
实测在 4 vCPU / 4GB 内存主机上 SenseVoiceSmall 可运行（服务端 ASR 阶段内存约 580MB），
但实机识别效果不如 Paraformer，因此默认选用 Paraformer。

其他关键配置：

- `close_connection_no_voice_time: 3600`：空闲 3600 秒无语音后断开连接（示例值，可按需调整）。
- `prompt`：系统提示词，要求助手以自然简洁的普通话回答。

### ASR 替换说明

- 上游服务端**暂无 MiniMax ASR 的内置适配**，配置 ASR 为 MiniMax 类型不会被识别。
- ASR 组件可**独立替换**：把 `selected_module.ASR` 指向上游已实现的任意 ASR 类型，
  并添加对应配置段即可，LLM/TTS 不受影响（本地 sherpa-onnx 模型需要在容器
  `models/` 卷中准备好）。

### 可选插件：天气与 Home Assistant

模板还包含两个可选插件：

- `get_weather`：通过 `QWEATHER_API_HOST`、`QWEATHER_API_KEY` 和默认城市配置和风天气；
- `home_assistant`：通过 `HOME_ASSISTANT_BASE_URL`、`HOME_ASSISTANT_API_KEY` 和
  `HOME_ASSISTANT_DEVICES` 配置 HA function call。清单生成和安全域策略见
  [home-assistant.md](home-assistant.md)。

`server/render-config.py` 会把私有 env 渲染进 `data/.config.yaml`，并设置输出权限为 `0600`。
不要将 env、渲染配置或真实地址/令牌提交到仓库。


真实密钥不写入仓库，流程如下：

1. 复制模板：`cp server/minimax.env.example xiaozhi-minimax.env`（放在部署主机上，
   例如 `/root/xiaozhi-minimax.env`）。
2. 填入真实值：`SERVER_IP`、`MINIMAX_API_KEY`、`MINIMAX_GROUP_ID`、`MINIMAX_LLM_MODEL`、
   `MINIMAX_TTS_MODEL`、`MINIMAX_TTS_VOICE`。
3. 渲染配置：

   ```bash
   python3 server/render-config.py xiaozhi-minimax.env \
       server/config.template.yaml /path/to/data/.config.yaml
   ```

   `render-config.py` 会校验必需项、替换 `__MINIMAX_*__` 占位符，
   输出文件权限设为 `0600`，且不会把密钥打印到终端。

> 不要把填好真实值的 env 文件或渲染产物提交进仓库；
> `tools/check-public-tree.py` 会拦截明显填了真实 MiniMax 密钥/组号的文本。

## Docker 部署

`server/docker-compose.yml`：

- 镜像：`ghcr.nju.edu.cn/xinnan-tech/xiaozhi-esp32-server:server_latest`
  （南京大学的 ghcr 镜像源；网络允许时可换成官方 `ghcr.io` 源）。
- 卷：`./data` → 服务端数据/配置目录；`./models` → 本地模型目录。
- 日志：json-file，单文件 20 MB、保留 3 个。
- `security_opt: seccomp:unconfined`：沿用上游镜像的运行要求。

启动：

```bash
cd server
# 先把渲染好的 .config.yaml 放到 ./data 下，并把模型放到 ./models 下
docker compose up -d
```

## 与客户端对接

- 客户端设置中的服务器地址填 `ws://<SERVER_IP>:8000/xiaozhi/v1/`。
- Token：上游服务端按部署配置校验；测试脚本使用占位 Token（见
  [testing.md](testing.md)），真实部署以服务端配置为准。
