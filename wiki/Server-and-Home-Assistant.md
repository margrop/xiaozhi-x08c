# Server and Home Assistant

## 默认组件

- ASR：`SherpaParaformerASR`，本地 Paraformer 中文模型；
- LLM：`MiniMaxLLM`；
- TTS：`MinimaxTTSHTTPStream`；
- 可选：QWeather 和 Home Assistant function call。

在部署目录的 `server/` 中使用 `render-config.py` 将私有 env 渲染到 `data/.config.yaml`，输出权限为 0600。

## Home Assistant

只开放明确列入 `HOME_ASSISTANT_DEVICES` 的实体。推荐域为 `light`、`switch`、`climate`、`fan`、`media_player`。
门锁、车库、报警器、摄像头、燃气/水阀、脚本和自动化排除。面包机是用户明确要求的例外，必须人工复核。

```bash
python3 -I ../tools/device/build-ha-device-list.py /path/to/xiaozhi-minimax.env
python3 -I render-config.py /path/to/xiaozhi-minimax.env config.template.yaml data/.config.yaml
docker compose restart
```

不要提交 env、长期访问令牌或渲染配置。
