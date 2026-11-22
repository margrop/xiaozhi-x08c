# AGENTS.md

## Scope

This repository contains the Android 9 / ARM32 X08C client and deployment configuration for the upstream Xiaozhi server. Keep the original XiaoAi service untouched.

## Before changing code

- Read the relevant Android, server, and documentation files first.
- Treat `server/data/`, `server/models/`, local env files, APKs, firmware images, device dumps, IP addresses, MAC addresses, tokens, and signing material as private.
- Use `<SERVER_IP>`, `<DEVICE_IP>`, `<DEVICE_MAC>`, and `<ACCESS_TOKEN>` placeholders in tracked files.
- Do not add real device identifiers to tests, logs, examples, Wiki pages, or commit messages.

## Android conventions

- Target device: Android 9, `armeabi-v7a`; do not add an ABI unless matching Sherpa/ONNX native libraries are supplied.
- `VoiceAssistantService` owns `AudioEngine`, `XiaozhiClient`, and KWS. Keep Activity code UI-only.
- The user-facing button is click-to-talk and uses auto/VAD. Manual APIs are compatibility paths.
- Preserve the three wake-word routes: 小智小智 and 小屁小屁 go to Xiaozhi; 小爱同学 yields to stock XiaoAi.
- Always release AudioRecord, AudioTrack, Opus, KWS, MediaPlayer, and WakeLock resources on shutdown.
- Runtime permission changes must restart capture when microphone permission is granted.

## Server and HA conventions

- Keep ASR, LLM, and TTS independently configurable. Paraformer is the default ASR; SenseVoice is an alternative.
- Render private configuration with `server/render-config.py`; output must be mode `0600` and never be committed.
- Home Assistant lists must use the safe-domain policy. The bread-machine exception is limited to approved low-risk entity domains and must exclude cameras.
- Do not broaden device-control scope without an explicit security review.

## Required checks

Run before committing:

```bash
python tools/check-public-tree.py
python -m compileall -q -f server tools
```

Build the APK with the repository's pinned toolchain:

```bash
./gradlew :app:assembleDebug
```

For this Windows workspace, WSL with Java 17 and a temporary ignored `local.properties` may be required. Never stage that file.

## Documentation and Wiki

Keep README, `docs/`, and `wiki/` aligned with the current implementation. The `wiki/` directory contains sanitized pages ready to publish to GitHub Wiki. Never publish secrets or concrete network/device identifiers.

## Commits

Use concise, descriptive commit messages. Include the verification commands and any skipped hardware/integration tests in the final report. Do not rewrite or force-push history unless the user explicitly requests it.
