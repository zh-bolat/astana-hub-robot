"""IP клиента для access-лога с учётом доверенных прокси.

X-Forwarded-For учитывается, только если непосредственный собеседник (scope["client"]) — доверенный
прокси (TRUSTED_PROXIES: по умолчанию сети docker 172.16.0.0/12 и 127.0.0.1). Цепочка XFF
разбирается справа налево: первый адрес не из доверенных сетей — клиент. Подделать IP, отправив
свой X-Forwarded-For напрямую на сервис, нельзя.
"""

from __future__ import annotations

import ipaddress
from collections.abc import Iterable

from starlette.types import Scope

Network = ipaddress.IPv4Network | ipaddress.IPv6Network


def parse_networks(value: str | Iterable[str]) -> tuple[Network, ...]:
    items = value.split(",") if isinstance(value, str) else value
    nets: list[Network] = []
    for item in items:
        item = item.strip()
        if item:
            nets.append(ipaddress.ip_network(item, strict=False))
    return tuple(nets)


def _trusted(ip: str, nets: tuple[Network, ...]) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    return any(addr.version == n.version and addr in n for n in nets)


def client_ip(scope: Scope, trusted: tuple[Network, ...]) -> str | None:
    client = scope.get("client")
    peer = client[0] if client else None
    if not peer or not _trusted(peer, trusted):
        return peer
    xff = ""
    for name, value in scope.get("headers", []):
        if name == b"x-forwarded-for":
            xff = (xff + "," if xff else "") + value.decode("latin-1")
    chain = [p.strip() for p in xff.split(",") if p.strip()]
    for hop in reversed(chain):
        if not _trusted(hop, trusted):
            return hop[:64]
    return chain[0] if chain else peer
