"""Профиль kurultai: ИИ-помощник Курултая Республики Казахстан на том же сервере (app/profiles.py).

- prompts.py — персона (из ASSISTANT_INSTRUCTIONS приложения Курултая) в голосовом формате сервера;
- quryltai.py — снимок публичных API quryltai.kz раз в час, текст для системного промпта;
- websearch.py — нужен ли веб-поиск (новости, прошедшие события, расписание);
- agent.py — KurultaiChatService: агент /v1/chat и /v1/live без базы знаний, мест и тулов Astana Hub.
"""
