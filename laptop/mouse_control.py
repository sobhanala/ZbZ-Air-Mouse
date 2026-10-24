from __future__ import annotations

import tkinter as tk

from config import MouseConfig

try:
    import pyautogui
except ImportError:
    pyautogui = None


class MouseController:
    def __init__(self, config: MouseConfig) -> None:
        self.config = config
        self.available = pyautogui is not None
        if self.available:
            pyautogui.FAILSAFE = True

    def screen_size(self, root: tk.Tk) -> tuple[int, int]:
        if self.available:
            try:
                size = pyautogui.size()
                return int(size.width), int(size.height)
            except Exception:
                pass
        return root.winfo_screenwidth(), root.winfo_screenheight()

    def position(self) -> tuple[int, int]:
        if not self.available:
            raise RuntimeError("pyautogui is not installed")
        point = pyautogui.position()
        return int(point.x), int(point.y)

    def move(self, dx: float, dy: float, sensitivity: float) -> None:
        if not self.available:
            raise RuntimeError("pyautogui is not installed")
        safe_dx = _clamp_float(dx, -self.config.max_move_delta, self.config.max_move_delta)
        safe_dy = _clamp_float(dy, -self.config.max_move_delta, self.config.max_move_delta)
        pyautogui.moveRel(safe_dx * sensitivity, safe_dy * sensitivity, duration=0)

    def click(self, button: str) -> None:
        if not self.available:
            raise RuntimeError("pyautogui is not installed")
        if button not in {"left", "right"}:
            raise ValueError(f"Unsupported button: {button}")
        pyautogui.click(button=button)

    def scroll(self, amount: int) -> None:
        if not self.available:
            raise RuntimeError("pyautogui is not installed")
        safe_amount = max(-self.config.max_scroll_amount, min(self.config.max_scroll_amount, int(amount)))
        pyautogui.scroll(safe_amount)


def _clamp_float(value: float, low: float, high: float) -> float:
    return max(low, min(high, float(value)))
