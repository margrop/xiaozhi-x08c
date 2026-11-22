# Sherpa-ONNX KWS model

- Model: `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`
- Upstream release: https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models
- Source archive SHA-256: `68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6`
- Packaged files: int8 Zipformer encoder, decoder, joiner, `tokens.txt`, and custom `keywords.txt`.
- Configured keywords: `小智小智`, `小屁小屁`, and `小爱同学`. The first two route to Xiaozhi; `小爱同学` yields to the stock assistant.

The upstream project is distributed under the Apache License 2.0. See
`licenses/SHERPA-ONNX.txt` in this repository.
