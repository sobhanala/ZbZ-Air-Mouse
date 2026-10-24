from __future__ import annotations

import queue
import secrets
import time
import tkinter as tk
from tkinter import scrolledtext, ttk

from config import AppConfig
from events import ServerEvent
from mouse_control import MouseController
from network import UdpServer
from network_address import get_local_ip
from protocol import qr_payload

try:
    import qrcode
    from PIL import ImageTk
except ImportError:
    qrcode = None
    ImageTk = None


class AirMouseLaptopApp:
    def __init__(self, root: tk.Tk, config: AppConfig) -> None:
        self.root = root
        self.config = config
        self.root.title("Air Mouse Laptop")
        self.root.minsize(config.ui.window_min_width, config.ui.window_min_height)
        self.mouse = MouseController(config.mouse)
        self.event_queue: queue.Queue[ServerEvent] = queue.Queue()
        self.host_ip = get_local_ip(config.server.preferred_ip_prefixes)
        self.session_token = secrets.token_urlsafe(config.session.token_bytes)
        self.sensitivity = tk.DoubleVar(value=config.mouse.default_sensitivity)
        self.status = tk.StringVar(value="Waiting for phone connection.")
        self.client = tk.StringVar(value="No client connected")
        self.qr_photo = None
        self.screen_size = self.mouse.screen_size(root)
        self.server = UdpServer(
            config.server,
            self.session_token,
            self.event_queue,
            self.mouse,
            self.screen_size,
            self.get_sensitivity,
        )
        self._configure_style()
        self._build_connection_screen()
        self.server.start()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(config.ui.event_poll_millis, self._drain_events)

    def get_sensitivity(self) -> float:
        return float(self.sensitivity.get())

    def close(self) -> None:
        self.server.stop()
        self.root.destroy()

    def _configure_style(self) -> None:
        style = ttk.Style()
        style.configure("Title.TLabel", font=("Helvetica", 22, "bold"))
        style.configure("Heading.TLabel", font=("Helvetica", 14, "bold"))
        style.configure("Value.TLabel", font=("Helvetica", 12))

    def _clear_root(self) -> None:
        for child in self.root.winfo_children():
            child.destroy()

    def _build_connection_screen(self) -> None:
        self._clear_root()
        frame = ttk.Frame(self.root, padding=self.config.ui.padding)
        frame.pack(fill=tk.BOTH, expand=True)

        ttk.Label(frame, text="Air Mouse Laptop", style="Title.TLabel").pack(anchor=tk.W)
        ttk.Label(frame, textvariable=self.status).pack(anchor=tk.W, pady=(4, 16))

        content = ttk.Frame(frame)
        content.pack(fill=tk.BOTH, expand=True)

        qr_frame = ttk.Frame(content)
        qr_frame.pack(side=tk.LEFT, fill=tk.Y, padx=(0, 24))
        payload = qr_payload(self.host_ip, self.config.server.port, self.session_token)
        qr_widget = self._build_qr_widget(qr_frame, payload)
        qr_widget.pack()

        details = ttk.Frame(content)
        details.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ttk.Label(details, text="Connection", style="Heading.TLabel").pack(anchor=tk.W)
        ttk.Label(details, text=f"Local IP: {self.host_ip}", style="Value.TLabel").pack(anchor=tk.W, pady=(12, 0))
        ttk.Label(details, text=f"Port: {self.config.server.port}", style="Value.TLabel").pack(anchor=tk.W, pady=(4, 12))
        ttk.Label(
            details,
            text="1. Ensure both devices are on the same Wi-Fi.\n"
            "2. Scan this QR code with your phone.\n"
            "3. Or enter IP and port manually.",
            justify=tk.LEFT,
        ).pack(anchor=tk.W, pady=(0, 16))
        ttk.Label(details, text="QR payload", style="Heading.TLabel").pack(anchor=tk.W)
        payload_text = tk.Text(details, height=6, wrap=tk.WORD)
        payload_text.insert("1.0", payload)
        payload_text.configure(state=tk.DISABLED)
        payload_text.pack(fill=tk.X, pady=(8, 16))
        if not self.mouse.available:
            ttk.Label(
                details,
                text="Mouse control unavailable until pyautogui is installed.",
                foreground="#9A3412",
            ).pack(anchor=tk.W)

        self.log_text = scrolledtext.ScrolledText(frame, height=8, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=False, pady=(20, 0))
        self._append_log("Connection screen ready.")

    def _build_connected_dashboard(self) -> None:
        self._clear_root()
        frame = ttk.Frame(self.root, padding=self.config.ui.padding)
        frame.pack(fill=tk.BOTH, expand=True)

        ttk.Label(frame, text="Connected", style="Title.TLabel").pack(anchor=tk.W)
        ttk.Label(frame, textvariable=self.client, style="Value.TLabel").pack(anchor=tk.W, pady=(6, 18))

        sensitivity_frame = ttk.Frame(frame)
        sensitivity_frame.pack(fill=tk.X)
        ttk.Label(sensitivity_frame, text="Sensitivity/DPI", style="Heading.TLabel").pack(anchor=tk.W)
        ttk.Scale(
            sensitivity_frame,
            from_=self.config.mouse.min_sensitivity,
            to=self.config.mouse.max_sensitivity,
            variable=self.sensitivity,
            command=lambda _value: self._update_sensitivity_label(),
        ).pack(fill=tk.X, pady=(8, 4))
        self.sensitivity_label = ttk.Label(sensitivity_frame, text="", style="Value.TLabel")
        self.sensitivity_label.pack(anchor=tk.W)
        self._update_sensitivity_label()

        ttk.Button(frame, text="Disconnect", command=self._disconnect_from_ui).pack(anchor=tk.W, pady=(18, 16))

        ttk.Label(frame, text="Debug log", style="Heading.TLabel").pack(anchor=tk.W)
        self.log_text = scrolledtext.ScrolledText(frame, height=16, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True, pady=(8, 0))
        self._append_log("Connected dashboard ready.")

    def _disconnect_from_ui(self) -> None:
        self.server.disconnect_client()

    def _update_sensitivity_label(self) -> None:
        if hasattr(self, "sensitivity_label"):
            self.sensitivity_label.configure(text=f"Current sensitivity: {self.get_sensitivity():.2f}")

    def _drain_events(self) -> None:
        while True:
            try:
                event = self.event_queue.get_nowait()
            except queue.Empty:
                break
            self._handle_event(event)
        self.root.after(self.config.ui.event_poll_millis, self._drain_events)

    def _handle_event(self, event: ServerEvent) -> None:
        self._append_log(event.message)
        if event.kind == "connected" and event.address is not None:
            self.client.set(f"Client: {event.address[0]}:{event.address[1]}")
            self.status.set("Phone connected.")
            self._build_connected_dashboard()
        elif event.kind == "disconnect":
            self.client.set("No client connected")
            self.status.set("Disconnected. Waiting for phone connection.")
            self._build_connection_screen()
        elif event.kind == "error":
            self.status.set(event.message)

    def _append_log(self, message: str) -> None:
        timestamp = time.strftime("%H:%M:%S")
        if not hasattr(self, "log_text"):
            return
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, f"[{timestamp}] {message}\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _build_qr_widget(self, parent: ttk.Frame, payload: str) -> tk.Widget:
        if qrcode is None or ImageTk is None:
            return ttk.Label(parent, text="QR unavailable\nInstall laptop requirements.", justify=tk.CENTER)
        qr_image = qrcode.make(payload).resize((self.config.ui.qr_size, self.config.ui.qr_size))
        self.qr_photo = ImageTk.PhotoImage(qr_image)
        return ttk.Label(parent, image=self.qr_photo)
