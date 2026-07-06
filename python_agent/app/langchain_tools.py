from __future__ import annotations

import json
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field, create_model

from .action_models import ActionStep, StepStatus, StepType, ToolName, ToolObservation
from .schemas import AskRequest
from .tool_catalog import LOW_RISK_TOOL_SPECS, TOOL_SPECS, ToolSpec
from .tool_executor import ToolExecutor


def build_langchain_tools(
    executor: ToolExecutor,
    request: AskRequest,
    *,
    low_risk_only: bool = True,
) -> list[StructuredTool]:
    """Expose ticket tools through LangChain while keeping our executor in control."""
    specs = LOW_RISK_TOOL_SPECS if low_risk_only else TOOL_SPECS
    return [build_langchain_tool(spec, executor, request) for spec in specs]


def build_langchain_tool(spec: ToolSpec, executor: ToolExecutor, request: AskRequest) -> StructuredTool:
    args_schema = build_args_schema(spec)

    async def run_tool(**kwargs: Any) -> str:
        step = ActionStep(
            id=f"lc_{spec.name}",
            type=StepType.TOOL,
            instruction=spec.description,
            tool=spec.name,
            args=dict(kwargs),
            status=StepStatus.PENDING,
        )
        observation = await executor.execute(step, request)
        return serialize_observation(observation)

    return StructuredTool.from_function(
        coroutine=run_tool,
        name=str(spec.name),
        description=spec.description,
        args_schema=args_schema,
        return_direct=False,
    )


def build_args_schema(spec: ToolSpec) -> type[BaseModel]:
    fields: dict[str, tuple[type, Field]] = {}
    for name, raw_type in spec.parameters.items():
        field_type = python_type_for_tool_param(raw_type)
        fields[name] = (
            field_type,
            Field(..., description=f"{name} for {spec.name}"),
        )
    model_name = "".join(part.capitalize() for part in str(spec.name).split("_")) + "Input"
    return create_model(model_name, **fields)


def serialize_observation(observation: ToolObservation) -> str:
    return json.dumps(
        {
            "toolName": observation.tool_name,
            "success": observation.success,
            "code": observation.code,
            "message": observation.message,
            "data": observation.data,
            "terminal": observation.terminal,
            "retryable": observation.retryable,
        },
        ensure_ascii=False,
        default=str,
    )


def langchain_tool_schema(*, low_risk_only: bool = True) -> list[dict[str, Any]]:
    tools = []
    specs = LOW_RISK_TOOL_SPECS if low_risk_only else TOOL_SPECS
    for spec in specs:
        schema = build_args_schema(spec).model_json_schema()
        tools.append(
            {
                "name": str(spec.name),
                "description": spec.description,
                "args_schema": schema,
                "risk_level": spec.risk_level,
                "side_effect": spec.side_effect,
            }
        )
    return tools


def tool_calling_schema(*, low_risk_only: bool = False) -> list[dict[str, Any]]:
    specs = LOW_RISK_TOOL_SPECS if low_risk_only else TOOL_SPECS
    tools = []
    for spec in specs:
        schema = build_args_schema(spec).model_json_schema()
        schema.pop("title", None)
        tools.append(
            {
                "type": "function",
                "function": {
                    "name": str(spec.name),
                    "description": spec.description,
                    "parameters": {
                        "type": "object",
                        "properties": schema.get("properties", {}),
                        "required": schema.get("required", []),
                        "additionalProperties": False,
                    },
                },
            }
        )
    return tools


def python_type_for_tool_param(raw_type: Any) -> type:
    normalized = str(raw_type).lower()
    if normalized in {"int", "integer"}:
        return int
    if normalized in {"float", "number"}:
        return float
    if normalized in {"bool", "boolean"}:
        return bool
    return str
