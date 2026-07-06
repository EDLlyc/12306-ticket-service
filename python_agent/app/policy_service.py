from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from pathlib import Path
import re

from .document_ingestion import load_policy_document_chunks
from .hybrid_retriever import HybridRetriever, RetrievedChunk
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event
from .policy_complexity import PolicyComplexityClassifier
from .settings import Settings
from .stream_events import StreamEventCallback, emit_stream_event


POLICY_KEYWORDS = (
    "政策",
    "规则",
    "规章",
    "规定",
    "学生票",
    "儿童票",
    "退票费",
    "改签",
    "报销",
    "候补",
    "乘车",
    "身份证",
    "实名",
    "优惠票",
    "行李",
    "携带",
    "宠物",
    "发票",
    "高铁票",
    "火车票",
)

SECTION_SPLIT_PATTERN = re.compile(r"(?m)^##?\s+")
ARTICLE_SPLIT_PATTERN = re.compile(r"(?m)^第[一二三四五六七八九十百千零〇\d]+条\s+")
NOISE_WORDS = {
    "请问",
    "帮我",
    "一下",
    "一下吧",
    "讲讲",
    "说说",
    "介绍",
    "解释",
    "政策",
    "规则",
    "规定",
    "规章",
    "什么",
    "怎么",
    "如何",
    "吗",
    "呢",
}
TOPIC_EXPANSIONS = {
    "学生票": ("资质核验", "优惠票", "学生证", "优惠区间"),
    "儿童票": ("免费儿童", "儿童优惠票", "未满14周岁", "未满6周岁"),
    "退票费": ("退票", "退款", "不收退票费"),
    "改签": ("变更到站", "改签费", "开车前48小时"),
    "报销": ("报销凭证",),
    "实名": ("身份证件", "实名制",),
    "行李": ("携带品", "托运",),
    "宠物": ("导盲犬", "活动物",),
}
FOLLOW_UP_KEYWORDS = (
    "再具体一点",
    "具体一点",
    "详细一点",
    "展开讲讲",
    "展开说说",
    "继续",
    "然后呢",
    "为什么",
    "什么意思",
    "再说细一点",
    "讲清楚一点",
    "再详细一点",
    "这个呢",
    "那这个呢",
    "那学生票呢",
    "那儿童票呢",
)
SESSION_MEMORY_LIMIT = 6


@dataclass(frozen=True)
class PolicyChunk:
    title: str
    content: str


@dataclass(frozen=True)
class PolicyAnswer:
    answer: str
    contexts: list[str]
    effective_question: str
    trace: dict | None = None


@dataclass(frozen=True)
class PolicyTurn:
    original_question: str
    effective_question: str
    contexts: list[str]


@dataclass(frozen=True)
class PolicyQueryPlan:
    effective_question: str
    sub_queries: list[str]
    planner: str
    complexity: dict

    @property
    def is_complex(self) -> bool:
        return len(self.sub_queries) > 1


class PolicyService:
    def __init__(
        self,
        settings: Settings,
        llm_client: ZhipuLLMClient,
        complexity_classifier: PolicyComplexityClassifier | None = None,
    ) -> None:
        self._settings = settings
        self._llm_client = llm_client
        self._complexity_classifier = complexity_classifier or PolicyComplexityClassifier(llm_client)
        self._chunks = self._load_chunks(Path(settings.policy_rules_path))
        self._session_turns: dict[str, deque[PolicyTurn]] = {}
        self._logger = get_logger("python_agent.policy_service")

    def is_policy_question(self, question: str) -> bool:
        normalized = question.strip()
        return any(keyword in normalized for keyword in POLICY_KEYWORDS) or is_follow_up_question(normalized)

    def answer(self, session_id: str, question: str) -> PolicyAnswer:
        plan = self.build_query_plan(session_id, question)
        contexts = self._retrieve_planned(plan)
        if not contexts:
            answer = "当前知识库里没有检索到直接相关的铁路规则，请换个更具体的问法。"
            result = PolicyAnswer(answer, [], plan.effective_question, trace={"plannedRag": self._plan_trace(plan)})
            self._remember(session_id, question, result)
            return result
        answer = self._generate_answer(plan.effective_question, contexts)
        result = PolicyAnswer(
            answer=answer,
            contexts=contexts,
            effective_question=plan.effective_question,
            trace={"plannedRag": self._plan_trace(plan)},
        )
        self._remember(session_id, question, result)
        return result

    def answer_with_retriever(
        self,
        session_id: str,
        question: str,
        retriever: HybridRetriever,
        event_callback: StreamEventCallback | None = None,
    ) -> PolicyAnswer:
        plan = self.build_query_plan(session_id, question)
        emit_stream_event(event_callback, "planned_rag", self._plan_trace(plan))
        emit_stream_event(
            event_callback,
            "retrieval_started",
            {
                "planner": plan.planner,
                "subQueries": plan.sub_queries,
                "complexity": plan.complexity,
            },
        )
        contexts, retrieval_trace = self._retrieve_with_hybrid(plan, retriever)
        emit_stream_event(event_callback, "retrieval_finished", retrieval_trace)
        if not contexts:
            answer = "当前知识库里没有检索到直接相关的铁路规则，请换个更具体的问法。"
            result = PolicyAnswer(
                answer=answer,
                contexts=[],
                effective_question=plan.effective_question,
                trace={
                    "plannedRag": self._plan_trace(plan),
                    "retrieval": retrieval_trace,
                },
            )
            self._remember(session_id, question, result)
            return result
        answer = self._generate_answer(plan.effective_question, contexts)
        result = PolicyAnswer(
            answer=answer,
            contexts=contexts,
            effective_question=plan.effective_question,
            trace={
                "plannedRag": self._plan_trace(plan),
                "retrieval": retrieval_trace,
            },
        )
        self._remember(session_id, question, result)
        return result

    def retrieve_with_hybrid_plan(
        self,
        plan: PolicyQueryPlan,
        retriever: HybridRetriever,
    ) -> tuple[list[str], dict]:
        return self._retrieve_with_hybrid(plan, retriever)

    def generate_answer(self, question: str, contexts: list[str]) -> str:
        return self._generate_answer(question, contexts)

    def remember_answer(self, session_id: str, original_question: str, answer: PolicyAnswer) -> None:
        self._remember(session_id, original_question, answer)

    def plan_trace(self, plan: PolicyQueryPlan) -> dict:
        return self._plan_trace(plan)

    def build_query_plan(self, session_id: str, question: str) -> PolicyQueryPlan:
        effective_question = self._bridge_question(session_id, question)
        complexity = self._complexity_classifier.classify(effective_question)
        if not self._settings.planned_rag_enabled or not complexity.complex:
            plan = PolicyQueryPlan(
                effective_question=effective_question,
                sub_queries=[effective_question],
                planner="single",
                complexity=complexity.trace(),
            )
            log_event(self._logger, "policy_query_plan", sessionId=session_id, plan=self._plan_trace(plan))
            return plan
        llm_sub_queries = None
        if self._llm_cloud_enabled():
            llm_sub_queries = self._llm_client.plan_policy_sub_queries(
                question=effective_question,
                max_queries=self._settings.planned_rag_max_subqueries,
            )
        sub_queries = normalize_sub_queries(
            llm_sub_queries
            or heuristic_sub_queries(
                effective_question,
                max_queries=self._settings.planned_rag_max_subqueries,
            ),
            effective_question,
            self._settings.planned_rag_max_subqueries,
        )
        planner = "llm" if llm_sub_queries else "heuristic"
        if len(sub_queries) <= 1:
            planner = "single"
        plan = PolicyQueryPlan(
            effective_question=effective_question,
            sub_queries=sub_queries,
            planner=planner,
            complexity=complexity.trace(),
        )
        log_event(self._logger, "policy_query_plan", sessionId=session_id, plan=self._plan_trace(plan))
        return plan

    def _retrieve(self, question: str) -> list[str]:
        if not self._chunks:
            return []
        terms = extract_terms(question)
        scored: list[tuple[int, PolicyChunk]] = []
        for chunk in self._chunks:
            score = 0
            haystack = f"{chunk.title}\n{chunk.content}"
            for term in terms:
                if term and term in haystack:
                    score += 3 if term in chunk.title else 1
            if score > 0:
                scored.append((score, chunk))
        scored.sort(key=lambda item: item[0], reverse=True)
        return [format_chunk(chunk) for _, chunk in scored[: self._settings.policy_top_k]]

    def _retrieve_planned(self, plan: PolicyQueryPlan) -> list[str]:
        if len(plan.sub_queries) == 1:
            return self._retrieve(plan.effective_question)
        merged: dict[str, str] = {}
        for sub_query in plan.sub_queries:
            for context in self._retrieve(sub_query):
                merged.setdefault(context, context)
        return list(merged.values())[: self._settings.policy_top_k]

    def _retrieve_with_hybrid(self, plan: PolicyQueryPlan, retriever: HybridRetriever) -> tuple[list[str], dict]:
        if len(plan.sub_queries) == 1:
            retrieval = retriever.retrieve(plan.effective_question)
            contexts = self._render_retrieved_chunks(retrieval.chunks)
            return contexts, retrieval.trace

        retrieval_runs = []
        all_chunks: list[RetrievedChunk] = []
        for sub_query in plan.sub_queries:
            retrieval = retriever.retrieve(sub_query)
            retrieval_runs.append(
                {
                    "question": sub_query,
                    "trace": retrieval.trace,
                    "chunkCount": len(retrieval.chunks),
                }
            )
            all_chunks.extend(retrieval.chunks)
        merged_chunks = merge_retrieved_chunks(all_chunks)
        contexts = self._render_retrieved_chunks(merged_chunks)
        return contexts, {
            "mode": "planned_rag",
            "subQueries": retrieval_runs,
            "mergedChunkCount": len(merged_chunks),
            "selectedCount": len(contexts),
        }

    def _generate_answer(self, question: str, contexts: list[str]) -> str:
        if self._llm_cloud_enabled():
            system_prompt = (
                "你是12306铁路规章制度专家。"
                "你的回答必须严格基于提供的上下文。"
                "默认输出简洁、结构化答案，优先给3到5个要点。"
            )
            user_prompt = (
                f"【用户问题】{question}\n"
                f"【检索上下文】\n" + "\n\n".join(contexts)
            )
            answer = self._llm_client.generate_policy_text(system_prompt, user_prompt)
            if answer:
                return answer
        return build_fallback_policy_answer(question, contexts)

    def _bridge_question(self, session_id: str, question: str) -> str:
        normalized = question.strip()
        if not is_follow_up_question(normalized):
            return normalized
        previous = self._get_last_turn(session_id)
        if previous is None:
            return normalized
        topic_terms = extract_terms(previous.effective_question)
        if not topic_terms:
            return normalized
        topic_prefix = "、".join(topic_terms[:3])
        return f"{topic_prefix}：{normalized}"

    def _remember(self, session_id: str, original_question: str, answer: PolicyAnswer) -> None:
        turns = self._session_turns.setdefault(session_id, deque(maxlen=SESSION_MEMORY_LIMIT))
        turns.append(
            PolicyTurn(
                original_question=original_question,
                effective_question=answer.effective_question,
                contexts=answer.contexts,
            )
        )

    def _render_retrieved_chunks(self, chunks: list[RetrievedChunk]) -> list[str]:
        rendered: list[str] = []
        seen: set[str] = set()
        for chunk in chunks:
            context = f"【{chunk.title}】\n{chunk.content}"
            if context in seen:
                continue
            seen.add(context)
            rendered.append(context)
            if len(rendered) >= self._settings.policy_top_k:
                break
        return rendered

    @staticmethod
    def _plan_trace(plan: PolicyQueryPlan) -> dict:
        return {
            "enabled": plan.is_complex,
            "planner": plan.planner,
            "subQueries": plan.sub_queries,
            "complexity": plan.complexity,
        }

    def _get_last_turn(self, session_id: str) -> PolicyTurn | None:
        turns = self._session_turns.get(session_id)
        if not turns:
            return None
        return turns[-1]

    def _llm_cloud_enabled(self) -> bool:
        if hasattr(self._llm_client, "cloud_enabled"):
            return bool(getattr(self._llm_client, "cloud_enabled"))
        return bool(getattr(self._llm_client, "enabled", False))

    @staticmethod
    def _load_chunks(path: Path) -> list[PolicyChunk]:
        return [
            PolicyChunk(title=chunk.title, content=chunk.content)
            for chunk in load_policy_document_chunks(path)
        ]


def extract_terms(question: str) -> list[str]:
    normalized = question.strip()
    terms = [keyword for keyword in POLICY_KEYWORDS if keyword in normalized]
    for keyword, expansions in TOPIC_EXPANSIONS.items():
        if keyword in normalized:
            terms.extend(expansions)
    for word in re.split(r"[\s，。；、：,:？?！!（）()]+", normalized):
        word = word.strip()
        if len(word) >= 2 and word not in NOISE_WORDS:
            terms.append(word)
    deduped: list[str] = []
    seen: set[str] = set()
    for term in terms:
        if term not in seen:
            seen.add(term)
            deduped.append(term)
    return deduped


def format_chunk(chunk: PolicyChunk) -> str:
    return f"【{chunk.title}】\n{chunk.content}"


def build_fallback_policy_answer(question: str, contexts: list[str]) -> str:
    lines = ["根据当前检索到的铁路规则，相关要点如下："]
    for context in contexts[:3]:
        compact = " ".join(context.split())
        lines.append(compact[:180] + ("..." if len(compact) > 180 else ""))
    return "\n".join(lines)


def heuristic_sub_queries(question: str, max_queries: int) -> list[str]:
    anchor = next((keyword for keyword in TOPIC_EXPANSIONS if keyword in question), "")
    raw_parts = re.split(r"[，；。]|以及|并且|同时|还有|另外|分别|或者", question)
    parts = []
    for raw in raw_parts:
        candidate = raw.strip(" ：:，。；、")
        if len(candidate) < 4:
            continue
        if anchor and anchor not in candidate and not any(keyword in candidate for keyword in POLICY_KEYWORDS):
            candidate = f"{anchor} {candidate}"
        parts.append(candidate)
    return normalize_sub_queries(parts, question, max_queries)


def normalize_sub_queries(sub_queries: list[str], fallback: str, max_queries: int) -> list[str]:
    cleaned: list[str] = []
    seen: set[str] = set()
    for item in sub_queries:
        candidate = item.strip(" -：:\n")
        if len(candidate) < 4:
            continue
        if candidate not in seen:
            seen.add(candidate)
            cleaned.append(candidate)
        if len(cleaned) >= max_queries:
            break
    return cleaned or [fallback]


def merge_retrieved_chunks(chunks: list[RetrievedChunk]) -> list[RetrievedChunk]:
    merged: dict[str, tuple[float, RetrievedChunk]] = {}
    for chunk in chunks:
        identity = chunk.chunk_key or chunk.title
        score = chunk.rerank_score + chunk.fused_score + chunk.dense_score + chunk.sparse_score
        existing = merged.get(identity)
        if existing is None or score > existing[0]:
            merged[identity] = (score, chunk)
    ranked = sorted(merged.values(), key=lambda item: item[0], reverse=True)
    return [chunk for _, chunk in ranked]


def split_section_to_chunks(title: str, content: str) -> list[PolicyChunk]:
    article_matches = list(ARTICLE_SPLIT_PATTERN.finditer(content))
    if not article_matches:
        return [PolicyChunk(title=title, content=content)]

    chunks: list[PolicyChunk] = []
    for index, match in enumerate(article_matches):
        article_start = match.start()
        article_end = article_matches[index + 1].start() if index + 1 < len(article_matches) else len(content)
        article_text = content[article_start:article_end].strip()
        article_title_end = article_text.find("\n")
        article_title = article_text[:article_title_end].strip() if article_title_end != -1 else article_text[:32].strip()
        chunks.append(PolicyChunk(title=f"{title} / {article_title}", content=article_text))
    return chunks


def is_follow_up_question(question: str) -> bool:
    normalized = question.strip()
    if normalized in FOLLOW_UP_KEYWORDS:
        return True
    return any(keyword in normalized for keyword in FOLLOW_UP_KEYWORDS)
