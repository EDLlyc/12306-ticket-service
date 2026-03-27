# 🚀 12306 智能购票系统 - 核心八股与面试亮点全解

这份文档基于你项目中真实的架构（**SpringBoot + Redis + Redisson + RocketMQ + Milvus + 智谱 GLM4**），提炼了面试官最爱问的“八股文”和可以作为亮点的实战经验。

请结合你代码中真实的实现（如 [TicketController](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/controller/TicketController.java#22-150) 中的缓存逻辑和 [RagServiceImpl](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/service/impl/RagServiceImpl.java#24-99) 中的向量逻辑）进行理解。

---

## 第一部分：后端高并发架构核心八股 (后端大类)

### 1. 缓存三剑客 (穿透、击穿、雪崩)

> **🌟 简历亮点话术 (可直接复制)：** 
> "针对 12306 查票/购票的极高并发场景，设计了 **Caffeine + Redis 多级缓存架构**，并配合 **Redisson 分布式锁** 彻底解决了缓存击穿与穿透问题。在扣减库存链路中，通过 **Redis Lua 脚本** 保证了扣减动作的原子性，成功解决了高并发下的超卖超量问题。"

在你的 [queryTrain](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/controller/TicketController.java#71-118) 接口中，体现了完整的防御体系：

*   **缓存穿透（查不到的数据疯狂查）**
    *   **现象**：黑客故意请求 `trainNumber = -1`，缓存查不到，全压给 MySQL，导致 DB 宕机。
    *   **你的项目怎么解决的？**：当你查数据库发现车次真的不存在时，你使用 `redisTemplate.opsForValue().set(cacheKey, "EMPTY", 5, TimeUnit.MINUTES)`，把**空对象**存入 Redis，并设置了一个较短的过期时间。下次黑客再查，直接拦截。

*   **缓存击穿（热点数据突然失效）**
    *   **现象**：一辆非常火的京沪高铁，缓存刚好到期，那一瞬间有 10000 个请求查不到缓存，全部冲向 MySQL 并且去重建缓存，导致 DB 瞬间被打瘫。
    *   **你的项目怎么解决的？**：你引入了 **Redisson 分布式锁**。
        1. 当发现缓存为空时，不是所有人都能去查 DB。
        2. `lock.tryLock(3, 10, TimeUnit.SECONDS)` 限制只能有一个线程拿到锁。
        3. 拿到锁之后进行**双重检查（Double Check）**，也就是再查一次缓存（防止其他拿到过锁的线程已经把数据放进去了）。
        4. 最后才去查 DB 写缓存。

*   **缓存雪崩（大量缓存同时失效）**
    *   **现象**：系统重启或大批缓存设置了相同的过期时间，导致某一时刻齐刷刷失效，查库洪峰到来。
    *   **你的方案（可扩展回答）**：项目中对写入二级缓存（Redis）的时间可以加一个随机数（比如 `30分钟 + Random(0~5分钟)`），避免集中过期。你还用到了 Caffeine 一级缓存作为保护。

### 2. 多级缓存架构 (Caffeine + Redis)
*   **面试官发问**：为什么要搞两层？只有 Redis 不好吗？
*   **你的回答**：
    1. Redis 虽然快，但还是需要建立网络连接（网络开销）。
    2. Caffeine 是进程内的本地缓存，速度是纳秒级，完全没有网络开销，可以抵抗第一波极高并发的洪峰。
    3. **策略**：请求先打本地缓存（Caffeine），没有再打分布式缓存（Redis），最后才打 DB。这极大地保护了底层的可用性。

### 3. Redis Lua 脚本原子性扣库存防超卖
*   **面试官发问**：讲讲你是怎么做抢票库存扣减的？会有并发安全问题吗？
*   **你的方案**：在 [bookTicketLua](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/controller/TicketController.java#118-141) 或者底层事务中，你使用了一段 Lua 脚本。
    *   **为什么用 Lua 脚本？**：因为高并发下，先 [get](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/controller/TicketController.java#142-149) 查询库存是否大于 0，然后再 `decr` 减库存，这两步加在一起**不是原子操作**。在两步之间，库存可能被别人减光了，就会导致卖超。
    *   **Lua 的优势**：Redis 会把整个 Lua 脚本作为一个整体去执行，执行过程中不会被其他命令插入，完美保证了动作的**原子性**。

### 4. RocketMQ 事务消息保障最终一致性 (核心流程)

这是解决 **“Redis 扣了库存，MySQL 没生成订单”** 这种分布式不一致问题的终极方案。

#### 形象理解：包裹寄送比喻
*   **Half Message (半消息)**：你把包裹给了快递员，但他先**扣下不发**，等你的确认指令。
*   **Local Transaction (本地事务)**：你回家确认一下家里是不是真的有货（**Redis 扣库存**）。
*   **Commit / Rollback**：
    *   如果有货，给快递员发个短信：“发货！”（**Commit**，消费者可见）。
    *   如果没货，给快递员发个短信：“包裹扔了！”（**Rollback**，消息取消）。
*   **Transaction Check Back (补偿回查)**：如果你忘了给快递员发短信，快递员会**主动打电话问你**：“那个包裹到底发不发？”（**回查状态**）。

#### 深度解析：三阶段执行流程
1.  **第一阶段 (Prepare)**：生产者发送 **Half Message**。MQ 收到后存入特殊的队列，此时消费者不可见。
2.  **第二阶段 (Execute)**：MQ 回调生产者的 `executeLocalTransaction`。在你的项目里，这里执行 **Redis Lua 脚本** 扣减库存。
3.  **第三阶段 (Finalize)**：根据本地事务结果返回 `COMMIT` 或 `ROLLBACK`。
    *   **回查机制**：如果因为网络断了 MQ 没收到 Finalize 指令，MQ 会定时向生产者发起的 `checkLocalTransaction`。

#### 深度解析：项目中 `TicketTransactionListener` 的落地实现

在你的 [TicketTransactionListener.java](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/mq/TicketTransactionListener.java) 中，体现了极高的生产实践参考价值：

1.  **原子扣减与标记 (executeLocalTransaction)**：
    *   **Redis Lua 脚本**：首先执行 Lua 脚本原子性扣减 Redis 库存。
    *   **DB 预记录**：扣减成功后，立即向 `t_order` 表插入一条状态为 `PENDING` 的记录。
    *   **作用**：这条 DB 记录就是**“本地事务执行成功”的证据**。如果这一步报错，说明 Redis 虽然扣了但逻辑没跑完，代码里有专门的 **catch 块进行 Redis 库存回补** (increment)，保证不丢票。

2.  **宕机补偿与状态回查 (checkLocalTransaction)**：
    *   **场景**：如果执行完 DB 插入，此时服务器突然断电，MQ 没收到 COMMIT 确认。
    *   **逻辑**：MQ Broker 会在几秒后发起回查。
    *   **绝杀招式**：回查代码并不再去操作 Redis，而是直接去 **MySQL 查有没有刚才那个 `order_sn` 的记录**。
    *   **判断**：
        *   有记录 → 说明刚才本地逻辑跑完了，直接补发 `COMMIT`，让消费者去落库正式订单。
        *   没记录 → 说明刚才逻辑没跑完或者失败了，返回 `ROLLBACK`。

#### 🌟 面试官：如果 Redis 扣了但回补失败了怎么办？
*   **你的回答**：代码中捕获了异常并尝试 `increment` 回补，如果回补也失败了（极极端网络抖动），会打印 **ERROR 级别日志并触发报警**，需要人工介入或定时任务对账修复。这是分布式系统中“一致性”与“可用性”权衡后的最佳实践。

### 5. MQ 消息防重复消费与幂等性
*   **面试官发问**：Consumer 重复收到一样的消息怎么处理？比如网络抖动，MQ 以为你没消费，又发给你一遍。
*   **你的方案 ([TicketConsumer](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/mq/TicketConsumer.java#14-56))**：利用数据库的**唯一约束**来实现绝杀幂等。
    1. 业务生成了唯一的 `order_sn`，并且你在对应表里建立了唯一索引。
    2. 插入订单以及减 MySQL 库存的代码，你套了 `@Transactional`。
    3. 当重复消息过来再次尝试 `INSERT` 时，会抛出 `DuplicateKeyException`。你在 `catch` 块里捕获它打个日志直接忽略即可，这样就保证了不管尝试多少次，影响结果等价于只尝试了一次（**幂等**）。

---

## 第二部分：AI 与 RAG (检索增强生成) 面试高亮区

这是你在众多竞品项目中可以形成降维打击的加分项。

### 1. 什么是 RAG？为什么要用 RAG？

> **🌟 简历亮点话术 (可直接复制)：** 
> "主导研发了铁路购票客服领域的智能分析模块，基于 **LangChain4j 框架** 与大语言模型对接。摒弃了简单的内存检索，**部署并集成了生产级的 Milvus 分布式向量数据库**，构建了完整的 **RAG (检索增强生成)** 知识库链路，彻底解决了大模型在回答专业铁路新规时产生‘幻觉’的痛点。"

*   **面试官发问**：你的项目结合了 AI，具体是怎么做的？
*   **你可以这样背**：RAG 的全称是 Retrieval-Augmented Generation。大模型虽然聪明，但它的知识是截止到训练那一天的，而且不懂企业内部的私有规章（比如最新的退票政策），且容易产生**幻觉 (Hallucination)** 瞎编乱造。
*   **项目应用**：我用 RAG 为 12306 搭建了智能客服。本质是用**自己的知识库**去检索出最贴切的段落，然后再把问题连同这段规章一起“喂”给大模型，让它“开卷考试”，不仅回答极度准确拟人，而且从不瞎编。

### 2. RAG 的核心链路 (项目中的 [RagServiceImpl](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/service/impl/RagServiceImpl.java#24-99))
*   **面试官发问**：描述一下你项目中 RAG 的全流程。
*   **必须掌握的四大步**：
    1.  **加载与切分 (Document Splitting)**：把长长的 PDF/txt 规章制度文本（比如用 PDFBox 解析），按照 500 字一块（加一点 overlap 重叠防止切断上下文），拆成小 Document 块。
    2.  **向量化 (Embedding)**：利用智谱大模型的 Embedding API，把切碎的每一句话，翻译成代表其数学语义的由浮点数组成的数组（向量）。
    3.  **入库 (Vector Database)**：把这些向量存进了 **Milvus** 向量数据库里。
    4.  **检索与生成 (Retrieve & QA)**：用户问“儿童票多高免费？”，系统先把问题也转化为向量，去 Milvus 里求**余弦相似度（或者内积、L2距离）**，把最相似的排名前 3 的规章段落捞出来，拼接成 Prompt，发回给 Chat 模型总结作答。

### 3. 为何选用 Milvus？
*   **重点展示技术深度**：
    我不满意简单的 `InMemoryEmbeddingStore`，虽然它在 Spring 内存里直接玩很方便。我选择了业界主流的分布式引擎 **Milvus**，它采用云原生存算分离架构。
    *   在我们的业务中，它不仅支持百亿级规模的近似最近邻（ANN）低延迟搜索，而且配合 Docker 部署开箱即用，是企业中大规模建立推荐系统、图片搜索和 RAG 底座的标准答案。

### 4. RAG 的痛点及如何优化 (进阶加分项)
如果面试官问得很深，你可以抛出这些平时遇到的问题和解决思路：
*   **Chunk 策略**：按字数强行切块容易把一句话切破，导致语义丢失。进阶做法是按语义（按句子或段落标记）或者加上一定的 Overlap（重叠区）。
*   **召回准确率低**：有时候仅仅做向量粗排是不够的，通常要配合一个重排模型（Reranker），或者引出**混合检索 (Hybrid Search)**，把全文检索（BM25 找关键词）和向量检索（找语义）结合起来。

---

## 第三部分：常考基础框架与组件八股 (防身必备)

既然你的项目用了目前简历上最吃香的微服务架构（Redis、RocketMQ、Spring Boot），面试官肯定会“向下深挖”问你这些底层的基本功。这里只列出**和你当前 12306 项目强相关的基础八股**：

### 1. 缓存基础（Redis 本身）
*   **问：你在 12306 项目里用到了 Redis 的哪些数据结构？**
    *   **答**：
        1.  **String（字符串）**：最常用的，用来存车次缓存、Token、甚至是简单的防超卖库存数字（基于 `INCR/DECR`）。
        2.  **Hash（哈希）**：也可以用来存车次详情（比如把一列火车的商务座、一等座余票分别存为一个 Hash 的小 field，便于局部修改）。
        3.  **Set（集合）**：我在防重复提交或者存储用户唯一标识（比如抢到票的用户 ID）时用过，天然去重。
*   **问：Redis 如果内存满了怎么办？（数据淘汰策略）**
    *   **答**：我们在 [application.yml](file:///d:/Project/12306-ticket-service/src/main/resources/application.yml) 里一般会配置 `maxmemory-policy`。常用的有 `allkeys-lru`（淘汰最近最少使用的缓存），这样能保证 12306 里最热点的京沪高铁车次一直留在内存里，没常人搜的冷门线路会被淘汰。

### 2. 消息队列基础（RocketMQ 原理）
*   **问：引入 RocketMQ 后，怎么防止消息丢失？**
    *   **答**：在 12306 的买票链路里绝不能丢消息。防丢要从三端来答：
        1.  **生产者端**：就像我前面的 [TicketTransactionListener](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/mq/TicketTransactionListener.java#13-52) 事务消息，或者开启了 Broker 的 ACK 确认机制。
        2.  **Broker 端**：配置同步刷盘（`SYNC_FLUSH`），先把消息实实在在写进磁盘再返回成功，而不是放在操作系统内存里就返回。
        3.  **消费者端**：消费者只有在自己的本地 DB 事务（订单入库）彻底成功后，才向 MQ 返回 `ConsumeConcurrentlyStatus.CONSUME_SUCCESS`，否则告诉 MQ 稍后再重试。
*   **问：怎么保证 RocketMQ 消费的顺序性？（比如先退票再买票）**
    *   **答**：只有把同一个用户的订单操作都扔进同一个 `MessageQueue` 里，并在消费者端单线程去拉取，才能保证顺序。通常在发送消息时，根据订单 ID 对队列数量取模（hash 法）来指定发往哪个具体的 Queue。

### 3. Spring Boot 原理
*   **问：你的 12306 接口是怎么做统一异常处理和参数校验的？**
    *   **答**：我在 Controller 层并不是每个方法都写 try-catch。
        1.  **参数校验**：利用了 Spring Boot Validation（`@Valid` / `@NotBlank` / `@NotNull`），直接在请求体实体类上打注解。
        2.  **全局异常**：写了一个类打上 `@RestControllerAdvice` 注解，定义用 `@ExceptionHandler(Exception.class)` 去捕获抛出的公共异常或者是我的自定义抢票异常（`BizException`），然后给前端返回统一的 JSON 格式（包含 code 和 msg）。

### 4. ReentrantLock 与 synchronized 核心区别 (面试必考)

> **🌟 面试官考察重点：** 
> 考察你是否只停留在“会用”层面，还是透彻理解了 **JVM 锁优化**与 **AQS (AbstractQueuedSynchronizer)** 的底层机制。

| 特性 | synchronized | ReentrantLock |
| :--- | :--- | :--- |
| **实现层面** | JVM 层面 (关键字) | JDK 层面 (API / AQS 实现) |
| **灵活性** | 低（由 JVM 自动维护，死板） | 高（支持中断、超时尝试、多个 Condition） |
| **公平性** | 只支持非公平 | 支持公平与非公平 (默认非公平) |
| **等待唤醒机制** | `wait / notify` (配合 Object 监视器) | `Condition` (`await / signal`) |
| **锁的释放** | 代码执行完或报异常，JVM 自动释放 | 必须在 `finally` 中手动释放（容易死锁） |

#### 深度解析 1：synchronized 的底层实现 (锁升级)
在你的项目代码里，简单的针对单机缓存更新加锁，通常用 `synchronized`。
*   **底层：** 基于 `Monitor` 对象实现，对应编译后的指令是 `MonitorEnter` 和 `MonitorExit`。
*   **锁升级过程 (Java 6+)：** 
    1.  **无锁** (No Lock)
    2.  **偏向锁** (Biased Lock)：锁会偏向第一个访问它的线程，后续该线程进入不需要 CAS。
    3.  **轻量级锁** (Lightweight Lock)：当有竞争时，由于线程在原地“自旋”等待，不阻塞 CPU，效率高。
    4.  **重量级锁** (Heavyweight Lock)：自旋超时或竞争激烈，升级为重量级锁，线程进入阻塞队列，交给 OS 调度。

#### 深度解析 2：ReentrantLock 的底层实现 (AQS)
如果你在项目中需要做更复杂的并发控制（比如限制排队时间），你会用到 `ReentrantLock`。
*   **核心：** 基于 **AQS (AbstractQueuedSynchronizer)**。
*   **关键要素：**
    1.  **State 状态：** 一个 `volatile int` 类型的变量，0 表示空闲，1 表示被占用，>1 表示重入次数。
    2.  **CAS 操作：** 使用 `compareAndSetState` 尝试抢锁，抢不到则进入等待队列。
    3.  **CLH 队列：** 一个双向链表组成的等待队列。
    4.  **LockSupport：** 通过 `park()` 和 `unpark()` 挂起和唤醒线程。

#### 💡 面试官追问：既然性能差不多，怎么选？
*   **优先选 synchronized**：
    1. 代码更简洁，不容易漏掉 `unlock` 导致死锁。
    2. JVM 会持续优化（比如锁消除、锁粗化）。
*   **选 ReentrantLock**：
    1. 需要**公平锁**时。
    2. 需要能够**响应中断**、**定时尝试获取锁**（防止死锁）时。
    3. 需要在一个锁上绑定多个 **Condition** 队列（更精准的唤醒）时。

### 5. Redis ZSet (有序集合) 底层原理与实战

> **🌟 面试官常考点：** 
> 考察你对 **跳表 (Skip List)** 的理解，以及为什么 Redis 不用红黑树。

| 特性 | ZSet (Sorted Set) |
| :--- | :--- |
| **构成** | **Member (成员)**: 唯一，不能重复；**Score (分值)**: 浮点数，可重复。 |
| **底层实现** | **Listpack (紧凑列表)** + **Skip List (跳表)**。 |
| **时间复杂度** | 插入、删除、查询均为 **O(log N)**。 |

#### 深度解析 1：底层实现切换机制
Redis 会根据 ZSet 的大小自动切换底层结构：
1.  **Listpack (新版) / Ziplist (旧版)**：
    *   **触发条件**：元素数量少（默认 < 128）且每个元素长度小（默认 < 64 字节）。
    *   **优点**：内存极其紧凑，省空间。
2.  **Skip List (跳表)**：
    *   **触发条件**：超出上述限制后升级。
    *   **核心逻辑**：在普通链表基础上增加多级索引，通过“空间换时间”实现类似二分查找的效果。

#### 深度解析 2：为什么选用跳表而不是红黑树？
这是一个经典的“求职绝杀题”：
1.  **范围查询更简单**：跳表底层是链表，找到最小值后顺着链表往后走即可；红黑树做范围查询（Range Query）需要中序遍历，逻辑复杂。
2.  **实现更简单**：跳表的代码实现比红黑树简单得多，且在高并发修改下，跳表不需要像红黑树那样做复杂的旋转平衡，只需调整前后指针。
3.  **并发友好**：跳表的局部性更好，更新时锁的粒度可以更小。

#### 💡 实战场景：12306 里的 ZSet 用在哪？
*   **延时任务 (Delay Queue)**：
    *   **场景**：用户下单后 15 分钟不支付自动取消订单。
    *   **逻辑**：把 `orderId` 作为 Member，`下单时间 + 15分钟` 作为 Score 存入 ZSet。系统有个轮询线程，用 `ZRANGEBYSCORE` 查当前时间之前的订单进行处理。
*   **实时排行**：
    *   **场景**：热门购票线路排行榜。
    *   **逻辑**：`ZINCRBY top_trains 1 "G101"`。

---

## 第六部分：MySQL 数据库八股与进阶

### 1. 索引底层原理：为什么是 B+ 树？

> **🌟 面试常考：** 为什么不用哈希表、平衡二分树、B 树？

*   **B+ 树 vs B 树**：
    1.  **更强的范围查询**：B+ 树叶子节点由链表相连，拿到首节点后可以顺藤摸瓜。
    2.  **更少的磁盘 IO**：B+ 树非叶子节点只存键，扇出更大，树更“矮胖”。
    3.  **查询性能更稳定**：大家都必须搜到叶子节点，查询时间一致。

### 2. 形象理解：聚簇索引、叶子节点与回表 (重点理解区)

如果你觉得术语太抽象，请看这个 **“图书馆”比喻**：

*   **聚簇索引 (主键索引)**：想象成图书馆里的 **“物理书架”**。书是按 `ID` 号从小到大整齐排列的。
    *   **叶子节点**：就是书架上那本 **“实实在在的书”**。你找到了 ID，就直接拿到了书的内容。
*   **二级索引 (辅助索引)**：想象成门口的一个 **“名片索引柜”**。名片上写着 `作者名` 和对应书的 `ID`。
    *   **叶子节点**：就是那张 **“索引名片”**。它不是书，只是一张纸条。
*   **什么是回表 (Look-up)？**
    *   你通过“作者名”找到了名片（二级索引），名片告诉你书的 ID 是 101。
    *   你必须拿着这个 ID，再走回大书架（聚簇索引）去找 101 号那本书。
    *   **这个“查完名片去翻书架”的过程，就叫“回表”。**
*   **什么是覆盖索引 (Covering Index)？**
    *   如果你查的是“作者名为张三的书其 ID 是多少？”，名片上已经写了 ID 了。
    *   你不用再去翻书架了，原地就能拿到结果。**这就是覆盖索引，不用回表，速度极快。**

### 3. 最左前缀原则与索引下推 (ICP)

*   **最左前缀**：联合索引 `(A, B, C)` 就像电话簿，先按姓排，再按名排。你跳过姓直接查名，索引就失效了。
*   **索引下推 (ICP)**：在查名片（二级索引）的时候，顺便把其他条件（比如年纪）也过滤了，减少去翻书架（回表）的次数。

### 💡 实战建议 (12306 系统中)：
*   **一定要建主键**：不要用 UUID，要用趋势递增的 ID，减少页分裂。

---

## 第七部分：微服务高可用防护 (Sentinel) 与 接口规范

### 1. Sentinel：核心接口的“保险丝”

> **🌟 面试话术：** 
> “在高并发抢票瞬间，瞬时流量可能是平时的百倍。为了不让系统直接崩溃，我们引入了 **Sentinel** 实现流量控制与熔断降级。”

*   **流量控制 (Flow Control)**：针对核心购票接口，根据 QPS（每秒请求数）设定阈值。如果超过，Sentinel 会按照“快速失败”或“排队等待”策略拦截多余请求。
*   **熔断降级 (Degradation)**：
    *   **RT (响应时间)**：如果余票查询接口因为数据库卡顿，响应时间变慢（比如超过 1s），Sentinel 会触发熔断，在接下来的几秒内直接返回预设的“默认结果”，防止请求堆积拖垮整个系统。
    *   **异常比例**：如果接口报错比例太高，自动熔断。

### 2. 全局统一返回值与异常捕获 (项目实战)

在你的代码中，通过 [GlobalExceptionHandler.java](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/common/GlobalExceptionHandler.java) 和 [Result.java](file:///d:/Project/12306-ticket-service/src/main/java/com/ahu/ticket/common/Result.java) 实现了标准化的接口管理：

*   **统一返回值 (`Result<T>`)**：
    *   包含了 `code` (状态码)、`message` (提示信息)、`data` (真实数据)。
    *   **好处**：前后端解耦，前端可以用一套逻辑处理所有接口返回值。
*   **统一异常捕获 (`@RestControllerAdvice`)**：
    *   **亮点：日志追踪 (traceId)**：在 `GlobalExceptionHandler` 中，通过 `MDC.get("traceId")` 获取并在异常日志中打印。
    *   **意义**：面试官非常看重这一点。在高并发分布式环境下，没有 `traceId` 你根本无法在成千上万行日志里定位某一次报错。通过全局拦截，不仅对前端更友好，也保证了敏感的堆栈信息不会暴露给外人。

#### 🌟 面试官：如果不用 Sentinel，你自己怎么实现一个简单的限流？
*   **答**：可以使用 Redis 的 `INCR` 配合 `EXPIRE` 实现单位时间内的计数，或者使用 Java 自带的 `Semaphore`（信号量）来限制并发线程数。但 Sentinel 的动态规则配置和可视化监控更适合复杂的 12306 业务。
