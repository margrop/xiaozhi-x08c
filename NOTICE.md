# Third-Party Notices

This project combines original code with the following third-party components.

## Opus

- Source: https://opus-codec.org/
- License: BSD 3-Clause
- License text: [licenses/OPUS.txt](licenses/OPUS.txt)

## nv-websocket-client

- Source: https://github.com/TakahikoKawasaki/nv-websocket-client
- License: Apache License 2.0
- License text: [licenses/NV-WEBSOCKET-CLIENT.txt](licenses/NV-WEBSOCKET-CLIENT.txt)

## Sherpa-ONNX

- Source: https://github.com/k2-fsa/sherpa-onnx
- License: Apache License 2.0
- License text: [licenses/SHERPA-ONNX.txt](licenses/SHERPA-ONNX.txt)
- KWS model: `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`, see [app/src/main/assets/kws/README.md](app/src/main/assets/kws/README.md)

## ONNX Runtime

- Source: https://github.com/microsoft/onnxruntime
- Version observed in the packaged Android binary: 1.28.2
- License: MIT License
- License/provenance: [licenses/ONNX-RUNTIME.md](licenses/ONNX-RUNTIME.md)
- Binary: `app/src/main/jniLibs/armeabi-v7a/libonnxruntime.so`


- Xiaozhi ESP32 firmware and protocol: https://github.com/78/xiaozhi-esp32
- Xiaozhi ESP32 Server: https://github.com/xinnan-tech/xiaozhi-esp32-server

The upstream projects are independent open-source projects. This repository
contains an X08C Android client, deployment configuration, and documentation;
it does not bundle the upstream server implementation.
