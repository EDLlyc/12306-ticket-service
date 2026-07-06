import unittest
from unittest.mock import patch

import httpx

from python_agent.app.settings import Settings
from python_agent.app.weather_mcp import WeatherMcpClient
from python_agent.app.weather_mcp_server import app, classify_weather_risk, normalize_city


class WeatherMcpServerTest(unittest.IsolatedAsyncioTestCase):
    async def test_tools_list_exposes_weather_tool(self):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://weather-mcp") as client:
            response = await client.post(
                "/mcp",
                json={"jsonrpc": "2.0", "id": "list-1", "method": "tools/list"},
            )

        payload = response.json()
        self.assertEqual(payload["jsonrpc"], "2.0")
        self.assertEqual(payload["id"], "list-1")
        self.assertEqual(payload["result"]["tools"][0]["name"], "get_weather_by_city")

    async def test_tools_call_returns_structured_weather(self):
        forecast = {
            "daily": {
                "weather_code": [61],
                "temperature_2m_min": [23.4],
                "temperature_2m_max": [30.2],
                "wind_speed_10m_max": [12.0],
            }
        }
        with patch(
            "python_agent.app.weather_mcp_server.fetch_open_meteo_weather",
            return_value=forecast,
        ):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://weather-mcp") as client:
                response = await client.post(
                    "/mcp",
                    json={
                        "jsonrpc": "2.0",
                        "id": "call-1",
                        "method": "tools/call",
                        "params": {
                            "name": "get_weather_by_city",
                            "arguments": {"city": "上海", "date": "2026-07-06"},
                        },
                    },
                )

        payload = response.json()
        structured = payload["result"]["structuredContent"]
        self.assertEqual(structured["city"], "上海")
        self.assertEqual(structured["date"], "2026-07-06")
        self.assertEqual(structured["weather"], "小雨")
        self.assertEqual(structured["temperature"], "23-30C")
        self.assertEqual(structured["risk"], "rain")
        self.assertEqual(structured["provider"], "open-meteo")

    async def test_weather_mcp_client_can_call_remote_mcp_app(self):
        forecast = {
            "daily": {
                "weather_code": [0],
                "temperature_2m_min": [20.0],
                "temperature_2m_max": [28.0],
                "wind_speed_10m_max": [8.0],
            }
        }
        settings = Settings(weather_mcp_endpoint="http://weather-mcp/mcp")
        transport = httpx.ASGITransport(app=app)
        with patch(
            "python_agent.app.weather_mcp_server.fetch_open_meteo_weather",
            return_value=forecast,
        ):
            report = await WeatherMcpClient(settings, transport=transport).get_weather("北京", "2026-07-06")

        self.assertEqual(report.city, "北京")
        self.assertEqual(report.weather, "晴")
        self.assertEqual(report.risk, "low")
        self.assertEqual(report.trace["source"], "remote")
        self.assertEqual(report.trace["method"], "tools/call")

    def test_weather_helpers_normalize_and_classify(self):
        self.assertEqual(normalize_city("上海市"), "上海")
        self.assertEqual(normalize_city("北京站"), "北京")
        self.assertEqual(classify_weather_risk(61, 10), "rain")
        self.assertEqual(classify_weather_risk(0, 45), "wind")
        self.assertEqual(classify_weather_risk(0, 10), "low")


if __name__ == "__main__":
    unittest.main()
