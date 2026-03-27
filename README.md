# 12306 Ticket Service & AI Assistant 🚅

这是一个基于 Spring Boot 3.x 构建的仿 12306 票务系统，集成了**高并发秒杀架构**与**基于 RAG 的 AI 智能助理**。

## 🌟 项目亮点

- **高并发票务引擎**：采用 Redis + Caffeine 两级缓存架构，结合 Redisson 分布式锁与 Lua 脚本实现高性能库存扣减。
- **智能化 RAG 助理**：基于 LangChain4j 与智谱 AI (GLM-4.7) 打造，支持向量检索（Milvus）与 PDF 知识库，能够精准回答票务规则与购票建议。
- **Agent 智能工具箱**：AI 助理集成内置工具（Function Calling），可直接调用 Java 后端接口执行查库、订票与退票操作。
- **响应式流式输出**：通过 Spring WebFlux 实现 SSE (Server-Sent Events)，提供像 ChatGPT 一样的打字机式交互体验。
- **自动化质量评估**：独立 Python 模块（Ragas Eval），量化评估 RAG 系统在忠实度与相关性上的表现。

## 🛠️ 技术栈

- **后端**: Spring Boot 3.0.2, Spring WebFlux, MyBatis Plus
- **AI 框架**: LangChain4j 0.36.0
- **中间件**: MySQL, Redis (Redisson), RocketMQ, Milvus (向量库), Sentinel (限流)
- **本地缓存**: Caffeine
- **自动化测试**: JMeter 5.x, Ragas (Evaluation)
- **配置与部署**: Docker Compose

## 🚀 快速开始

### 1. 克隆与环境准备
确保您已安装：
- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- Python 3.9+ (用于 RAG 评估)

### 2. 启动中间件
使用项目根目录下的 Docker 配置文件启动基础设施：
```bash
docker-compose -f milvus-docker-compose.yml up -d
# 启动其他中间件 (MySQL, Redis, RocketMQ) ...
```

### 3. 配置应用
1. 复制配置模板：
   ```bash
   cp src/main/resources/application.yml.template src/main/resources/application.yml
   ```
2. 编辑 `src/main/resources/application.yml`，填入您的：
   - `ai.zhipu.api-key`
   - 数据库连接信息

### 4. 运行后端
```bash
mvn clean install
java -jar target/12306-ticket-service-0.0.1-SNAPSHOT.jar
```

## 📊 性能与评估

### RAG 系统评估
进入 `ragas_eval` 目录运行评估脚本：
```bash
cd ragas_eval
pip install -r requirements.txt
# 设置环境变量 ZHIPU_API_KEY
python evaluate.py
```

### 压测报告
项目包含 `ticket-query-load-test.jmx` 文件，可使用 JMeter 直接进行高并发压力测试。

## 📁 目录结构

- `src/main/java/com/ahu/ticket/rag`: RAG 核心逻辑与 Agent 工具。
- `src/main/java/com/ahu/ticket/service`: 业务服务层（高并发订票逻辑）。
- `ragas_eval`: 基于 Python 的 RAG 评估模块。
- `volumes`: 容器持久化数据。

## 📜 许可证

本项目仅用于学习与研究。
