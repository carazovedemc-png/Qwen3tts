# Что изменено

1. **Готовые голоса** — модель CustomVoice (Settings → переключатель «CustomVoice»), список в Studio → Voice и Voices → «Готовые голоса», фильтры по языку и полу.
2. **Клонирование из файла** — Voices → «Клонировать из аудиофайла» (mp3/m4a/ogg/wav/голосовые/видео, 3–30 с).
3. **Локальный API** — Settings → «Локальный API». OpenAI-совместимый `POST /v1/audio/speech`, ключ в Bearer/X-API-Key.

Код не компилировался на сервере (нет Android SDK) — первая сборка может выдать мелкие ошибки.
Сборка: залить в GitHub и запустить workflow «Android Release» (workflow_dispatch) либо `./gradlew :app:assembleDebug`.
