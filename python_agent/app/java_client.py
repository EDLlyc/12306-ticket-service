from typing import Any

import httpx

from .schemas import JavaResult
from .settings import Settings


class JavaTicketClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.java_ticket_base_url.rstrip("/"),
            timeout=httpx.Timeout(20.0, connect=5.0),
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def health(self) -> dict[str, Any]:
        try:
            response = await self._client.get("/train/all")
            return {
                "reachable": response.status_code < 500,
                "status_code": response.status_code,
            }
        except httpx.HTTPError as exc:
            return {"reachable": False, "error": str(exc)}

    async def login(self, username: str) -> JavaResult:
        return await self._request("POST", "/user/login", params={"username": username})

    async def check_login(self, token: str) -> JavaResult:
        return await self._request("GET", "/user/check", params={"token": token})

    async def list_trains(self) -> Any:
        response = await self._client.get("/train/all")
        response.raise_for_status()
        return response.json()

    async def query_train(self, train_number: str) -> JavaResult:
        return await self._request("GET", "/train/query", params={"trainNumber": train_number})

    async def book_ticket(self, train_number: str, token: str) -> JavaResult:
        return await self._request(
            "POST",
            "/train/book/lua",
            token=token,
            params={"trainNumber": train_number},
        )

    async def query_orders(self, token: str) -> JavaResult:
        return await self._request("GET", "/user/orders", token=token)

    async def refund_order(self, order_sn: str, token: str) -> JavaResult:
        return await self._request(
            "POST",
            "/user/refund",
            token=token,
            params={"orderSn": order_sn},
        )

    async def _request(
        self,
        method: str,
        path: str,
        *,
        token: str | None = None,
        params: dict[str, Any] | None = None,
    ) -> JavaResult:
        headers = {}
        if token:
            headers[self._settings.java_ticket_token_header] = token

        response = await self._client.request(method, path, params=params, headers=headers)
        response.raise_for_status()
        payload = response.json()
        return JavaResult.model_validate(payload)
