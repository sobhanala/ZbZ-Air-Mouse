from __future__ import annotations

import ipaddress
import re
import socket
import subprocess


def get_local_ip(preferred_prefixes: tuple[str, ...]) -> str:
    candidates = _candidate_ips()
    private_candidates = [address for address in candidates if _is_usable_private(address)]
    for prefix in preferred_prefixes:
        for address in private_candidates:
            if address.startswith(prefix):
                return address
    if private_candidates:
        return private_candidates[0]
    if candidates:
        return candidates[0]
    return "127.0.0.1"


def _candidate_ips() -> list[str]:
    candidates: list[str] = []
    candidates.extend(_default_interface_ips())
    candidates.extend(_ifconfig_ips())
    candidates.extend(_hostname_ips())
    candidates.extend(_route_probe_ips())
    return _unique(candidates)


def _default_interface_ips() -> list[str]:
    interface = _default_interface()
    if not interface:
        return []
    output = _run(["ipconfig", "getifaddr", interface])
    return [output.strip()] if output.strip() else []


def _default_interface() -> str | None:
    output = _run(["route", "-n", "get", "default"])
    for line in output.splitlines():
        key, _, value = line.partition(":")
        if key.strip() == "interface":
            return value.strip()
    return None


def _ifconfig_ips() -> list[str]:
    output = _run(["ifconfig"])
    return re.findall(r"\binet\s+(\d+\.\d+\.\d+\.\d+)\b", output)


def _hostname_ips() -> list[str]:
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
    except socket.gaierror:
        return []
    return [info[4][0] for info in infos]


def _route_probe_ips() -> list[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("8.8.8.8", 80))
            return [probe.getsockname()[0]]
    except OSError:
        return []


def _run(command: list[str]) -> str:
    try:
        return subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL, timeout=2)
    except (OSError, subprocess.SubprocessError):
        return ""


def _is_usable_private(address: str) -> bool:
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError:
        return False
    return parsed.is_private and not parsed.is_loopback and not parsed.is_link_local


def _unique(addresses: list[str]) -> list[str]:
    seen: set[str] = set()
    unique_addresses: list[str] = []
    for address in addresses:
        if address not in seen:
            seen.add(address)
            unique_addresses.append(address)
    return unique_addresses
