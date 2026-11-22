# device：设备端 ADB 维护脚本

针对 X08C 的常用 adb 操作脚本。使用前请确保已通过
[../enable-adb/README.md](../enable-adb/README.md) 开启 ADB 并连接设备：

```bash
adb connect <DEVICE_IP>:5555
```

## 脚本一览

### set-x08e-home.sh

把默认桌面设置为第三方桌面 `moe.rua.x08clauncher/.MainActivity`，并触发一次
HOME Intent 使其立即生效：

```bash
sh tools/device/set-x08e-home.sh
```

### restore-xiaomi-home.sh

把默认桌面恢复为原厂小米音箱桌面 `com.xiaomi.micolauncher/.Launcher`：

```bash
sh tools/device/restore-xiaomi-home.sh
```

### restore-system-apps.sh

恢复若干出厂应用的启用状态（对当前用户 `--user 0` 执行
`pm install-existing`）。适用于此前为精简系统而停用、现在需要还原的场景：

```bash
sh tools/device/restore-system-apps.sh
```

涉及的应用包括小米音箱桌面、应用商店、反馈、屏保及若干预装音视频应用等
（完整列表见脚本内容）。

## 说明与限制

- 所有操作仅作用于当前用户（`--user 0`），不修改系统分区，基本可逆。
- `set-home-activity` 需要目标桌面应用已安装在设备上，否则命令会报错。
- 这些脚本与客户端应用无关；安装 APK 请直接 `adb install -r <apk>`，
  参见仓库根 [README.md](../../README.md) 的“安装”一节。
