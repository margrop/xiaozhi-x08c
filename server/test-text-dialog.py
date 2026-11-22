import asyncio
import json
import sys
import time

import websockets


async def run(server_url, prompt):
    headers = {
        "Authorization": "Bearer test-token",
        "Protocol-Version": "1",
        "Device-Id": "x08c-test-device",
        "Client-Id": "00000000-0000-4000-8000-000000000001",
    }
    async with websockets.connect(server_url, additional_headers=headers) as connection:
        hello = {
            "type": "hello",
            "version": 1,
            "transport": "websocket",
            "audio_params": {
                "format": "opus",
                "sample_rate": 16000,
                "channels": 1,
                "frame_duration": 60,
            },
        }
        await connection.send(json.dumps(hello))
        response = json.loads(await connection.recv())
        if response.get("type") != "hello" or not response.get("session_id"):
            raise RuntimeError("Invalid server hello: " + json.dumps(response, ensure_ascii=False))
        print("hello_ok session_len=%d output_rate=%s" % (
            len(response["session_id"]),
            response.get("audio_params", {}).get("sample_rate", "default"),
        ))

        request = {
            "type": "listen",
            "state": "detect",
            "text": prompt,
            "source": "text",
            "session_id": response["session_id"],
        }
        await connection.send(json.dumps(request, ensure_ascii=False))

        binary_bytes = 0
        deadline = time.time() + 45
        while time.time() < deadline:
            try:
                message = await asyncio.wait_for(connection.recv(), timeout=deadline - time.time())
            except asyncio.TimeoutError:
                break
            if isinstance(message, bytes):
                binary_bytes += len(message)
                continue
            payload = json.loads(message)
            message_type = payload.get("type")
            if message_type in ("stt", "llm", "tts", "error"):
                text = payload.get("text") or payload.get("state") or payload.get("message")
                print("%s %s" % (message_type, str(text)[:160]))
            if message_type == "tts" and payload.get("state") == "stop":
                break
        print("binary_audio_bytes=%d" % binary_bytes)
        if binary_bytes <= 0:
            raise RuntimeError("Server returned no TTS audio")


if __name__ == "__main__":
    prompt = sys.argv[2] if len(sys.argv) > 2 else "你好，请用一句简短的话介绍你自己。"
    asyncio.run(run(sys.argv[1], prompt))
