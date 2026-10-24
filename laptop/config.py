"""Configuration loading for the laptop app."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:
    yaml = None


CONFIG_PATH = Path(__file__).with_name("config.yaml")


@dataclass(frozen=True)
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 5000
    socket_timeout_seconds: float = 0.2
    buffer_size: int = 4096
    max_move_packet_age_millis: int = 150
    cursor_update_interval_millis: int = 40
    log_move_packets: bool = False
    preferred_ip_prefixes: tuple[str, ...] = (
        "192.168.",
        "10.",
        "172.16.",
        "172.17.",
        "172.18.",
        "172.19.",
        "172.20.",
        "172.21.",
        "172.22.",
        "172.23.",
        "172.24.",
        "172.25.",
        "172.26.",
        "172.27.",
        "172.28.",
        "172.29.",
        "172.30.",
        "172.31.",
    )


@dataclass(frozen=True)
class SessionConfig:
    token_bytes: int = 18


@dataclass(frozen=True)
class MouseConfig:
    default_sensitivity: float = 1.0
    min_sensitivity: float = 0.25
    max_sensitivity: float = 3.0
    max_move_delta: float = 80.0
    max_scroll_amount: int = 10


@dataclass(frozen=True)
class UiConfig:
    window_min_width: int = 640
    window_min_height: int = 560
    qr_size: int = 220
    event_poll_millis: int = 100
    padding: int = 24


@dataclass(frozen=True)
class AppConfig:
    server: ServerConfig = ServerConfig()
    session: SessionConfig = SessionConfig()
    mouse: MouseConfig = MouseConfig()
    ui: UiConfig = UiConfig()


def load_config(path: Path = CONFIG_PATH) -> AppConfig:
    data: dict[str, Any] = {}
    if yaml is not None and path.exists():
        loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
        if isinstance(loaded, dict):
            data = loaded
    return AppConfig(
        server=ServerConfig(
            host=str(_value(data, "server", "host", ServerConfig.host)),
            port=int(_value(data, "server", "port", ServerConfig.port)),
            socket_timeout_seconds=float(
                _value(data, "server", "socketTimeoutSeconds", ServerConfig.socket_timeout_seconds)
            ),
            buffer_size=int(_value(data, "server", "bufferSize", ServerConfig.buffer_size)),
            max_move_packet_age_millis=int(
                _value(data, "server", "maxMovePacketAgeMillis", ServerConfig.max_move_packet_age_millis)
            ),
            cursor_update_interval_millis=int(
                _value(data, "server", "cursorUpdateIntervalMillis", ServerConfig.cursor_update_interval_millis)
            ),
            log_move_packets=bool(_value(data, "server", "logMovePackets", ServerConfig.log_move_packets)),
            preferred_ip_prefixes=_string_tuple(
                _value(data, "server", "preferredIpPrefixes", ServerConfig.preferred_ip_prefixes)
            ),
        ),
        session=SessionConfig(
            token_bytes=int(_value(data, "session", "tokenBytes", SessionConfig.token_bytes)),
        ),
        mouse=MouseConfig(
            default_sensitivity=float(
                _value(data, "mouse", "defaultSensitivity", MouseConfig.default_sensitivity)
            ),
            min_sensitivity=float(_value(data, "mouse", "minSensitivity", MouseConfig.min_sensitivity)),
            max_sensitivity=float(_value(data, "mouse", "maxSensitivity", MouseConfig.max_sensitivity)),
            max_move_delta=float(_value(data, "mouse", "maxMoveDelta", MouseConfig.max_move_delta)),
            max_scroll_amount=int(_value(data, "mouse", "maxScrollAmount", MouseConfig.max_scroll_amount)),
        ),
        ui=UiConfig(
            window_min_width=int(_value(data, "ui", "windowMinWidth", UiConfig.window_min_width)),
            window_min_height=int(_value(data, "ui", "windowMinHeight", UiConfig.window_min_height)),
            qr_size=int(_value(data, "ui", "qrSize", UiConfig.qr_size)),
            event_poll_millis=int(_value(data, "ui", "eventPollMillis", UiConfig.event_poll_millis)),
            padding=int(_value(data, "ui", "padding", UiConfig.padding)),
        ),
    )


def _value(data: dict[str, Any], section: str, key: str, default: Any) -> Any:
    section_data = data.get(section)
    if not isinstance(section_data, dict):
        return default
    return section_data.get(key, default)


def _string_tuple(value: Any) -> tuple[str, ...]:
    if isinstance(value, (list, tuple)):
        return tuple(str(item) for item in value)
    return tuple(str(value).split(","))
