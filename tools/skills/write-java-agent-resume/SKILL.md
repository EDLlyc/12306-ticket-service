---
name: write-java-agent-resume
description: Use this skill when the user wants to write, rewrite, optimize, or review a resume for Java backend, distributed systems, AI application, RAG, or Agent engineering roles, especially for large Chinese tech companies. It is for converting project experience into recruiter-readable, ATS-friendly bullets with strong technical signal, quantified outcomes, and role-specific positioning.
---

# Write Java Agent Resume

Use this skill when the task is to produce or improve a resume for:
- Java backend roles
- distributed systems / middleware / platform roles
- AI application engineering roles
- RAG / Agent / LLM engineering roles
- mixed positioning such as "Java + Agent", "backend + AI application", or "engineering + intelligent systems"

This skill is optimized for Chinese big-tech style resume expectations:
- hard technical signal first
- project depth over vague self-description
- concrete ownership over "participated in"
- quantified or falsifiable outcomes where possible
- clear mapping from project content to target JD

## Workflow

1. Identify the target role mix.
   Infer whether the user is primarily:
   - Java backend
   - Java backend + Agent
   - AI application / Agent
   - platform / infrastructure with AI elements

2. Read the current resume or raw project notes.
   Extract:
   - systems built
   - scale / concurrency / latency / throughput / cost / stability signals
   - architecture decisions
   - tools, frameworks, middleware, data stores
   - evaluation methods
   - ownership and outcomes

3. Map each project to hiring signals.
   Use [references/role-signals.md](references/role-signals.md).
   For each bullet, decide which signal it proves:
   - language and framework depth
   - distributed system design
   - high-concurrency engineering
   - reliability / observability / production readiness
   - retrieval / planning / tool use / memory / evaluation
   - productized AI engineering rather than demo-level prompting

4. Rewrite bullets in evidence-first form.
   Use [references/resume-writing-rules.md](references/resume-writing-rules.md).
   Preferred pattern:
   - problem/context
   - technical action
   - mechanism/design choice
   - measurable or defensible result

5. Remove weak resume language.
   Rewrite or delete bullets dominated by:
   - "参与"
   - "负责协助"
   - "了解/熟悉/接触"
   - buzzwords without mechanism
   - stacks listed with no actual implementation detail

6. Separate Java signal and Agent signal explicitly.
   For hybrid candidates, the resume must make both lines legible:
   - Java line: concurrency, cache, MQ, transaction consistency, service governance, performance, DB
   - Agent line: orchestration, tool calling, RAG retrieval, rerank, planning, memory, evaluation, streaming, guardrails

7. Check for ATS and recruiter readability.
   Ensure:
   - target keywords appear naturally
   - section names are standard and scannable
   - bullets are not overlong when they can be split
   - important nouns appear early in each bullet

## Output Standard

When rewriting resume content:
- prefer strong declarative bullets
- keep one bullet focused on one core contribution
- mention exact frameworks, middleware, and mechanisms
- quantify with measured values if available
- if no metric exists, use bounded engineering evidence instead

Good bounded evidence examples:
- "压测场景下未观察到超卖"
- "引入事务回查与幂等补偿，解决 MQ 重试导致的重复回补"
- "通过 Parent-Child 切片、双路召回与重排提升复杂政策问答命中质量"

Avoid fake precision. Do not invent QPS, latency, token cost reduction, recall gain, or accuracy improvement.

## Role-Specific Guidance

### Java Backend

Emphasize:
- Java fundamentals, collections, concurrency, JVM
- Spring Boot / Spring Cloud ecosystem only if actually used
- MySQL indexing, transactions, isolation, SQL optimization
- Redis patterns, Lua, distributed locks, cache consistency
- MQ transaction flow, retries, idempotency, compensation
- Sentinel, thread pools, service protection, observability

### Agent / RAG / AI Application

Emphasize:
- not just "used LangChain4j / Dify / LangGraph"
- explain what was built with them
- planner / executor / replan
- tool interfaces and structured observations
- retrieval quality: chunking, dense + sparse recall, rerank
- memory design: short-term, long-term, on-demand injection
- streaming architecture and backpressure handling
- evaluation: RAGAS, LLM-as-a-judge, offline benchmark, A/B comparison

### Hybrid Java + Agent

Present the candidate as an engineer who can:
- build stable backend systems
- productize AI features in production architecture
- reason about consistency, latency, reliability, and evaluation

This is stronger than appearing as "prompt engineer".

## Review Checklist

When asked to review a resume, check:
- Does each project prove something job-relevant?
- Are there bullets that only name tools without showing architecture?
- Is there enough backend signal for Java roles?
- Is there enough engineering signal for Agent roles beyond prompts?
- Are results concrete, measured, or at least technically defensible?
- Is the top half of the first page strong enough for a recruiter skim?

## Editing Style

- Keep Chinese concise and technical.
- Prefer "基于 X 实现 Y，解决 Z" over decorative prose.
- Use English terms only when they are industry-standard and increase precision.
- Preserve truthfulness; compress, strengthen, and reorder, but do not fabricate.

## When To Read References

- Read [references/role-signals.md](references/role-signals.md) when deciding what big-tech Java / Agent roles reward.
- Read [references/resume-writing-rules.md](references/resume-writing-rules.md) when rewriting bullets, summaries, or skill sections.
- Read [references/source-links.md](references/source-links.md) when you need the external basis behind the heuristics or want to refresh the skill against newer JD patterns.
