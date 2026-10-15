# Технологический стек

**Статус: утверждён 2026-10-15** (шаг 1.2 в `docs/roadmap.md`).
Пункты с пометкой ⚠ — ещё уточняются с Astana Hub или по итогам спайков/eval.

## Android-приложение (`android-app/`)
| Слой | Выбор | Почему |
|---|---|---|
| Язык | Kotlin 2.x | SDK вендора на Kotlin, корутины в примерах |
| Сборка | Gradle (Kotlin DSL), AGP 8.x; Gradle запускается на JDK 17, байткод `jvmTarget = 11` | SDK требует Java 11 как target; AGP 8 сам требует JDK 17 |
| SDK робота | `com.orionstar.agent:sdk:0.4.12-SNAPSHOT` (AgentOS + RobotAPI внутри) | требование платформы |
| minSdk | 26; targetSdk — по версии Android на роботе (уточнить в шаге 2.1) | |
| UI | XML Views + ViewBinding, Material Components, одна Activity, альбомный киоск 1920×1080 | как в примерах вендора, консервативно и предсказуемо на прошивке робота |
| Архитектура | MVVM + `StateFlow`; интерфейсы `RobotGateway` / `VoiceGateway` / `CameraGateway` / `BackendApi` с Fake- и Real-реализациями; flavors `emulator` / `robot` | разработка без робота, железо подменяется заглушками |
| DI | ручной (`AppContainer`) | приложение маленькое, Hilt избыточен |
| Сеть | OkHttp + Retrofit + kotlinx.serialization; SSE/стриминг через OkHttp | стандарт, стриминг ответа для ≤ 3 с |
| Кеш | DataStore / JSON-файл (места, синонимы, частые вопросы) | офлайн-режим для `where_is` |
| Изображение | `YuvImage` → JPEG (≈ 80%, 640×480) | без доп. библиотек |
| Логи / тесты | Timber; JUnit 4 + MockK + Turbine; Espresso на эмуляторе | |

## Backend (`backend/`)
| Слой | Выбор | Почему |
|---|---|---|
| Язык / фреймворк | Python 3.12 + FastAPI + Pydantic v2, менеджер `uv` | лучшая экосистема для LLM/RAG, OpenAPI из коробки |
| LLM-слой | свой интерфейс `LLMProvider` (text / vision), реализации `GeminiProvider`, `DeepSeekProvider`; провайдер и модель — в `.env` | смена провайдера без изменения кода и контракта |
| LLM (текст) ⚠ | Gemini (Flash-класс) — основной; DeepSeek — альтернатива/резерв | выбор по eval 3.8 (качество RU/KZ/EN) и замеру 3.9 (задержка); context caching у Gemini для «база в контексте» |
| LLM (vision) | Gemini (мультимодальная модель) для `/look` | у DeepSeek нет vision-API |
| Поиск по базе | MVP: вся база в контексте (с context caching). Рост базы → Postgres + `pgvector` + полнотекст (гибрид), эмбеддинги мультиязычные (Gemini embeddings или `bge-m3`) | база хаба небольшая — «всё в контексте» проще и точнее RAG; переход без смены API |
| БД | PostgreSQL 16 (+ `pgvector` при необходимости) | факты, места, логи вопросов |
| Источник базы знаний | Google-таблица + папка Google Drive → синхронизация в Postgres | сотрудники редактируют без программиста, админку писать не нужно |
| Лёгкая админка | позже, если понадобится: SQLAdmin поверх FastAPI | |
| Авторизация робота | токен в заголовке `Authorization: Bearer`, HTTPS | ключи LLM остаются только на сервере |
| Тесты / eval | pytest + свой eval-скрипт по 50 вопросам | критерий приёмки ТЗ |

## Голос (решается в шаге 2.9 по итогам спайка)
| Вариант | Когда |
|---|---|
| A. Встроенный ASR/TTS AgentOS | если RU и KZ распознаются/озвучиваются приемлемо |
| B. Свой ASR/TTS через backend ⚠: Yandex SpeechKit или Azure Speech (оба умеют `ru`, `kk`, `en`) | если встроенный не умеет RU/KZ и AgentOS отдаёт сырой звук |
| C. Гибрид | встроенный для EN, свой для RU/KZ |

Ключи ASR/TTS — тоже только на сервере (робот шлёт аудио в backend, тот проксирует).

## Инфраструктура (`infra/`)
| Слой | Выбор | Почему |
|---|---|---|
| Хостинг | зарубежный VPS (по умолчанию Hetzner, EU), 2 vCPU / 4 GB | дёшево и просто; задержка до Астаны ~80–120 мс укладывается в бюджет ≤ 3 с |
| Запуск | Docker Compose: `api`, `postgres`, `caddy` | просто, одна машина |
| HTTPS | Caddy (автоматический Let's Encrypt) | ноль ручной настройки |
| CI/CD | GitHub Actions: lint/tests/eval, сборка APK (артефакт), деплой backend по тегу через SSH | репозиторий уже на GitHub |
| Секреты | GitHub Secrets → `.env` на сервере; `appId` и токен робота — `secrets.properties` вне git; `gitleaks` в CI | |
| Мониторинг | Uptime Kuma (аптайм), Sentry (ошибки backend + Android), алерты в Telegram | бесплатно / self-hosted |
| Доставка APK | `adb install` из CI/ноутбука; в эксплуатации — облачная панель OrionStar (ORM/EO) | |

## Открытые вопросы ⚠
1. Согласовать с Astana Hub отправку кадров камеры и текстов вопросов в Gemini/DeepSeek (зарубежные сервисы) и бюджет на LLM.
2. Основная текстовая модель (Gemini vs DeepSeek) — по eval 3.8 и замеру задержки 3.9.
3. Провайдер ASR/TTS для RU/KZ — после спайка 2.3–2.6.
