"""Real-time command push hub.

Each enrolled device keeps a persistent WebSocket open to
``/api/v1/commands/ws/{device_id}``. When the admin panel issues a command we
``db.commit()`` it (so the poll fallback still works) and then *wake* the device
over its socket, so the app fetches and executes the command instantly instead
of waiting for the next 2s poll.

The hub holds no durable state — if the API restarts, devices reconnect via
``StreamingWsClient`` automatically and the 2s poll covers the gap.
"""
from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from typing import Dict, Optional, Set

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class CommandHub:
    """In-memory registry of device command sockets keyed by ``device_id``."""

    def __init__(self) -> None:
        self._devices: Dict[str, Set[WebSocket]] = defaultdict(set)
        self._lock = asyncio.Lock()
        # Captured on the first WS connect so synchronous request handlers
        # (which run in a threadpool) can schedule a wake on the event loop.
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    async def attach(self, device_id: str, ws: WebSocket) -> None:
        self._loop = asyncio.get_running_loop()
        async with self._lock:
            self._devices[device_id].add(ws)
        logger.info("CommandHub: device attached device=%s total=%d",
                    device_id, len(self._devices[device_id]))

    async def detach(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            self._devices[device_id].discard(ws)
            if not self._devices[device_id]:
                self._devices.pop(device_id, None)

    def is_online(self, device_id: str) -> bool:
        return bool(self._devices.get(device_id))

    async def _wake(self, device_id: str) -> None:
        targets = list(self._devices.get(device_id, ()))
        if not targets:
            return
        dead: list[WebSocket] = []
        for ws in targets:
            try:
                await ws.send_text('{"event":"command"}')
            except Exception:
                dead.append(ws)
        if dead:
            async with self._lock:
                for d in dead:
                    self._devices[device_id].discard(d)

    def wake_threadsafe(self, device_id) -> None:
        """Wake a device from synchronous code (e.g. a request handler).

        Safe to call even if no event loop / socket is available — it is a
        best-effort nudge layered on top of the existing poll + FCM fallbacks.
        """
        loop = self._loop
        if loop is None:
            return
        try:
            asyncio.run_coroutine_threadsafe(self._wake(str(device_id)), loop)
        except Exception as exc:  # pragma: no cover - best effort
            logger.debug("CommandHub wake failed for %s: %s", device_id, exc)


command_hub = CommandHub()
