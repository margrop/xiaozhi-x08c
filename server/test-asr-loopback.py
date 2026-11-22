import asyncio
import json
import sys
import time

import websockets


HEADERS = {
    "Authorization": "Bearer test-token",
    "Protocol-Version": "1",
    "Device-Id": "x08c-test-device",
    "Client-Id": "00000000-0000-4000-8000-000000000002",
}
HELLO = {
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


async def receive_hello(connection):
    response = json.loads(await connection.recv())
    if response.get("type") != "hello" or not response.get("session_id"):
        raise RuntimeError("Invalid server hello")
    return response


async def generate_voice(server_url):
    async with websockets.connect(server_url, additional_headers=HEADERS) as connection:
        await connection.send(json.dumps(HELLO))
        hello = await receive_hello(connection)
        await connection.send(json.dumps({
            "type": "listen",
            "state": "detect",
            "text": "你好，请用一句简短的话介绍你自己。",
            "source": "text",
            "session_id": hello["session_id"],
        }, ensure_ascii=False))
        frames = []
        deadline = time.time() + 45
        while time.time() < deadline:
            message = await asyncio.wait_for(connection.recv(), timeout=deadline - time.time())
            if isinstance(message, bytes):
                frames.append(message)
            else:
                payload = json.loads(message)
                if payload.get("type") == "tts" and payload.get("state") == "stop":
                    break
        if not frames:
            raise RuntimeError("No generated audio frames")
        print("generated_opus_frames=%d bytes=%d" % (len(frames), sum(map(len, frames))))
        return frames


async def recognize_voice(server_url, frames):
    async with websockets.connect(server_url, additional_headers=HEADERS) as connection:
        await connection.send(json.dumps(HELLO))
        hello = await receive_hello(connection)
        await connection.send(json.dumps({
            "type": "listen",
            "state": "start",
            "mode": "manual",
            "session_id": hello["session_id"],
        }))
        for frame in frames:
            await connection.send(frame)
            await asyncio.sleep(0.06)
        await connection.send(json.dumps({
            "type": "listen",
            "state": "stop",
            "mode": "manual",
            "session_id": hello["session_id"],
        }))
        deadline = time.time() + 30
        while time.time() < deadline:
            message = await asyncio.wait_for(connection.recv(), timeout=deadline - time.time())
            if isinstance(message, bytes):
                continue
            payload = json.loads(message)
            if payload.get("type") == "stt":
                print("asr_text=%s" % payload.get("text", ""))
                return payload.get("text", "")
            if payload.get("type") == "error":
                raise RuntimeError("ASR session failed: " + str(payload))
        raise RuntimeError("Timed out waiting for ASR result")


async def run(server_url):
    start = time.time()
    frames = await generate_voice(server_url)
    generated_at = time.time()
    text = await recognize_voice(server_url, frames)
    finished_at = time.time()
    print("tts_generation_seconds=%.2f" % (generated_at - start))
    print("asr_loop_seconds=%.2f" % (finished_at - generated_at))
    if not text:
        raise RuntimeError("ASR returned empty text")


if __name__ == "__main__":
    asyncio.run(run(sys.argv[1]))
