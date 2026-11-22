# Home Assistant 接入

小智服务端可通过 function call 查询或控制 Home Assistant，不需要替换 MiniMax LLM。

## 地址与令牌

小智服务端必须能访问 HA，例如：

```text
http://<HOME_ASSISTANT_IP>:<PORT>
```

在 HA 用户设置中创建长期访问令牌。令牌只完整显示一次，不要提交到 Git 或发送给第三方。

## 设备清单与安全策略

清单格式为：

```text
位置,设备名称,entity_id;位置,设备名称,entity_id
```

默认建议只开放：`light`、`switch`、`climate`、`fan`、`media_player`。
门锁、车库门、报警器、摄像头、燃气/水阀、脚本和自动化默认排除。

本项目的自动清单脚本
[`tools/device/build-ha-device-list.py`](../tools/device/build-ha-device-list.py) 会：

- 请求 HA `/api/states`；
- 过滤安全域；
- 按项目策略额外保留名称匹配“面包”或 `toaster` 的面包机实体；
- 排除名称中含摄像机/camera 的普通实体；
- 更新 env 中的 `HOME_ASSISTANT_DEVICES`，并将 env 权限设为 `0600`。

面包机是用户明确要求的例外；它可能是高功率设备，必须在部署前人工复核实体清单。
小智插件没有危险操作的二次确认，设备清单就是实际控制权限。

## 私有 env

在部署主机创建私有 env（不要放进仓库）：

```bash
cp server/minimax.env.example /path/to/xiaozhi-minimax.env
```

至少填写：

```bash
HOME_ASSISTANT_BASE_URL=http://<HOME_ASSISTANT_IP>:<PORT>
HOME_ASSISTANT_API_KEY=<HOME_ASSISTANT_ACCESS_TOKEN>
HOME_ASSISTANT_DEVICES=客厅,主灯,light.living_room;卧室,台灯,light.bedroom
```

## 渲染与启动

在部署目录中保留仓库的 `server/` 内容及 `data/`、`models/`：

```bash
cd /path/to/xiaozhi-x08c/server
python3 -I render-config.py \
  /path/to/xiaozhi-minimax.env \
  config.template.yaml \
  data/.config.yaml
docker compose up -d
```

`render-config.py` 会替换 MiniMax、QWeather 和 HA 占位符，输出配置权限为 `0600`，
不会把令牌打印到终端。`docker-compose.yml` 将 `./data` 挂载到服务端容器的配置目录，
将 `./models` 挂载为本地模型目录。

如果使用设备清单生成器：

```bash
python3 -I ../tools/device/build-ha-device-list.py /path/to/xiaozhi-minimax.env
python3 -I render-config.py \
  /path/to/xiaozhi-minimax.env config.template.yaml data/.config.yaml
docker compose restart
```

## 验证

当前上游配置通过 `Intent.function_call.functions` 中的 `hass_state` 展开为
`hass_get_state` / `hass_set_state`。查看容器启动日志确认函数已注册，然后测试：

```text
小智小智，卧室台灯开着吗？
小智小智，打开客厅主灯。
```

如果新增设备，重新生成清单、渲染配置并重启服务端。配置文件和 env 都不要提交到仓库。
