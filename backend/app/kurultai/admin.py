"""Админка материалов Курултая: сотрудники аппарата сами загружают сведения для робота (`/app/admin`).

Вход — пароль KURULTAI_ADMIN_PASSWORD (пусто — админка выключена, 404). После входа — подписанная cookie на
KURULTAI_ADMIN_SESSION_S; 8 неверных паролей за 15 минут с адреса — пауза. Запросы API — только с заголовком
X-Kurultai-Admin (cookie SameSite=Strict + свой заголовок: чужой сайт не отправит запрос от имени сотрудника).

Путь /app/* уже проброшен в Caddy Курултая; лимит тела загрузки — в блоке Caddy (docs/kurultai/caddy-admin-upload.sh).
"""

from __future__ import annotations

import hashlib
import hmac
import time
import uuid
from datetime import date
from pathlib import Path
from typing import Annotated, Any

from fastapi import APIRouter, File, Form, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, Field

from app.config import Settings, secret_text
from app.kurultai import download
from app.kurultai.materials import extract as ex
from app.kurultai.materials.store import MaterialsStore
from app.logging_setup import logger
from app.profiles import KURULTAI
from app.schemas import ChatRequest

router = APIRouter(prefix="/app/admin")

COOKIE = "k_admin"
CSRF_HEADER = "x-kurultai-admin"
MAX_FILES = 20
PAGE = Path(__file__).with_name("admin_page.html")
_HEADERS = {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
    "connect-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'",
}
_failures = download._Failures()


# ---------- вход ----------
def _settings(request: Request) -> Settings:
    return request.app.state.settings


def enabled(request: Request) -> bool:
    return (
        bool(secret_text(_settings(request).kurultai_admin_password))
        and _store(request) is not None
    )


def _store(request: Request) -> MaterialsStore | None:
    return getattr(request.app.state, "kurultai_materials", None)


def _key(settings: Settings) -> bytes:
    material = (
        "kurultai-admin\0"
        + secret_text(settings.kurultai_admin_password)
        + "\0"
        + secret_text(settings.kurultai_robot_token)
    )
    return hashlib.sha256(material.encode()).digest()


def session_cookie(settings: Settings, now: float | None = None) -> str:
    exp = int((now if now is not None else time.time()) + settings.kurultai_admin_session_s)
    sig = hmac.new(_key(settings), f"admin/{exp}".encode(), hashlib.sha256).hexdigest()
    return f"{exp}.{sig}"


def _authed(request: Request) -> bool:
    raw = request.cookies.get(COOKIE, "")
    exp_s, _, sig = raw.partition(".")
    if not exp_s.isdigit() or int(exp_s) < time.time():
        return False
    good = hmac.new(_key(_settings(request)), f"admin/{exp_s}".encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(sig, good)


def _ip(request: Request) -> str:
    return download._ip(request, _settings(request))


def _guard(request: Request) -> Response | None:
    """API: админка включена, вход выполнен, заголовок против подделки запроса."""
    if not enabled(request):
        return Response(status_code=404)
    if not _authed(request):
        return JSONResponse({"error": "Войдите заново"}, status_code=401, headers=_HEADERS)
    if request.method != "GET" and request.headers.get(CSRF_HEADER) != "1":
        return JSONResponse({"error": "Запрос отклонён"}, status_code=403, headers=_HEADERS)
    return None


def _login_page(error: str = "", status: int = 200) -> HTMLResponse:
    body = (
        '<form class="card" method="post" action="/app/admin/login">'
        + (f'<p class="err">{error}</p>' if error else "")
        + '<label for="p">Пароль администратора</label>'
        '<input id="p" name="password" type="password" autocomplete="current-password" autofocus required>'
        '<button type="submit">Войти</button></form>'
    )
    doc = (
        '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ақылтай · материалы</title>'
        "<style>"
        + download._STYLE
        + '</style></head><body><div class="wrap"><h1>Материалы для робота</h1>'
        '<p class="kk">Аппарат Курултая · Ақылтай</p>' + body + "</div></body></html>"
    )
    return HTMLResponse(doc, status_code=status, headers=_HEADERS)


@router.get("", include_in_schema=False)
async def admin_page(request: Request) -> Response:
    if not enabled(request):
        return Response(status_code=404)
    if not _authed(request):
        return _login_page()
    return HTMLResponse(PAGE.read_text(encoding="utf-8"), headers=_HEADERS)


@router.post("/login", include_in_schema=False)
async def admin_login(request: Request, password: str = Form("", max_length=200)) -> Response:
    if not enabled(request):
        return Response(status_code=404)
    settings, ip, now = _settings(request), _ip(request), time.monotonic()
    if _failures.blocked(ip, now):
        return _login_page("Слишком много попыток. Подождите 15 минут.", 429)
    if not hmac.compare_digest(
        password.strip().encode(), secret_text(settings.kurultai_admin_password).encode()
    ):
        _failures.add(ip, now)
        logger.warning("kurultai_admin_wrong_password", extra={"client_ip": ip})
        return _login_page("Неверный пароль", 401)
    logger.info("kurultai_admin_login", extra={"client_ip": ip})
    resp = RedirectResponse("/app/admin", status_code=303, headers={"Cache-Control": "no-store"})
    resp.set_cookie(
        COOKIE,
        session_cookie(settings),
        max_age=settings.kurultai_admin_session_s,
        path="/app/admin",
        httponly=True,
        secure=settings.kurultai_admin_cookie_secure,
        samesite="strict",
    )
    return resp


@router.post("/logout", include_in_schema=False)
async def admin_logout(request: Request) -> Response:
    resp = RedirectResponse("/app/admin", status_code=303)
    resp.delete_cookie(COOKIE, path="/app/admin")
    return resp


# ---------- API ----------
def _json(data: Any, status: int = 200) -> JSONResponse:
    return JSONResponse(data, status_code=status, headers=_HEADERS)


def _overview(store: MaterialsStore, settings: Settings) -> dict:
    stats = store.block_stats()
    return {
        "items": store.items(),
        "stats": {
            "chars": stats.chars,
            "budget": stats.budget,
            "included": len(stats.included),
            "skipped": len(stats.skipped),
            "expired": len(stats.expired),
        },
        "model": settings.kurultai_materials_model,
        "upload_mb": settings.kurultai_materials_upload_mb,
        "formats": sorted(ex.SUPPORTED),
    }


@router.get("/api/materials", include_in_schema=False)
async def list_materials(request: Request) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    return _json(_overview(store, _settings(request)))


@router.post("/api/materials", include_in_schema=False)
async def upload_materials(
    request: Request,
    files: Annotated[list[UploadFile] | None, File()] = None,
    text: str = Form("", max_length=ex.MAX_TEXT_CHARS),
    title: str = Form("", max_length=200),
) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store, settings, ip = _store(request), _settings(request), _ip(request)
    assert store is not None
    limit = settings.kurultai_materials_upload_mb * 1024 * 1024
    files = [f for f in (files or []) if f.filename]
    if len(files) > MAX_FILES:
        return _json({"error": f"Не больше {MAX_FILES} файлов за раз"}, 400)
    created: list[int] = []
    rejected: list[dict] = []
    for f in files:
        name = Path(f.filename or "file").name[:200]
        ext = Path(name).suffix.lower()
        if ext and ext not in ex.SUPPORTED:
            rejected.append({"file": name, "error": f"формат {ext} не поддерживается"})
            continue
        data = await f.read(limit + 1)
        if len(data) > limit:
            rejected.append(
                {"file": name, "error": f"больше {settings.kurultai_materials_upload_mb} МБ"}
            )
            continue
        if not data:
            rejected.append({"file": name, "error": "пустой файл"})
            continue
        mid = store.create(title=title if len(files) == 1 else "", filename=name, mime=f.content_type,
                           data=data, text=None, ip=ip)  # fmt: skip
        store.start_processing(mid)
        created.append(mid)
    if text.strip():
        mid = store.create(
            title=title, filename=None, mime="text/plain", data=None, text=text, ip=ip
        )
        store.start_processing(mid)
        created.append(mid)
    if not created and not rejected:
        return _json({"error": "Выберите файлы или вставьте текст"}, 400)
    logger.info(
        "kurultai_admin_upload",
        extra={"client_ip": ip, "files_created": len(created), "files_rejected": len(rejected)},
    )
    return _json({"created": created, "rejected": rejected}, 201 if created else 400)


def _id_or_404(request: Request, material_id: int) -> tuple[MaterialsStore, dict] | Response:
    store = _store(request)
    assert store is not None
    item = store.get(material_id)
    if item is None:
        return _json({"error": "Материал не найден"}, 404)
    return store, item


@router.get("/api/materials/{material_id}", include_in_schema=False)
async def get_material(request: Request, material_id: int) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    got = _id_or_404(request, material_id)
    return got if isinstance(got, Response) else _json(got[1])


class MaterialPatch(BaseModel):
    title: str | None = Field(default=None, max_length=200)
    kind: str | None = None
    brief: str | None = Field(default=None, max_length=200_000)
    event_date: date | None = None
    valid_until: date | None = None
    clear_event_date: bool = False
    clear_valid_until: bool = False


@router.patch("/api/materials/{material_id}", include_in_schema=False)
async def patch_material(request: Request, material_id: int, patch: MaterialPatch) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    fields = patch.model_dump(exclude_none=True, exclude={"clear_event_date", "clear_valid_until"})
    if patch.clear_event_date:
        fields["event_date"] = None
    if patch.clear_valid_until:
        fields["valid_until"] = None
    try:
        item = store.update(material_id, _ip(request), **fields)
    except ValueError as exc:
        return _json({"error": str(exc)}, 400)
    return _json(item) if item is not None else _json({"error": "Материал не найден"}, 404)


async def _publish(request: Request, material_id: int, published: bool) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    try:
        item = store.publish(material_id, published, _ip(request))
    except ValueError as exc:
        return _json({"error": str(exc)}, 409)
    return _json(item) if item is not None else _json({"error": "Материал не найден"}, 404)


@router.post("/api/materials/{material_id}/publish", include_in_schema=False)
async def publish_material(request: Request, material_id: int) -> Response:
    return await _publish(request, material_id, True)


@router.post("/api/materials/{material_id}/unpublish", include_in_schema=False)
async def unpublish_material(request: Request, material_id: int) -> Response:
    return await _publish(request, material_id, False)


@router.post("/api/materials/{material_id}/reprocess", include_in_schema=False)
async def reprocess_material(request: Request, material_id: int) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    if store.get(material_id) is None:
        return _json({"error": "Материал не найден"}, 404)
    if not store.mark_processing(material_id, _ip(request)):
        return _json({"error": "Материал уже обрабатывается"}, 409)
    store.start_processing(material_id)
    return _json(store.get(material_id), 202)


@router.delete("/api/materials/{material_id}", include_in_schema=False)
async def delete_material(request: Request, material_id: int) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    if not store.delete(material_id, _ip(request)):
        return _json({"error": "Материал не найден"}, 404)
    return _json({"deleted": material_id})


@router.get("/api/materials/{material_id}/file", include_in_schema=False)
async def material_file(request: Request, material_id: int) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    got = store.file(material_id)
    if got is None:
        return _json({"error": "Файла нет"}, 404)
    name, mime, data = got
    safe = (
        "".join(ch if ch.isascii() and (ch.isalnum() or ch in "._-") else "_" for ch in name)
        or "file"
    )
    return Response(
        data,
        media_type=mime,
        headers={
            "Content-Disposition": f"attachment; filename=\"{safe}\"; filename*=UTF-8''{_quote(name)}",
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
        },
    )


def _quote(name: str) -> str:
    from urllib.parse import quote

    return quote(name, safe="")


@router.get("/api/preview", include_in_schema=False)
async def preview(request: Request) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    stats = store.block_stats()
    return _json(
        {
            "block": store.prompt_block(),
            "chars": stats.chars,
            "budget": stats.budget,
            "included": stats.included,
            "skipped": stats.skipped,
            "expired": stats.expired,
        }
    )


@router.get("/api/events", include_in_schema=False)
async def events(request: Request) -> Response:
    if (denied := _guard(request)) is not None:
        return denied
    store = _store(request)
    assert store is not None
    return _json({"events": store.events(200)})


class AskBody(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    lang: str = "ru"


@router.post("/api/ask", include_in_schema=False)
async def ask(request: Request, body: AskBody) -> Response:
    """Пробный вопрос роботу: тот же агент Курултая, что отвечает голосом (с опубликованными материалами)."""
    if (denied := _guard(request)) is not None:
        return denied
    chat = request.app.state.chats.get(KURULTAI)
    if chat is None:
        return _json({"error": "Профиль Курултая выключен"}, 404)
    req = ChatRequest(
        question=body.question.strip(),
        lang="kk" if body.lang == "kk" else "ru",
        session_id="admin-" + uuid.uuid4().hex[:24],
    )
    started = time.perf_counter()
    try:
        resp = await chat.chat(req, "admin_" + uuid.uuid4().hex[:16])
    except Exception as exc:
        logger.exception("kurultai_admin_ask_failed")
        return _json({"error": f"Робот не ответил ({type(exc).__name__})"}, 502)
    return _json(
        {
            "answer": resp.answer,
            "lang": resp.lang,
            "ms": int((time.perf_counter() - started) * 1000),
        }
    )
