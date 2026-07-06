

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .action_models import ToolName


@dataclass(frozen=True)
class ToolSpec:
    name: ToolName
    description: str
    parameters: dict[str, Any]
    risk_level: str = "low"
    side_effect: bool = False


TOOL_SPECS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name=ToolName.LIST_TRAINS,
        description="List all currently available trains.",
        parameters={},
    ),
    ToolSpec(
        name=ToolName.QUERY_TRAIN,
        description="Query a train by train number and return current ticket or schedule information.",
        parameters={"trainNumber": "string"},
    ),
    ToolSpec(
        name=ToolName.SEARCH_TRAINS,
        description="Search trains by date, departure station, and arrival station.",
        parameters={"date": "string", "fromStation": "string", "toStation": "string"},
    ),
    ToolSpec(
        name=ToolName.BOOK_TICKET,
        description="Book a ticket directly when the user provides a train number.",
        parameters={"trainNumber": "string"},
        risk_level="high",
        side_effect=True,
    ),
    ToolSpec(
        name=ToolName.BOOK_TICKET_BY_ROUTE,
        description="Book a ticket by route and date when the user does not provide a train number.",
        parameters={"date": "string", "fromStation": "string", "toStation": "string"},
        risk_level="high",
        side_effect=True,
    ),
    ToolSpec(
        name=ToolName.QUERY_ORDERS,
        description="Query the current user's order list.",
        parameters={},
    ),
    ToolSpec(
        name=ToolName.TRAVEL_ADVICE,
        description="Generate route travel advice by combining train availability with weather from an MCP weather tool.",
        parameters={"date": "string", "fromStation": "string", "toStation": "string"},
    ),
    ToolSpec(
        name=ToolName.REFUND_ORDER,
        description="Refund a ticket by order number.",
        parameters={"orderSn": "string"},
        risk_level="high",
        side_effect=True,
    ),
    ToolSpec(
        name=ToolName.REFUND_ORDER_BY_TRAIN,
        description="Refund the user's latest refundable order for a specified train number.",
        parameters={"trainNumber": "string"},
        risk_level="high",
        side_effect=True,
    ),
)


LOW_RISK_TOOL_SPECS: tuple[ToolSpec, ...] = tuple(tool for tool in TOOL_SPECS if tool.risk_level == "low")
HIGH_RISK_TOOL_SPECS: tuple[ToolSpec, ...] = tuple(tool for tool in TOOL_SPECS if tool.risk_level == "high")


def build_tool_catalog_prompt() -> str:
    lines = []
    for tool in TOOL_SPECS:
        lines.append(
            f"- {tool.name}: {tool.description} | params={tool.parameters} "
            f"| risk={tool.risk_level} | sideEffect={tool.side_effect}"
        )
    return "\n".join(lines)
