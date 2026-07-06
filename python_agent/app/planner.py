import re
from datetime import date, timedelta

from .action_models import ActionPlan, ActionStep, StepType, ToolName
from .schemas import AskRequest


TRAIN_NUMBER_PATTERN = re.compile(r"(?<![A-Za-z0-9])(?:G|D|C|Z|T|K|Y|L)\d{1,4}(?![A-Za-z0-9])", re.I)
ORDER_SN_PATTERN = re.compile(
    r"(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])"
)
DATE_PATTERN = re.compile(r"(?<!\d)(\d{4}-\d{2}-\d{2})(?!\d)")
ROUTE_FROM_TO_PATTERN = re.compile(r"从\s*([\u4e00-\u9fa5A-Za-z]{2,20})\s*(?:到|至)\s*([\u4e00-\u9fa5A-Za-z]{2,20})")
ROUTE_ARROW_PATTERN = re.compile(r"([\u4e00-\u9fa5A-Za-z]{2,20})\s*(?:->|→|到|至)\s*([\u4e00-\u9fa5A-Za-z]{2,20})")

BOOK_KEYWORDS = ("买票", "购票", "订票", "预订", "预定", "购买", "帮我买", "帮我订")
REFUND_KEYWORDS = ("退票", "退款", "取消订单", "撤单", "退掉", "不想要")
ORDER_QUERY_KEYWORDS = ("我的订单", "查订单", "订单列表", "购票记录", "我的车票", "订单状态")
TICKET_QUERY_KEYWORDS = ("余票", "票价", "时刻表", "车次", "列车", "发车", "到达", "几点", "查票")
LIST_ALL_TRAINS_KEYWORDS = ("所有车次", "全部车次", "全部列车", "所有列车", "全部火车", "所有火车")
TRAVEL_ADVICE_KEYWORDS = ("天气", "适合", "出行建议", "会不会受天气影响", "是否适合", "坐高铁", "出行风险")
STATION_SUFFIXES = (
    "会不会受天气影响",
    "适不适合坐高铁",
    "适合坐高铁",
    "是否适合",
    "出行建议",
    "坐高铁",
    "天气",
    "适合",
    "的余票",
    "余票",
    "票价",
    "时刻表",
    "发车时间",
    "到达时间",
    "的车票",
    "车票",
    "的票",
    "票",
    "有票",
    "有",
    "吗",
    "么",
)
STATION_PREFIXES = ("今天", "明天", "后天")


class ActionPlanner:
    def build_plan(self, request: AskRequest) -> ActionPlan:
        question = request.question.strip()
        train_number = extract_train_number(question)
        order_sn = extract_order_sn(question)
        route = extract_route(question)
        travel_date = extract_travel_date(question)

        if route and has_any(question, TRAVEL_ADVICE_KEYWORDS):
            return single_tool_plan(
                "travel_advice",
                ToolName.TRAVEL_ADVICE,
                "结合车次和天气生成出行建议",
                {
                    "date": travel_date,
                    "fromStation": route[0],
                    "toStation": route[1],
                },
            )

        if has_any(question, ORDER_QUERY_KEYWORDS):
            return single_tool_plan(
                "query_user_orders",
                ToolName.QUERY_ORDERS,
                "查询当前用户订单",
                {},
            )

        if has_any(question, REFUND_KEYWORDS):
            if order_sn:
                return single_tool_plan(
                    "refund_order",
                    ToolName.REFUND_ORDER,
                    "按订单号退票",
                    {"orderSn": order_sn},
                )
            if train_number:
                return single_tool_plan(
                    "refund_order_by_train",
                    ToolName.REFUND_ORDER_BY_TRAIN,
                    "按车次退最近一张可退订单",
                    {"trainNumber": train_number},
                )
            return respond_plan("need_refund_target", "请提供要退票的订单号，或说明要退哪趟车次。")

        if has_any(question, BOOK_KEYWORDS):
            if train_number:
                return single_tool_plan(
                    "book_ticket",
                    ToolName.BOOK_TICKET,
                    "按车次购票",
                    {"trainNumber": train_number},
                )
            if route:
                return single_tool_plan(
                    "book_ticket_by_route",
                    ToolName.BOOK_TICKET_BY_ROUTE,
                    "按路线选择有票车次并购票",
                    {
                        "date": travel_date,
                        "fromStation": route[0],
                        "toStation": route[1],
                    },
                )
            return respond_plan("need_booking_target", "请提供车次号，或说明出发地、目的地和日期。")

        if route and has_any(question, TICKET_QUERY_KEYWORDS):
            return single_tool_plan(
                "search_trains",
                ToolName.SEARCH_TRAINS,
                "按路线查询车次",
                {
                    "date": travel_date,
                    "fromStation": route[0],
                    "toStation": route[1],
                },
            )

        if has_any(question, LIST_ALL_TRAINS_KEYWORDS):
            return single_tool_plan(
                "list_all_trains",
                ToolName.LIST_TRAINS,
                "查询当前所有车次",
                {},
            )

        if train_number:
            return single_tool_plan(
                "query_train",
                ToolName.QUERY_TRAIN,
                "按车次查询余票和车次信息",
                {"trainNumber": train_number},
            )

        return respond_plan(
            "unsupported_or_policy_question",
            "请补充更明确的车次、路线、订单号，或直接提出政策问答问题。",
        )


def single_tool_plan(goal: str, tool: ToolName, instruction: str, args: dict[str, str | None]) -> ActionPlan:
    return ActionPlan(
        goal=goal,
        steps=[
            ActionStep(
                id="step_1",
                type=StepType.TOOL,
                tool=tool,
                instruction=instruction,
                args={key: value for key, value in args.items() if value},
            )
        ],
    )


def respond_plan(goal: str, answer: str) -> ActionPlan:
    return ActionPlan(
        goal=goal,
        steps=[
            ActionStep(
                id="respond",
                type=StepType.RESPOND,
                instruction=answer,
            )
        ],
    )


def extract_train_number(question: str) -> str | None:
    match = TRAIN_NUMBER_PATTERN.search(question)
    return match.group(0).upper() if match else None


def extract_order_sn(question: str) -> str | None:
    match = ORDER_SN_PATTERN.search(question)
    return match.group(0) if match else None


def extract_route(question: str) -> tuple[str, str] | None:
    for pattern in (ROUTE_FROM_TO_PATTERN, ROUTE_ARROW_PATTERN):
        match = pattern.search(question)
        if match:
            return clean_station(match.group(1)), clean_station(match.group(2))
    return None


def extract_travel_date(question: str) -> str:
    match = DATE_PATTERN.search(question)
    if match:
        return match.group(1)
    if "后天" in question:
        return (date.today() + timedelta(days=2)).isoformat()
    if "明天" in question:
        return (date.today() + timedelta(days=1)).isoformat()
    return date.today().isoformat()


def has_any(text: str, keywords: tuple[str, ...]) -> bool:
    return any(keyword in text for keyword in keywords)


def clean_station(station: str) -> str:
    cleaned = station.strip()
    for prefix in STATION_PREFIXES:
        if cleaned.startswith(prefix) and len(cleaned) > len(prefix):
            cleaned = cleaned[len(prefix) :].strip()
    changed = True
    while changed:
        changed = False
        for suffix in STATION_SUFFIXES:
            if cleaned.endswith(suffix) and len(cleaned) > len(suffix):
                cleaned = cleaned[: -len(suffix)].strip()
                changed = True
                break
    return cleaned.strip()
