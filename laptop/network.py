from __future__ import annotations

import queue
import socket
import threading
import time
from typing import Any, Callable

from config import ServerConfig
from events import ServerEvent
from mouse_control import MouseController
from protocol import cursor_payload, decode_packet, disconnect_payload, encode_payload, packet_seq


class UdpServer:
    def __init__(
        self,
        config: ServerConfig,
        session_token: str,
        event_queue: queue.Queue[ServerEvent],
        mouse: MouseController,
        screen_size: tuple[int, int],
        get_sensitivity: Callable[[], float],
    ) -> None:
        self.config = config
        self.session_token = session_token
        self.event_queue = event_queue
        self.mouse = mouse
        self.screen_size = screen_size
        self.get_sensitivity = get_sensitivity
        self.connected_client: tuple[str, int] | None = None
        self._last_cursor_sent = 0.0
        self._stop_event = threading.Event()
        self._socket: socket.socket | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="airmouse-udp", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._socket is not None:
            self._socket.close()

    def disconnect_client(self) -> None:
        old_client = self.connected_client
        if old_client is not None and self._socket is not None:
            self._send_json(self._socket, disconnect_payload(), old_client)
        self.connected_client = None
        self._emit("disconnect", "Disconnected client.", old_client)

    def _run(self) -> None:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_socket:
                self._socket = udp_socket
                udp_socket.bind((self.config.host, self.config.port))
                udp_socket.settimeout(self.config.socket_timeout_seconds)
                self._emit("log", f"UDP server listening on {self.config.host}:{self.config.port}")
                while not self._stop_event.is_set():
                    try:
                        raw_packet, address = udp_socket.recvfrom(self.config.buffer_size)
                    except socket.timeout:
                        continue
                    except OSError:
                        break
                    self._handle_packet(udp_socket, raw_packet, address)
        except OSError as exc:
            self._emit("error", f"Could not start UDP server: {exc}")
        finally:
            self._emit("log", "UDP server stopped.")

    def _handle_packet(
        self,
        udp_socket: socket.socket,
        raw_packet: bytes,
        address: tuple[str, int],
    ) -> None:
        packet = decode_packet(raw_packet)
        if packet is None:
            self._emit("log", f"Ignored malformed packet from {address[0]}:{address[1]}")
            return

        packet_type = packet.get("type")
        if packet_type == "hello":
            self._handle_hello(udp_socket, packet, address)
            return

        if self.connected_client != address:
            self._emit("log", f"Ignored {packet_type!r} packet from unconnected client {address}")
            return

        try:
            if packet_type == "move":
                self._handle_move(packet, address)
                self._maybe_send_cursor(udp_socket, address)
            elif packet_type == "click":
                self._handle_click(udp_socket, packet, address)
                self._maybe_send_cursor(udp_socket, address, force=True)
            elif packet_type == "scroll":
                self._handle_scroll(udp_socket, packet, address)
                self._maybe_send_cursor(udp_socket, address, force=True)
            elif packet_type == "disconnect":
                self.disconnect_client()
            else:
                self._emit("log", f"Ignored unknown packet type {packet_type!r} from {address}")
        except (TypeError, ValueError, RuntimeError) as exc:
            self._emit("error", f"Could not handle {packet_type!r}: {exc}", address)

    def _handle_hello(
        self,
        udp_socket: socket.socket,
        packet: dict[str, Any],
        address: tuple[str, int],
    ) -> None:
        seq = packet_seq(packet)
        requested_token = packet.get("sessionToken")
        if requested_token is not None and requested_token != self.session_token:
            self._send_json(udp_socket, self._hello_response(seq, "rejected"), address)
            self._emit("log", f"Rejected hello from {address[0]}:{address[1]}: bad session token")
            return

        self.connected_client = address
        self._send_json(udp_socket, self._hello_response(seq, "ok"), address)
        self._emit("connected", f"Connected to {address[0]}:{address[1]}", address)

    def _hello_response(self, seq: int, status: str) -> dict[str, Any]:
        return {
            "ack": True,
            "type": "hello_ack",
            "seq": seq,
            "status": status,
            "screenWidth": self.screen_size[0],
            "screenHeight": self.screen_size[1],
            "serverSensitivity": self.get_sensitivity(),
        }

    def _handle_move(self, packet: dict[str, Any], address: tuple[str, int]) -> None:
        if self._is_stale_move(packet):
            return
        dx = float(packet.get("dx", 0))
        dy = float(packet.get("dy", 0))
        self.mouse.move(dx, dy, self.get_sensitivity())
        if self.config.log_move_packets:
            self._emit("log", f"Move dx={dx:.1f}, dy={dy:.1f}", address)

    def _is_stale_move(self, packet: dict[str, Any]) -> bool:
        max_age = self.config.max_move_packet_age_millis
        if max_age <= 0:
            return False
        try:
            timestamp = int(packet.get("timestamp", 0))
        except (TypeError, ValueError):
            return False
        if timestamp <= 0:
            return False
        return int(time.time() * 1000) - timestamp > max_age

    def _handle_click(
        self,
        udp_socket: socket.socket,
        packet: dict[str, Any],
        address: tuple[str, int],
    ) -> None:
        self.mouse.click(str(packet.get("button", "left")))
        self._send_ack(udp_socket, packet, address)
        self._emit("log", f"Click {packet.get('button', 'left')}", address)

    def _handle_scroll(
        self,
        udp_socket: socket.socket,
        packet: dict[str, Any],
        address: tuple[str, int],
    ) -> None:
        self.mouse.scroll(int(packet.get("amount", 0)))
        self._send_ack(udp_socket, packet, address)
        self._emit("log", f"Scroll {packet.get('amount', 0)}", address)

    def _send_ack(
        self,
        udp_socket: socket.socket,
        packet: dict[str, Any],
        address: tuple[str, int],
    ) -> None:
        self._send_json(
            udp_socket,
            {"ack": True, "seq": packet_seq(packet), "status": "ok"},
            address,
        )

    def _maybe_send_cursor(
        self,
        udp_socket: socket.socket,
        address: tuple[str, int],
        force: bool = False,
    ) -> None:
        if not self.mouse.available:
            return
        now = time.time()
        if not force:
            interval = self.config.cursor_update_interval_millis / 1000.0
            if now - self._last_cursor_sent < interval:
                return
        try:
            x, y = self.mouse.position()
        except RuntimeError:
            return
        self._last_cursor_sent = now
        self._send_json(udp_socket, cursor_payload(x, y), address)

    def _send_json(
        self,
        udp_socket: socket.socket,
        payload: dict[str, Any],
        address: tuple[str, int],
    ) -> None:
        udp_socket.sendto(encode_payload(payload), address)

    def _emit(
        self,
        kind: str,
        message: str,
        address: tuple[str, int] | None = None,
    ) -> None:
        self.event_queue.put(ServerEvent(kind, message, address))
