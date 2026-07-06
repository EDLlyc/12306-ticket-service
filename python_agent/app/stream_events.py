from __future__ import annotations

from collections.abc import Callable
from typing import Any


StreamEventPayload = dict[str, Any] | list[Any] | str
StreamEventCallback = Callable[[str, StreamEventPayload], None]


def emit_stream_event(
    callback: StreamEventCallback | None,
    event: str,
    payload: StreamEventPayload,
) -> None:
    if callback is None:
        return
    callback(event, payload)
