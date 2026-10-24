from __future__ import annotations

import json
import time
from typing import Any


def packet_seq(packet: dict[str, Any]) -> int:
    try:
        return int(packet.get("seq", 0))
    except (TypeError, ValueError):
        return 0


def decode_packet(raw_packet: bytes) -> dict[str, Any] | None:
    try:
        packet = json.loads(raw_packet.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(packet, dict):
        return None
    return packet


def encode_payload(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def qr_payload(host: str, port: int, session_token: str) -> str:
    return json.dumps(
        {
            "protocol": "airmouse",
            "host": host,
            "port": port,
            "sessionToken": session_token,
        },
        separators=(",", ":"),
    )


def cursor_payload(x: int, y: int) -> dict[str, Any]:
    return {
        "type": "cursor",
        "x": x,
        "y": y,
        "timestamp": int(time.time() * 1000),
    }


def disconnect_payload() -> dict[str, Any]:
    return {
        "seq": 0,
        "type": "disconnect",
        "timestamp": int(time.time() * 1000),
    }
