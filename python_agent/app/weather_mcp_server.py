from __future__ import annotations

from datetime import date
import json
from typing import Any

import httpx
from fastapi import FastAPI, Request

from .settings import get_settings


app = FastAPI(title="12306 Weather MCP Server", version="0.1.0")

CITY_COORDINATES: dict[str, tuple[float, float]] = {
    "北京": (39.9042, 116.4074),
    "上海": (31.2304, 121.4737),
    "南京": (32.0603, 118.7969),
    "杭州": (30.2741, 120.1551),
    "广州": (23.1291, 113.2644),
    "深圳": (22.5431, 114.0579),
    "成都": (30.5728, 104.0668),
    "武汉": (30.5928, 114.3055),
    "西安": (34.3416, 108.9398),
    "合肥": (31.8206, 117.2272),
    "天津": (39.3434, 117.3616),
    "重庆": (29.563, 106.5516),
}

WEATHER_CODE_TEXT = {
    0: "晴",
    1: "大部晴朗",
    2: "局部多云",
    3: "阴",
    45: "雾",
    48: "雾凇",
    51: "小毛毛雨",
    53: "毛毛雨",
    55: "较强毛毛雨",
    61: "小雨",
    63: "中雨",
    65: "大雨",
    71: "小雪",
    73: "中雪",
    75: "大雪",
    80: "阵雨",
    81: "较强阵雨",
    82: "强阵雨",
    95: "雷雨",
    96: "雷雨伴冰雹",
    99: "强雷雨伴冰雹",
}


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "weather_mcp"}


@app.post("/mcp")
async def mcp_endpoint(request: Request) -> dict[str, Any]:
    payload = await request.json()
    request_id = payload.get("id")
    method = str(payload.get("method") or "")
    try:
        if method == "tools/list":
            return json_rpc_result(request_id, {"tools": [weather_tool_schema()]})
        if method == "tools/call":
            params = payload.get("params") if isinstance(payload.get("params"), dict) else {}
            return json_rpc_result(request_id, await call_tool(params))
        return json_rpc_error(request_id, -32601, f"Method not found: {method}")
    except ValueError as exc:
        return json_rpc_error(request_id, -32602, str(exc))
    except Exception as exc:
        return json_rpc_error(request_id, -32000, str(exc))


async def call_tool(params: dict[str, Any]) -> dict[str, Any]:
    name = str(params.get("name") or "")
    arguments = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
    if name != "get_weather_by_city":
        raise ValueError(f"Unknown tool: {name}")
    city = str(arguments.get("city") or "").strip()
    travel_date = str(arguments.get("date") or date.today().isoformat()).strip()
    if not city:
        raise ValueError("city is required")
    report = await get_weather_by_city(city, travel_date)
    return {
        "content": [
            {
                "type": "text",
                "text": json.dumps(report, ensure_ascii=False),
            }
        ],
        "structuredContent": report,
    }


async def get_weather_by_city(city: str, travel_date: str) -> dict[str, Any]:
    normalized_city = normalize_city(city)
    if normalized_city not in CITY_COORDINATES:
        raise ValueError(f"Unsupported city: {city}")
    latitude, longitude = CITY_COORDINATES[normalized_city]
    raw = await fetch_open_meteo_weather(latitude, longitude, travel_date)
    daily = raw.get("daily") if isinstance(raw.get("daily"), dict) else {}
    weather_code = first_value(daily.get("weather_code"), 3)
    min_temp = first_value(daily.get("temperature_2m_min"), None)
    max_temp = first_value(daily.get("temperature_2m_max"), None)
    wind_speed = first_value(daily.get("wind_speed_10m_max"), None)
    risk = classify_weather_risk(weather_code, wind_speed)
    weather = WEATHER_CODE_TEXT.get(int(weather_code), "未知")
    temperature = format_temperature(min_temp, max_temp)
    wind = format_wind(wind_speed)
    return {
        "city": normalized_city,
        "date": travel_date,
        "weather": weather,
        "temperature": temperature,
        "wind": wind,
        "risk": risk,
        "suggestion": suggestion_for_risk(risk),
        "provider": "open-meteo",
    }


async def fetch_open_meteo_weather(latitude: float, longitude: float, travel_date: str) -> dict[str, Any]:
    settings = get_settings()
    timeout = settings.weather_mcp_timeout_ms / 1000
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max",
        "timezone": "Asia/Shanghai",
        "start_date": travel_date,
        "end_date": travel_date,
    }
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.get("https://api.open-meteo.com/v1/forecast", params=params)
        response.raise_for_status()
        return response.json()


def weather_tool_schema() -> dict[str, Any]:
    return {
        "name": "get_weather_by_city",
        "description": "Get daily weather for a Chinese city and date for travel advice.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "city": {"type": "string", "description": "Chinese city name, such as 北京 or 上海"},
                "date": {"type": "string", "description": "Date in YYYY-MM-DD format"},
            },
            "required": ["city", "date"],
            "additionalProperties": False,
        },
    }


def normalize_city(city: str) -> str:
    cleaned = city.strip()
    for suffix in ("市", "站", "南站", "北站", "东站", "西站"):
        if cleaned.endswith(suffix) and len(cleaned) > len(suffix):
            cleaned = cleaned[: -len(suffix)]
            break
    return cleaned


def first_value(value: Any, fallback: Any) -> Any:
    if isinstance(value, list) and value:
        return value[0]
    return fallback


def format_temperature(min_temp: Any, max_temp: Any) -> str:
    if min_temp is None or max_temp is None:
        return "未知"
    return f"{round(float(min_temp))}-{round(float(max_temp))}C"


def format_wind(wind_speed: Any) -> str:
    if wind_speed is None:
        return "未知"
    speed = float(wind_speed)
    if speed >= 39:
        level = "较大"
    elif speed >= 20:
        level = "中等"
    else:
        level = "较小"
    return f"{level}，最大风速 {round(speed, 1)} km/h"


def classify_weather_risk(weather_code: Any, wind_speed: Any) -> str:
    code = int(weather_code)
    if wind_speed is not None and float(wind_speed) >= 39:
        return "wind"
    if code in {61, 63, 65, 80, 81, 82, 95, 96, 99}:
        return "rain"
    if code in {71, 73, 75}:
        return "snow"
    if code in {45, 48}:
        return "visibility"
    return "low"


def suggestion_for_risk(risk: str) -> str:
    if risk == "rain":
        return "有降雨风险，建议提前出门、携带雨具，并关注进出站接驳时间。"
    if risk == "snow":
        return "有降雪风险，建议关注列车运行公告并预留更多到站时间。"
    if risk == "wind":
        return "风力较大，建议预留换乘和进站缓冲时间。"
    if risk == "visibility":
        return "能见度可能偏低，建议关注道路接驳和车站公告。"
    return "天气风险较低，按正常出行节奏安排即可。"


def json_rpc_result(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def json_rpc_error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": message,
        },
    }
