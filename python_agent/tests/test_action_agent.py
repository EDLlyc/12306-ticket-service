import unittest
import json

from python_agent.app.action_models import ActionPlan, ActionStep, StepType, ToolName, ToolObservation
from python_agent.app.agent_service import PythonActionAgentService
from python_agent.app.conversation_bridge import ConversationBridge
from python_agent.app.context_classifier import ContextDependencyClassifier
from python_agent.app.eval_service import EvalService
from python_agent.app.hybrid_retriever import HybridRetriever
from python_agent.app.intent_scorer import MultiIntentScorer
from python_agent.app.intent_router import IntentLabel, IntentRouter
from python_agent.app.langchain_tools import build_langchain_tools, langchain_tool_schema
from python_agent.app.llm_planner import LLMActionPlanner, parse_plan, plan_from_tool_calls
from python_agent.app.memory_store import RedisBackedSessionMemoryStore, SessionMemoryStore
from python_agent.app.orchestrator import AgentOrchestrator
from python_agent.app.policy_complexity import PolicyComplexityClassifier
from python_agent.app.policy_router import PolicyRouter
from python_agent.app.policy_service import PolicyService
from python_agent.app.planner import ActionPlanner, extract_route
from python_agent.app.query_refiner import QueryRefiner
from python_agent.app.response_summarizer import ResponseSummarizer
from python_agent.app.schemas import AskRequest, JavaResult
from python_agent.app.semantic_cache import RedisBackedSemanticCache, SemanticCache
from python_agent.app.settings import Settings
from python_agent.app.summary_memory import SummaryMemoryService
from python_agent.app.tool_executor import ToolExecutor
from python_agent.app.agent_graph import AgentGraphRunner
from python_agent.app.weather_mcp import WeatherMcpClient


class FakeJavaClient:
    async def login(self, username):
        return JavaResult(code=200, message="ok", data="token-" + username)

    async def query_train(self, train_number):
        return JavaResult(code=200, message="ok", data=f"{train_number} 余票 8 张")

    async def list_trains(self):
        return [
            {
                "trainNumber": "G1",
                "startStation": "北京",
                "endStation": "上海",
                "startTime": "2026-06-15T08:00:00",
                "endTime": "2026-06-15T12:30:00",
                "availableStock": 8,
            },
            {
                "trainNumber": "G2",
                "startStation": "南京",
                "endStation": "杭州",
                "startTime": "2026-06-15T09:30:00",
                "endTime": "2026-06-15T11:00:00",
                "availableStock": 3,
            },
        ]

    async def book_ticket(self, train_number, token):
        return JavaResult(code=200, message="ok", data=f"已受理 {train_number} {token}")

    async def query_orders(self, token):
        return JavaResult(
            code=200,
            message="ok",
            data=[
                {
                    "order_sn": "11111111-1111-1111-1111-111111111111",
                    "train_number": "G1",
                    "status": "PENDING_PAYMENT",
                }
            ],
        )

    async def refund_order(self, order_sn, token):
        return JavaResult(code=200, message="ok", data=f"已退票 {order_sn}")


class FakeRedis:
    def __init__(self):
        self._store = {}

    def ping(self):
        return True

    def rpush(self, key, value):
        self._store.setdefault(key, [])
        self._store[key].append(value)

    def ltrim(self, key, start, end):
        values = self._store.get(key, [])
        if start < 0:
            start = max(len(values) + start, 0)
        if end < 0:
            end = len(values) + end
        self._store[key] = values[start : end + 1]

    def lrange(self, key, start, end):
        values = self._store.get(key, [])
        if end == -1:
            end = len(values) - 1
        return values[start : end + 1]

    def set(self, key, value):
        self._store[key] = value

    def get(self, key):
        return self._store.get(key)


class FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


class FakeOpenSearchClient:
    def __init__(self, *, search_payload=None, search_error=None, healthy=True):
        self._search_payload = search_payload or {"hits": {"hits": []}}
        self._search_error = search_error
        self._healthy = healthy

    def get(self, path):
        if path == "/_cluster/health" and self._healthy:
            return FakeResponse(200, {"status": "green"})
        return FakeResponse(503, {"status": "red"})

    def post(self, path, json):
        if self._search_error is not None:
            raise self._search_error
        return FakeResponse(200, self._search_payload)


class FakeLLMClient:
    def __init__(self, *, rerank_indexes=None, sub_queries=None, enabled=True):
        self.enabled = enabled
        self._rerank_indexes = rerank_indexes
        self._sub_queries = sub_queries

    def generate_policy_text(self, system_prompt, user_prompt):
        return "根据当前检索到的铁路规则，相关要点如下：\n" + user_prompt[:120]

    def refine_query(self, user_prompt):
        return "学生票资质核验要求是什么"

    def classify_context_dependency(self, *, question, state_card):
        return {"dependent": True, "reason": "llm_follow_up"}

    def classify_policy_complexity(self, *, question):
        return {"complex": True, "reason": "llm_multi_clause"}

    def rerank_documents(self, *, query, documents, top_n):
        return self._rerank_indexes

    def plan_policy_sub_queries(self, *, question, max_queries):
        return self._sub_queries

    def score_intent(self, *, question, context_dependency=None):
        return {
            "actionScore": 0.1,
            "ticketScore": 0.1,
            "policyScore": 0.9,
            "chitchatScore": 0.0,
            "recommended": "RAG",
            "confidence": 0.9,
            "reason": "policy_like",
        }


class FakePlannerLLMClient:
    cloud_enabled = True

    def __init__(self, *, tool_calls=None, text_plan=None):
        self.tool_calls = tool_calls
        self.text_plan = text_plan
        self.tool_call_invoked = False
        self.text_invoked = False

    def generate_agent_tool_calls(self, **kwargs):
        self.tool_call_invoked = True
        return self.tool_calls

    def generate_agent_text(self, system_prompt, user_prompt):
        self.text_invoked = True
        return self.text_plan


class FakeMilvusClient:
    def __init__(self, rows=None, collections=None, search_error=None):
        self._rows = rows or [[]]
        self._collections = collections or ["rules_embedding3_1024"]
        self._search_error = search_error

    def list_collections(self):
        return self._collections

    def search(self, **kwargs):
        if self._search_error is not None:
            raise self._search_error
        return self._rows


class HybridRetrieverWithStubEmbedding(HybridRetriever):
    def __init__(self, settings, *, embedding=None, embedding_trace=None, **kwargs):
        super().__init__(settings, **kwargs)
        self._embedding = embedding
        self._embedding_trace = embedding_trace or {"provider": "stub", "enabled": True}

    def _embed_query(self, question):
        return self._embedding, self._embedding_trace


class ActionPlannerTest(unittest.TestCase):
    def test_extract_route_strips_noise_suffixes(self):
        self.assertEqual(extract_route("从南京到杭州有票吗"), ("南京", "杭州"))
        self.assertEqual(extract_route("北京到上海票价"), ("北京", "上海"))
        self.assertEqual(extract_route("从北京到上海时刻表"), ("北京", "上海"))

    def test_plan_list_all_trains(self):
        plan = ActionPlanner().build_plan(AskRequest(session_id="s1", username="u1", question="帮我查一下所有车次"))
        self.assertEqual(plan.goal, "list_all_trains")
        self.assertEqual(plan.steps[0].tool, ToolName.LIST_TRAINS)

    def test_plan_travel_advice_for_route_weather_question(self):
        plan = ActionPlanner().build_plan(AskRequest(session_id="s1", username="u1", question="明天北京到上海适合坐高铁吗"))
        self.assertEqual(plan.goal, "travel_advice")
        self.assertEqual(plan.steps[0].tool, ToolName.TRAVEL_ADVICE)
        self.assertEqual(plan.steps[0].args["fromStation"], "北京")
        self.assertEqual(plan.steps[0].args["toStation"], "上海")

    def test_parse_llm_plan_json(self):
        plan = parse_plan(
            '{"goal":"book","steps":[{"id":"s1","type":"TOOL","tool":"book_ticket","args":{"trainNumber":"G1"}},{"id":"s2","type":"RESPOND","instruction":"完成后告知用户"}]}'
        )
        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan.goal, "book")
        self.assertEqual(plan.steps[0].tool, ToolName.BOOK_TICKET)
        self.assertEqual(plan.steps[-1].type, StepType.RESPOND)

    def test_plan_from_standard_tool_calls(self):
        plan = plan_from_tool_calls(
            [{"name": "query_train", "args": {"trainNumber": "G1"}}],
            "查一下G1",
        )

        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan.steps[0].tool, ToolName.QUERY_TRAIN)
        self.assertEqual(plan.steps[0].args["trainNumber"], "G1")
        self.assertEqual(plan.steps[-1].type, StepType.RESPOND)

    def test_llm_planner_prefers_standard_tool_calling(self):
        llm = FakePlannerLLMClient(
            tool_calls=[{"name": "query_train", "args": {"trainNumber": "G1"}}],
            text_plan='{"goal":"fallback","steps":[{"id":"s1","type":"TOOL","tool":"query_train","args":{"trainNumber":"G2"}}]}',
        )
        planner = LLMActionPlanner(llm)
        plan = planner.build_plan(
            username="u1",
            original_question="查一下G1",
            safe_question="查一下G1",
        )

        self.assertTrue(llm.tool_call_invoked)
        self.assertFalse(llm.text_invoked)
        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan.steps[0].tool, ToolName.QUERY_TRAIN)
        self.assertEqual(plan.steps[0].args["trainNumber"], "G1")

    def test_llm_planner_falls_back_to_json_plan_when_tool_calling_empty(self):
        llm = FakePlannerLLMClient(
            tool_calls=None,
            text_plan='{"goal":"fallback","steps":[{"id":"s1","type":"TOOL","tool":"query_train","args":{"trainNumber":"G2"}}]}',
        )
        planner = LLMActionPlanner(llm)
        plan = planner.build_plan(
            username="u1",
            original_question="查一下G2",
            safe_question="查一下G2",
        )

        self.assertTrue(llm.tool_call_invoked)
        self.assertTrue(llm.text_invoked)
        self.assertIsNotNone(plan)
        assert plan is not None
        self.assertEqual(plan.steps[0].tool, ToolName.QUERY_TRAIN)
        self.assertEqual(plan.steps[0].args["trainNumber"], "G2")


class LangChainToolAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_langchain_tool_invokes_existing_executor(self):
        executor = ToolExecutor(FakeJavaClient())
        request = AskRequest(session_id="s1", username="u1", question="查一下G1")
        tools = build_langchain_tools(executor, request)
        query_tool = next(tool for tool in tools if tool.name == "query_train")

        raw = await query_tool.ainvoke({"trainNumber": "G1"})
        payload = json.loads(raw)

        self.assertEqual(payload["toolName"], "query_train")
        self.assertTrue(payload["success"])
        self.assertEqual(payload["code"], "JAVA_SUCCESS")
        self.assertIn("G1", payload["message"])

    def test_langchain_tool_schema_exports_safe_ticket_tools_by_default(self):
        schemas = langchain_tool_schema()
        names = {item["name"] for item in schemas}

        self.assertIn("query_train", names)
        self.assertIn("search_trains", names)
        self.assertIn("query_orders", names)
        self.assertNotIn("book_ticket_by_route", names)
        self.assertNotIn("refund_order_by_train", names)
        query_schema = next(item for item in schemas if item["name"] == "query_train")
        self.assertIn("trainNumber", query_schema["args_schema"]["properties"])
        self.assertEqual(query_schema["risk_level"], "low")
        self.assertFalse(query_schema["side_effect"])

    def test_langchain_all_tool_schema_keeps_high_risk_tools_for_controlled_runtime(self):
        schemas = langchain_tool_schema(low_risk_only=False)
        names = {item["name"] for item in schemas}

        self.assertIn("book_ticket_by_route", names)
        self.assertIn("refund_order_by_train", names)
        refund_schema = next(item for item in schemas if item["name"] == "refund_order_by_train")
        self.assertEqual(refund_schema["risk_level"], "high")
        self.assertTrue(refund_schema["side_effect"])


class PolicyServiceTest(unittest.TestCase):
    def test_policy_question_detection(self):
        service = PolicyService(Settings(), llm_client=type("StubLLM", (), {"enabled": False})())
        self.assertTrue(service.is_policy_question("学生票规则是什么"))
        self.assertTrue(service.is_policy_question("改签政策怎么规定"))
        self.assertTrue(service.is_policy_question("再具体一点"))
        self.assertFalse(service.is_policy_question("帮我买G1"))

    def test_policy_answer_uses_local_rules(self):
        service = PolicyService(Settings(), llm_client=type("StubLLM", (), {"enabled": False})())
        result = service.answer("policy-1", "学生票资质核验有什么要求")
        self.assertTrue(result.contexts)
        self.assertIn("铁路规则", result.answer)

    def test_policy_follow_up_reuses_previous_topic(self):
        service = PolicyService(Settings(), llm_client=type("StubLLM", (), {"enabled": False})())
        first = service.answer("policy-2", "学生票资质核验有什么要求")
        second = service.answer("policy-2", "再具体一点")
        self.assertTrue(first.contexts)
        self.assertTrue(second.contexts)
        self.assertIn("学生票", second.effective_question)

    def test_policy_follow_up_short_topic_switch(self):
        service = PolicyService(Settings(), llm_client=type("StubLLM", (), {"enabled": False})())
        result = service.answer("policy-3", "那学生票呢")
        self.assertIn("学生票", result.effective_question)

    def test_policy_service_builds_planned_rag_queries(self):
        llm = FakeLLMClient(
            sub_queries=[
                "学生票资质核验要求是什么",
                "学生票未核验乘车后如何补退差价",
            ]
        )
        service = PolicyService(Settings(), llm_client=llm)
        plan = service.build_query_plan("policy-4", "学生票资质核验和补票退款分别怎么规定")
        self.assertEqual(plan.planner, "llm")
        self.assertEqual(len(plan.sub_queries), 2)
        self.assertTrue(plan.complexity["complex"])

    def test_policy_service_answer_with_retriever_uses_planned_rag_trace(self):
        llm = FakeLLMClient(
            sub_queries=[
                "学生票资质核验要求是什么",
                "学生票未核验乘车后如何补退差价",
            ]
        )
        service = PolicyService(Settings(), llm_client=llm)
        retriever = HybridRetriever(
            Settings(),
            opensearch_http=FakeOpenSearchClient(
                search_payload={
                    "hits": {
                        "hits": [
                            {
                                "_score": 8.5,
                                "_source": {
                                    "docId": "doc-1",
                                    "parentId": "parent-1",
                                    "text": "第二十条 学生优惠票\n学生旅客需完成资质核验。",
                                    "parentText": "第二十条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                                },
                            }
                        ]
                    }
                }
            ),
        )
        result = service.answer_with_retriever("policy-5", "学生票资质核验和补票退款分别怎么规定", retriever)
        self.assertTrue(result.contexts)
        self.assertEqual(result.trace["plannedRag"]["planner"], "llm")
        self.assertEqual(result.trace["retrieval"]["mode"], "planned_rag")

    def test_policy_service_emits_stream_events(self):
        llm = FakeLLMClient(
            sub_queries=[
                "学生票资质核验要求是什么",
                "学生票未核验乘车后如何补退差价",
            ]
        )
        service = PolicyService(Settings(), llm_client=llm)
        retriever = HybridRetriever(
            Settings(),
            opensearch_http=FakeOpenSearchClient(
                search_payload={
                    "hits": {
                        "hits": [
                            {
                                "_score": 8.5,
                                "_source": {
                                    "docId": "doc-1",
                                    "parentId": "parent-1",
                                    "text": "第二十条 学生优惠票\n学生旅客需完成资质核验。",
                                    "parentText": "第二十条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                                },
                            }
                        ]
                    }
                }
            ),
        )
        events = []
        result = service.answer_with_retriever(
            "policy-stream-1",
            "学生票资质核验和补票退款分别怎么规定",
            retriever,
            event_callback=lambda name, payload: events.append((name, payload)),
        )
        self.assertTrue(result.contexts)
        self.assertEqual([name for name, _ in events[:3]], ["planned_rag", "retrieval_started", "retrieval_finished"])


class IntentRouterTest(unittest.TestCase):
    def test_intent_routing(self):
        router = IntentRouter(0.75, 0.12)
        self.assertEqual(router.route("帮我买G1").intent, IntentLabel.ACTION)
        self.assertEqual(router.route("北京到上海余票").intent, IntentLabel.TICKET)
        self.assertEqual(router.route("学生票规则是什么").intent, IntentLabel.RAG)
        self.assertEqual(
            router.route("请对比说明学生票每学年资质核验要求，以及如果没核验直接乘车，后续补票和退款怎么处理？").intent,
            IntentLabel.RAG,
        )


class ConversationBridgeTest(unittest.TestCase):
    def test_follow_up_bridge_uses_latest_turn(self):
        memory = SessionMemoryStore(limit=4)
        memory.append("s1", "学生票资质核验有什么要求", "需要资质核验", "RAG")
        bridge = ConversationBridge(
            memory,
            ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})()),
        )
        result = bridge.bridge("s1", "再具体一点", "RAG")
        self.assertTrue(result.applied)
        self.assertIn("上一轮问题", result.question)
        self.assertTrue(result.context_dependency["dependent"])

    def test_follow_up_bridge_skips_topic_switch(self):
        memory = SessionMemoryStore(limit=4)
        memory.append("s1", "学生票资质核验有什么要求", "需要资质核验", "RAG")
        bridge = ConversationBridge(
            memory,
            ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})()),
        )
        result = bridge.bridge("s1", "帮我买G1", "ACTION")
        self.assertFalse(result.applied)
        self.assertEqual(result.reason, "topic_switched")


class QueryRefinerTest(unittest.TestCase):
    def test_query_refiner_completes_follow_up(self):
        memory = SessionMemoryStore(limit=4)
        memory.append("s1", "学生票资质核验有什么要求", "需要资质核验", "RAG")
        refiner = QueryRefiner(
            memory,
            type("StubLLM", (), {"enabled": False})(),
            ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})()),
        )
        result = refiner.refine("s1", "再具体一点")
        self.assertTrue(result.applied)
        self.assertIn("学生票资质核验有什么要求", result.retrieval_question)
        self.assertTrue(result.context_dependency["dependent"])

    def test_query_refiner_uses_llm_when_available(self):
        memory = SessionMemoryStore(limit=4)
        memory.append("s1", "学生票资质核验有什么要求", "需要资质核验", "RAG")
        refiner = QueryRefiner(memory, FakeLLMClient(), ContextDependencyClassifier(FakeLLMClient()))
        result = refiner.refine("s1", "那这个规则呢")
        self.assertTrue(result.used_llm)
        self.assertEqual(result.retrieval_question, "学生票资质核验要求是什么")


class ContextDependencyClassifierTest(unittest.TestCase):
    def test_context_dependency_llm_used_for_ambiguous_short_question(self):
        classifier = ContextDependencyClassifier(FakeLLMClient())
        latest = type("Turn", (), {"question": "学生票资质核验有什么要求", "answer": "需要核验"})()
        result = classifier.classify("这个呢", latest)
        self.assertTrue(result.dependent)
        self.assertEqual(result.source, "llm")


class PolicyComplexityClassifierTest(unittest.TestCase):
    def test_policy_complexity_classifier_marks_multi_clause_question(self):
        classifier = PolicyComplexityClassifier(type("StubLLM", (), {"enabled": False})())
        result = classifier.classify("请说明学生票资质核验要求，并说明未核验乘车后补差价和退款规则。")
        self.assertTrue(result.complex)
        self.assertEqual(result.source, "heuristic")


class SummaryMemoryServiceTest(unittest.TestCase):
    def test_summary_created_after_enough_turns(self):
        memory = SessionMemoryStore(limit=12)
        service = SummaryMemoryService(llm_client=type("StubLLM", (), {"enabled": False})(), memory_store=memory)
        for idx in range(6):
            memory.append("s1", f"Q{idx}", f"A{idx}", "RAG")
        summary = service.refresh_summary_if_needed("s1")
        self.assertIsNotNone(summary)
        assert summary is not None
        self.assertIn("最近对话摘要", summary)


class SemanticCacheTest(unittest.TestCase):
    def test_semantic_cache_hit(self):
        cache = SemanticCache(limit=4)
        cache.put("学生票规则", "answer", {"mode": "policy_rag"})
        hit = cache.get("学生票规则")
        self.assertIsNotNone(hit)
        assert hit is not None
        self.assertEqual(hit.answer, "answer")

    def test_redis_backed_semantic_cache(self):
        cache = RedisBackedSemanticCache(FakeRedis(), limit=4)
        cache.put("学生票规则", "answer", {"mode": "policy_rag"})
        hit = cache.get("学生票规则")
        self.assertIsNotNone(hit)
        assert hit is not None
        self.assertEqual(hit.answer, "answer")


class HybridRetrieverTest(unittest.TestCase):
    def test_hybrid_retriever_finds_policy_chunk(self):
        settings = Settings()
        retriever = HybridRetriever(settings)
        result = retriever.retrieve("学生票 资质核验")
        self.assertTrue(result.chunks)
        self.assertIn("学生优惠票", result.chunks[0].title)
        self.assertEqual(result.trace["retriever"], "hybrid_rrf")
        self.assertIn("dense", result.trace)
        self.assertIn("sparse", result.trace)

    def test_hybrid_retriever_rerank_trace(self):
        settings = Settings(rerank_enabled=True)
        retriever = HybridRetriever(settings)
        result = retriever.retrieve("学生票 资质核验")
        self.assertTrue(result.chunks)
        self.assertTrue(any(chunk.rerank_score >= 0 for chunk in result.chunks))
        self.assertIn("source", result.trace["rerank"])

    def test_hybrid_retriever_backend_health_trace(self):
        settings = Settings(milvus_enabled=True, opensearch_enabled=True)
        retriever = HybridRetriever(settings)
        result = retriever.retrieve("学生票 资质核验")
        self.assertIn("healthy", result.trace["dense"])
        self.assertIn("healthy", result.trace["sparse"])

    def test_hybrid_retriever_uses_remote_opensearch_results(self):
        settings = Settings(opensearch_enabled=True)
        opensearch = FakeOpenSearchClient(
            search_payload={
                "hits": {
                    "hits": [
                        {
                            "_score": 8.5,
                            "_source": {
                                "docId": "doc-1",
                                "parentId": "parent-1",
                                "text": "第二十条 学生优惠票\n学生旅客需完成资质核验。",
                                "parentText": "第二十条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                            },
                        }
                    ]
                }
            }
        )
        retriever = HybridRetriever(settings, opensearch_http=opensearch)
        result = retriever.retrieve("学生票 资质核验")
        self.assertTrue(result.chunks)
        self.assertEqual(result.trace["sparse"]["source"], "remote")
        self.assertEqual(result.trace["fallbackMode"], "local_dense_remote_sparse")
        self.assertIn("学生优惠票", result.chunks[0].title)

    def test_hybrid_retriever_uses_remote_milvus_results(self):
        settings = Settings(milvus_enabled=True, zhipu_api_key="stub-key")
        milvus = FakeMilvusClient(
            rows=[
                [
                    {
                        "id": "child-1",
                        "distance": 0.2,
                        "entity": {
                            "id": "child-1",
                            "text": "第二十条 学生优惠票\n学生旅客需完成资质核验。",
                            "metadata": {
                                "parent_id": "parent-1",
                                "parent_text": "第二十条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                                "chapter": "第二章 旅客运输",
                                "section": "第三节 售票与购票",
                                "article_title": "学生优惠票",
                            },
                        },
                    }
                ]
            ]
        )
        retriever = HybridRetrieverWithStubEmbedding(
            settings,
            milvus_client=milvus,
            embedding=[0.1] * 1024,
        )
        result = retriever.retrieve("学生票 资质核验")
        self.assertTrue(result.chunks)
        self.assertEqual(result.trace["dense"]["source"], "remote")
        self.assertEqual(result.trace["fallbackMode"], "remote_dense_local_sparse")
        self.assertIn("学生优惠票", result.chunks[0].title)

    def test_hybrid_retriever_uses_remote_reranker_when_available(self):
        settings = Settings(rerank_enabled=True)
        retriever = HybridRetriever(
            settings,
            opensearch_http=FakeOpenSearchClient(
                search_payload={
                    "hits": {
                        "hits": [
                            {
                                "_score": 5.0,
                                "_source": {
                                    "docId": "doc-1",
                                    "parentId": "parent-1",
                                    "text": "第二十条 学生优惠票\n学生旅客需完成资质核验。",
                                    "parentText": "第二十条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                                },
                            },
                            {
                                "_score": 4.0,
                                "_source": {
                                    "docId": "doc-2",
                                    "parentId": "parent-2",
                                    "text": "第四十五条 退票\n未核验乘车需先补差价。",
                                    "parentText": "第四十五条 退票\n未核验乘车需先补差价，到站30日内核验合格可退补票款。",
                                },
                            },
                        ]
                    }
                }
            ),
            llm_client=FakeLLMClient(rerank_indexes=[1, 0]),
        )
        result = retriever.retrieve("学生票资质核验和补票退款")
        self.assertEqual(result.trace["rerank"]["source"], "remote")
        self.assertIn("退票", result.chunks[0].title)

    def test_hybrid_retriever_falls_back_when_remote_sparse_fails(self):
        settings = Settings(opensearch_enabled=True)
        opensearch = FakeOpenSearchClient(search_error=RuntimeError("boom"))
        retriever = HybridRetriever(settings, opensearch_http=opensearch)
        result = retriever.retrieve("学生票 资质核验")
        self.assertTrue(result.chunks)
        self.assertEqual(result.trace["sparse"]["source"], "local_fallback")
        self.assertEqual(result.trace["sparse"]["reason"], "remote_search_failed")
        self.assertIn("error", result.trace["sparse"])


class RedisBackedSessionMemoryStoreTest(unittest.TestCase):
    def test_redis_backed_memory_store(self):
        memory = RedisBackedSessionMemoryStore(FakeRedis(), limit=4)
        memory.append("s1", "Q1", "A1", "RAG")
        memory.append("s1", "Q2", "A2", "ACTION")
        recent = memory.recent("s1")
        self.assertEqual(len(recent), 2)
        self.assertEqual(recent[-1].question, "Q2")
        memory.set_summary("s1", "summary")
        self.assertEqual(memory.get_summary("s1"), "summary")


class FakeLLMPlanner:
    def __init__(self, plan):
        self._plan = plan
        self.enabled = True

    def build_plan(self, *, username, original_question, safe_question, previous_plan=None, replan_reason=None):
        return self._plan


class FakeSummarizer(ResponseSummarizer):
    def __init__(self):
        pass

    def summarize(self, *, original_question, respond_step, observations):
        return observations[-1].message


class SequenceLLMPlanner:
    def __init__(self, plans):
        self._plans = list(plans)
        self.enabled = True
        self.calls = []

    def build_plan(self, *, username, original_question, safe_question, previous_plan=None, replan_reason=None):
        self.calls.append(
            {
                "username": username,
                "original_question": original_question,
                "safe_question": safe_question,
                "previous_plan": previous_plan.goal if previous_plan is not None else None,
                "replan_reason": replan_reason,
            }
        )
        if not self._plans:
            return None
        return self._plans.pop(0)


class NonTerminalBookExecutor(ToolExecutor):
    async def execute(self, step, request):
        if step.tool == ToolName.BOOK_TICKET:
            return ToolObservation(
                tool_name=str(step.tool),
                success=True,
                code="JAVA_SUCCESS",
                message="已受理 G1 token-alice",
                data={"trainNumber": "G1"},
                terminal=False,
            )
        return await super().execute(step, request)


class PythonActionAgentServiceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.service = PythonActionAgentService(ActionPlanner(), ToolExecutor(FakeJavaClient()))

    async def test_query_train(self):
        result = await self.service.answer(AskRequest(session_id="s1", question="查一下G1"))
        self.assertEqual(result.plan.goal, "query_train")
        self.assertIn("G1 余票 8 张", result.answer)

    async def test_search_trains_by_route(self):
        result = await self.service.answer(AskRequest(session_id="s1", question="北京到上海余票"))
        self.assertEqual(result.plan.goal, "search_trains")
        self.assertIn("查询结果", result.answer)
        self.assertIn("G1", result.answer)

    async def test_travel_advice_combines_trains_and_weather_mcp(self):
        service = PythonActionAgentService(
            ActionPlanner(),
            ToolExecutor(FakeJavaClient(), WeatherMcpClient(Settings())),
        )
        result = await service.answer(AskRequest(session_id="s1", question="明天北京到上海适合坐高铁吗"))

        self.assertEqual(result.plan.goal, "travel_advice")
        self.assertIn("出行建议", result.answer)
        self.assertIn("G1", result.answer)
        self.assertIn("目的地天气", result.answer)
        assert result.final_observation is not None
        self.assertEqual(result.final_observation.code, "TRAVEL_ADVICE_READY")
        data = result.final_observation.data
        self.assertIsInstance(data, dict)
        assert isinstance(data, dict)
        self.assertEqual(data["mcp"]["tools"][0]["method"], "tools/call")
        self.assertEqual(data["mcp"]["tools"][0]["protocol"], "mcp")

    async def test_book_ticket_by_route(self):
        result = await self.service.answer(
            AskRequest(session_id="s1", username="alice", question="帮我买明天从北京到上海的票")
        )
        self.assertEqual(result.plan.goal, "book_ticket_by_route")
        self.assertIn("请回复“确认”", result.answer)
        confirm = await self.service.answer(AskRequest(session_id="s1", username="alice", question="确认"))
        self.assertEqual(confirm.plan.goal, "confirmed_book_ticket_by_route")
        self.assertIn("已为您选择车次 G1", confirm.answer)

    async def test_query_orders(self):
        result = await self.service.answer(AskRequest(session_id="s1", username="alice", question="查订单"))
        self.assertEqual(result.plan.goal, "query_user_orders")
        self.assertIn("您的订单", result.answer)

    async def test_refund_order_by_train(self):
        result = await self.service.answer(AskRequest(session_id="s1", username="alice", question="G1退掉"))
        self.assertEqual(result.plan.goal, "refund_order_by_train")
        self.assertIn("请回复“确认”", result.answer)
        confirm = await self.service.answer(AskRequest(session_id="s1", username="alice", question="确认"))
        self.assertEqual(confirm.plan.goal, "confirmed_refund_order_by_train")
        self.assertIn("已匹配订单", confirm.answer)

    async def test_cancel_pending_side_effect(self):
        result = await self.service.answer(AskRequest(session_id="cancel1", username="alice", question="G1退掉"))
        self.assertEqual(result.final_observation.code, "CONFIRM_REQUIRED")
        cancelled = await self.service.answer(AskRequest(session_id="cancel1", username="alice", question="取消"))
        self.assertEqual(cancelled.final_observation.code, "CONFIRM_CANCELLED")
        self.assertIn("已取消", cancelled.answer)

    async def test_invalid_llm_plan_falls_back_to_rule_planner(self):
        service = PythonActionAgentService(
            ActionPlanner(),
            ToolExecutor(FakeJavaClient()),
            llm_planner=FakeLLMPlanner(None),
        )
        result = await service.answer(AskRequest(session_id="s1", question="查一下G1"))
        self.assertEqual(result.plan.goal, "query_train")
        self.assertIn("G1 余票 8 张", result.answer)

    async def test_llm_plan_can_drive_execution(self):
        llm_plan = ActionPlan(
            goal="llm_query_train",
            steps=[
                ActionStep(
                    id="s1",
                    type=StepType.TOOL,
                    tool=ToolName.QUERY_TRAIN,
                    instruction="查询车次",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s2",
                    type=StepType.RESPOND,
                    instruction="总结查询结果",
                ),
            ],
        )
        service = PythonActionAgentService(
            ActionPlanner(),
            ToolExecutor(FakeJavaClient()),
            llm_planner=FakeLLMPlanner(llm_plan),
            summarizer=FakeSummarizer(),
        )
        result = await service.answer(AskRequest(session_id="s1", question="查一下G1"))
        self.assertEqual(result.plan.goal, "llm_query_train")
        self.assertTrue(result.answer.startswith("G1 余票 8 张"))

    async def test_replan_reason_history_recorded(self):
        duplicate_plan = ActionPlan(
            goal="duplicate_book",
            steps=[
                ActionStep(
                    id="s1",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="先购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s2",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="重复购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s3",
                    type=StepType.RESPOND,
                    instruction="总结结果",
                ),
            ],
        )
        repaired_plan = ActionPlan(
            goal="repair_book",
            steps=[
                ActionStep(
                    id="s1",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s2",
                    type=StepType.RESPOND,
                    instruction="总结结果",
                ),
            ],
        )
        planner = SequenceLLMPlanner([duplicate_plan, repaired_plan])
        service = PythonActionAgentService(
            ActionPlanner(),
            NonTerminalBookExecutor(FakeJavaClient()),
            llm_planner=planner,
            summarizer=FakeSummarizer(),
            confirmation_required=False,
        )
        result = await service.answer(AskRequest(session_id="s1", username="alice", question="帮我买G1"))
        self.assertTrue(result.replanned)
        self.assertEqual(result.replan_count, 1)
        self.assertEqual(len(result.replan_reason_history), 1)
        self.assertIn("DUPLICATE_SIDE_EFFECT_STEP", result.replan_reason_history[0])
        self.assertIn("已受理 G1 token-alice", result.answer)
        self.assertEqual(planner.calls[1]["previous_plan"], "duplicate_book")
        self.assertIsNotNone(planner.calls[1]["replan_reason"])

    async def test_action_agent_emits_plan_and_tool_events(self):
        events = []
        result = await self.service.answer(
            AskRequest(session_id="s1", question="查一下G1"),
            event_callback=lambda name, payload: events.append((name, payload)),
        )
        self.assertIn("G1 余票 8 张", result.answer)
        event_names = [name for name, _ in events]
        self.assertIn("plan_generated", event_names)
        self.assertIn("tool_executed", event_names)


class AgentOrchestratorTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        settings = Settings(milvus_enabled=False, opensearch_enabled=False)
        self.memory_store = SessionMemoryStore(limit=8)
        self.cache = SemanticCache(limit=8)
        self.action_agent = PythonActionAgentService(ActionPlanner(), ToolExecutor(FakeJavaClient()))
        self.policy_service = PolicyService(settings, llm_client=type("StubLLM", (), {"enabled": False})())
        self.context_classifier = ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})())
        self.orchestrator = AgentOrchestrator(
            intent_router=IntentRouter(0.75, 0.12),
            intent_scorer=MultiIntentScorer(type("StubLLM", (), {"enabled": False})()),
            bridge=ConversationBridge(self.memory_store, self.context_classifier),
            memory_store=self.memory_store,
            semantic_cache=self.cache,
            action_agent=self.action_agent,
            policy_router=PolicyRouter(type("StubLLM", (), {"enabled": False})()),
            policy_service=self.policy_service,
            hybrid_retriever=HybridRetriever(settings),
            query_refiner=QueryRefiner(
                self.memory_store,
                type("StubLLM", (), {"enabled": False})(),
                self.context_classifier,
            ),
        )

    async def test_orchestrator_routes_policy_question(self):
        result = await self.orchestrator.answer(AskRequest(session_id="p1", question="学生票资质核验有什么要求"))
        self.assertEqual(result.trace["mode"], "policy_rag")
        self.assertIn("contexts", result.trace)
        self.assertIn("plannedRag", result.trace)
        self.assertIn("queryRewrite", result.trace)

    async def test_orchestrator_routes_action_question(self):
        result = await self.orchestrator.answer(AskRequest(session_id="a1", username="alice", question="帮我买G1"))
        self.assertEqual(result.trace["mode"], "action_agent")
        self.assertIn("agentTrace", result.trace)
        self.assertIn("请回复“确认”", result.answer)
        confirmed = await self.orchestrator.answer(AskRequest(session_id="a1", username="alice", question="确认"))
        self.assertEqual(confirmed.trace["mode"], "action_agent")
        self.assertIn("已受理 G1", confirmed.answer)

    async def test_orchestrator_uses_semantic_cache(self):
        request = AskRequest(session_id="p2", question="学生票资质核验有什么要求")
        first = await self.orchestrator.answer(request)
        second = await self.orchestrator.answer(request)
        self.assertEqual(first.answer, second.answer)
        self.assertEqual(second.trace["mode"], "semantic_cache")
        self.assertGreaterEqual(len(self.memory_store.recent("p2")), 2)

    async def test_orchestrator_produces_session_summary_after_many_turns(self):
        for idx in range(6):
            await self.orchestrator.answer(AskRequest(session_id="sum1", question=f"学生票规则 {idx}"))
        result = await self.orchestrator.answer(AskRequest(session_id="sum1", question="学生票资质核验有什么要求"))
        self.assertIn("sessionSummary", result.trace)

    async def test_orchestrator_emits_realtime_rag_events(self):
        events = []
        result = await self.orchestrator.answer(
            AskRequest(session_id="rag-stream-1", question="学生票资质核验有什么要求"),
            event_callback=lambda name, payload: events.append((name, payload)),
        )
        self.assertEqual(result.trace["mode"], "policy_rag")
        event_names = [name for name, _ in events]
        self.assertIn("query_refined", event_names)
        self.assertIn("intent_routed", event_names)
        self.assertIn("bridge_evaluated", event_names)
        self.assertIn("policy_route", event_names)
        self.assertIn("planned_rag", event_names)
        self.assertIn("retrieval_started", event_names)
        self.assertIn("retrieval_finished", event_names)
        self.assertIn("memory_updated", event_names)


class AgentGraphRunnerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.memory_store = SessionMemoryStore(limit=8)
        self.cache = SemanticCache(limit=8)
        self.context_classifier = ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})())
        self.settings = Settings(milvus_enabled=False, opensearch_enabled=False)
        self.runner = AgentGraphRunner(
            intent_router=IntentRouter(0.75, 0.12),
            intent_scorer=MultiIntentScorer(type("StubLLM", (), {"enabled": False})()),
            bridge=ConversationBridge(self.memory_store, self.context_classifier),
            memory_store=self.memory_store,
            semantic_cache=self.cache,
            action_agent=PythonActionAgentService(ActionPlanner(), ToolExecutor(FakeJavaClient())),
            policy_router=PolicyRouter(type("StubLLM", (), {"enabled": False})()),
            policy_service=PolicyService(self.settings, llm_client=type("StubLLM", (), {"enabled": False})()),
            hybrid_retriever=HybridRetriever(self.settings),
            query_refiner=QueryRefiner(
                self.memory_store,
                type("StubLLM", (), {"enabled": False})(),
                self.context_classifier,
            ),
            summary_memory=SummaryMemoryService(
                llm_client=type("StubLLM", (), {"enabled": False})(),
                memory_store=self.memory_store,
            ),
        )

    async def test_graph_runner_routes_policy_question(self):
        result = await self.runner.run(
            AskRequest(session_id="graph-rag-1", question="学生票资质核验有什么要求"),
        )
        self.assertEqual(result.trace["mode"], "policy_rag")
        self.assertIn("retrieval", result.trace)

    async def test_graph_runner_routes_action_question(self):
        result = await self.runner.run(
            AskRequest(session_id="graph-action-1", username="alice", question="帮我买G1"),
        )
        self.assertEqual(result.trace["mode"], "action_agent")
        self.assertIn("agentTrace", result.trace)
        self.assertIn("请回复“确认”", result.answer)
        confirmed = await self.runner.run(
            AskRequest(session_id="graph-action-1", username="alice", question="确认"),
        )
        self.assertIn("已受理 G1", confirmed.answer)

    async def test_graph_runner_action_replan_loop(self):
        duplicate_plan = ActionPlan(
            goal="duplicate_book",
            steps=[
                ActionStep(
                    id="s1",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="先购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s2",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="重复购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s3",
                    type=StepType.RESPOND,
                    instruction="总结结果",
                ),
            ],
        )
        repaired_plan = ActionPlan(
            goal="repair_book",
            steps=[
                ActionStep(
                    id="s1",
                    type=StepType.TOOL,
                    tool=ToolName.BOOK_TICKET,
                    instruction="购票",
                    args={"trainNumber": "G1"},
                ),
                ActionStep(
                    id="s2",
                    type=StepType.RESPOND,
                    instruction="总结结果",
                ),
            ],
        )
        planner = SequenceLLMPlanner([duplicate_plan, repaired_plan])
        runner = AgentGraphRunner(
            intent_router=IntentRouter(0.75, 0.12),
            intent_scorer=MultiIntentScorer(type("StubLLM", (), {"enabled": False})()),
            bridge=ConversationBridge(self.memory_store, self.context_classifier),
            memory_store=self.memory_store,
            semantic_cache=self.cache,
            action_agent=PythonActionAgentService(
                ActionPlanner(),
                NonTerminalBookExecutor(FakeJavaClient()),
                llm_planner=planner,
                summarizer=FakeSummarizer(),
                confirmation_required=False,
            ),
            policy_router=PolicyRouter(type("StubLLM", (), {"enabled": False})()),
            policy_service=PolicyService(self.settings, llm_client=type("StubLLM", (), {"enabled": False})()),
            hybrid_retriever=HybridRetriever(self.settings),
            query_refiner=QueryRefiner(
                self.memory_store,
                type("StubLLM", (), {"enabled": False})(),
                self.context_classifier,
            ),
        )
        events = []
        result = await runner.run(
            AskRequest(session_id="graph-replan-1", username="alice", question="帮我买G1"),
            event_callback=lambda name, payload: events.append((name, payload)),
        )
        self.assertEqual(result.trace["mode"], "action_agent")
        self.assertTrue(result.trace["agentTrace"]["replanned"])
        self.assertEqual(result.trace["agentTrace"]["replanCount"], 1)
        self.assertIn("DUPLICATE_SIDE_EFFECT_STEP", result.trace["agentTrace"]["replanReasonHistory"][0])
        self.assertIn("已受理 G1 token-alice", result.answer)
        self.assertIn("replan_triggered", [name for name, _ in events])


class EvalServiceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        settings = Settings(milvus_enabled=False, opensearch_enabled=False)
        self.memory_store = SessionMemoryStore(limit=8)
        self.cache = SemanticCache(limit=8)
        self.context_classifier = ContextDependencyClassifier(type("StubLLM", (), {"enabled": False})())
        self.orchestrator = AgentOrchestrator(
            intent_router=IntentRouter(0.75, 0.12),
            intent_scorer=MultiIntentScorer(type("StubLLM", (), {"enabled": False})()),
            bridge=ConversationBridge(self.memory_store, self.context_classifier),
            memory_store=self.memory_store,
            semantic_cache=self.cache,
            action_agent=PythonActionAgentService(ActionPlanner(), ToolExecutor(FakeJavaClient())),
            policy_router=PolicyRouter(type("StubLLM", (), {"enabled": False})()),
            policy_service=PolicyService(settings, llm_client=type("StubLLM", (), {"enabled": False})()),
            hybrid_retriever=HybridRetriever(settings),
            query_refiner=QueryRefiner(
                self.memory_store,
                type("StubLLM", (), {"enabled": False})(),
                self.context_classifier,
            ),
            summary_memory=SummaryMemoryService(
                llm_client=type("StubLLM", (), {"enabled": False})(),
                memory_store=self.memory_store,
            ),
        )
        self.eval_service = EvalService(self.orchestrator, self.memory_store)

    async def test_replay_uses_custom_warmup_and_restores_memory(self):
        replay = await self.eval_service.replay_with_and_without_memory(
            "eval-1",
            "再具体一点",
            "eval-user",
            warmup_questions=["学生票资质核验有什么要求"],
        )
        self.assertEqual(replay.warmup_questions, ["学生票资质核验有什么要求"])
        self.assertTrue(replay.metrics["warmup_applied"])
        self.assertGreaterEqual(replay.metrics["memory_turn_count"], 1)
        self.assertGreaterEqual(replay.metrics["memory_block_chars"], 1)
        self.assertIsInstance(replay.with_memory_trace, dict)
        self.assertIsInstance(replay.no_memory_trace, dict)
        self.assertEqual(len(self.memory_store.recent("eval-1")), 2)

    async def test_replay_without_empty_session_warmup_flag_off(self):
        await self.orchestrator.answer(AskRequest(session_id="eval-2", question="学生票资质核验有什么要求"))
        replay = await self.eval_service.replay_with_and_without_memory(
            "eval-2",
            "再具体一点",
            "eval-user",
        )
        self.assertEqual(replay.warmup_questions, [])
        self.assertFalse(replay.metrics["warmup_applied"])


if __name__ == "__main__":
    unittest.main()
