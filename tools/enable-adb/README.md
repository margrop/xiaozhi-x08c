# enable-adb：开启设备 ADB

在 X08C（Android 9）上启用 ADB 的辅助脚本。设备默认可能未开放 ADB，
本工具通过修改 persistent 属性（`persist.sys.usb.config=mtp,adb`）来开启。

## 脚本

`patch_persistent_properties.py`：

- 输入：从设备取出的 persistent 属性文件（位置因设备解锁方式而异，本文不展开）。
- 输出：改写后的属性文件，用于写回设备。
- 编码：protobuf 风格的 varint/length 记录，脚本按记录逐个解析并重编码。

用法：

```bash
python3 tools/enable-adb/patch_persistent_properties.py <原始属性文件> <输出文件>
```

行为：

1. 解析全部属性记录；
2. round-trip 校验：除待更新项外，重编码结果必须与原始文件字节一致，
   否则中止并报错；
3. 移除旧的 `persist.sys.usb.config`，追加 `persist.sys.usb.config=mtp,adb`；
4. 写出结果并打印前后大小。

## 前提与风险

- 需要能把设备的 persistent 属性文件取出并写回（依赖已解锁/可挂载分区的环境）。
- 写回分区属性有变砖风险。动手前请先完成整盘备份并校验，
  见 [../backup-verify/README.md](../backup-verify/README.md)。
- 开启 ADB 会扩大设备攻击面，请只在可信网络中使用，调试完成后按需关闭。
