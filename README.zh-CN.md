<div align="center">

# LoomVault

**为 AI Agent 工作流打造的持久化执行（durable execution）内核 —— 可重放、可恢复、对预算有感知。**

[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)](.github/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-132_passing-brightgreen.svg)](#测试)

*如果你的 Agent 工作流跑到一半挂了，你不该为已经跑完的前半程再付一次钱。*

[English](README.md) · **简体中文**

[快速开始](#快速开始) · [为什么](#为什么再造一个-agent-框架) · [工作原理](#工作原理) · [API](#api) · [路线图](#路线图)

</div>

---

## 问题所在

每一个在做 LLM 自动化的团队，都会按同样的顺序撞上同一堵墙：

1. **一次十分钟的运行，在第九分钟挂了。** 最直白的做法是「整个工作流重跑」—— 于是所有已经成功的模型调用被重新计费，所有已经发生的副作用被重新触发。给客户重复发一条通知，就是一次真事故。
2. **凌晨三点，某个 Agent 烧掉了 40 美元的 token。** 在账单到手之前没人会发现。「设个花费上限」说起来轻巧，但在大多数框架里，你根本无法按工作流去强制执行。
3. **昨天的运行没法复现。** 模型被升级了，提示词被改过了，某个工具的行为变了。而对话记录早已丢失，事后复盘只能靠猜。
4. **需要人来审批。** 于是工作流要么退化成 servlet 里的一个 `Thread.sleep`，要么变成一封邮件线程，要么……干脆什么都没有。

现有的 Python Agent 框架，优化的是**怎么表达**一个 Agent。LoomVault 优化的是**怎么在生产环境里跑起来**：每一步都进日志（journal），每次重启都是恢复而不是重跑，每一个 token 都对着一份声明式预算记账，任何一次运行都能从它自己的日志里重放出来。

## 你能得到什么

| | |
|---|---|
| **确定性重放（Deterministic replay）** | 节点输出被记入只追加（append-only）的日志。重启后的运行从日志里恢复已完成的节点，**不会重新执行它们** —— 所以成功的模型调用永远不会被重复计费，已提交的副作用也永远不会重放。 |
| **一行代码级别的崩溃恢复** | 启动时，引擎会把死进程遗留为 `RUNNING` 的执行找出来并恢复。Demo 里已验证：4 个并行节点保持了各自原有的时序，只有被中断的那个节点重新跑了一遍。 |
| **预算是一等公民** | 在工作流**和**节点两个层级上都能限制 token、成本、调用次数与墙钟时长，并提供四种策略 —— `FAIL`、`DEGRADE`（降级到更便宜的模型）、`TRUNCATE`、`WARN`。预算能扛过重启，所以崩溃循环（crash loop）无法靠反复挂掉来超支。 |
| **能扛过重启的人机协同（human-in-the-loop）** | 审批节点会让整个执行停下来（park）—— 状态已持久化，容器可以放心去死。几小时后有人点了通过，运行就从它停下的那一处继续。 |
| **内容寻址（content-addressed）的响应缓存** | 缓存键 = 模型 + temperature + messages 的 SHA-256。同样的输入重跑一次，每一次调用都从缓存命中，**零 token、零美元**（实测：941 tokens → 0）。 |
| **是有条件 DAG，不是一条链** | 并行扇出、带上下文合并的扇入、条件边，以及沿「未走分支」向下传播的跳过（skip-propagation）。调度跑在 **Java 21 虚拟线程（virtual threads）** 上。 |
| **没有 API Key 也能跑** | 默认是确定性的 mock provider，所以 `docker compose up` 几秒内就能产出一个完整、可检视的执行。改一个配置值就能指向 OpenAI/DeepSeek/Qwen/Ollama。 |

> **设计立场：** LoomVault 不是一个「拼提示词」的库。它是**托在它下面**的那一层 ——
> 决定某一步什么时候跑、允许它花多少钱、进程死掉时会发生什么，以及事后你凭什么证明
> 它确实按它宣称的方式做了。

## 快速开始

### Docker（PostgreSQL + Redis + 应用）

```bash
git clone https://github.com/<your-account>/loomvault.git
cd loomvault/deploy
docker compose up --build
```

打开 **http://localhost:8080** —— 三个示例工作流已经注册好了，还有一次 demo 运行正等着你审批。不需要 API Key，不需要任何配置。

### 本地运行，零依赖

```bash
mvn spring-boot:run -pl loomvault-server -Dspring-boot.run.profiles=standalone
```

使用内存 H2 数据库和进程内缓存。完整的 JDBC 持久化链路依然会被走一遍；不同的只是数据库引擎。需要 **JDK 21**。

### 五条命令，看懂全貌

```bash
# 1. 端到端跑一个工作流
curl -s -X POST 'localhost:8080/api/executions?wait=true' \
  -H 'Content-Type: application/json' \
  -d '{"workflowId":"support-triage","inputs":{"ticketId":"TCK-1001"}}' | jq '.status'
# → "WAITING_APPROVAL"   运行把自己停下来等人审批

# 2. 批准它 —— 运行从它停下的那一处继续
curl -s -X POST localhost:8080/api/executions/<id>/approve \
  -H 'Content-Type: application/json' -d '{"approver":"you"}' | jq '.status'
# → "SUCCEEDED"

# 3. 读那份让以上一切可审计的日志
curl -s localhost:8080/api/executions/<id>/journal | jq '.[] | {seq,type,nodeId}'

# 4. 用同样的输入重跑：每一次模型调用都由缓存接管，免费
curl -s -X POST 'localhost:8080/api/executions?wait=true' \
  -H 'Content-Type: application/json' \
  -d '{"workflowId":"content-review","inputs":{"draft":"..."}}' | jq '.usage'
# 第二次完全相同的运行 → {"totalTokens":0,"llmCalls":0,"cacheHits":5,"costUsd":0}

# 5. 故意把它搞崩，然后看它自愈
#    起一次运行，中途 `docker compose kill loomvault`，再 `docker compose up` ——
#    启动恢复会把它接上，日志里显示：replayedEntries=15 restored=4
```

想**亲眼看着**护栏触发？设置 `loomvault.llm.mock.fail-first-calls=2` 然后跑 `reliability-drill`：你会在日志里看到带退避（backoff）的真实重试，以及一份刻意收紧到 900 token 的预算，逼出 `DEGRADE` 策略。

## 为什么再造一个 Agent 框架？

问得对。下面是诚实的对比。

**对比 Python Agent 框架（LangChain / LangGraph / CrewAI）**
它们在「描述一个 Agent」这件事上非常出色，模型集成也远比我们丰富。但它们以 Python 为先、进程内运行，并且在持久化执行这部分 —— 只是把检查点（checkpoint）写成一个 blob，其语义还得你自己去推敲。如果你的技术栈是 Java，而你的要求是「扛过一次 Pod 重启且不重复扣费」，那这一层你横竖都得自己写。

**对比 Temporal / Cadence**
Temporal 是一个极优秀的通用持久化执行引擎，LoomVault 借用了它的核心思想。但它并非为「Agent 之所以为 Agent」的那些东西而设计：作为一等限制的 token/成本预算、内容寻址的模型缓存、预算承压时的模型降级，以及非工程师也能操作的审批节点。而且它要求你运维一个集群。LoomVault 只要一个 Spring Boot jar 加两个容器，并且它的成本模型是一等概念，而不是你在上面自己搭出来的东西。

**对比工作流编排器（Dify / n8n / Flowise）**
那些是低代码画布。LoomVault 在**创作（authoring）**上没有画布 —— 工作流就是你仓库里的 YAML，走 Pull Request 评审，配一张可以单元测试的图。内置 UI 是用来**运营**运行的，不是用来拖拽设计的。

**它填的那个空：** 一个 Java 原生的执行内核，在那里**持久性、成本治理与人机审批是同一个特性**，而不是三个集成。

## 工作原理

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│  loomvault-server          REST · SSE · 控制室 UI · Micrometer · 恢复              │
├──────────────────────────────────────────────────────────────────────────────────┤
│  loomvault-core            DAG 调度器 · 日志 · 重放 · 预算 · 表达式                 │
│                            (无 Spring、无 JDBC —— 可嵌入、可单元测试)              │
├───────────────────────────────┬──────────────────────────────────────────────────┤
│  loomvault-llm                │  loomvault-store                                 │
│  mock · openai-compatible     │  JDBC (PostgreSQL/H2) · in-memory                │
│  cache · circuit breaker      │  Flyway migrations                               │
└───────────────────────────────┴──────────────────────────────────────────────────┘
```

### 持久化执行，具体怎么走

```
   ┌─ journal (append-only, the source of truth) ─────────────────────────────┐
   │  #1 EXECUTION_STARTED        #6 NODE_COMPLETED  classify   {category:...}│
   │  #2 NODE_SCHEDULED fetch     #7 NODE_STARTED    score_risk               │
   │  #3 NODE_STARTED   fetch     #8 NODE_COMPLETED  score_risk {overall:...} │
   │  #4 NODE_COMPLETED fetch     ...                                          │
   │  #5 NODE_COMPLETED load_...  #15 APPROVAL_REQUESTED approve_reply        │
   └──────────────────────────────────────────────────────────────────────────┘
                                      │
                     process dies here ┘
                                      ▼
   on restart:  replay entries #1..#15
                ├─ completed nodes  → restored from their recorded outputs (no model calls)
                ├─ interrupted node → re-executed, with a RECOVERY_WARNING if non-idempotent
                └─ budget ledger    → re-hydrated with the tokens already spent
```

调度器（scheduler）是单线程的，独自持有全部可变状态（就绪队列、前驱计数器、在途映射）。工作线程（workers）在**不可变上下文快照**上运行处理器（handler），并把结果通过一个队列回传。这里没有任何加锁顺序可供写错；并行度来自虚拟线程把 IO 密集的模型调用扇出去，并由 `max-parallel-nodes` 封顶 —— 于是一次八个节点的扇出，不会把一个 provider 的瞬时抖动放大成一场重试风暴（retry storm）。

完整设计说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

### 成本治理

预算在工作流定义里声明，由调度器在两次完成之间强制执行：

```yaml
budget:
  maxTotalTokens: 24000
  maxLlmCalls: 12
  maxCostUsd: "0.75"
  onExceed: DEGRADE        # FAIL | DEGRADE | TRUNCATE | WARN
```

`DEGRADE` 让处理器回退到节点的 `fallbackModel`；`FAIL` 会中止运行并把它标记为 `BUDGET_EXCEEDED`。因为恢复时账本（ledger）是从日志重建的，所以这个上限能跨重启累计花费 —— 崩溃循环无法靠「死得足够勤」来超支。

### 表达式引擎

边条件与提示词模板共用同一门小巧的手写语言 —— 没有 SpEL、没有 JavaScript，也就没有表达式注入面：

```yaml
edges:
  - from: aggregate
    to: publish_ready
    condition: nodes.aggregate.pass == true && nodes.aggregate.overall >= 60
```

求值是**全函数（total）**的：缺失路径和除零都产出 `null`，而 `null` 在布尔位为假，所以条件永远不会在运行中途炸掉。未知的**函数**在解析期就被拒绝，于是一个拼写错误在工作流加载时就失败，而不是在凌晨三点。

## 模块

| 模块 | 内容 | 依赖 |
|---|---|---|
| `loomvault-core` | 领域模型、DAG 调度器、日志/重放、预算账本、表达式引擎、重试、端口（ports） | 仅 Jackson、SLF4J |
| `loomvault-llm` | 确定性 mock provider、OpenAI 兼容客户端、内容寻址缓存、熔断器（circuit breaker）、韧性装饰器 | core |
| `loomvault-store` | JDBC 与内存仓储、日志存储、Flyway 迁移（PostgreSQL + H2） | core、spring-jdbc、Flyway |
| `loomvault-server` | REST API、SSE、可观测性、启动恢复、内置控制室 | 以上全部、Spring Boot |

`loomvault-core` 没有任何 Spring 依赖。正因如此，引擎才能以毫秒级端到端测试，才能嵌入到任何东西里。

## API

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/executions` | 启动一次运行（`?wait=true` 则阻塞）。你提供 `executionId` 时是幂等（idempotent）的。 |
| `GET` | `/api/executions` | 最近的运行 |
| `GET` | `/api/executions/{id}` | 完整运行：状态、用量、逐节点状态 |
| `GET` | `/api/executions/{id}/journal` | 只追加的日志 |
| `GET` | `/api/executions/{id}/events` | **SSE** 实时事件流 |
| `POST` | `/api/executions/{id}/approve` · `/reject` | 裁决一个停下的审批节点 |
| `POST` | `/api/executions/{id}/resume` · `/cancel` | 手动恢复 / 取消 |
| `GET` `POST` `DELETE` | `/api/workflows[/{id}]` | 目录（注册时校验） |
| `POST` | `/api/workflows/validate` | 在不部署的前提下 lint 一份定义 |
| `GET` | `/api/system/{info,stats,tools,models,effects,live}` | 内省 |
| `GET` | `/actuator/prometheus` | 给 Grafana 用的指标 |

错误是统一的：一个稳定的 `code`（`GRAPH_CYCLE_DETECTED`、`BUDGET_EXCEEDED`、`LLM_CIRCUIT_OPEN`……）、一个在 `X-Trace-Id` 头里回显的 `traceId`，以及一个常常能直接告诉你哪个节点出问题的 `context` map。

## 可观测性

每一行日志都带着 `traceId`（来自 HTTP 请求）和 `executionId`（来自引擎），所以一次 grep 就能重建整次运行。指标是从事件流派生出来的，而不是散落在调度器各处：

`loomvault_executions_finished_total{status}` · `loomvault_nodes_settled_total{result}` ·
`loomvault_llm_tokens_total{kind}` · `loomvault_llm_cache_total{result}` ·
`loomvault_llm_cost_usd_total` · `loomvault_budget_events_total{type}` · `loomvault_retries_total`

## 配置

一切都放在 `application.yml` 的 `loomvault.*` 下。最要紧的几个开关：

| 键 | 默认值 | 说明 |
|---|---|---|
| `loomvault.engine.max-parallel-nodes` | `4` | 在途上限。保护你的 provider 配额最有效的一招。 |
| `loomvault.llm.provider` | `mock` | `mock` 或 `openai`（任何 `/v1/chat/completions` 端点：OpenAI、DeepSeek、Qwen、Ollama、vLLM）。 |
| `loomvault.llm.openai.base-url` / `api-key` | — | 或者设置 `LOOMVAULT_LLM_OPENAI_BASE_URL` / `LOOMVAULT_LLM_OPENAI_API_KEY`。 |
| `loomvault.cache.mode` | `redis` | 单节点用 `memory`。若 Redis 不可达，会退回内存并打一条警告。 |
| `loomvault.engine.recover-on-startup` | `true` | 恢复被死进程遗留为 `RUNNING` 的执行。 |
| `loomvault.pricing.overrides.<model>` | — | 每百万 token 的美元单价。未知模型按**零**计价，绝不猜测。 |

使用真实模型：

```bash
LOOMVAULT_LLM_PROVIDER=openai \
LOOMVAULT_LLM_OPENAI_BASE_URL=https://api.deepseek.com/v1 \
LOOMVAULT_LLM_OPENAI_API_KEY=sk-... \
docker compose up
```

## 测试

```bash
mvn verify        # 132 个测试
```

覆盖率聚焦在那些「搞错了代价高昂」的性质上：三值表达式语义、带路径报告的环检测、预算阈值运算、LRU/TTL 缓存淘汰、熔断器状态迁移，以及端到端的引擎行为 —— 包括 **"被重放的节点不会重新执行"**，而这正是整套设计存在的意义所在。

## 路线图

- [ ] **子工作流（Sub-workflows）** —— 一种运行另一个工作流的节点类型，带父子日志关联
- [ ] **定时与事件触发器** —— cron 与 webhook 入口，不再只有 `POST`
- [ ] **集合上的并行 for（parallel-for）** —— 宽度在运行时才确定的动态扇出
- [ ] **Provider 流式输出** —— 为长文本生成提供逐 token 的 SSE，增量写入日志
- [ ] **OpenTelemetry 导出** —— 每节点一个 span，接上现有的 trace id
- [ ] **Grafana 仪表盘** —— 一份使用上述指标、带版本管理的 JSON 面板
- [ ] **按租户配额** —— 预算账本已经有了合适的结构

## 参与贡献

欢迎提 Issue 和 Pull Request。开 PR 之前：

1. `mvn verify` 必须通过。
2. 新行为需要配一个「没有它就会失败」的测试。涉及调度器的改动，测试应放在 `ExecutionEngineTest`。
3. 注释与提交信息请用英文。
4. 保持 `loomvault-core` 不引入 Spring 和 JDBC。如果你需要一项新能力，在 `io.loomvault.core.port` 里加一个端口，然后在外层模块实现它。

适合新手的 issue 打了 [`good first issue`](https://github.com/<your-account>/loomvault/labels/good%20first%20issue) 标签。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 调度器设计、重放语义、并发模型、权衡取舍 |
| [`docs/DEMO.md`](docs/DEMO.md) | 一份 5 分钟脚本，逐项走完所有主打特性 |
| [`docs/WORKFLOW-SPEC.md`](docs/WORKFLOW-SPEC.md) | 工作流定义参考（节点、边、预算、表达式） |

## 许可证

Apache License 2.0 —— 见 [LICENSE](LICENSE)。
