from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from typing import Any
from uuid import uuid4

import httpx

from .settings import Settings


@dataclass(frozen=True)
class WeatherReport:
    city: str
    date: str
    weather: str
    temperature: str
    wind: str
    risk: str
    suggestion: str
    trace: dict[str, Any]

    def data(self) -> dict[str, Any]:
        return {
            "city": self.city,
            "date": self.date,
            "weather": self.weather,
            "temperature": self.temperature,
            "wind": self.wind,
            "risk": self.risk,
            "suggestion": self.suggestion,
            "mcpTrace": self.trace,
        }


class WeatherMcpClient:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._endpoint = settings.weather_mcp_endpoint
        self._tool_name = settings.weather_mcp_tool_name
        self._timeout = settings.weather_mcp_timeout_ms / 1000
        self._transport = transport

    async def get_weather(self, city: str, travel_date: str) -> WeatherReport:
        if self._endpoint:
            try:
                return await self._get_remote_weather(city, travel_date)
            except Exception as exc:
                return self._local_weather(city, travel_date, fallback_reason=str(exc))
        return self._local_weather(city, travel_date, fallback_reason="endpoint_not_configured")

    async def _get_remote_weather(self, city: str, travel_date: str) -> WeatherReport:
        request_id = str(uuid4())
        payload = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "tools/call",
            "params": {
                "name": self._tool_name,
                "arguments": {
                    "city": city,
                    "date": travel_date,
                },
            },
        }
        async with httpx.AsyncClient(timeout=self._timeout, transport=self._transport) as client:
            response = await client.post(self._endpoint, json=payload)
            response.raise_for_status()
        body = response.json()
        if body.get("error"):
            raise RuntimeError(str(body["error"]))
        report_payload = parse_mcp_tool_result(body.get("result"))
        return WeatherReport(
            city=str(report_payload.get("city") or city),
            date=str(report_payload.get("date") or travel_date),
            weather=str(report_payload.get("weather") or "未知"),
            temperature=str(report_payload.get("temperature") or "未知"),
            wind=str(report_payload.get("wind") or "未知"),
            risk=str(report_payload.get("risk") or "unknown"),
            suggestion=str(report_payload.get("suggestion") or "请以当地实时天气预警为准。"),
            trace={
                "protocol": "mcp",
                "transport": "json_rpc_http",
                "source": "remote",
                "endpoint": self._endpoint,
                "method": "tools/call",
                "toolName": self._tool_name,
                "requestId": request_id,
            },
        )

    def _local_weather(self, city: str, travel_date: str, *, fallback_reason: str) -> WeatherReport:
        profiles = (
            ("晴", "22-30C", "微风", "low", "天气条件较好，按正常出行节奏安排即可。"),
            ("多云", "21-29C", "东北风 2级", "low", "天气整体平稳，建议预留常规进站时间。"),
            ("小雨", "23-28C", "东南风 3级", "rain", "可能影响市内交通，建议提前出门并携带雨具。"),
            ("阵雨", "24-31C", "南风 3级", "rain", "有短时降雨风险，建议关注车站到达后的接驳时间。"),
            ("大风", "18-25C", "北风 5级", "wind", "风力较大，建议预留换乘和进站缓冲时间。"),
        )
        digest = hashlib.sha1(f"{city}:{travel_date}".encode("utf-8")).hexdigest()
        weather, temperature, wind, risk, suggestion = profiles[int(digest[:2], 16) % len(profiles)]
        return WeatherReport(
            city=city,
            date=travel_date,
            weather=weather,
            temperature=temperature,
            wind=wind,
            risk=risk,
            suggestion=suggestion,
            trace={
                "protocol": "mcp",
                "transport": "in_process_fallback",
                "source": "local_fallback",
                "method": "tools/call",
                "toolName": self._tool_name,
                "reason": fallback_reason,
            },
        )


def parse_mcp_tool_result(result: Any) -> dict[str, Any]:
    if isinstance(result, dict):
        structured = result.get("structuredContent") or result.get("structured_content")
        if isinstance(structured, dict):
            return structured
        content = result.get("content")
        if isinstance(content, list):
            for item in content:
                if not isinstance(item, dict):
                    continue
                text = item.get("text")
                if isinstance(text, str):
                    parsed = parse_json_object(text)
                    if parsed is not None:
                        return parsed
        if isinstance(result.get("data"), dict):
            return result["data"]
    return {}


def parse_json_object(text: str) -> dict[str, Any] | None:
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None
