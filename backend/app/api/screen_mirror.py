"""Live screen-mirror WebSocket hub.

The Android app uploads JPEG frames over a WebSocket; admin dashboards open a
second WebSocket per device and receive each frame in real time.

This module deliberately holds no persistent state — frames are forwarded
in-memory and never written to disk. If the API is restarted both ends will
reconnect on their own.
"""
from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from typing import Dict, Set

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/screen", tags=["Screen Mirror"])


class ScreenMirrorHub:
    """In-memory pub/sub of binary JPEG frames keyed by device_id."""

    def __init__(self) -> None:
        self._viewers: Dict[str, Set[WebSocket]] = defaultdict(set)
        self._producers: Dict[str, WebSocket] = {}
        self._lock = asyncio.Lock()

    async def attach_producer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            old = self._producers.get(device_id)
            self._producers[device_id] = ws
            if old is not None and old is not ws:
                try:
                    await old.close(code=4000, reason="replaced")
                except Exception:
                    pass
        logger.info("ScreenMirror: producer attached device=%s", device_id)

    async def detach_producer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            if self._producers.get(device_id) is ws:
                self._producers.pop(device_id, None)
        logger.info("ScreenMirror: producer detached device=%s", device_id)

    async def attach_viewer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            self._viewers[device_id].add(ws)
        logger.info(
            "ScreenMirror: viewer attached device=%s total=%d",
            device_id, len(self._viewers[device_id])
        )

    async def detach_viewer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            self._viewers[device_id].discard(ws)
            if not self._viewers[device_id]:
                self._viewers.pop(device_id, None)

    async def broadcast(self, device_id: str, frame: bytes) -> None:
        targets = list(self._viewers.get(device_id, ()))
        if not targets:
            return
        dead: list[WebSocket] = []
        for v in targets:
            try:
                await v.send_bytes(frame)
            except Exception:
                dead.append(v)
        if dead:
            async with self._lock:
                for d in dead:
                    self._viewers[device_id].discard(d)

    def has_producer(self, device_id: str) -> bool:
        return device_id in self._producers


hub = ScreenMirrorHub()
audio_hub = ScreenMirrorHub()

# Cache last announced audio format per device so late viewers can sync.
_audio_format: Dict[str, dict] = {}


@router.websocket("/upload/{device_id}")
async def screen_upload(ws: WebSocket, device_id: str):
    """Device endpoint — sends JPEG screen frames as binary messages."""
    await ws.accept()
    await hub.attach_producer(device_id, ws)
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            payload = msg.get("bytes")
            if payload:
                await hub.broadcast(device_id, payload)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("screen_upload error device=%s: %s", device_id, e)
    finally:
        await hub.detach_producer(device_id, ws)


@router.websocket("/view/{device_id}")
async def screen_view(ws: WebSocket, device_id: str):
    """Admin endpoint — receives binary JPEG frames."""
    await ws.accept()
    await hub.attach_viewer(device_id, ws)
    try:
        await ws.send_text('{"event":"connected"}')
        while True:
            await ws.receive()
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("screen_view error device=%s: %s", device_id, e)
    finally:
        await hub.detach_viewer(device_id, ws)


@router.websocket("/audio/upload/{device_id}")
async def audio_upload(ws: WebSocket, device_id: str):
    """Device endpoint — sends raw PCM16 audio frames as binary messages.
    First message may be a JSON text frame announcing sample_rate / channels."""
    await ws.accept()
    await audio_hub.attach_producer(device_id, ws)
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("text") is not None:
                # Format announcement: {"sample_rate":16000,"channels":1,"bits":16}
                import json
                try:
                    fmt = json.loads(msg["text"])
                    _audio_format[device_id] = fmt
                    # Relay to current viewers so they can (re)init their audio context
                    targets = list(audio_hub._viewers.get(device_id, ()))  # noqa: SLF001
                    for v in targets:
                        try:
                            await v.send_text(msg["text"])
                        except Exception:
                            pass
                except Exception:
                    pass
                continue
            payload = msg.get("bytes")
            if payload:
                await audio_hub.broadcast(device_id, payload)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("audio_upload error device=%s: %s", device_id, e)
    finally:
        await audio_hub.detach_producer(device_id, ws)
        _audio_format.pop(device_id, None)


@router.websocket("/audio/view/{device_id}")
async def audio_view(ws: WebSocket, device_id: str):
    """Admin endpoint — receives raw PCM16 audio frames as binary messages.
    On connect, a JSON text frame is sent describing the audio format."""
    await ws.accept()
    await audio_hub.attach_viewer(device_id, ws)
    try:
        fmt = _audio_format.get(device_id) or {"sample_rate": 16000, "channels": 1, "bits": 16}
        import json
        await ws.send_text(json.dumps({"event": "connected", **fmt}))
        while True:
            await ws.receive()
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("audio_view error device=%s: %s", device_id, e)
    finally:
        await audio_hub.detach_viewer(device_id, ws)


@router.get("/status/{device_id}")
async def screen_status(device_id: str):
    return {
        "device_id": device_id,
        "video_streaming": hub.has_producer(device_id),
        "audio_streaming": audio_hub.has_producer(device_id),
    }
