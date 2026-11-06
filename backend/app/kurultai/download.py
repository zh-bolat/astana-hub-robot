"""Скачивание APK робота Курултая прямо с сервера: в парламент не пускают с ноутбуками, приложение ставится
из браузера робота по адресу `https://<сервер Курултая>/app`.

- `GET /app` — страница с паролем (KURULTAI_APP_PASSWORD). Пустой пароль — функция выключена, всё отвечает 404.
- `POST /app` — верный пароль → список APK из KURULTAI_APP_DIR (новые сверху) со ссылками на скачивание.
- `GET /app/d/{exp}/{sig}/{name}` — сам файл. Ссылка подписана (HMAC) и живёт KURULTAI_APP_LINK_TTL_S: менеджер
  загрузок Android перезапрашивает файл сам, без пароля и куки браузера, поэтому доступ — в ссылке.

APK без пароля не раздаём: в нём токен робота для нашего сервера. Неверный пароль — не больше
APP_MAX_FAILURES попыток за APP_FAILURE_WINDOW_S с одного адреса.
Файлы кладёт `docs/kurultai/publish-apk.sh` (репозиторий kurultai) в каталог, смонтированный в kurultai-api.
"""

from __future__ import annotations

import hashlib
import hmac
import html
import re
import secrets
import time
from collections import deque
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi import APIRouter, Form, Request
from fastapi.responses import FileResponse, HTMLResponse, Response

from app.client_ip import client_ip, parse_networks
from app.config import Settings, secret_text
from app.logging_setup import logger

router = APIRouter()

APK_MEDIA_TYPE = "application/vnd.android.package-archive"
APP_MAX_FAILURES = 8
APP_FAILURE_WINDOW_S = 15 * 60
APP_LIST_LIMIT = 6
_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.apk$")
_VERSION_RE = re.compile(r"v?(\d+\.\d+(?:\.\d+)?-[0-9a-f]{7,12}-\d{8})")
_ASTANA = timedelta(hours=5)  # время Астаны — UTC+5 круглый год

_HEADERS = {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
    "frame-ancestors 'none'",
}


@dataclass(frozen=True)
class ApkFile:
    name: str
    size: int
    mtime: float
    sha256: str


def enabled(settings: Settings) -> bool:
    return bool(secret_text(settings.kurultai_app_password))


def _key(settings: Settings) -> bytes:
    """Ключ подписи ссылок: от пароля страницы и токена робота — сменили пароль, старые ссылки не работают."""
    material = (
        "kurultai-app\0"
        + secret_text(settings.kurultai_app_password)
        + "\0"
        + secret_text(settings.kurultai_robot_token)
    )
    return hashlib.sha256(material.encode()).digest()


def sign(settings: Settings, name: str, exp: int) -> str:
    return hmac.new(_key(settings), f"{exp}/{name}".encode(), hashlib.sha256).hexdigest()[:32]


def link(settings: Settings, name: str, now: float | None = None) -> str:
    exp = int((now if now is not None else time.time()) + settings.kurultai_app_link_ttl_s)
    return f"/app/d/{exp}/{sign(settings, name, exp)}/{name}"


_sha_cache: dict[tuple[str, int, float], str] = {}


def _sha256(path: Path, size: int, mtime: float) -> str:
    key = (path.name, size, mtime)
    if key not in _sha_cache:
        h = hashlib.sha256()
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        _sha_cache[key] = h.hexdigest()
    return _sha_cache[key]


def list_apks(directory: Path) -> list[ApkFile]:
    """APK каталога, новые сверху (по времени файла); чужие имена и не-файлы пропускаются."""
    if not directory.is_dir():
        return []
    out: list[ApkFile] = []
    for p in directory.iterdir():
        if not _NAME_RE.match(p.name) or not p.is_file():
            continue
        st = p.stat()
        out.append(ApkFile(p.name, st.st_size, st.st_mtime, _sha256(p, st.st_size, st.st_mtime)))
    out.sort(key=lambda a: (a.mtime, a.name), reverse=True)
    return out[:APP_LIST_LIMIT]


class _Failures:
    """Неверные пароли по адресу: не больше APP_MAX_FAILURES за окно."""

    def __init__(self) -> None:
        self._by_ip: dict[str, deque[float]] = {}

    def blocked(self, ip: str, now: float) -> bool:
        q = self._by_ip.get(ip)
        while q and now - q[0] > APP_FAILURE_WINDOW_S:
            q.popleft()
        return bool(q) and len(q) >= APP_MAX_FAILURES

    def add(self, ip: str, now: float) -> None:
        self._by_ip.setdefault(ip, deque()).append(now)
        if len(self._by_ip) > 10_000:  # не растём бесконечно от сканеров
            self._by_ip.clear()


_failures = _Failures()


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _ip(request: Request, settings: Settings) -> str:
    return client_ip(request.scope, parse_networks(settings.trusted_proxies)) or "?"


def _not_found() -> Response:
    return Response(status_code=404, headers={"Cache-Control": "no-store"})


# ---------- страницы ----------
_STYLE = """
body{margin:0;background:#f3f6fa;color:#182642;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
.wrap{max-width:900px;margin:0 auto;padding:32px 20px 48px}
h1{font-size:34px;margin:0 0 4px}.kk{color:#68748a;font-size:20px;margin:0 0 24px}
.card{background:#fff;border:1px solid #dce4ee;border-radius:20px;padding:24px;margin:0 0 18px}
label{display:block;font-size:22px;margin:0 0 12px}
input{width:100%;box-sizing:border-box;font-size:30px;padding:14px 16px;border:2px solid #268fc8;border-radius:14px}
button,.btn{display:inline-block;margin-top:16px;background:#268fc8;color:#fff;border:0;border-radius:14px;
font-size:26px;font-weight:700;padding:16px 30px;text-decoration:none}
.err{color:#b8404b;font-size:20px;margin:0 0 12px}
.name{font-size:24px;font-weight:700;margin:0 0 6px;word-break:break-all}
.meta{color:#68748a;font-size:18px;margin:0;word-break:break-all}
.tag{display:inline-block;background:#e3f1fa;color:#1d6f9e;border-radius:8px;padding:2px 10px;font-size:17px;
margin-left:8px;vertical-align:middle}
ol{font-size:20px;line-height:1.5;padding-left:26px;margin:0}
button,input{font-family:inherit}
"""


def _page(body: str, status: int = 200) -> HTMLResponse:
    doc = (
        '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>Приложение робота Курултая</title><style>"
        + _STYLE
        + '</style></head><body><div class="wrap"><h1>Приложение робота Курултая</h1>'
        '<p class="kk">Құрылтай роботының қосымшасы</p>' + body + "</div></body></html>"
    )
    return HTMLResponse(doc, status_code=status, headers=_HEADERS)


def _form(error: str = "", status: int = 200) -> HTMLResponse:
    err = f'<p class="err">{html.escape(error)}</p>' if error else ""
    return _page(
        '<form class="card" method="post" action="/app">'
        + err
        + '<label for="p">Пароль · Құпиясөз</label>'
        '<input id="p" name="password" type="password" autocomplete="current-password" autofocus required>'
        '<button type="submit">Войти · Кіру</button></form>',
        status,
    )


def _version(name: str) -> str:
    m = _VERSION_RE.search(name)
    return m.group(1) if m else ""


def _when(mtime: float) -> str:
    return (datetime.fromtimestamp(mtime, UTC) + _ASTANA).strftime("%d.%m.%Y %H:%M")


def _list_page(settings: Settings, apks: list[ApkFile]) -> HTMLResponse:
    if not apks:
        return _page(
            '<div class="card"><p class="name">Файлов пока нет · Файлдар әзірге жоқ</p></div>'
        )
    now = time.time()
    rows = []
    for i, a in enumerate(apks):
        tags = ""
        if i == 0:
            tags += '<span class="tag">новая · жаңа</span>'
        if "STABLE" in a.name.upper():
            tags += '<span class="tag">стабильная · тұрақты</span>'
        version = _version(a.name)
        rows.append(
            '<div class="card">'
            f'<p class="name">{html.escape(version or a.name)}{tags}</p>'
            f'<p class="meta">{html.escape(a.name)} · {a.size / 1_048_576:.1f} МБ · {_when(a.mtime)}</p>'
            f'<p class="meta">sha256 {a.sha256}</p>'
            f'<a class="btn" href="{html.escape(link(settings, a.name, now))}" download>'
            "Скачать · Жүктеу</a></div>"
        )
    minutes = settings.kurultai_app_link_ttl_s // 60
    howto = (
        '<div class="card"><ol>'
        "<li>Нажмите «Скачать», затем откройте файл и нажмите «Установить».</li>"
        "<li>Если Android спросит — разрешите установку приложений из этого браузера.</li>"
        "<li>Новая версия ставится поверх старой. Чтобы поставить версию старше установленной, "
        "сначала удалите приложение.</li>"
        "<li>После установки запускайте приложение через RobotOS Home.</li>"
        f"<li>Ссылки действуют {minutes} минут; потом снова введите пароль.</li>"
        "</ol></div>"
    )
    return _page("".join(rows) + howto)


# ---------- маршруты ----------
@router.get("/app", include_in_schema=False)
async def app_page(request: Request) -> Response:
    if not enabled(_settings(request)):
        return _not_found()
    return _form()


@router.post("/app", include_in_schema=False)
async def app_login(request: Request, password: str = Form("", max_length=200)) -> Response:
    settings = _settings(request)
    if not enabled(settings):
        return _not_found()
    ip, now = _ip(request, settings), time.monotonic()
    if _failures.blocked(ip, now):
        return _form("Слишком много попыток. Подождите 15 минут.", 429)
    expected = secret_text(settings.kurultai_app_password)
    if not secrets.compare_digest(password.strip().encode(), expected.encode()):
        _failures.add(ip, now)
        logger.warning("kurultai_app_wrong_password", extra={"client_ip": ip})
        return _form("Неверный пароль · Құпиясөз қате", 401)
    apks = list_apks(settings.kurultai_app_dir)
    logger.info("kurultai_app_list", extra={"client_ip": ip, "files": len(apks)})
    return _list_page(settings, apks)


@router.get("/app/d/{exp}/{sig}/{name}", include_in_schema=False)
async def app_download(request: Request, exp: int, sig: str, name: str) -> Response:
    settings = _settings(request)
    if not enabled(settings) or not _NAME_RE.match(name):
        return _not_found()
    if exp < time.time() or not hmac.compare_digest(sig, sign(settings, name, exp)):
        return _not_found()
    directory = settings.kurultai_app_dir
    path = directory / name
    if not path.is_file() or path.resolve().parent != directory.resolve():
        return _not_found()
    logger.info("kurultai_app_download", extra={"client_ip": _ip(request, settings), "file": name})
    return FileResponse(
        path,
        media_type=APK_MEDIA_TYPE,
        filename=name,
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
    )
