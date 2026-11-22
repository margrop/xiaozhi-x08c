# 发布与版本号规则

## 目标

项目同时发布 Android APK、服务端部署配置和文档。版本号必须能从 Git 历史、APK 元数据和 GitHub Release 互相核对。

## 版本格式

稳定版本使用 SemVer：

```text
vMAJOR.MINOR.PATCH
```

它必须与 `app/build.gradle` 中的 `versionName` 完全一致，例如：

```text
versionName '0.2.0'
Git tag: v0.2.0
GitHub Release: v0.2.0
```

含义：

- `MAJOR`：不兼容的协议、配置或设备行为变化；
- `MINOR`：向后兼容的新功能，例如新增唤醒词、HA 能力或新的用户入口；
- `PATCH`：向后兼容的 bug 修复、文档修正或构建修复，不改变公开功能契约。

预发布版本可以使用 SemVer 后缀，例如 `0.3.0-rc.1`、`0.3.0-beta.1`；预发布 tag 必须与
`versionName` 完全相同，不能把预发布包标为稳定版。

## Android versionCode

`versionCode` 使用确定性公式：

```text
MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + BUILD
```

其中 `BUILD` 为同一 SemVer 版本的重打包/修订序号，范围 `0..99`。稳定版本第一次发布使用 `BUILD=0`。

当前版本：

```text
versionName = 0.2.0
versionCode = 20000
```

计算：`0*1,000,000 + 2*10,000 + 0*100 + 0 = 2000`。

规则：

- 同一个 `versionName` 的每次可交付 APK 都必须递增 `BUILD`；
- 任何 `versionName` 变化都必须重新按公式计算，不能手工沿用旧 code；
- `versionCode` 不能回退；
- `tools/check-version.py` 会校验 `versionName`、`versionCode`、Git tag 和公式。

## 分支与发布前提

1. 日常修复在功能分支完成；
2. 合并到 `main` 前必须通过 CI；
3. 稳定发布只从干净的 `main` 创建；
4. 发布提交不能包含真实 IP、MAC、Token、env、渲染配置、APK 或设备转储；
5. 运行以下检查：

```bash
python tools/check-version.py
python tools/check-public-tree.py
python -m compileall -q -f server tools
./gradlew :app:assembleDebug
```

Windows/WSL 构建按照 `AGENTS.md` 的 Java 17 和临时 `local.properties` 约束执行。

## Tag 和 GitHub Release

稳定发布流程：

```bash
git switch main
git pull --ff-only
git status --short
python tools/check-version.py
python tools/check-public-tree.py
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin v0.2.0
gh release create v0.2.0 \
  app/build/outputs/apk/debug/app-debug.apk \
  --title "v0.2.0" \
  --generate-notes
```

实际发布前将示例版本替换为当前版本。若使用正式签名 APK，应上传经过签名的 release 包，并在 Release notes
中记录 SHA-256、适用设备（X08C / ARM32）、服务端配置要求和已知限制。Debug APK 只能作为测试构建，
不应冒充稳定分发包。

## Changelog 要求

每个 GitHub Release 至少包含：

- 新增功能；
- 修复问题；
- 破坏性变化或配置迁移；
- APK 文件名、SHA-256 和 ABI；
- 服务端 ASR/LLM/TTS 变更；
- Home Assistant 权限范围变化；
- 实机验证范围和未覆盖项目。

## 回滚

如果实机验证失败，不要重用已经发布的 tag。修复后递增 PATCH 或 BUILD，重新运行全部检查，再发布新 tag。
已推送的稳定 tag 不移动、不强行覆盖；必要时在 GitHub Release 标记为 withdrawn，并发布修正版。
