from __future__ import annotations


import json
from typing import Any
import uuid

import httpx

from .ollama_client import OllamaAuxiliaryModelClient
from .logging_utils import get_logger, log_event
from .settings import Settings


class ZhipuLLMClient:
    JSON_OBJECT_RESPONSE_FORMAT: dict[str, str] = {"type": "json_object"}

    def __init__(self, settings: Settings, ollama_client: OllamaAuxiliaryModelClient | None = None) -> None:
        self._settings = settings
        self._ollama_client = ollama_client
        self._logger = get_logger("python_agent.llm")
        self._client = None
        if settings.zhipu_api_key:
            from zhipuai import ZhipuAI

            self._client = ZhipuAI(api_key=settings.zhipu_api_key)

    @property
    def enabled(self) -> bool:
        return self.cloud_enabled

    @property
    def cloud_enabled(self) -> bool:
        return self._client is not None

    @property
    def ollama_enabled(self) -> bool:
        return self._ollama_client is not None and self._ollama_client.enabled

    @property
    def lightweight_enabled(self) -> bool:
        return self.ollama_enabled or self.cloud_enabled

    def generate_text(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        model: str | None = None,
        response_format: dict[str, Any] | None = None,
    ) -> str | None:
        if self._client is None:
            return None

        request_model = model or self._settings.zhipu_agent_model
        try:
            request_body: dict[str, Any] = {
                "model": request_model,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            }
            if response_format is not None:
                request_body["response_format"] = response_format
            response = self._client.chat.completions.create(**request_body)
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_text_generation_failed",
                model=request_model,
                error=str(exc),
            )
            return None

        try:
            choices = getattr(response, "choices", None) or []
            if not choices:
                return None
            message = choices[0].message
            content = getattr(message, "content", None)
            if isinstance(content, str):
                return content.strip()
            if isinstance(content, list):
                parts: list[str] = []
                for item in content:
                    if isinstance(item, dict):
                        text = item.get("text")
                        if text:
                            parts.append(str(text))
                return "".join(parts).strip() or None
            return str(content).strip() if content else None
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_text_generation_parse_failed",
                model=request_model,
                error=str(exc),
            )
            return None

    def generate_agent_text(self, system_prompt: str, user_prompt: str) -> str | None:
        return self.generate_text(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=self._settings.zhipu_agent_model,
        )

    def generate_agent_tool_calls(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        tools: list[dict[str, Any]],
        tool_choice: str | dict[str, Any] = "auto",
    ) -> list[dict[str, Any]] | None:
        if self._client is None or not tools:
            return None

        request_model = self._settings.zhipu_agent_model
        try:
            response = self._client.chat.completions.create(
                model=request_model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                tools=tools,
                tool_choice=tool_choice,
            )
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_tool_calling_failed",
                model=request_model,
                error=str(exc),
            )
            return None

        try:
            choices = getattr(response, "choices", None) or []
            if not choices:
                return None
            message = choices[0].message
            raw_tool_calls = getattr(message, "tool_calls", None) or []
            parsed: list[dict[str, Any]] = []
            for raw_call in raw_tool_calls:
                function = getattr(raw_call, "function", None)
                name = getattr(function, "name", None) if function is not None else None
                arguments = getattr(function, "arguments", None) if function is not None else None
                if not name:
                    continue
                args = self._extract_json_payload(arguments) if isinstance(arguments, str) else arguments
                parsed.append(
                    {
                        "name": str(name),
                        "args": args if isinstance(args, dict) else {},
                    }
                )
            return parsed or None
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_tool_calling_parse_failed",
                model=request_model,
                error=str(exc),
            )
            return None

    def generate_policy_text(
        self,
        system_prompt: str,
        user_prompt: str,
        response_format: dict[str, Any] | None = None,
    ) -> str | None:
        return self.generate_text(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=self._settings.zhipu_policy_model,
            response_format=response_format,
        )

    def generate_fast_text(
        self,
        system_prompt: str,
        user_prompt: str,
        response_format: dict[str, Any] | None = None,
    ) -> str | None:
        if self.ollama_enabled:
            local = self._ollama_client.chat(
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                model=self._ollama_client.current_model,
                temperature=0.0,
                response_format=response_format,
            )
            if local:
                return local
        return self.generate_text(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=self._settings.zhipu_policy_fast_model,
            response_format=response_format,
        )

    def refine_query(self, user_prompt: str) -> str | None:
        system_prompt = (
            "你是一个专业的铁路客服检索问题改写器，你的目标是提升检索命中率，而不是扩展问题范围。"
            "必须严格遵守以下规则："
            "1. 保留原问题的核心主题、对象和意图，只做同义改写、错别字修正、口语转书面。"
            "2. 严禁主动补充用户没有明确提到的维度。"
            "3. 如果输入中带有【会话状态卡】，说明当前是追问。你必须结合这些状态信息，把当前追问改写成一个可独立检索的完整问题。"
            "4. 如果用户只是追问“再具体一点/展开讲讲”，只允许补全主语或承接上一轮主题，禁止改写成多维度清单式问题。"
            "5. 如果缺少足够历史信息，直接返回原问题，不要猜。"
            "6. 优先输出短句，避免使用“以及、包括、等、和”等并列扩写结构。"
            "7. 只输出重写后的问题文本，不要解释，不要加前缀。"
        )
        return self.generate_fast_text(system_prompt, user_prompt)

    def classify_context_dependency(self, *, question: str, state_card: str) -> dict[str, Any] | None:
        system_prompt = (
            "你是12306多轮对话中的上下文依赖判定器。"
            "判断当前问题是否必须依赖上一轮上下文才能正确理解。"
            "只输出JSON，格式为 {\"dependent\":true,\"reason\":\"...\"}。"
        )
        user_prompt = f"【会话状态卡】\n{state_card}\n【当前问题】{question}"
        raw = self.generate_fast_text(
            system_prompt,
            user_prompt,
            response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
        )
        payload = self._extract_json_payload(raw)
        if payload is None or "dependent" not in payload:
            return None
        payload["dependent"] = self._coerce_bool(payload.get("dependent"))
        return payload

    def classify_policy_complexity(self, *, question: str) -> dict[str, Any] | None:
        system_prompt = (
            "你是12306政策问答的复杂度判定器。"
            "判断一个政策问题是否需要拆成多个子问题分别检索。"
            "只输出JSON，格式为 {\"complex\":true,\"reason\":\"...\"}。"
        )
        user_prompt = f"【政策问题】{question}"
        raw = self.generate_fast_text(
            system_prompt,
            user_prompt,
            response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
        )
        payload = self._extract_json_payload(raw)
        if payload is None or "complex" not in payload:
            return None
        payload["complex"] = self._coerce_bool(payload.get("complex"))
        return payload

    def score_intent(self, *, question: str, context_dependency: dict[str, Any] | None = None) -> dict[str, Any] | None:
        system_prompt = (
            "你是一个铁路客服多专家路由评分器。"
            "请对同一个用户问题同时给 ACTION、TICKET、RAG、CHITCHAT 四类意图打分。"
            "你必须只输出一个 JSON 对象，禁止输出解释、Markdown、前后缀。"
            "JSON schema: "
            "{\"actionScore\":0.0,\"ticketScore\":0.0,\"policyScore\":0.0,\"chitchatScore\":0.0,"
            "\"recommended\":\"ACTION|TICKET|RAG|CHITCHAT|UNCERTAIN\",\"confidence\":0.0,"
            "\"reason\":\"<=20字简短原因\"}."
        )
        user_prompt = f"【当前问题】{question}"
        if context_dependency:
            user_prompt += f"\n【上下文依赖判定】{json.dumps(context_dependency, ensure_ascii=False)}"
        return self._extract_json_payload(
            self.generate_fast_text(
                system_prompt,
                user_prompt,
                response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
            )
        )

    def classify_policy_question(self, *, question: str) -> dict[str, Any] | None:
        system_prompt = (
            "你是一个铁路客服政策问题分类器。"
            "你的任务是判断用户问题是否应该进入铁路规章制度知识库问答链路。"
            "你必须只输出一个 JSON 对象，禁止输出解释、Markdown、前后缀。"
            "JSON schema: "
            "{\"label\":\"POLICY|NON_POLICY|UNCERTAIN\",\"confidence\":0.0,\"reason\":\"<=20字简短原因\"} "
            "POLICY 表示铁路规则、办理条件、限制、例外、证件要求、费用规则、乘车规定等制度性内容；"
            "NON_POLICY 表示执行动作、查订单、查余票、查车次或闲聊；"
            "UNCERTAIN 表示无法高置信判断。"
        )
        return self._extract_json_payload(
            self.generate_fast_text(
                system_prompt,
                question.strip(),
                response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
            )
        )

    def judge_policy_evidence(self, *, question: str, raw_contexts: list[str]) -> dict[str, Any] | None:
        context_block = (
            "[EMPTY_CONTEXT]"
            if not raw_contexts
            else "\n\n---\n\n".join(str(item).strip() for item in raw_contexts[:3] if str(item).strip())
        )
        system_prompt = (
            "你是一个铁路政策证据判定器。"
            "请判断给定参考片段是否足以证明当前问题属于铁路规章制度问答。"
            "你必须只输出一个 JSON 对象，禁止输出解释、Markdown、前后缀。"
            "JSON schema: "
            "{\"supported\":true,\"confidence\":0.0,\"reason\":\"<=20字简短原因\"} "
            "supported=true 表示这些片段明显在回答铁路政策、规则、办理条件、限制、收费、证件等制度性问题。"
        )
        user_prompt = f"【用户问题】{question.strip()}\n\n【参考片段】\n{context_block}"
        return self._extract_json_payload(
            self.generate_fast_text(
                system_prompt,
                user_prompt,
                response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
            )
        )

    def rerank_documents(self, *, query: str, documents: list[str], top_n: int) -> list[int] | None:
        if self._client is None or not documents or top_n <= 0:
            return None
        request_body = {
            "model": self._settings.zhipu_rerank_model,
            "query": query[:4096],
            "documents": [document[:4096] for document in documents],
            "top_n": min(top_n, len(documents)),
            "return_documents": True,
            "request_id": str(uuid.uuid4()),
        }
        try:
            response = httpx.post(
                "https://open.bigmodel.cn/api/paas/v4/rerank",
                headers={
                    "Authorization": f"Bearer {self._settings.zhipu_api_key}",
                    "Content-Type": "application/json",
                },
                json=request_body,
                timeout=12.0,
            )
            response.raise_for_status()
            payload = response.json()
            results = payload.get("results") or []
            indexes: list[int] = []
            seen: set[int] = set()
            for item in results:
                index = int(item.get("index", -1))
                if 0 <= index < len(documents) and index not in seen:
                    seen.add(index)
                    indexes.append(index)
            return indexes or None
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_rerank_failed",
                model=self._settings.zhipu_rerank_model,
                error=str(exc),
            )
            return None

    def embed_text(self, text: str) -> list[float] | None:
        if self._client is None:
            return None
        try:
            response = self._client.embeddings.create(
                input=text[:4096],
                model=self._settings.zhipu_embedding_model,
                dimensions=self._settings.zhipu_embedding_dimensions,
            )
            data = getattr(response, "data", None) or []
            if not data:
                return None
            vector = getattr(data[0], "embedding", None)
            return list(vector) if vector else None
        except Exception as exc:
            log_event(
                self._logger,
                "cloud_embedding_failed",
                model=self._settings.zhipu_embedding_model,
                error=str(exc),
            )
            return None

    def plan_policy_sub_queries(self, *, question: str, max_queries: int) -> list[str] | None:
        if self._client is None or max_queries <= 1:
            return None
        system_prompt = (
            "你是12306政策问答的检索规划器。"
            "你的任务是把复杂政策问题拆成2到3个可独立检索的子问题。"
            "如果原问题不复杂，就只返回原问题。"
            "只输出JSON，格式为 {\"subQueries\":[\"...\",\"...\"]}。"
        )
        user_prompt = (
            f"请拆解这个政策问题，最多返回 {max_queries} 个子问题。\n"
            f"【问题】{question}"
        )
        raw = self.generate_policy_text(
            system_prompt,
            user_prompt,
            response_format=self.JSON_OBJECT_RESPONSE_FORMAT,
        )
        if not raw:
            return None
        try:
            start = raw.find("{")
            end = raw.rfind("}")
            payload = json.loads(raw[start:end + 1] if start != -1 and end != -1 else raw)
            sub_queries = payload.get("subQueries") or []
            normalized = [str(item).strip() for item in sub_queries if str(item).strip()]
            return normalized[:max_queries] or None
        except Exception:
            lines = [line.strip("-* \n") for line in raw.splitlines()]
            normalized = [line for line in lines if len(line) >= 4]
            return normalized[:max_queries] or None

    @property
    def settings(self) -> Settings:
        return self._settings

    @staticmethod
    def _extract_json_payload(raw: str | None) -> dict[str, Any] | None:
        if not raw:
            return None
        try:
            start = raw.find("{")
            end = raw.rfind("}")
            payload = json.loads(raw[start:end + 1] if start != -1 and end != -1 else raw)
            return payload if isinstance(payload, dict) else None
        except Exception:
            return None

    @staticmethod
    def _coerce_bool(value: Any) -> bool:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            return value.strip().lower() in {"true", "1", "yes", "y"}
        return bool(value)
