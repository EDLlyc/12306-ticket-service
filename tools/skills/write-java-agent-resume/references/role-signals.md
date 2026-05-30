# Role Signals

Use this file to map resume content to recruiter and interviewer signals for Java backend and Agent roles.

These signals are distilled from official job descriptions and official career-writing guidance listed in [source-links.md](source-links.md). If the user provides a real JD, that JD overrides these defaults.

## 1. Java Backend Signals

Common strong signals across large tech companies and mature backend teams:

- At least one main backend language with depth:
  Java is primary; sometimes JD wording is "Java/Go/C++ one or more".
- Solid computer science basics:
  data structures, OS, networks, databases.
- Backend framework depth:
  Spring Boot, IOC/AOP, transactions, common middleware integration.
- Database engineering:
  MySQL schema design, indexes, transactions, SQL optimization.
- Cache and high concurrency:
  Redis, hotspot protection, consistency strategy, Lua, lock granularity.
- Message-driven consistency:
  MQ retries, idempotency, compensation, transaction messages, eventual consistency.
- Production engineering:
  rate limiting, degradation, alerting, observability, fault isolation, thread pool discipline.

What recruiters like to see in bullets:
- "why the mechanism was needed"
- "what consistency or concurrency risk was solved"
- "what exact middleware/pattern was used"
- "what happened after the change"

## 2. Agent / AI Application Signals

Common strong signals for applied AI / Agent engineering:

- Built an application system, not only prompt experiments.
- Model orchestration:
  planner, executor, routing, specialist agents, workflow graph.
- Tool calling:
  typed tool interfaces, structured tool outputs, retries, fallback handling.
- Retrieval engineering:
  chunking strategy, hybrid retrieval, reranking, domain indexing.
- Memory engineering:
  short-term context, long-term summary, selective injection, contamination control.
- Runtime architecture:
  streaming response, async processing, concurrency control, cost control.
- Evaluation:
  offline benchmarks, RAGAS, judge models, rule-based checks, before/after comparison.
- Reliability:
  replan, guardrails, failure recovery, structured observation, timeout strategy.

Weak AI bullets:
- "接入大模型实现智能问答"
- "使用 LangChain / LangChain4j 开发 Agent"
- "实现了 RAG"

Stronger AI bullets:
- "将复杂购退票请求拆解为 Planner JSON 计划，由 Executor 调用票务工具并消费结构化 ToolObservation 结果，结合 Replan 提升执行稳定性"
- "采用 Parent-Child 切片、Dense + BM25 双路召回与重排提升复杂政策问答命中质量"
- "构建短期滑动窗口 + 长期摘要压缩双层记忆，并按需注入长期摘要以降低上下文噪声"

## 3. Hybrid Positioning: Java + Agent

This positioning is strongest when the resume proves both:

- classical backend engineering competence
- applied AI systems engineering competence

The candidate should not read like a pure CRUD engineer.
The candidate should also not read like a prompt-only AI tinkerer.

Target impression:
- can build stable services
- can integrate AI into real systems
- understands architecture, evaluation, and operational tradeoffs

## 4. What Large Tech Recruiters Usually Filter For

In practice, first-pass filtering often looks for:

- familiar tech nouns aligned with JD
- evidence of ownership
- scale, performance, reliability, or quality outcomes
- project complexity that matches target level
- absence of vague filler

So the resume should surface, early and repeatedly:
- Java
- Spring Boot
- MySQL
- Redis
- MQ
- distributed lock / transaction / consistency
- WebFlux / Reactor if reactive engineering is real
- LangChain4j / LangGraph / Dify only as means, not as the core story
- RAG / rerank / memory / tool calling / evaluation where relevant

## 5. Ordering Guidance

For a Java + Agent candidate, prioritize:

1. Strong backend project with concurrency / consistency / reliability
2. Strong AI application / Agent project with architecture and evaluation
3. Skill section that groups backend and Agent capabilities cleanly

Do not bury backend depth under AI buzzwords.
Do not bury AI system design under generic backend wording.
