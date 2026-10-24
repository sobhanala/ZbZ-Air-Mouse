from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ServerEvent:
    kind: str
    message: str
    address: tuple[str, int] | None = None
