# Resume Writing Rules

Use this file when rewriting bullets, project summaries, or skill sections.

## 1. Bullet Formula

Preferred formula:

- context/problem
- technical action
- mechanism
- result

Examples:

- "基于 Redis Lua 实现库存原子预扣减，并设计分桶库存模型分摊热点流量，降低单 Key 冲突，在压测场景下未观察到超卖。"
- "采用 Parent-Child 切片、Dense + BM25 双路召回与重排优化复杂政策检索链路，提升命中质量并减少无关召回。"

## 2. Strong vs Weak Wording

Replace weak verbs:

- `参与` -> `设计` / `实现` / `重构` / `优化` / `搭建`
- `负责` -> say what was actually built
- `熟悉` -> move to skill section, not project bullets
- `了解` -> usually delete

Prefer engineering nouns:

- "缓存一致性"
- "幂等补偿"
- "事务回查"
- "双路召回"
- "结构化观测"
- "上下文桥接"
- "背压控制"
- "量化评估"

## 3. Quantification Rules

Use real metrics when available:

- QPS
- P95 latency
- recall / hit rate / answer quality gain
- token or cost reduction
- timeout rate / failure rate / duplicate execution reduction

If real metrics are unavailable, use honest bounded evidence:

- "降低重复请求成本"
- "减少上下文噪声"
- "提升链路稳定性"
- "压测场景下未观察到超卖"
- "解决 MQ 重试导致的重复补库存问题"

Never fabricate numbers.

## 4. Skill Section Rules

A good skill section for this domain should:

- lead with strongest language and engineering foundations
- group related items
- avoid long shopping lists

Recommended grouping:

- Java / concurrency / JVM
- Spring Boot / MyBatis / MySQL
- Redis / Redisson / Lua / MQ / Sentinel
- WebFlux / Reactor / streaming
- LangChain4j / LangGraph / Dify / tool calling / RAG / rerank / evaluation

Bad skill section:
- just a comma-separated list with no hierarchy

Better skill section:
- each line signals a capability cluster

## 5. First-Page Priority

The top half of the first page should already show:

- target role alignment
- strongest tech stack
- one backend-depth signal
- one AI-systems signal if applying for Agent roles

If the first half page is generic, the resume is weak even if later content is good.

## 6. Review Red Flags

Rewrite when you see:

- stack names with no mechanism
- claims that sound like blog summaries
- too many adjectives, too few systems details
- too many bullets that begin the same way
- English buzzwords pasted in without context
- bullets too long to scan but still not specific

## 7. Truthfulness Constraint

Improve signal by:

- reordering
- compressing
- clarifying ownership
- replacing vague verbs
- surfacing mechanisms

Do not improve signal by:

- inventing metrics
- claiming sole ownership without evidence
- upgrading experiments into production if they were not production
- claiming architecture depth that the user cannot explain in interview
