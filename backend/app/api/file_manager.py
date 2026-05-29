"""Remote file-manager WebSocket hub.

Admins browse the device's full storage (internal + SD card) through the
dashboard. The Android app runs a tiny file-server foreground service that
connects to /files/device/{device_id} and answers JSON requests forwarded
from connected admins on /files/admin/{device_id}.

All messages are JSON text frames. File data is streamed as base64-encoded
chunks inside JSON `chunk` frames so a single WS protocol handles every
listing / download / error event.
"""
from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from typing import Dict, Set

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/files", tags=["File Manager"])


class FileHub:
    """In-memory pub/sub of JSON file-manager messages keyed by device_id."""

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
        logger.info("FileHub: producer attached device=%s", device_id)
        await self.broadcast_to_viewers(device_id, '{"event":"device_online"}')

    async def detach_producer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            if self._producers.get(device_id) is ws:
                self._producers.pop(device_id, None)
        logger.info("FileHub: producer detached device=%s", device_id)
        await self.broadcast_to_viewers(device_id, '{"event":"device_offline"}')

    async def attach_viewer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            self._viewers[device_id].add(ws)

    async def detach_viewer(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            self._viewers[device_id].discard(ws)
            if not self._viewers[device_id]:
                self._viewers.pop(device_id, None)

    async def send_to_producer(self, device_id: str, text: str) -> bool:
        ws = self._producers.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_text(text)
            return True
        except Exception:
            return False

    async def broadcast_to_viewers(self, device_id: str, text: str) -> None:
        targets = list(self._viewers.get(device_id, ()))
        if not targets:
            return
        dead: list[WebSocket] = []
        for v in targets:
            try:
                await v.send_text(text)
            except Exception:
                dead.append(v)
        if dead:
            async with self._lock:
                for d in dead:
                    self._viewers[device_id].discard(d)

    def has_producer(self, device_id: str) -> bool:
        return device_id in self._producers


hub = FileHub()


@router.websocket("/device/{device_id}")
async def file_device(ws: WebSocket, device_id: str):
    """Android endpoint — receives admin JSON requests, sends JSON responses."""
    await ws.accept()
    await hub.attach_producer(device_id, ws)
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            text = msg.get("text")
            if text is not None:
                await hub.broadcast_to_viewers(device_id, text)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("file_device error device=%s: %s", device_id, e)
    finally:
        await hub.detach_producer(device_id, ws)


@router.websocket("/admin/{device_id}")
async def file_admin(ws: WebSocket, device_id: str):
    """Admin endpoint — sends action requests, receives JSON responses."""
    await ws.accept()
    await hub.attach_viewer(device_id, ws)
    try:
        await ws.send_text(
            '{"event":"device_online"}' if hub.has_producer(device_id)
            else '{"event":"device_offline"}'
        )
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            text = msg.get("text")
            if text is not None:
                ok = await hub.send_to_producer(device_id, text)
                if not ok:
                    await ws.send_text('{"event":"device_offline"}')
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("file_admin error device=%s: %s", device_id, e)
    finally:
        await hub.detach_viewer(device_id, ws)


@router.get("/status/{device_id}")
async def file_status(device_id: str):
    return {"device_id": device_id, "online": hub.has_producer(device_id)}
