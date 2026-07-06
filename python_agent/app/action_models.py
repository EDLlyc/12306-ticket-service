from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class StepType(StrEnum):
    TOOL = "TOOL"
    RESPOND = "RESPOND"


class StepStatus(StrEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class ToolName(StrEnum):
    LIST_TRAINS = "list_trains"
    QUERY_TRAIN = "query_train"
    SEARCH_TRAINS = "search_trains"
    BOOK_TICKET = "book_ticket"
    BOOK_TICKET_BY_ROUTE = "book_ticket_by_route"
    QUERY_ORDERS = "query_orders"
    REFUND_ORDER = "refund_order"
    REFUND_ORDER_BY_TRAIN = "refund_order_by_train"
    TRAVEL_ADVICE = "travel_advice"


@dataclass
class ActionStep:
    id: str
    type: StepType
    instruction: str
    tool: ToolName | None = None
    args: dict[str, Any] = field(default_factory=dict)
    status: StepStatus = StepStatus.PENDING
    observation: dict[str, Any] | None = None
    observation_detail: dict[str, Any] | None = None


@dataclass
class ActionPlan:
    goal: str
    steps: list[ActionStep]


@dataclass(frozen=True)
class ToolObservation:
    tool_name: str
    success: bool
    code: str
    message: str
    data: dict[str, Any] | list[Any] | str | None = None
    terminal: bool = True
    retryable: bool = False


@dataclass(frozen=True)
class AgentResult:
    answer: str
    plan: ActionPlan
    final_observation: ToolObservation | None = None
    replanned: bool = False
    fallback_used: bool = False
    replan_count: int = 0
    replan_reason_history: tuple[str, ...] = ()

    def trace(self) -> dict[str, Any]:
        return {
            "goal": self.plan.goal,
            "replanned": self.replanned,
            "fallbackUsed": self.fallback_used,
            "replanCount": self.replan_count,
            "replanReasonHistory": list(self.replan_reason_history),
            "steps": [
                {
                    "id": step.id,
                    "type": step.type,
                    "tool": step.tool,
                    "args": step.args,
                    "status": step.status,
                    "observation": step.observation,
                    "observationDetail": step.observation_detail,
                }
                for step in self.plan.steps
            ],
            "finalObservation": None
            if self.final_observation is None
            else {
                "toolName": self.final_observation.tool_name,
                "success": self.final_observation.success,
                "code": self.final_observation.code,
                "message": self.final_observation.message,
                "data": self.final_observation.data,
                "terminal": self.final_observation.terminal,
                "retryable": self.final_observation.retryable,
            },
        }
