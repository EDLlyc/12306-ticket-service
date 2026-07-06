import asyncio
from typing import Any

import httpx

from .action_models import ActionStep, ToolName, ToolObservation
from .java_client import JavaTicketClient
from .schemas import AskRequest
from .weather_mcp import WeatherMcpClient


class ToolExecutor:
    def __init__(self, java_client: JavaTicketClient, weather_mcp_client: WeatherMcpClient | None = None) -> None:
        self._java_client = java_client
        self._weather_mcp_client = weather_mcp_client

    async def execute(self, step: ActionStep, request: AskRequest) -> ToolObservation:
        if step.tool is None:
            return observation("system", False, "MISSING_TOOL", "执行步骤缺少工具名称。")

        try:
            if step.tool == ToolName.LIST_TRAINS:
                return await self._list_trains()
            if step.tool == ToolName.QUERY_TRAIN:
                return await self._query_train(step)
            if step.tool == ToolName.SEARCH_TRAINS:
                return await self._search_trains(step)
            if step.tool == ToolName.BOOK_TICKET:
                return await self._book_ticket(step, request)
            if step.tool == ToolName.BOOK_TICKET_BY_ROUTE:
                return await self._book_ticket_by_route(step, request)
            if step.tool == ToolName.QUERY_ORDERS:
                return await self._query_orders(request)
            if step.tool == ToolName.TRAVEL_ADVICE:
                return await self._travel_advice(step)
            if step.tool == ToolName.REFUND_ORDER:
                return await self._refund_order(step, request)
            if step.tool == ToolName.REFUND_ORDER_BY_TRAIN:
                return await self._refund_order_by_train(step, request)
        except httpx.HTTPStatusError as exc:
            return observation(step.tool, False, "JAVA_HTTP_ERROR", f"Java 票务服务返回异常: {exc.response.status_code}", retryable=True)
        except httpx.HTTPError as exc:
            return observation(step.tool, False, "JAVA_UNAVAILABLE", f"无法连接 Java 票务服务: {exc}", retryable=True)

        return observation(step.tool, False, "UNKNOWN_TOOL", f"未知工具: {step.tool}")

    async def _list_trains(self) -> ToolObservation:
        trains = await self._java_client.list_trains()
        if not trains:
            return observation(ToolName.LIST_TRAINS, False, "NO_TRAINS_FOUND", "当前没有可查询的车次。")
        return observation(
            ToolName.LIST_TRAINS,
            True,
            "TRAINS_FOUND",
            format_all_trains(trains),
            {"matchCount": len(trains), "trains": trains},
        )

    async def _query_train(self, step: ActionStep) -> ToolObservation:
        train_number = require_arg(step, "trainNumber")
        result = await self._java_client.query_train(train_number)
        return from_java_result(ToolName.QUERY_TRAIN, result.model_dump())

    async def _search_trains(self, step: ActionStep) -> ToolObservation:
        from_station = require_arg(step, "fromStation")
        to_station = require_arg(step, "toStation")
        trains = await self._java_client.list_trains()
        matched = [
            train for train in trains
            if station_matches(train, "startStation", from_station)
            and station_matches(train, "endStation", to_station)
        ]
        if not matched:
            return observation(
                ToolName.SEARCH_TRAINS,
                False,
                "NO_MATCHED_TRAINS",
                f"{step.args.get('date', '')} 从 {from_station} 到 {to_station} 暂无匹配车次。",
                {"fromStation": from_station, "toStation": to_station, "matchCount": 0},
            )
        message = format_train_list(step.args.get("date"), from_station, to_station, matched)
        return observation(
            ToolName.SEARCH_TRAINS,
            True,
            "TRAINS_FOUND",
            message,
            {"fromStation": from_station, "toStation": to_station, "matchCount": len(matched), "trains": matched},
        )

    async def _book_ticket(self, step: ActionStep, request: AskRequest) -> ToolObservation:
        token = await self._resolve_token(request)
        if not token:
            return observation(ToolName.BOOK_TICKET, False, "LOGIN_REQUIRED", "请先登录后再购票。")
        result = await self._java_client.book_ticket(require_arg(step, "trainNumber"), token)
        return from_java_result(ToolName.BOOK_TICKET, result.model_dump())

    async def _book_ticket_by_route(self, step: ActionStep, request: AskRequest) -> ToolObservation:
        token = await self._resolve_token(request)
        if not token:
            return observation(ToolName.BOOK_TICKET_BY_ROUTE, False, "LOGIN_REQUIRED", "请先登录后再购票。")

        from_station = require_arg(step, "fromStation")
        to_station = require_arg(step, "toStation")
        trains = await self._java_client.list_trains()
        candidates = [
            train for train in trains
            if station_matches(train, "startStation", from_station)
            and station_matches(train, "endStation", to_station)
            and available_stock(train) > 0
        ]
        if not candidates:
            return observation(
                ToolName.BOOK_TICKET_BY_ROUTE,
                False,
                "NO_DIRECT_TRAIN_AVAILABLE",
                f"{step.args.get('date', '')} 从 {from_station} 到 {to_station} 暂无可直接购买的有票车次。",
            )
        selected = sorted(candidates, key=lambda train: str(train.get("startTime") or ""))[0]
        train_number = str(selected.get("trainNumber"))
        result = await self._java_client.book_ticket(train_number, token)
        payload = result.model_dump()
        payload["selectedTrainNumber"] = train_number
        return from_java_result(ToolName.BOOK_TICKET_BY_ROUTE, payload, prefix=f"已为您选择车次 {train_number}。\n")

    async def _query_orders(self, request: AskRequest) -> ToolObservation:
        token = await self._resolve_token(request)
        if not token:
            return observation(ToolName.QUERY_ORDERS, False, "LOGIN_REQUIRED", "请先登录后再查询订单。")
        result = await self._java_client.query_orders(token)
        payload = result.model_dump()
        payload["messageText"] = format_orders(result.data)
        return from_java_result(ToolName.QUERY_ORDERS, payload)

    async def _travel_advice(self, step: ActionStep) -> ToolObservation:
        from_station = require_arg(step, "fromStation")
        to_station = require_arg(step, "toStation")
        travel_date = str(step.args.get("date") or "")
        trains = await self._java_client.list_trains()
        matched = [
            train for train in trains
            if station_matches(train, "startStation", from_station)
            and station_matches(train, "endStation", to_station)
        ]
        weather_client = self._weather_mcp_client
        if weather_client is None:
            return observation(
                ToolName.TRAVEL_ADVICE,
                False,
                "WEATHER_MCP_UNAVAILABLE",
                "天气 MCP 工具未配置，暂时无法生成综合出行建议。",
                retryable=True,
            )

        origin_weather, destination_weather = await asyncio.gather(
            weather_client.get_weather(from_station, travel_date),
            weather_client.get_weather(to_station, travel_date),
        )
        advice = format_travel_advice(
            travel_date,
            from_station,
            to_station,
            matched,
            origin_weather.data(),
            destination_weather.data(),
        )
        return observation(
            ToolName.TRAVEL_ADVICE,
            True,
            "TRAVEL_ADVICE_READY",
            advice,
            {
                "date": travel_date,
                "fromStation": from_station,
                "toStation": to_station,
                "matchCount": len(matched),
                "trains": matched[:5],
                "weather": {
                    "origin": origin_weather.data(),
                    "destination": destination_weather.data(),
                },
                "mcp": {
                    "tools": [
                        origin_weather.trace,
                        destination_weather.trace,
                    ]
                },
            },
        )

    async def _refund_order(self, step: ActionStep, request: AskRequest) -> ToolObservation:
        token = await self._resolve_token(request)
        if not token:
            return observation(ToolName.REFUND_ORDER, False, "LOGIN_REQUIRED", "请先登录后再退票。")
        result = await self._java_client.refund_order(require_arg(step, "orderSn"), token)
        return from_java_result(ToolName.REFUND_ORDER, result.model_dump())

    async def _refund_order_by_train(self, step: ActionStep, request: AskRequest) -> ToolObservation:
        token = await self._resolve_token(request)
        if not token:
            return observation(ToolName.REFUND_ORDER_BY_TRAIN, False, "LOGIN_REQUIRED", "请先登录后再退票。")
        train_number = require_arg(step, "trainNumber")
        orders_result = await self._java_client.query_orders(token)
        orders = orders_result.data if isinstance(orders_result.data, list) else []
        target = next(
            (
                order for order in orders
                if str(order.get("train_number") or order.get("trainNumber")).upper() == train_number
                and str(order.get("status", "")).upper() not in {"CANCELLED", "REFUNDED"}
            ),
            None,
        )
        if not target:
            return observation(
                ToolName.REFUND_ORDER_BY_TRAIN,
                False,
                "ORDER_NOT_FOUND",
                f"没有找到您名下车次 {train_number} 的可退订单。",
            )
        order_sn = str(target.get("order_sn") or target.get("orderSn"))
        result = await self._java_client.refund_order(order_sn, token)
        return from_java_result(ToolName.REFUND_ORDER_BY_TRAIN, result.model_dump(), prefix=f"已匹配订单 {order_sn}。\n")

    async def _resolve_token(self, request: AskRequest) -> str | None:
        if request.token:
            return request.token
        if not request.username:
            return None
        login_result = await self._java_client.login(request.username)
        return str(login_result.data) if login_result.data else None


def require_arg(step: ActionStep, key: str) -> str:
    value = step.args.get(key)
    if value is None or str(value).strip() == "":
        raise ValueError(f"步骤 {step.id} 缺少参数 {key}")
    return str(value).strip()


def from_java_result(tool_name: ToolName, payload: dict[str, Any], prefix: str = "") -> ToolObservation:
    code = int(payload.get("code", 500))
    data = payload.get("data")
    message = payload.get("messageText") or format_data(data) or str(payload.get("message") or "")
    return observation(
        tool_name,
        code == 200,
        "JAVA_SUCCESS" if code == 200 else f"JAVA_{code}",
        prefix + message,
        payload,
        retryable=code >= 500,
    )


def observation(
    tool_name: ToolName | str,
    success: bool,
    code: str,
    message: str,
    data: dict[str, Any] | list[Any] | str | None = None,
    retryable: bool = False,
) -> ToolObservation:
    return ToolObservation(
        tool_name=str(tool_name),
        success=success,
        code=code,
        message=message,
        data=data,
        retryable=retryable,
    )


def station_matches(train: dict[str, Any], field: str, station: str) -> bool:
    value = str(train.get(field) or "")
    return station in value or value in station


def available_stock(train: dict[str, Any]) -> int:
    for key in ("availableStock", "stock"):
        value = train.get(key)
        if isinstance(value, int):
            return value
        if value is not None and str(value).isdigit():
            return int(value)
    return 0


def format_train_list(travel_date: str | None, from_station: str, to_station: str, trains: list[dict[str, Any]]) -> str:
    header = f"查询结果（{travel_date or '当前'} {from_station} -> {to_station}）："
    lines = [header]
    for index, train in enumerate(trains, start=1):
        lines.append(
            f"{index}. {train.get('trainNumber')} 次，"
            f"{format_time(train.get('startTime'))} 发车 -> {format_time(train.get('endTime'))} 到达，"
            f"余票: {available_stock(train)} 张"
        )
    return "\n".join(lines)


def format_all_trains(trains: list[dict[str, Any]]) -> str:
    lines = ["当前可查询车次："]
    for index, train in enumerate(trains, start=1):
        lines.append(
            f"{index}. {train.get('trainNumber')} 次，"
            f"{train.get('startStation')} -> {train.get('endStation')}，"
            f"{format_time(train.get('startTime'))} 发车 -> {format_time(train.get('endTime'))} 到达，"
            f"余票: {available_stock(train)} 张"
        )
    return "\n".join(lines)


def format_orders(orders: Any) -> str:
    if not isinstance(orders, list) or not orders:
        return "暂无订单。"
    lines = ["您的订单："]
    for index, order in enumerate(orders, start=1):
        lines.append(
            f"{index}. 订单号: {order.get('order_sn') or order.get('orderSn')}，"
            f"车次: {order.get('train_number') or order.get('trainNumber')}，"
            f"状态: {order.get('status')}"
        )
    return "\n".join(lines)


def format_travel_advice(
    travel_date: str,
    from_station: str,
    to_station: str,
    trains: list[dict[str, Any]],
    origin_weather: dict[str, Any],
    destination_weather: dict[str, Any],
) -> str:
    lines = [f"出行建议（{travel_date or '当前'} {from_station} -> {to_station}）："]
    if trains:
        lines.append("可选车次：")
        for index, train in enumerate(trains[:3], start=1):
            lines.append(
                f"{index}. {train.get('trainNumber')} 次，"
                f"{format_time(train.get('startTime'))} 发车 -> {format_time(train.get('endTime'))} 到达，"
                f"余票: {available_stock(train)} 张"
            )
    else:
        lines.append("当前没有查到直接匹配的可展示车次，建议换一个日期或路线再试。")

    lines.append(
        f"出发地天气：{origin_weather.get('city', from_station)} {origin_weather.get('weather', '未知')}，"
        f"{origin_weather.get('temperature', '未知')}，{origin_weather.get('wind', '未知')}。"
    )
    lines.append(
        f"目的地天气：{destination_weather.get('city', to_station)} {destination_weather.get('weather', '未知')}，"
        f"{destination_weather.get('temperature', '未知')}，{destination_weather.get('wind', '未知')}。"
    )
    risk = choose_travel_risk(origin_weather, destination_weather)
    if risk == "low":
        lines.append("综合判断：天气风险较低，适合正常安排高铁出行，按常规时间到站即可。")
    elif risk == "rain":
        lines.append("综合判断：存在降雨风险，高铁本身通常受影响较小，但市内交通和进出站可能变慢，建议提前出门并携带雨具。")
    elif risk == "wind":
        lines.append("综合判断：存在大风风险，建议预留换乘和进站缓冲时间，并关注车站公告。")
    else:
        lines.append("综合判断：建议出发前再次确认天气预警、车站公告和列车状态。")
    return "\n".join(lines)


def choose_travel_risk(origin_weather: dict[str, Any], destination_weather: dict[str, Any]) -> str:
    risks = {str(origin_weather.get("risk") or ""), str(destination_weather.get("risk") or "")}
    if "wind" in risks:
        return "wind"
    if "rain" in risks:
        return "rain"
    if risks <= {"", "low"}:
        return "low"
    return "unknown"


def format_data(data: Any) -> str:
    if data is None:
        return ""
    if isinstance(data, str):
        return data
    if isinstance(data, list):
        return "\n".join(str(item) for item in data)
    return str(data)


def format_time(value: Any) -> str:
    if not value:
        return "未知时间"
    text = str(value)
    if "T" in text:
        return text.split("T", 1)[1][:5]
    return text[:5]
