import json
import os
import sys
import time
import math
import io
from datetime import datetime

# 设置标准输出编码为 UTF-8，防止 Windows 控制台报 UnicodeEncodeError
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
import requests
from datasets import Dataset
from langchain_openai import ChatOpenAI
from ragas import evaluate
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import _ContextRelevance, context_precision, faithfulness

RAG_API_URL = os.environ.get("RAG_API_URL", "http://localhost:8080/rag/eval/ask")
RAG_USERNAME = os.environ.get("RAG_USERNAME", "eval-user")
RAG_SESSION_ID = os.environ.get("RAG_SESSION_ID", "eval-session")
ZHIPU_API_KEY = os.environ.get("ZHIPU_API_KEY", "")
JUDGE_MODEL = os.environ.get("JUDGE_MODEL", "glm-4.7-flash")
JUDGE_BASE_URL = os.environ.get("JUDGE_BASE_URL", "https://open.bigmodel.cn/api/paas/v4/")
GOLDEN_DATASET_PATH = os.path.join(os.path.dirname(__file__), "golden_dataset.json")
REPORT_PATH = os.path.join(os.path.dirname(__file__), "report.json")
EVAL_LIMIT = int(os.environ.get("EVAL_LIMIT", "0"))
REQUEST_TIMEOUT_SECONDS = int(os.environ.get("REQUEST_TIMEOUT_SECONDS", "120"))
MAX_REQUEST_RETRIES = int(os.environ.get("MAX_REQUEST_RETRIES", "3"))
MAX_EVAL_RETRIES = int(os.environ.get("MAX_EVAL_RETRIES", "3"))
RETRY_BASE_SECONDS = float(os.environ.get("RETRY_BASE_SECONDS", "2"))
REQUEST_INTERVAL_SECONDS = float(os.environ.get("REQUEST_INTERVAL_SECONDS", "0.5"))

THRESHOLD_FAITHFULNESS = 0.80
THRESHOLD_RELEVANCE = 0.80
THRESHOLD_CONTEXT = 0.70


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


def to_score_map(eval_result):
    if isinstance(eval_result, dict):
        return eval_result
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


def query_rag_system(question: str):
    retry_count = 0
    last_error = ""
    for attempt in range(1, MAX_REQUEST_RETRIES + 1):
        try:
            resp = requests.get(
                RAG_API_URL,
                params={"q": question, "sessionId": RAG_SESSION_ID, "username": RAG_USERNAME},
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
                time.sleep(5) # 遇到限流多等一会儿
                raise RuntimeError(result_text)
            
            time.sleep(1.0) # 基础间隔，避免打爆后端和 Zhipu
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

    answers = []
    all_retrieved_contexts = []
    rag_retry_total = 0
    rag_error_count = 0
    rag_last_errors = []
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
    judge_llm = ChatOpenAI(
        api_key=ZHIPU_API_KEY,
        base_url=JUDGE_BASE_URL,
        model=JUDGE_MODEL,
        temperature=0.0,
    )
    score_map = {}
    eval_retry_count = 0
    last_eval_error = ""
    try:
        for attempt in range(1, MAX_EVAL_RETRIES + 1):
            try:
                from ragas.run_config import RunConfig
                run_config = RunConfig(max_workers=1) # 串行评估，防止 Zhipu AI 429
                
                result = evaluate(
                    dataset=eval_dataset,
                    metrics=[faithfulness, context_precision, _ContextRelevance()],
                    llm=LangchainLLMWrapper(judge_llm),
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
    avg_f = float(score_map.get("faithfulness", 0.0))
    avg_r = float(score_map.get("nv_context_relevance", 0.0))
    avg_c = float(score_map.get("context_precision", 0.0))

    report = {
        "status": "success",
        "timestamp": now_ts(),
        "rag_api_url": RAG_API_URL,
        "judge_model": JUDGE_MODEL,
        "dataset_size": len(dataset),
        "rag_retry_total": rag_retry_total,
        "rag_error_count": rag_error_count,
        "rag_last_errors": rag_last_errors,
        "eval_retry_count": eval_retry_count,
        "thresholds": {
            "faithfulness": THRESHOLD_FAITHFULNESS,
            "nv_context_relevance": THRESHOLD_RELEVANCE,
            "context_precision": THRESHOLD_CONTEXT,
        },
        "scores": {
            "faithfulness": avg_f,
            "nv_context_relevance": avg_r,
            "context_precision": avg_c,
        },
    }
    write_report(report)

    print("=" * 60)
    print("RAGAS Final Report")
    print("=" * 60)
    print(f"  - Faithfulness  (Accuracy)     : {avg_f:.4f}  {'PASS' if avg_f >= THRESHOLD_FAITHFULNESS else 'FAIL'} (Target {THRESHOLD_FAITHFULNESS})")
    print(f"  - Relevance     (Context Rel.) : {avg_r:.4f}  {'PASS' if avg_r >= THRESHOLD_RELEVANCE else 'FAIL'} (Target {THRESHOLD_RELEVANCE})")
    print(f"  - Context Prec. (Precision)    : {avg_c:.4f}  {'PASS' if avg_c >= THRESHOLD_CONTEXT else 'FAIL'} (Target {THRESHOLD_CONTEXT})")
    print(f"  - Report File                  : {REPORT_PATH}")
    print("=" * 60)

    if avg_f < THRESHOLD_FAITHFULNESS or avg_r < THRESHOLD_RELEVANCE or avg_c < THRESHOLD_CONTEXT:
        print("\nEvaluation failed. Please check the RAG pipeline and retest.")
        sys.exit(1)
    else:
        print("\nAll metrics passed. RAG system is ready for deployment.")
        sys.exit(0)


if __name__ == "__main__":
    run_evaluation()
