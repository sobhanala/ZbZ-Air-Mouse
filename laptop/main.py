"""Entry point for the laptop side of the Air Mouse project."""

from __future__ import annotations

import tkinter as tk

from config import load_config
from ui import AirMouseLaptopApp


def main() -> None:
    root = tk.Tk()
    AirMouseLaptopApp(root, load_config())
    root.mainloop()


if __name__ == "__main__":
    main()
