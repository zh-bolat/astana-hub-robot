# Astana Hub Robot

Голосовой помощник Astana Hub на роботе OrionStar GreetingBot Nova: отвечает на вопросы о хабе (RU / KZ / EN), подсказывает дорогу и проводит до точки, описывает увиденное камерой, встречает посетителей.

## Структура
| Папка | Что внутри |
|---|---|
| `android-app/` | Приложение на роботе (Kotlin, AgentOS SDK, RobotAPI) |
| `backend/` | Сервер: `/ask`, `/look`, RAG по базе знаний, LLM |
| `infra/` | ADB, сборка и установка APK, деплой сервера, CI |
| `docs/` | Контекст и решения (`context.md`), контракт API, документация вендора |

## Правила
- Секреты (API-ключи LLM, keystore, `actionRegistry.json` с `appId`, `.env`) — **никогда не коммитим**. Шаблоны — в `*.example`.
- Ключи LLM — только на сервере, не в APK.
- Изменения контракта робот ↔ сервер — сначала в `docs/api-contract.md`, потом в код.
- Работаем в ветках, в `main` — через pull request.
