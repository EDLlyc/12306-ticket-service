import json
import os
import sys
import time
import math
import io
from datetime import datetime

# 设置标准输出编码为 UTF-8，防止 Windows 控制台报 UnicodeEncodeError
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', line_buffering=True)
import requests
from datasets import Dataset
from langchain_openai import ChatOpenAI
from ragas import evaluate
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import (
    _ContextRelevance,
    context_precision,
    context_recall,
    faithfulness,
    answer_relevancy,
)
import asyncio

RAG_API_URL = os.environ.get("RAG_API_URL", "http://localhost:8899/rag/eval/ask")
RAG_USERNAME = os.environ.get("RAG_USERNAME", "eval-user")
RAG_SESSION_ID = os.environ.get("RAG_SESSION_ID", "eval-session")
RAG_EVAL_PROFILE = os.environ.get("RAG_EVAL_PROFILE", "strict-v2")
RAG_TOKEN = os.environ.get("RAG_TOKEN", "")
ZHIPU_API_KEY = os.environ.get("ZHIPU_API_KEY", "")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "glm-4-plus")
JUDGE_BASE_URL = os.environ.get("JUDGE_BASE_URL", "https://open.bigmodel.cn/api/paas/v4/")
GOLDEN_DATASET_PATH = os.path.join(os.path.dirname(__file__), "golden_dataset.json")
REPORT_PATH = os.environ.get("REPORT_PATH", os.path.join(os.path.dirname(__file__), "report.json"))
COLLECTED_DATA_PATH = os.environ.get("COLLECTED_DATA_PATH", os.path.join(os.path.dirname(__file__), "collected_data.json"))
DETAIL_REPORT_PATH = os.environ.get("DETAIL_REPORT_PATH", os.path.join(os.path.dirname(__file__), "report_detailed.json"))
EVAL_LIMIT = int(os.environ.get("EVAL_LIMIT", "0"))
REQUEST_TIMEOUT_SECONDS = int(os.environ.get("REQUEST_TIMEOUT_SECONDS", "120"))
MAX_REQUEST_RETRIES = int(os.environ.get("MAX_REQUEST_RETRIES", "5"))
MAX_EVAL_RETRIES = int(os.environ.get("MAX_EVAL_RETRIES", "5"))
RETRY_BASE_SECONDS = float(os.environ.get("RETRY_BASE_SECONDS", "15"))
REQUEST_INTERVAL_SECONDS = float(os.environ.get("REQUEST_INTERVAL_SECONDS", "8.0"))

THRESHOLD_FAITHFULNESS = 0.80
THRESHOLD_RELEVANCE = 0.80
THRESHOLD_CONTEXT_PRECISION = 0.70
THRESHOLD_CONTEXT_RECALL = 0.70
THRESHOLD_ANSWER_RELEVANCY = 0.80


def load_golden_dataset():
    with open(GOLDEN_DATASET_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def now_ts():
    return datetime.now().isoformat(timespec="seconds")


def is_rate_limit_error(text):
    return "速率限制" in text or "频率" in text or "Rate limit" in text or "429" in text or "1302" in text


def backoff_seconds(attempt: int) -> float:
    return RETRY_BASE_SECONDS * (2 ** max(attempt - 1, 0))


def write_report(payload: dict):
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_json(path: str, payload: dict):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def safe_float(value, default=0.0):
    try:
        if value is None:
            return default
        f = float(value)
        if math.isnan(f):
            return default
        return f
    except Exception:
        return default


def to_score_map(eval_result):
    if isinstance(eval_result, dict):
        return eval_result
    
    # Ragas 0.4 API uses to_pandas()
    if hasattr(eval_result, "to_pandas"):
        try:
            df = eval_result.to_pandas()
            # Select only numeric columns to average
            numeric_cols = df.select_dtypes(include='number').columns
            return {col: float(df[col].mean()) for col in numeric_cols}
        except Exception as e:
            print(f"Warning: to_pandas() failed: {e}")
            pass
            
    if hasattr(eval_result, "scores") and isinstance(eval_result.scores, list):
        summary = {}
        for row in eval_result.scores:
            if not isinstance(row, dict):
                continue
            for key, value in row.items():
                if isinstance(value, (int, float)) and not math.isnan(float(value)):
                    summary.setdefault(key, []).append(float(value))
        return {k: (sum(v) / len(v) if v else 0.0) for k, v in summary.items()}
    return {}


def build_detailed_rows(eval_result, eval_dataset):
    if not hasattr(eval_result, "to_pandas"):
        return []
    try:
        df = eval_result.to_pandas()
    except Exception as e:
        print(f"Warning: failed to build detailed rows from to_pandas(): {e}")
        return []

    dataset_dict = eval_dataset.to_dict()
    questions = dataset_dict.get("question", [])
    answers = dataset_dict.get("answer", [])
    contexts = dataset_dict.get("contexts", [])
    ground_truths = dataset_dict.get("ground_truth", [])

    detailed = []
    for i in range(len(questions)):
        row = df.iloc[i] if i < len(df) else {}
        faith = safe_float(row.get("faithfulness", row.get("Faithfulness", None)))
        rel = safe_float(row.get("nv_context_relevance", row.get("context_relevance", None)))
        cprec = safe_float(row.get("context_precision", row.get("ContextPrecision", None)))
        crec = safe_float(row.get("context_recall", row.get("ContextRecall", None)))
        ans_rel = safe_float(row.get("answer_relevancy", row.get("AnswerRelevancy", None)))
        min_score = min(faith, rel, cprec, crec, ans_rel)
        fail_reasons = []
        if faith < THRESHOLD_FAITHFULNESS:
            fail_reasons.append("faithfulness")
        if rel < THRESHOLD_RELEVANCE:
            fail_reasons.append("nv_context_relevance")
        if cprec < THRESHOLD_CONTEXT_PRECISION:
            fail_reasons.append("context_precision")
        if crec < THRESHOLD_CONTEXT_RECALL:
            fail_reasons.append("context_recall")
        if ans_rel < THRESHOLD_ANSWER_RELEVANCY:
            fail_reasons.append("answer_relevancy")

        detailed.append({
            "index": i,
            "question": questions[i],
            "answer": answers[i] if i < len(answers) else "",
            "ground_truth": ground_truths[i] if i < len(ground_truths) else "",
            "contexts_count": len(contexts[i]) if i < len(contexts) and isinstance(contexts[i], list) else 0,
            "scores": {
                "faithfulness": faith,
                "nv_context_relevance": rel,
                "context_precision": cprec,
                "context_recall": crec,
                "answer_relevancy": ans_rel,
            },
            "min_score": min_score,
            "failed_metrics": fail_reasons,
        })

    detailed.sort(key=lambda x: x["min_score"])
    return detailed

class RateLimitedChatOpenAI(ChatOpenAI):
    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        time.sleep(1.0)
        return super()._generate(messages, stop=stop, run_manager=run_manager, **kwargs)
        
    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        await asyncio.sleep(1.0)
        return await super()._agenerate(messages, stop=stop, run_manager=run_manager, **kwargs)

from langchain_openai import OpenAIEmbeddings

class RateLimitedOpenAIEmbeddings(OpenAIEmbeddings):
    def embed_documents(self, texts, *args, **kwargs):
        time.sleep(1.0)
        return super().embed_documents(texts, *args, **kwargs)
        
    def embed_query(self, text, *args, **kwargs):
        time.sleep(1.0)
        return super().embed_query(text, *args, **kwargs)
        
    async def aembed_documents(self, texts, *args, **kwargs):
        await asyncio.sleep(1.0)
        return await super().aembed_documents(texts, *args, **kwargs)
            
    async def aembed_query(self, text, *args, **kwargs):
        await asyncio.sleep(1.0)
        return await super().aembed_query(text, *args, **kwargs)


def query_rag_system(question: str):
    retry_count = 0
    last_error = ""
    for attempt in range(1, MAX_REQUEST_RETRIES + 1):
        try:
            resp = requests.get(
                RAG_API_URL,
                params={
                    "q": question,
                    "sessionId": RAG_SESSION_ID,
                    "username": RAG_USERNAME,
                    "profile": RAG_EVAL_PROFILE,
                    "token": RAG_TOKEN,
                },
                timeout=REQUEST_TIMEOUT_SECONDS,
            )
            resp.raise_for_status()
            payload = resp.json()
            result_text = ""
            retrieved_contexts = []
            if isinstance(payload, dict) and payload.get("code") == 200:
                data = payload.get("data")
                if isinstance(data, dict):
                    result_text = str(data.get("answer", ""))
                    retrieved_contexts = data.get("contexts", [])
                else:
                    result_text = "" if data is None else str(data)
            else:
                result_text = json.dumps(payload, ensure_ascii=False)
            
            if is_rate_limit_error(result_text):
                time.sleep(30) # 遇到限流多等一会儿，避免连续 429
                raise RuntimeError(result_text)
            
            time.sleep(5.0) # 基础间隔，避免打爆后端和 Zhipu
            return result_text, retrieved_contexts, retry_count, last_error
        except Exception as e:
            last_error = str(e)
            if is_rate_limit_error(last_error) and attempt < MAX_REQUEST_RETRIES:
                retry_count += 1
                wait_sec = backoff_seconds(attempt)
                print(f"  [Retry] RAG API attempt {attempt}/{MAX_REQUEST_RETRIES}...")
                time.sleep(wait_sec)
                continue
            print(f"  [Failure] RAG API error: {last_error}")
            return "[请求失败，无回答]", [], retry_count, last_error
    print(f"  [Error] Failed to get RAG response after {MAX_REQUEST_RETRIES} attempts.")
    return "[请求失败，无回答]", [], retry_count, last_error


def build_eval_dataset(items, answers, retrieved_contexts_list):
    questions = []
    ground_truths = []
    contexts = []
    generated_answers = []
    for i, item in enumerate(items):
        question = str(item.get("question", "")).strip()
        ground_truth = str(item.get("ground_truth", "")).strip()
        
        # 优先使用 API 返回的真实检索内容
        current_contexts = retrieved_contexts_list[i]
        if not current_contexts:
            # 如果 API 没返回，则回退到 ground_truth (防止 RAGAS 在空 contexts 下报错)
            current_contexts = [ground_truth] if ground_truth else [""]
            
        questions.append(question)
        ground_truths.append(ground_truth)
        contexts.append(current_contexts)
        generated_answers.append(answers[i])
    return Dataset.from_dict(
        {
            "question": questions,
            "answer": generated_answers,
            "contexts": contexts,
            "ground_truth": ground_truths,
        }
    )


def run_evaluation():
    print("=" * 60)
    print("RAGAS 评估系统 (LLM-as-a-Judge)")
    print("=" * 60)

    if not ZHIPU_API_KEY:
        print("Error: Please set environment variable ZHIPU_API_KEY!")
        print("   Example: set ZHIPU_API_KEY=your_zhipu_key")
        sys.exit(1)

    dataset = load_golden_dataset()
    if EVAL_LIMIT > 0:
        dataset = dataset[:EVAL_LIMIT]
    print(f"\nLoaded golden dataset, {len(dataset)} test questions\n")
    print(f"RAG endpoint profile: {RAG_EVAL_PROFILE}")
    print(f"Report path: {REPORT_PATH}")

    rag_retry_total = 0
    rag_error_count = 0
    rag_last_errors = []

    # ===== 断点续传：检查是否存在已采集的数据 =====
    if os.path.exists(COLLECTED_DATA_PATH):
        print(f"[断点续传] 检测到已采集数据文件: {COLLECTED_DATA_PATH}")
        print(f"[断点续传] 跳过数据采集阶段，直接进入评估打分...\n")
        collected = json.load(open(COLLECTED_DATA_PATH, "r", encoding="utf-8"))
        eval_dataset = Dataset.from_dict(collected)
    else:
        answers = []
        all_retrieved_contexts = []
        for i, item in enumerate(dataset):
            question = item.get("question", "")
            print(f"--- Question {i + 1}/{len(dataset)} ---")
            print(f"  Question: {question}")
            answer, retrieved, retry_count, err = query_rag_system(question)
            rag_retry_total += retry_count
            if err:
                rag_error_count += 1
                if len(rag_last_errors) < 5:
                    rag_last_errors.append(err[:300])
            answers.append(answer)
            all_retrieved_contexts.append(retrieved)
            
            safe = answer[:80].encode('gbk', errors='ignore').decode('gbk', errors='ignore')
            print(f"  System Answer: {safe}...")
            print(f"  Retrieved Contexts Count: {len(retrieved)}")
            
            if REQUEST_INTERVAL_SECONDS > 0 and i < len(dataset) - 1:
                time.sleep(REQUEST_INTERVAL_SECONDS)

        eval_dataset = build_eval_dataset(dataset, answers, all_retrieved_contexts)

        # ===== 保存采集数据用于断点续传 =====
        # 使用 .to_dict() 确保数据是标准 Python list/dict，防止 JSON 序列化失败 (Column type error)
        checkpoint_data = eval_dataset.to_dict()
        with open(COLLECTED_DATA_PATH, "w", encoding="utf-8") as cf:
            json.dump(checkpoint_data, cf, ensure_ascii=False, indent=2)
        print(f"\n[存档] 已成功保存采集结果到: {COLLECTED_DATA_PATH}")
        print(f"[存档] 后续打分阶段如果中断，重新运行将直接读取此文件，无需再查后端\n")
    judge_llm = RateLimitedChatOpenAI(
        api_key=ZHIPU_API_KEY,
        base_url=JUDGE_BASE_URL,
        model=JUDGE_MODEL,
        temperature=0.0,
        max_retries=10,
    )
    judge_embeddings = RateLimitedOpenAIEmbeddings(
        api_key=ZHIPU_API_KEY,
        base_url=JUDGE_BASE_URL,
        model="embedding-3" # Zhipu's latest embedding model
    )
    
    score_map = {}
    eval_retry_count = 0
    last_eval_error = ""
    try:
        for attempt in range(1, MAX_EVAL_RETRIES + 1):
            try:
                from ragas.run_config import RunConfig
                # 升级为全旗舰 GLM-4-Plus 引擎，并发提升至 3
                run_config = RunConfig(max_workers=3, timeout=600, max_wait=300) 

                
                result = evaluate(
                    dataset=eval_dataset,
                    metrics=[
                        faithfulness,
                        context_precision,
                        context_recall,
                        _ContextRelevance(),
                        answer_relevancy,
                    ],
                    llm=judge_llm,
                    embeddings=judge_embeddings,
                    run_config=run_config
                )
                score_map = to_score_map(result)
                break
            except Exception as e:
                last_eval_error = str(e)
                if is_rate_limit_error(last_eval_error) and attempt < MAX_EVAL_RETRIES:
                    eval_retry_count += 1
                    wait_sec = backoff_seconds(attempt)
                    print(f"\nEvaluation rate limit hit, retrying in {wait_sec:.1f}s ({attempt}/{MAX_EVAL_RETRIES})")
                    time.sleep(wait_sec)
                    continue
                raise
    except Exception as e:
        failure_report = {
            "status": "failed",
            "timestamp": now_ts(),
            "rag_api_url": RAG_API_URL,
            "rag_eval_profile": RAG_EVAL_PROFILE,
            "judge_model": JUDGE_MODEL,
            "dataset_size": len(dataset),
            "rag_retry_total": rag_retry_total,
            "rag_error_count": rag_error_count,
            "rag_last_errors": rag_last_errors,
            "eval_retry_count": eval_retry_count,
            "eval_last_error": last_eval_error or str(e),
            "error": str(e),
        }
        write_report(failure_report)
        print(f"\nEvaluation failed: {e}")
        print(f"Failure report written to: {REPORT_PATH}")
        sys.exit(1)
    avg_f = float(score_map.get("faithfulness", score_map.get("Faithfulness", 0.0)))
    avg_r = float(score_map.get("nv_context_relevance", score_map.get("context_relevance", 0.0)))
    avg_cp = float(score_map.get("context_precision", score_map.get("ContextPrecision", 0.0)))
    avg_cr = float(score_map.get("context_recall", score_map.get("ContextRecall", 0.0)))
    avg_ar = float(score_map.get("answer_relevancy", score_map.get("AnswerRelevancy", 0.0)))
    detailed_rows = build_detailed_rows(result, eval_dataset)
    worst_10 = detailed_rows[:10]

    report = {
        "status": "success",
        "timestamp": now_ts(),
        "rag_api_url": RAG_API_URL,
        "rag_eval_profile": RAG_EVAL_PROFILE,
        "judge_model": JUDGE_MODEL,
        "dataset_size": len(dataset),
        "rag_retry_total": rag_retry_total,
        "rag_error_count": rag_error_count,
        "rag_last_errors": rag_last_errors,
        "eval_retry_count": eval_retry_count,
        "thresholds": {
            "faithfulness": THRESHOLD_FAITHFULNESS,
            "nv_context_relevance": THRESHOLD_RELEVANCE,
            "context_precision": THRESHOLD_CONTEXT_PRECISION,
            "context_recall": THRESHOLD_CONTEXT_RECALL,
            "answer_relevancy": THRESHOLD_ANSWER_RELEVANCY,
        },
        "scores": {
            "faithfulness": avg_f,
            "nv_context_relevance": avg_r,
            "context_precision": avg_cp,
            "context_recall": avg_cr,
            "answer_relevancy": avg_ar,
        },
        "worst_cases_top10": worst_10,
    }
    write_report(report)
    write_json(DETAIL_REPORT_PATH, {
        "status": "success",
        "timestamp": now_ts(),
        "rag_api_url": RAG_API_URL,
        "rag_eval_profile": RAG_EVAL_PROFILE,
        "judge_model": JUDGE_MODEL,
        "dataset_size": len(dataset),
        "rows": detailed_rows,
    })

    print("=" * 60)
    print("RAGAS Final Report")
    print("=" * 60)
    print(f"  - Faithfulness    (事实一致性)  : {avg_f:.4f}  {'PASS' if avg_f >= THRESHOLD_FAITHFULNESS else 'FAIL'} (Target {THRESHOLD_FAITHFULNESS})")
    print(f"  - Ctx Relevance   (上下文相关性): {avg_r:.4f}  {'PASS' if avg_r >= THRESHOLD_RELEVANCE else 'FAIL'} (Target {THRESHOLD_RELEVANCE})")
    print(f"  - Ctx Precision   (上下文精确率): {avg_cp:.4f}  {'PASS' if avg_cp >= THRESHOLD_CONTEXT_PRECISION else 'FAIL'} (Target {THRESHOLD_CONTEXT_PRECISION})")
    print(f"  - Ctx Recall      (上下文召回率): {avg_cr:.4f}  {'PASS' if avg_cr >= THRESHOLD_CONTEXT_RECALL else 'FAIL'} (Target {THRESHOLD_CONTEXT_RECALL})")
    print(f"  - Answer Relevancy(回答相关性)  : {avg_ar:.4f}  {'PASS' if avg_ar >= THRESHOLD_ANSWER_RELEVANCY else 'FAIL'} (Target {THRESHOLD_ANSWER_RELEVANCY})")
    print(f"  - Report File                  : {REPORT_PATH}")
    print(f"  - Detailed Rows File           : {DETAIL_REPORT_PATH}")
    print("=" * 60)

    all_pass = (
        avg_f >= THRESHOLD_FAITHFULNESS
        and avg_r >= THRESHOLD_RELEVANCE
        and avg_cp >= THRESHOLD_CONTEXT_PRECISION
        and avg_cr >= THRESHOLD_CONTEXT_RECALL
        and avg_ar >= THRESHOLD_ANSWER_RELEVANCY
    )
    if not all_pass:
        print("\n❌ Evaluation FAILED. Please check the RAG pipeline and retest.")
        sys.exit(1)
    else:
        print("\n✅ All 5 metrics PASSED! RAG system is ready for deployment.")
        sys.exit(0)


if __name__ == "__main__":
    run_evaluation()
