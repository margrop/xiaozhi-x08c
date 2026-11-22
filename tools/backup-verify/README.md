# backup-verify：固件备份校验与安全解包

对 X08C 整盘备份（GPT 分区镜像）做完整性校验，并对备份压缩包做安全解包。
**对设备分区做任何修改之前，请先完成本目录的校验流程。**

## hash_backup.py：分区镜像校验 + SHA-256 清单

校验备份目录中的分区镜像是否齐全、大小是否符合预期，并为全部文件生成
SHA-256 校验清单。

目录约定（以备份根目录 `<BACKUP_ROOT>` 表示）：

```
<BACKUP_ROOT>/
├── partitions/
│   ├── proinfo.bin
│   ├── nvram.bin
│   ├── persist.bin
│   ├── boot_a.bin / boot_b.bin
│   ├── system_a.bin / system_b.bin
│   ├── vendor_a.bin / vendor_b.bin
│   ├── userdata.bin
│   └── ...（共 26 个 GPT 分区镜像）
└── SHA256SUMS.txt   （脚本生成）
```

用法：

```bash
python3 tools/backup-verify/hash_backup.py <BACKUP_ROOT>
```

行为：

- 逐一检查 26 个分区镜像存在且大小与内置的分区表一致（缺失或大小不符即失败退出）；
- 对备份根目录下所有文件（清单本身除外）计算 SHA-256，追加写入
  `<BACKUP_ROOT>/SHA256SUMS.txt`；
- 全部通过时输出 `Verified all 26 GPT partitions`。

> 分区表（名称与大小）针对 X08C 的 GPT 布局硬编码在脚本内；
> 其他机型请勿直接使用该表。

## safe_extract.py：备份压缩包安全解包

从压缩包恢复备份文件时，先校验再解包，防止恶意构造的压缩包破坏宿主系统。

用法：

```bash
python3 tools/backup-verify/safe_extract.py <压缩包> <目标目录>
```

安全检查：

- CRC 校验（`testzip`），损坏即中止；
- 拒绝绝对路径、`..` 穿越、盘符开头的条目；
- 拒绝符号链接条目；
- 目标目录必须为空（不存在则创建），避免覆盖已有文件。

任一检查失败都会中止并说明原因。

## 建议流程

1. 备份设备分区到 `<BACKUP_ROOT>/partitions/`。
2. 运行 `hash_backup.py <BACKUP_ROOT>`，确认 26 个分区全部通过并留存
   `SHA256SUMS.txt`。
3. 压缩备份并异地保存；恢复时先用 `safe_extract.py` 解包，
   再用 `sha256sum -c SHA256SUMS.txt` 核对。
