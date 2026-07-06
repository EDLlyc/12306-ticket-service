# 12306 Python Agent 面试讲解稿

## 30 秒定位

这个项目不是把 12306 票务系统简单接一个聊天机器人，而是把票务系统拆成两层：

- Java 票务内核：负责查票、订票、退票、订单、库存扣减、RocketMQ 事务消息和最终一致性。
- Python Agent 编排层：负责意图识别、计划生成、工具调用、RAG、MCP 外部工具、记忆、流式交互和评测。

这样做的好处是：高并发交易能力仍由 Java 保证，LLM/Agent 变化快的部分放到 Python，便于使用 LangGraph、MCP、DeepEval 等生态。

## 架构

```text
用户 / 前端
  -> Python FastAPI Agent
      -> LangGraph 状态图
          -> semantic cache
          -> query rewrite
          -> intent routing
          -> context bridge
          -> action planner / RAG planner
          -> tool executor
              -> Java 票务服务
              -> Weather MCP Server
          -> response / SSE stream

Java 票务服务
  -> Redis / Lua / Redisson
  -> MySQL
  -> RocketMQ
  -> Sentinel

Weather MCP Server
  -> MCP JSON-RPC tools/list
  -> MCP JSON-RPC tools/call
  -> Open-Meteo
```

## 典型问题

用户问：

```text
明天北京到上海适合坐高铁吗
```

执行链路：

1. Intent Router 识别为票务/出行建议类请求。
2. Planner 从问题中解析日期、出发地、目的地。
3. Planner 生成 `travel_advice` 工具计划。
4. ToolExecutor 调 Java 票务服务查询北京到上海车次。
5. ToolExecutor 调 Weather MCP Server 两次，分别查北京和上海天气。
6. Agent 汇总车次可用性、出发地天气、目的地天气和出行风险。
7. trace 记录 selected tool、tool args、final observation、MCP tools/call 元数据。

## 为什么加 MCP

天气不是 12306 交易内核的一部分，不适合直接写进 Java 票务服务。MCP 适合承载这类外部只读工具：

- 工具边界清晰：`get_weather_by_city(city, date)`。
- Agent 不关心天气 API 细节，只通过 MCP `tools/call` 调用。
- 可以在 trace 中明确看到外部工具调用。
- 未来可以继续接地图、酒店、日历等外部出行工具。

第一版只接只读天气工具，不接订票/退票这类副作用工具，避免 LLM 误触发高风险动作。

## 高风险工具治理

订票、退票属于副作用工具。项目里做了四层保护：

- 工具分级：`book_ticket`、`refund_order` 等标记为 high risk / side effect。
- 显式确认：首次命中高风险工具只保存 pending action 并提示用户确认，用户回复 `确认` 后才执行，回复 `取消` 会清除 pending action。
- 执行去重：同一计划里重复出现副作用工具时会触发重复调用拦截。
- Replan：工具返回未知、空结果或执行异常时进入重规划，而不是盲目继续执行。
- 登录边界：未登录时不会进入确认态，直接提示用户先登录。

## RAG 链路

政策类问题不走动作工具，而是走 RAG：

- Milvus Dense 召回
- OpenSearch BM25 Sparse 召回
- RRF 融合
- Reranker 精排
- Planned RAG 子问题拆解
- Redis 语义缓存
- 短期窗口记忆 + 长期摘要记忆

可以讲的例子：

```text
学生票资质核验有什么要求？
```

这类问题不会调用订票/退票工具，而是走铁路规则知识库。

## DeepEval / Agent Eval

项目里做了两层评测。

第一层是确定性 golden-case 评测：

- goal 是否正确
- tool selection 是否正确
- tool args 是否正确
- answer 是否包含关键片段
- MCP trace 是否存在
- 副作用工具是否出现重复执行风险

第二层是可选 LLM-as-judge：

- 使用 DeepEval G-Eval。
- judge 可以走智谱 OpenAI-compatible 接口。
- 输入包括用户问题、期望任务、实际回答、selected tool、tool args、final observation 和评审 rubric。
- LLM judge 只补充回答质量评分，不替代确定性断言。

运行命令：

```bash
.venv/bin/python -m python_agent.evals.evaluate_deepeval_agent
```

使用智谱 judge：

```bash
export ZHIPU_API_KEY='your-key'
.venv/bin/python -m python_agent.evals.evaluate_deepeval_agent \
  --require-deepeval-judge \
  --judge-provider zhipu
```

## 3 分钟讲法

我把项目拆成 Java 交易内核和 Python Agent 编排层。Java 侧负责高并发票务链路，包括 Redis Lua 库存预扣、RocketMQ 事务消息和退款补偿；Python 侧负责 Agent 的状态机、工具调用、RAG 和评测。

在 Python Agent 里，我用 LangGraph 把语义缓存、问题改写、意图路由、上下文桥接、RAG、Action Planner、Tool Executor 串起来。动作类问题会生成 ActionPlan，再由 Executor 调 Java 票务工具；政策类问题走 Milvus + OpenSearch 的混合检索。

为了验证多工具协同，我加了一个出行建议场景。用户问“明天北京到上海适合坐高铁吗”，Agent 会先查 Java 车次工具，再通过 MCP 调天气工具分别查北京和上海天气，最后综合车次、两地天气和风险给建议。这个 MCP Server 是本地 JSON-RPC 服务，背后接 Open-Meteo；Agent 只依赖标准 `tools/call`。

评测上我没有只看最终回答，而是做了 Agent golden cases，检查工具选择、参数、MCP trace 和副作用安全；另外接了 DeepEval G-Eval，可以用智谱模型做 LLM-as-judge，评估回答是否忠实于工具结果、是否完成任务。

## 面试官可能追问

**为什么不用 Java 写 Agent？**

Java 更适合保留稳定交易内核。Agent 编排、MCP、评测和模型生态在 Python 里迭代更快，拆层后两边职责更清楚。

**MCP 和普通 HTTP 工具有区别吗？**

普通 HTTP 是业务接口，MCP 是面向 Agent 的工具协议。它可以标准化工具发现、参数 schema、调用结果和 trace。这个项目里天气工具通过 MCP 接入，Agent 不关心具体天气 API。

**LLM 误调用订票/退票怎么办？**

第一版把天气 MCP 限制为只读工具；订票/退票仍走受控 Executor，并标记为副作用工具，做重复调用拦截。进一步可以加确认态和幂等键。

**DeepEval 有什么价值？**

确定性测试能测工具和参数，但不能很好判断回答质量。DeepEval G-Eval 用 LLM 判断回答是否忠实于工具 trace、是否完成用户任务。它是补充评分，不替代 deterministic checks。
