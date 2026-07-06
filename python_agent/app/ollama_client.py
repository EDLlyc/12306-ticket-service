from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

import httpx

from .logging_utils import get_logger, log_event
from .settings import Settings


class OllamaAuxiliaryModelClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._logger = get_logger("python_agent.ollama")
        self._current_model = settings.ollama_aux_model

    @property
    def enabled(self) -> bool:
        return self._settings.ollama_enabled

    @property
    def current_model(self) -> str:
        return self._current_model

    def set_current_model(self, model: str) -> str:
        normalized = (model or "").strip()
        if not normalized:
            raise ValueError("model must not be empty")
        self._current_model = normalized
        log_event(self._logger, "ollama_model_switched", model=normalized)
        return normalized

    def chat(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        temperature: float = 0.0,
        max_tokens: int | None = None,
        response_format: dict[str, Any] | str | None = None,
    ) -> str | None:
        if not self.enabled:
            return None

        try:
            request_body: dict[str, Any] = {
                "model": model or self.current_model,
                "stream": False,
                "options": {
                    "temperature": temperature,
                    "num_predict": max_tokens or self._settings.ollama_chat_max_tokens,
                },
                "messages": [
                    {"role": "system", "content": system_prompt or ""},
                    {"role": "user", "content": user_prompt or ""},
                ],
            }
            ollama_format = self._to_ollama_format(response_format)
            if ollama_format is not None:
                request_body["format"] = ollama_format

            response = httpx.post(
                f"{self._normalize_base_url()}/api/chat",
                headers={"Content-Type": "application/json"},
                json=request_body,
                timeout=httpx.Timeout(
                    connect=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                    read=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    write=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    pool=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                ),
            )
            response.raise_for_status()
            payload = response.json()
            content = ((payload or {}).get("message") or {}).get("content")
            if not content:
                log_event(self._logger, "ollama_empty_response", model=model or self.current_model)
                return None
            return str(content).strip() or None
        except Exception as exc:
            log_event(
                self._logger,
                "ollama_request_failed",
                model=model or self.current_model,
                error=str(exc),
            )
            return None

    def stream_chat(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        temperature: float = 0.0,
        max_tokens: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        if not self.enabled:
            return

        request_model = model or self.current_model
        content_chars = 0
        chunk_count = 0
        try:
            with httpx.stream(
                "POST",
                f"{self._normalize_base_url()}/api/chat",
                headers={"Content-Type": "application/json"},
                json={
                    "model": request_model,
                    "stream": True,
                    "options": {
                        "temperature": temperature,
                        "num_predict": max_tokens or self._settings.ollama_chat_max_tokens,
                    },
                    "messages": [
                        {"role": "system", "content": system_prompt or ""},
                        {"role": "user", "content": user_prompt or ""},
                    ],
                },
                timeout=httpx.Timeout(
                    connect=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                    read=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    write=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    pool=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                ),
            ) as response:
                response.raise_for_status()
                for line in response.iter_lines():
                    if not line:
                        continue
                    payload = json.loads(line)
                    message = (payload.get("message") or {}).get("content") or ""
                    done = bool(payload.get("done"))
                    if message:
                        chunk_count += 1
                        content_chars += len(message)
                        yield {
                            "type": "chunk",
                            "content": str(message),
                            "model": request_model,
                        }
                    if done:
                        log_event(
                            self._logger,
                            "ollama_stream_complete",
                            model=request_model,
                            chunkCount=chunk_count,
                            contentChars=content_chars,
                            doneReason=payload.get("done_reason"),
                            evalCount=payload.get("eval_count"),
                            promptEvalCount=payload.get("prompt_eval_count"),
                        )
                        yield {
                            "type": "done",
                            "model": request_model,
                            "chunk_count": chunk_count,
                            "content_chars": content_chars,
                            "done_reason": payload.get("done_reason"),
                        }
                        return
                if chunk_count == 0:
                    log_event(self._logger, "ollama_stream_empty_response", model=request_model)
                    yield {"type": "empty", "model": request_model}
        except Exception as exc:
            log_event(
                self._logger,
                "ollama_stream_failed",
                model=request_model,
                error=str(exc),
                chunkCount=chunk_count,
                contentChars=content_chars,
            )
            yield {
                "type": "error",
                "model": request_model,
                "error": str(exc),
                "chunk_count": chunk_count,
                "content_chars": content_chars,
            }

    def unload_model(self, model: str | None = None) -> bool:
        if not self.enabled:
            return False

        request_model = model or self.current_model
        try:
            response = httpx.post(
                f"{self._normalize_base_url()}/api/generate",
                headers={"Content-Type": "application/json"},
                json={
                    "model": request_model,
                    "keep_alive": 0,
                },
                timeout=httpx.Timeout(
                    connect=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                    read=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    write=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    pool=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                ),
            )
            response.raise_for_status()
            log_event(self._logger, "ollama_model_unloaded", model=request_model)
            return True
        except Exception as exc:
            log_event(self._logger, "ollama_model_unload_failed", model=request_model, error=str(exc))
            return False

    def list_models(self) -> list[str]:
        if not self.enabled:
            return []

        try:
            response = httpx.get(
                f"{self._normalize_base_url()}/api/tags",
                timeout=httpx.Timeout(
                    connect=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                    read=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    write=max(self._settings.ollama_read_timeout_ms / 1000.0, 1.0),
                    pool=max(self._settings.ollama_connect_timeout_ms / 1000.0, 0.5),
                ),
            )
            response.raise_for_status()
            payload = response.json() or {}
            models = payload.get("models") or []
            names = [str(item.get("name")).strip() for item in models if str(item.get("name") or "").strip()]
            return names
        except Exception as exc:
            log_event(self._logger, "ollama_list_models_failed", error=str(exc))
            return []

    def _normalize_base_url(self) -> str:
        normalized = self._settings.ollama_base_url.strip()
        return normalized[:-1] if normalized.endswith("/") else normalized

    @staticmethod
    def _to_ollama_format(response_format: dict[str, Any] | str | None) -> dict[str, Any] | str | None:
        if response_format is None:
            return None
        if isinstance(response_format, str):
            return response_format
        if response_format.get("type") == "json_object":
            return "json"
        return response_format
