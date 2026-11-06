"""Скачивание APK робота Курултая со страницы /app (app/kurultai/download.py)."""

from __future__ import annotations

import hashlib
import os
import re
import time

import pytest
from pydantic import SecretStr

from app.kurultai import download

PASSWORD = "parlament-2026"
APK_NEW = "Kurultai-Ustem-v5.50-1cca2d5-20261106.apk"
APK_STABLE = "Kurultai-Ustem-v5.50-a0427c6-20261030-STABLE.apk"


@pytest.fixture(autouse=True)
def _fresh_failures(monkeypatch):
    monkeypatch.setattr(download, "_failures", download._Failures())


@pytest.fixture
def apk_dir(tmp_path):
    d = tmp_path / "apk"
    d.mkdir()
    (d / APK_STABLE).write_bytes(b"PK-stable")
    os.utime(d / APK_STABLE, (time.time() - 3600, time.time() - 3600))
    (d / APK_NEW).write_bytes(b"PK-new-build")
    (d / "notes.txt").write_text("не APK")
    (tmp_path / "secret.apk").write_bytes(b"outside")
    return d


@pytest.fixture
def app_client(make_client, apk_dir):
    def _make(password: str = PASSWORD, **overrides):
        return make_client(
            kurultai_robot_token=SecretStr("kurultai-token"),
            kurultai_app_password=SecretStr(password),
            kurultai_app_dir=apk_dir,
            **overrides,
        )

    return _make


def _links(html: str) -> list[str]:
    return re.findall(r'href="(/app/d/[^"]+)"', html)


def test_off_without_password(app_client):
    c = app_client(password="")
    assert c.get("/app").status_code == 404
    assert c.post("/app", data={"password": ""}).status_code == 404
    assert c.get(f"/app/d/9999999999/x/{APK_NEW}").status_code == 404


def test_form_needs_no_robot_token_and_has_no_files(app_client):
    r = app_client().get("/app")
    assert r.status_code == 200
    assert 'type="password"' in r.text
    assert APK_NEW not in r.text
    assert r.headers["cache-control"] == "no-store"
    assert "default-src 'none'" in r.headers["content-security-policy"]


def test_wrong_password_and_lockout(app_client):
    c = app_client()
    for _ in range(download.APP_MAX_FAILURES):
        r = c.post("/app", data={"password": "1234"})
        assert r.status_code == 401
        assert APK_NEW not in r.text
    r = c.post("/app", data={"password": PASSWORD})  # даже верный — после лимита ждать
    assert r.status_code == 429


def test_list_newest_first_and_download(app_client, apk_dir):
    c = app_client()
    r = c.post("/app", data={"password": f" {PASSWORD} "})
    assert r.status_code == 200
    assert r.text.index(APK_NEW) < r.text.index(APK_STABLE)
    assert "notes.txt" not in r.text and "secret.apk" not in r.text
    assert "5.50-1cca2d5-20261106" in r.text
    assert hashlib.sha256(b"PK-new-build").hexdigest() in r.text
    links = _links(r.text)
    assert len(links) == 2 and links[0].endswith(APK_NEW)
    # менеджер загрузок приходит без пароля и куки — хватает подписанной ссылки
    f = c.get(links[0])
    assert f.status_code == 200
    assert f.content == b"PK-new-build"
    assert f.headers["content-type"] == download.APK_MEDIA_TYPE
    assert APK_NEW in f.headers["content-disposition"]


def test_bad_expired_or_foreign_links(app_client, make_client, apk_dir):
    c = app_client()
    s = c.app.state.settings
    good = download.link(s, APK_NEW)
    exp, sig = good.split("/")[3:5]
    assert c.get(good).status_code == 200
    assert c.get(f"/app/d/{exp}/{'0' * 32}/{APK_NEW}").status_code == 404  # чужая подпись
    assert c.get(f"/app/d/{exp}/{sig}/{APK_STABLE}").status_code == 404  # подпись другого файла
    assert c.get(download.link(s, APK_NEW, now=time.time() - 10_000)).status_code == 404  # истекла
    assert c.get(download.link(s, "secret.apk")).status_code == 404  # нет в каталоге
    assert c.get(download.link(s, "missing.apk")).status_code == 404
    # другой пароль — старые ссылки не работают
    other = make_client(
        kurultai_robot_token=SecretStr("kurultai-token"),
        kurultai_app_password=SecretStr("другой-пароль"),
        kurultai_app_dir=apk_dir,
    )
    assert other.get(good).status_code == 404


def test_empty_dir(app_client, apk_dir):
    for p in apk_dir.iterdir():
        p.unlink()
    r = app_client().post("/app", data={"password": PASSWORD})
    assert r.status_code == 200
    assert _links(r.text) == []


def test_hub_routes_unchanged(app_client):
    c = app_client()
    assert c.get("/v1/health").status_code == 200
    assert c.get("/v1/places").status_code == 401  # /v1/* по-прежнему только с токеном
