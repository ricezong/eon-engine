# eon-engine

> Agent 引擎内核：主循环、钩子链、会话执行态、上下文窗口与端口装配。

`eon-engine` 是一个纯 Java 17 实现的 **Agent 运行时内核**。它只负责一件事：驱动「模型调用 ⇄ 工具执行」的主循环，并把循环过程中的一切——上下文组装、压缩、持久化、门禁、事件——治理好。它**不包含**任何具体的模型客户端、工具实现、存储实现或传输层（SSE/WebSocket），这些全部通过端口（接口）由应用层装配注入。

| | |
|---|---|
| 坐标 | `cn.kong.eon:eon-engine:1.0.0` |
| 语言 / 构建 | Java 17 · Maven |
| 运行时依赖 | slf4j-api、jtokkit、jackson-databind、caffeine（仅 4 个，无任何框架） |
| 内核规模 | 52 个类，约 3000 行源码 |

---

## 目录

- [设计理念](#设计理念)
- [架构总览](#架构总览)
- [核心机制详解](#核心机制详解)
  - [主循环 TurnLoop](#1-主循环-turnloop)
  - [双结构：账本 + 窗口](#2-双结构账本--窗口)
  - [上下文压缩](#3-上下文压缩)
  - [关卡体系](#4-关卡体系)
  - [钩子系统](#5-钩子系统)
  - [工具系统](#6-工具系统)
  - [人机交互](#7-人机交互)
  - [会话生命周期](#8-会话生命周期)
  - [事件系统](#9-事件系统)
- [包结构导览](#包结构导览)
- [快速上手](#快速上手)
- [配置参考](#配置参考engineconfig)
- [端口契约参考](#端口契约参考)
- [事件参考](#事件参考engineevent)
- [线程模型](#线程模型)
- [关键不变量](#关键不变量)
- [构建](#构建)
- [依赖清单](#依赖清单)

---

## 设计理念

1. **内核极简，端口外置。** 内核只实现与具体供应商无关的通用机制（循环、压缩、状态、事件）；模型接入、工具实现、存储、人机交互全部是端口接口，由应用层（host app）提供实现并通过 Builder 装配。`AgentEngine.Builder.build()` 会对 7 个必选端口做缺失校验，缺哪个报哪个。

2. **账本（append-only）是唯一真相源。** 会话消息全部追加写入 `LedgerPort`，永不改写；内存中的上下文窗口可以被压缩任意改写、裁剪、回收——因为任何时候都能从账本重放重建。压缩丢了什么都可以找回来，这是整套压缩机制敢"破坏性改写"的底气。

3. **失败即结果，不中断循环。** 工具异常、未知工具、超时、门禁拒绝、引擎关闭……一律转成 `ToolResult.failure` 回填给模型，让模型自行调整策略；只有钩子（Stop 结论）和基础设施错误才能终止一次 run。

4. **判档与发送同口径。** 上下文水位判档用的估量与最终发给模型的消息组装完全同源（同一布局、同一估算器、含注入段与工具表开销），避免"估的"和"发的"不一致导致压缩失真。

5. **宁不压缩，不丢真相。** 所有压缩动作失败即零变更（降档失败还原样保留、摘要全失败退化为指向账本位置的兜底文案、工件落盘失败保留原文入窗）。

---

## 架构总览

```
                          ┌────────────────────────────────────────────────┐
                          │                AgentEngine（门面）               │
                          │   run / interrupt / answer / evict / close      │
                          │   subscribe / unsubscribe（只读事件流）           │
                          └───────┬────────────────────────────────────────┘
                                  │ 装配（Builder 注入端口与注入段）
             ┌────────────────────┼───────────────────────────────┐
             │                    │                               │
   ┌─────────▼─────────┐ ┌────────▼────────┐  ┌──────────────────▼──────────┐
   │  SessionManager   │ │    TurnLoop     │  │      ContextPipeline        │
   │  Caffeine TTL 缓存 │ │   唯一的 while   │  │ clearOldThinking→判档→压缩   │
   │  账本重放恢复会话    │ │                 │─▶│ →复测→layout→配对修复        │
   └─────────┬─────────┘ └───┬─────────┬───┘  └──────────────┬──────────────┘
             │               │         │                     │
             │        ┌──────▼──┐ ┌────▼─────┐        ┌──────▼─────┐
             │        │ HookChain│ │ 工具池执行 │        │ Compressor │
             │        │ 四阶段钩子│ │ 门禁/超时  │        │ snip/prune │
             │        └──────┬──┘ └────┬─────┘        │ summarize  │
             │               │         │              └──────┬─────┘
   ┌─────────▼───────────────▼─────────▼─────────────────────▼──────────────┐
   │                            EventPublisher（同步有序事件总线）              │
   └────────────────────────────────────────────────────────────────────────┘

   ════════════════════════ 端口层（应用层实现，内核只依赖接口） ════════════════════════

   LlmPort           模型接入（流式 chat + 同步 complete）
   ToolSet/Tool      工具集与单个工具
   LedgerPort        append-only 账本（真相源）
   SnapshotPort      会话终态快照（用量/压缩水位/轮次）
   ToolResultPort    超大工具结果工件落盘与读回
   InteractionChannel 提问问答 + 破坏性操作门禁
   Workspace         会话文件沙箱（读区/写区）
   EventSink         事件出口（SSE/WebSocket/日志/回放）
```

一次 run 的数据流：

```
用户输入 ──▶ 账本追加 ──▶ 窗口追加
                              │
        ┌─────── 每轮循环 ◀────┘
        ▼
  PreModel 钩子 ──▶ 组装(压缩管线) ──▶ 模型(流式) ──▶ 提交关卡
        │                                            │
        ▼                                            ▼
  (Stop?)                                    账本追加 + 窗口追加
                                                     │
  PostModel 钩子 ◀────────────────────────────────────┘
        │
   无工具调用？──是──▶ 有 Nudge？──否──▶ COMPLETED 正常退出
        │                  │
        否                 是 → 注入补救轮（续写/重发工具调用）
        ▼
  PreTool 钩子(门禁) ──▶ 执行工具(池化/内联/超时) ──▶ PostTool 钩子(观察)
        │
        ▼
  入账关卡(ResultIntake) ──▶ 账本追加 + 窗口追加 ──▶ 下一轮
```

---

## 核心机制详解

### 1. 主循环 TurnLoop

`TurnLoop` 是**整个引擎唯一的 while 循环**（`loop/TurnLoop.java`）。每轮的固定流水：

1. **检查点**：中断标志 / 步数上限（`maxTurns`，按"单次 run"计，会话累计轮次单独统计在 `SessionState`）。
2. **PreModel 钩子**：可注入 Nudge（下一轮组装生效）、可 Stop 终止；Stop 后本循环立即收尾。
3. **组装**：`ToolSet.specs()` 提供工具表，`ContextPipeline.assemble()` 产出消息列表；组装后本轮 Nudge 清空。
4. **模型调用**：经 `LlmPort.chat()` 流式回调，三种增量（正文 / 思维链 / 工具参数）实时转发为 `ModelDelta` 事件；终点必须是 `onDone` 或 `onError` 之一，否则视为适配器违约抛 `LlmPortException`。
5. **提交关卡（commitGate）**：模型回复落盘前的最后一道检查，见[关卡体系](#4-关卡体系)。
6. **落盘**：assistant 消息（正文 + 思维链 + 工具调用）追加账本、追加窗口；发布 `TurnFinished`（这是轮次的**权威记录**，流式增量只是预览帧）。
7. **PostModel 钩子**：每轮有回复必经（含补救轮）。
8. **退出判定**：无工具调用且无 Nudge → `COMPLETED` 正常退出；无工具调用但有 Nudge → **补救重试**（`turnInRun+1`，若已达步数上限则 `MAX_TURNS`）；有工具调用则继续。
9. **PreTool 钩子**（门禁审批点）→ 中断检查点 → **工具执行**（详见[工具系统](#6-工具系统)）。
10. **PostTool 钩子**（纯观察，不能终止）。
11. **入账关卡**：每个工具结果先过 `ResultIntake` 体积关卡，再落账本、回填窗口，进入下一轮。

**收尾 `finish()` 是统一出口**：无论 COMPLETED / MAX_TURNS / INTERRUPTED / INTERNAL_ERROR，都走同一段代码——发布 `RunFinished`、尽力保存 `SessionSnapshot`（失败仅告警，不影响结果），返回 `RunResult(category, message, totalUsage)`。

**补救 Nudge 是内核自带的两条提示词**（非钩子产生）：

- `TRUNCATED_TEXT_NUDGE`：`finishReason == "length"` 且本轮无工具调用 → 正文被截断，注入"从截断处继续输出，过长请分多次输出"。
- `BROKEN_TOOLS_NUDGE`：本轮存在参数残缺的工具调用 → 残缺调用被丢弃（见下），注入"重新发起被丢弃的调用，确保参数是完整合法的 JSON"。

### 2. 双结构：账本 + 窗口

| | 账本（LedgerPort） | 上下文窗口（ContextWindow） |
|---|---|---|
| 性质 | **append-only**，持久化 | 内存可变结构 |
| 内容 | 每条消息一个 `seq`（会话内 1 起递增） | `Block` 列表（USER / ASSISTANT / TOOL_RESULT），每个 Block 记录来源 `messageSeq` |
| 写入 | 只追加 | 追加 / 就地改写（`replace`）/ 摘要式回收（`removeBeforeSeq`） |
| 角色 | **真相源**：重放可完整重建会话 | 组装上下文的直接原料，压缩作用在这里 |

窗口的每一块都有 `messageSeq` 指回账本，这是压缩水位的坐标系：SUMMARIZE 档回收窗口时按 `messageSeq < guardStartSeq` 移除，并把"已摘要覆盖到哪个 seq"记入 `CompressionState`，随快照持久化。

`ContextWindow.protectedFrom()` 划出**尾部保护区**：最后 `tailGuardBlocks`（默认 12）个块永不参与 SNIP/PRUNE/回收——最近发生的对话永远保持原文。

### 3. 上下文压缩

压缩是 `ContextPipeline.assemble()` 内置流程（**不经钩子**），每轮组装时执行：

```
思维链清除 → 估量判档 → 就地压缩 → 复测水位 → 布局组装 → 配对修复
```

**水位判档（WaterLevels，纯函数）**：水位 = 估量 tokens ÷ `maxContextTokens`，三档阈值从低到高：

| 水位 | 档位 | 动作（Compressor） |
|---|---|---|
| ≥ 0.65 | `SNIP` | 保护区外的 TOOL_RESULT 头尾截断（各留 `snipKeepChars`，中缝 `\n...\n`） |
| ≥ 0.80 | `PRUNE` | 覆盖 SNIP 全部动作，另加：ASSISTANT 正文截断、过长工具调用参数**骨架化**、已落盘的 TOOL_RESULT 换成引用占位符 |
| ≥ 0.92 | `SUMMARIZE` | 生成摘要 → 回收窗口 → 推进水位 |
| < 0.65 | `NONE` | 不压缩 |

- **参数骨架化**：参数 JSON 整体超过 `argsPruneMinChars`（默认 2000）才处理；其中长度 > 200 的字符串字段替换为 `"<N 字符已裁剪，原文不在上下文中>"`，结构与其余字段保留；只有严格更短时才采纳，保证幂等。
- **思维链清除**：每轮组装最先执行，只保留最近 `thinkingReplayTurns`（默认 2）轮的思维链，更早的置 null。**不受档位与保护区约束**——思维链是推理辅助，历史价值最低。
- **SUMMARIZE**：原料取自**账本** `seq ∈ [窗口首块 seq, guardStartSeq-1]`（窗口可能已被降档，账本才是全文），按 `summarizeMaxInputChars` 分段（段边界不切断行），逐段与旧摘要**合并**成固定四段结构（History Q&A / Key Context and Decisions / User Preferences and Updates / Pending Tasks and Current Work）；用户消息原文要求逐条照抄不概括（这是后续了解用户诉求的唯一依据）。单段失败跳过继续；全部失败退化为指向账本 `locator` 的兜底文案；无可摘要区间或原料为空则零变更。摘要完成后 `removeBeforeSeq(guardStartSeq)` 回收窗口（回收量按存储全文粗估），`CompressionState` 推进。
- `maxContextTokens <= 0` 可整体关闭压缩（仍保留思维链清除与配对修复）。

**入账关卡 ResultIntake**（工具结果的体积关卡，独立于水位判档，每条必经）：工具结果正文超过 `spillThresholdChars`（默认 8000 字符）时，调用 `ToolResultPort.spill()` 把全文落为工件，窗口内替换为「头 + `…` + 尾 + `[完整内容见 tool_result://{refId}]`」（头尾各 `spillKeepChars`，默认 2000）。应用可提供 `read_artifact` 之类的工具，经 `ToolContext.toolResult().read()` 分段读回原文。**唯一例外**：落盘失败时保留原文入窗——宁超限也不丢结果。

**配对修复 repairPairing**（组装的最后一步，纯消息列表操作，不动窗口不落账本）：

- 有调用无结果（悬空调用——assistant 落盘后、结果落盘前的中断会留下）→ 补合成结果 `[工具执行结果缺失]`；
- 有结果无调用（孤儿结果）→ 丢弃。

保证发给模型的消息序列永远满足 tool_call / tool_result 成对，不会被 API 拒绝。

**组装布局**（layout）：

```
System(systemPrompt)
→ 注入段 sections[order < 1000]      （按 order 升序，<label>…</label> 包裹为 USER 消息）
→ <session_summary>（有摘要时）
→ 窗口历史（USER / ASSISTANT(+thinking+toolCalls) / TOOL_RESULT）
→ 注入段 sections[order ≥ 1000]
→ Nudge（本轮全部提醒合并为一条 USER 消息）
```

`ContextSection` 是应用向上下文注入内容（记忆、环境信息、待办等）的**唯一形式**：`render(sessionId)` 返回 null/空白按本轮缺省，抛异常降级跳过，绝不阻断循环。

### 4. 关卡体系

内核在三个关键位置设关卡，各自担保一条不变量：

| 关卡 | 位置 | 担保 |
|---|---|---|
| **提交关卡** commitGate | 模型回复落盘前 | **落盘必可重放**：账本 append-only，残缺参数一旦落盘将永久毒化会话。逐个检查 `ToolCall.replayable()`（廉价的 JSON 完整性判断：字符串闭合、括号配对，只会漏判不会误杀），发现残缺则**保留前缀、丢弃尾部**（流式顺序保证残缺必为后缀），同时注入补救 Nudge。`length` 截断仅在无调用时改判为正文续写 |
| **入账关卡** ResultIntake | 工具结果落账本前 | 单条 TOOL 正文 ≤ `spillThresholdChars`（工件落盘失败保留原文是唯一例外） |
| **配对修复** repairPairing | 组装出口 | 发给模型的消息 tool_call / tool_result 严格成对 |

### 5. 钩子系统

四阶段钩子接口，按 `order()` 升序执行（默认 100，小者先），由 `HookChain` 统一调度：

| 阶段 | 接口 | 签名 | 能力 |
|---|---|---|---|
| 模型调用前 | `PreModelHook` | `beforeModel(turn)` | Nudge / Stop |
| 模型返回后 | `PostModelHook` | `afterModel(turn)` | Nudge / Stop（每轮有回复必经，含补救轮；预算熔断、敏感内容拦截等在此实现） |
| 工具执行前 | `PreToolHook` | `beforeTools(turn, calls)` | Nudge / Stop（审批、限流、危险调用拦截） |
| 工具执行后 | `PostToolHook` | `afterTools(turn, results)` | **纯观察**，返回 void，不能终止（审计、指标采集） |

三种结论（sealed `HookResult`）：

- `ok()`：放行；
- `nudge(message)`：温和提醒——文本注入**下一轮**组装（Nudge 在每阶段累积，组装后由循环清空），并发布 `GuardTriggered(stopped=false)` 事件；
- `stop(category, message)`：立即终止本次 run，其后钩子不再执行，并发布 `GuardTriggered(stopped=true)` 事件。

`TurnContext` 提供 run 级扩展槽：`attributes()`（ConcurrentHashMap）供钩子/守卫存放跨轮状态（如连续无进展计数），随 run 结束自然丢弃；`reply()` 可读取本轮模型回复（PostModel 钩子中可用）。

> `StopCategory` 中 `BUDGET_EXCEEDED`（预算耗尽）与 `GATE_REJECTED`（门禁拒绝）是预留给应用层钩子的终止类别——引擎内置路径只会产生 `COMPLETED` / `MAX_TURNS` / `INTERRUPTED` / `INTERNAL_ERROR`。其中预算熔断不走估算器，应以 `LlmReply.usage` 的真实用量为准（如 PostModel 钩子累加判断）。

### 6. 工具系统

- **`Tool`**：`spec()` 暴露给模型的描述（`ToolSpec`：名称、描述、JSON Schema 参数、`destructive` 标记）；`execute(call, ctx)` 拿到**原始参数 JSON 字符串**（内核不解析，工具自行处理），应返回 `failure` 结果表达失败而非抛异常（抛了也会被 `ToolSet` 兜底转为 failure）。
- **`inline()` 工具**：默认 false。返回 true 的工具在 **run 线程上内联执行**——不进工具池、**不受超时约束**，专供"阻塞等人回答"（ask_question）与顺序敏感的工具使用。
- **超时**：非内联工具进固定大小工具池并行执行，每个工具按 `Tool.timeout()`（可覆盖引擎默认 `toolTimeout`）独立计时；超时转 failure 并 `cancel(true)`，不中断循环。
- **调度顺序**：先全部入池，再跑内联任务，最后逐个等待池内结果——避免长时间阻塞的内联任务让并行任务干等。
- **破坏性门禁**：`ToolSpec.destructive == true` 且未配置 `autoApproveDestructive` 时，执行前必须经 `InteractionChannel.gate()` 审批；`REJECTED` 转 failure 结果「门禁拒绝：破坏性操作未获批准」。
- **`ToolContext`**：每次执行注入 `sessionId`、`turn`、`workspace`（文件沙箱）、`toolResult`（工件读回）、`interaction`（提问/门禁）、`events`（可发布 `SessionStateUpdated` 等 app 级事件）。

### 7. 人机交互

`InteractionChannel` 是人机交互的统一出口，两个用途：

1. **提问（ask_question 模式）**：工具实现调用 `ask(question, timeout)` **阻塞在工具线程上**等待答案；前端通过 `engine.answer(sessionId, questionId, answers)` 投递，实现方经 `deliverAnswer()` 唤醒等待者并校验 questionId 归属。一次提问可含多个问题（`Question → Ask → Option` 三层），支持多选（`multiple`）与自填文本（`Answer.other`）；回复为 sealed `QuestionReply`：`Answered` / `TimedOut` / `Cancelled`。此类工具应声明 `inline() == true` 以免被超时打断。
2. **门禁（gate）**：破坏性工具执行前的审批，返回 `APPROVED` / `REJECTED`。同步确认、异步审批均可由实现决定。

### 8. 会话生命周期

**`Session` 是聚合根**：窗口 + 累计状态 + 状态机。状态迁移全部 CAS：

```
IDLE ──tryAcquire──▶ RUNNING ──release──▶ IDLE
  │                    │
  │              requestInterrupt
  │                    ▼
  │                 STOPPING ──release──▶ IDLE（同时清中断标志）
  │
  └─evictIfIdle──▶ CLOSED（终态，不可逆；tryAcquire 必然失败）
```

- **同一会话同时至多一个 RUNNING**：`engine.run()` 先 `acquire`（缓存取/磁盘重建）再 `tryAcquire` 抢运行权，抢不到直接抛「会话正在运行中」。
- **中断是标记式软中断**：`requestInterrupt()` 把 RUNNING 迁到 STOPPING 并置标志，循环在三个检查点（轮首 / PreModel 钩子后 / 工具执行前）观察并优雅停止，**不抢占**在途的模型调用或工具执行（等待工具结果期间的中断同样等到检查点才生效）。

**`SessionManager`：Caffeine TTL 缓存 + 重建 + 第一道并发闸**

- 缓存时间由 **run 生命周期驱动**：抢到运行权与 run 结束各 `retime` 一次；**读不续期**——过期只以最后一次 retime 为准。运行中（RUNNING/STOPPING）**永不过期**（TTL = ∞），因为逐出在途 run 的实例会破坏单 RUNNING 不变量。
- `sessionCacheMaxSize > 0` 时启用容量上限淘汰；`sessionIdleTtl`（默认 120 分钟）控制闲置淘汰。
- **恢复算法**：快照存在且 `CompressionState.consistentWith(ledgerSize)`（摘要与水位同真同假、水位未越过账本长度）→ 从 `summarizedThroughSeq + 1` 重放（已摘要覆盖的消息不再入窗，摘要状态随快照恢复；SNIP/PRUNE 的就地降档随重放自然还原——账本存的是全文）；快照缺失或不自洽 → **降级为全量回放**且不恢复摘要——最坏是多占 token，按坏快照回放会静默丢历史。
- 显式 `evict`（`engine.evict()`）：CAS IDLE→CLOSED 成功才移除，仅清内存不动持久层；幂等（不存在视为成功），运行中返回 false。TTL/容量淘汰回调同样只关停 IDLE 实例。

### 9. 事件系统

`EngineEvent` 是 sealed 接口，共 11 类，分六层（完整字段见[事件参考](#事件参考engineevent)）：

- **运行层**：RunStarted / UserMessage / TurnStarted / TurnFinished（轮次权威记录）
- **透传层**：ModelDelta（模型流式增量预览帧，TEXT / THINKING / TOOL_ARGS）
- **工具层**：ToolExecutionStarted / ToolExecutionFinished
- **治理层**：GuardTriggered（钩子 Nudge/Stop）、CompressionApplied（档位 + 压缩前后 token）
- **扩展层**：SessionStateUpdated（app 工具经 `ToolContext.events()` 发布，viewJson 不透明透传）
- **收尾**：RunFinished

**派发契约（订阅者必须遵守）**：同步、按发生顺序、在发布线程上派发（部分订阅者功能性依赖该时序，故不做异步多播）；订阅者不得阻塞、不得做慢 IO（传输类订阅者须内部出舱）；订阅者异常被隔离（记日志，不中断执行）。

---

## 包结构导览

```
cn.kong.eon.engine
├── AgentEngine            引擎门面 + Builder 装配（线程池、会话管理、循环的组装点）
├── EngineConfig           不可变配置（Builder 模式，全参数见配置参考）
├── EngineException        引擎级运行时异常
├── TokenEstimator         jtokkit o200k_base BPE 计数（仅供水位判档；预算熔断走真实 usage）
├── loop/
│   ├── TurnLoop           主循环（唯一的 while）
│   ├── TurnContext        一次 run 的上下文（reply / nudges / attributes / runTurn）
│   └── RunResult          run 结果（StopCategory + message + 累计用量）
├── context/               上下文管线
│   ├── ContextPipeline    思维链清除→判档→压缩→布局→配对修复
│   ├── Compressor         全部窗口加工：snip/prune/clearOldThinking/summarize
│   ├── WaterLevels        水位判档（纯函数）
│   ├── CompressionLevel   档位：NONE / SNIP / PRUNE / SUMMARIZE
│   ├── CompressionState   摘要 + 水位（会话级，随快照持久化，含自洽校验）
│   ├── ResultIntake       工具结果入账关卡（溢出落盘）
│   └── ContextSection     有序注入段接口（app 注入上下文的唯一形式）
├── window/
│   ├── ContextWindow      会话历史内存结构（追加/改写/回收 + 尾部保护区）
│   ├── Block              不可变窗口块（messageSeq 回指账本）
│   └── BlockKind          USER / ASSISTANT / TOOL_RESULT
├── msg/                   引擎自有消息模型（与任何 LLM SDK 解耦）
│   ├── ChatMsg / Role     四角色消息（text/thinking/toolCalls/toolCallId/toolName/toolView）
│   ├── ToolCall           工具调用（含 replayable() 完整性快检）
│   ├── ToolResult         工具结果（output 模型可见正文 + toolView UI 视图透传）
│   ├── ToolSpec           工具规格（schema + destructive）
│   └── LlmUsage           单次调用 token 用量（可加总）
├── hook/                  四阶段钩子 + HookChain + HookResult(Ok/Nudge/Stop)
├── event/
│   ├── EngineEvent        11 类事件的 sealed 层级
│   ├── EventPublisher     同步有序发布器（订阅者异常隔离）
│   └── EventSink          事件出口端口
├── scope/                 会话域
│   ├── Session            聚合根（CAS 状态机 + 中断标志）
│   ├── SessionManager     Caffeine TTL 缓存 + 账本重放恢复
│   ├── SessionState       累计轮次/用量/压缩状态（synchronized）
│   ├── SessionStatus      IDLE / RUNNING / STOPPING / CLOSED
│   └── Task               一次 run 的任务记录
├── stop/
│   └── StopCategory       终止分类（统一收尾的唯一判定依据）
└── port/                  端口层（全部为接口，应用层实现）
    ├── model/             LlmPort / LlmRequest / LlmReply / StreamCallbacks / LlmPortException
    ├── tool/              Tool / ToolSet / ToolContext
    ├── store/             LedgerPort / SnapshotPort / ToolResultPort / Workspace
    └── interaction/       InteractionChannel（问答 + 门禁）
```

---

## 快速上手

### 1. 引入依赖

```xml
<dependency>
    <groupId>cn.kong.eon</groupId>
    <artifactId>eon-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 实现端口

内核有 7 个必选端口。以下给出最小可用的内存/桩实现示例（生产环境请替换为真实实现）：

```java
// LlmPort：桥接任意模型 SDK。契约：
//  - finishReason 统一翻译为 openai 词表（stop/length/tool_calls…）
//  - 无参工具调用的 argumentsJson 产出 "{}"（不得为 null/空串，空串无法与截断区分）
//  - 流式增量顺序回调；终点必须是 onDone 或 onError 之一
public class MyLlmPort implements LlmPort {
    @Override
    public void chat(LlmRequest request, StreamCallbacks cb) {
        // 调用模型 SDK，逐段回调：
        //   cb.onTextDelta("..."); cb.onThinkingDelta("..."); cb.onToolCallDelta(callId, "...");
        // 终点：
        //   cb.onDone(new LlmReply(text, thinking, toolCalls, usage, finishReason));
        //   或 cb.onError(new LlmPortException("..."));
    }

    @Override
    public String complete(List<ChatMsg> messages) {
        return "..."; // 同步纯文本补全，引擎内部用于 SUMMARIZE 摘要
    }
}

// LedgerPort：append-only 账本（真相源）。生产建议持久化到 DB/文件
public class InMemoryLedger implements LedgerPort {
    private final Map<String, List<LedgerRecord>> store = new ConcurrentHashMap<>();

    @Override public long append(String sessionId, ChatMsg msg) {
        var list = store.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(new LedgerRecord(list.size() + 1, msg));
            return list.size();          // 首条为 1，等于追加后的账本长度
        }
    }
    @Override public List<LedgerRecord> replay(String sessionId, long fromSeq) {
        var list = store.getOrDefault(sessionId, List.of());
        synchronized (list) {
            return list.stream().filter(r -> r.seq() >= fromSeq).toList(); // fromSeq 含
        }
    }
    @Override public long size(String sessionId) {
        return store.getOrDefault(sessionId, List.of()).size();
    }
}

// SnapshotPort：会话终态快照（累计用量、压缩水位、轮次）
public class InMemorySnapshotStore implements SnapshotPort {
    private final Map<String, SessionSnapshot> store = new ConcurrentHashMap<>();
    @Override public void save(SessionSnapshot s) { store.put(s.sessionId(), s); }
    @Override public SessionSnapshot load(String sessionId) { return store.get(sessionId); }
}

// ToolResultPort：超大工具结果的工件落盘与读回
public class InMemoryArtifactStore implements ToolResultPort {
    @Override public ToolResultRef spill(String sessionId, String toolName, String content, long seq) {
        String refId = "a-" + seq;                       // 用 seq 保证跨重放稳定
        // 实际实现：写文件/对象存储
        return new ToolResultRef(refId, content.split("\n").length);
    }
    @Override public String read(String sessionId, String refId, int offset, int limit) {
        return "...";                                    // 按 offset/limit 分段读回
    }
}

// InteractionChannel：提问问答 + 破坏性门禁
public class MyInteraction implements InteractionChannel {
    @Override public QuestionReply ask(Question q, Duration timeout) {
        // 阻塞等待前端答案（engine.answer() 投递），或返回 TimedOut/Cancelled
        return new QuestionReply.TimedOut();
    }
    @Override public Decision gate(ToolCall call, ToolSpec spec) {
        return Decision.APPROVED;                        // 或弹前端确认
    }
}

// Workspace：会话文件沙箱。实现按 sessionId 路由到会话目录，单例即可
public class MyWorkspace implements Workspace {
    private final Path root = Path.of("/data/workspaces");
    @Override public Path sessionRoot(String sessionId) { return root.resolve(sessionId); }
    @Override public Path resolve(String sessionId, String relativePath) {
        // 读区 = 会话目录整体；越界抛 IllegalArgumentException
        return sessionRoot(sessionId).resolve(relativePath).normalize();
    }
    @Override public Path resolveWritable(String sessionId, String relativePath) {
        // 写区 = 实现约定的可写子区，写类工具一律走这里，无法绕过
        return resolve(sessionId, relativePath);
    }
    @Override public String relativize(String sessionId, Path absolute) {
        return "/rel/path";                              // 正斜杠；会话目录外退化为文件名
    }
}
```

### 3. 实现工具

```java
public class SearchTool implements Tool {
    private final ToolSpec spec = ToolSpec.of("web_search", "搜索网络获取资料");

    @Override public ToolSpec spec() { return spec; }

    @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
        try {
            String query = extract(call.argumentsJson(), "query"); // 参数是原始 JSON，自行解析
            return ToolResult.success(call, doSearch(query));      // 失败请返回 failure，不要抛异常
        } catch (Exception e) {
            return ToolResult.failure(call, e.getMessage());
        }
    }
}

public class DeleteFileTool implements Tool {           // 破坏性工具 → 默认走门禁
    private final ToolSpec spec = ToolSpec.destructive("delete_file", "删除指定文件");
    @Override public ToolSpec spec() { return spec; }
    @Override public ToolResult execute(ToolCall call, ToolContext ctx) { /* ... */ }
}
```

### 4. 装配并运行

```java
AgentEngine engine = AgentEngine.builder()
        .config(EngineConfig.builder()
                .maxTurns(30)
                .maxContextTokens(200_000)
                .build())
        .llm(new MyLlmPort())
        .tools(new MyToolSet(List.of(new SearchTool(), new DeleteFileTool())))
        .ledger(new InMemoryLedger())
        .snapshots(new InMemorySnapshotStore())
        .toolResult(new InMemoryArtifactStore())
        .interaction(new MyInteraction())
        .workspace(new MyWorkspace())
        .hooks(new MyBudgetGuard())          // 可选：预算熔断等钩子
        .sections(List.of(new EnvSection())) // 可选：记忆/环境/待办等注入段
        .build();

// 订阅事件（SSE/WebSocket/日志/回放都是 EventSink 的实现）
engine.subscribe(event -> {
    if (event instanceof EngineEvent.ModelDelta d) {
        System.out.print(d.delta());         // 流式输出预览帧
    } else if (event instanceof EngineEvent.RunFinished f) {
        System.out.println("\n[run] " + f.category() + ": " + f.message());
    }
});

// 发起运行（异步）：同一会话同时至多一个 RUNNING，重复 run 抛 EngineException
engine.run("session-001", "帮我调研 eon-engine 并写一份摘要");

// 中断（标记式软中断，下一个检查点优雅停止）；false = 无在途 run 可中断
engine.interrupt("session-001");

// 投递提问答案（配合 ask_question 类工具）
engine.answer("session-001", "q-123", List.of(new InteractionChannel.Answer("ask-1", List.of("确认"), null)));

// 淘汰会话缓存（仅清内存，不动持久层；运行中返回 false）
engine.evict("session-001");

// 优雅关闭（等待在途 run，上限 = toolTimeout + 30s）；close() 为立即关闭
engine.shutdown();
```

---

## 配置参考（EngineConfig）

不可变配置，Builder 模式；只放引擎行为参数，模型参数（temperature 等）属于 LLM 适配器自身。

### 循环

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `maxTurns` | int | 30 | 单次 run 最大轮次（按 run 计，会话累计轮次另算） |
| `toolParallelism` | int | 4 | 工具池并行度（固定大小线程池） |
| `toolTimeout` | Duration | 30s | 单个工具执行上限（可被 `Tool.timeout()` 覆盖；内联工具不受约束） |
| `autoApproveDestructive` | boolean | false | 破坏性工具免门禁审批 |

### 上下文

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `systemPrompt` | String | 内置中文默认 | 系统提示词 |
| `maxContextTokens` | long | 200,000 | 上下文预算，水位判档的分母；**≤ 0 关闭压缩** |
| `tailGuardBlocks` | int | 12 | 尾部保护块数，压缩不动（SUMMARIZE 回收同样止步于此） |
| `thinkingReplayTurns` | int | 2 | 保留思维链的最近轮数，更早的从窗口清除 |
| `spillThresholdChars` | int | 8,000 | 工具结果溢出阈值（入账关卡） |
| `spillKeepChars` | int | 2,000 | 溢出后头尾各保留字符数 |
| `snipWaterLevel` | double | 0.65 | SNIP 档水位下限 |
| `pruneWaterLevel` | double | 0.80 | PRUNE 档水位下限 |
| `summarizeWaterLevel` | double | 0.92 | SUMMARIZE 档水位下限 |
| `snipKeepChars` | int | 1,000 | SNIP/PRUNE 截断后头尾各保留字符数 |
| `argsPruneMinChars` | int | 2,000 | 参数整体超过该长度才骨架化 |
| `summarizeMaxInputChars` | int | 80,000 | 单段摘要输入上限，超出分段 |
| `summarizeMaxOutputChars` | int | 12,000 | 摘要输出上限，写入摘要提示词 |
| `sessionIdleTtl` | Duration | 120min | 会话缓存闲置 TTL（读不续期，run 生命周期驱动续期） |
| `sessionCacheMaxSize` | int | 0（不限） | 会话缓存容量上限 |

---

## 端口契约参考

| 端口 | 必选 | 职责 | 关键契约 |
|---|---|---|---|
| `LlmPort` | ✅ | 模型接入 | 流式 `chat()` 增量顺序回调、终点必为 `onDone`/`onError` 之一；`finishReason` 统一为 openai 词表；无参调用 argumentsJson 产出 `"{}"`；同步 `complete()` 供摘要 |
| `ToolSet` | ✅ | 工具集合 | 统一执行入口担保：未知工具、实现异常都转 failure 回填模型，不中断循环 |
| `LedgerPort` | ✅ | append-only 账本 | `append` 返回会话内递增 seq（首条 1）；`replay(fromSeq)` 含头；真相源，永不改写；`locator()` 可选（摘要兜底文案引导模型回查原文） |
| `SnapshotPort` | ✅ | 会话终态快照 | 保存/恢复累计用量、压缩水位、轮次；不自洽时引擎自动降级全量回放 |
| `ToolResultPort` | ✅ | 工件落盘 | `spill` 用 seq 生成跨重放稳定 refId；`read` 按 offset/limit 分段读回 |
| `InteractionChannel` | ✅ | 问答 + 门禁 | `ask` 在工具线程阻塞；答案经 `engine.answer()` → `deliverAnswer` 唤醒；同步通道 deliverAnswer 返回 false |
| `Workspace` | ✅ | 文件沙箱 | 读区 = 会话目录整体；写区 = 实现约定的可写子区（`resolveWritable`，无法绕过）；越界抛 IllegalArgumentException |
| `EventSink` | 可选×N | 事件出口 | 同步有序派发；不得阻塞/慢 IO；异常被隔离 |
| `Hook`×4 | 可选×N | 四阶段治理 | 见[钩子系统](#5-钩子系统) |
| `ContextSection` | 可选×N | 上下文注入段 | `order<1000` 注入历史之前、`≥1000` 之后；渲染失败按缺省降级 |

---

## 事件参考（EngineEvent）

| 事件 | 层 | 字段 | 时机 |
|---|---|---|---|
| `RunStarted` | 运行 | sessionId | run 任务体起点 |
| `UserMessage` | 运行 | text | 用户输入（账本追加前发布） |
| `TurnStarted` | 运行 | turn | 每轮开始（1 起算，按 run 计） |
| `TurnFinished` | 运行 | turn, text, thinking, toolCalls, finishReason, usage | 提交关卡之后的最终形态，**轮次权威记录**，落盘即发布 |
| `ModelDelta` | 透传 | turn, kind(TEXT/THINKING/TOOL_ARGS), toolCallId, delta | 模型流式增量预览帧（非权威） |
| `ToolExecutionStarted` | 工具 | toolCallId, toolName | 单个工具开始执行 |
| `ToolExecutionFinished` | 工具 | result | 单个工具结束（含超时/门禁拒绝等 failure） |
| `GuardTriggered` | 治理 | hook, message, stopped | 钩子产生 Nudge(false) / Stop(true) |
| `CompressionApplied` | 治理 | level, beforeTokens, afterTokens | 压缩实际执行后（净释放 = before - after，与判档同口径） |
| `SessionStateUpdated` | 扩展 | key, viewJson | app 工具经 `ToolContext.events()` 发布，引擎不解释内容 |
| `RunFinished` | 收尾 | category, message, totalUsage | run 统一收尾（快照保存随后尽力执行） |

---

## 线程模型

| 线程 | 池 | 职责 |
|---|---|---|
| 调用方线程 | — | `engine.run()` 只做 acquire + tryAcquire + 提交任务，立即返回 |
| `engine-run-N`（cached，守护） | runPool | 驱动主循环：模型调用、内联工具、账本/窗口写入、事件发布 |
| `engine-tool-N`（fixed = toolParallelism，守护） | toolPool | 并行执行非内联工具，各自独立计时超时 |
| LLM 适配器回调线程 | 实现自有 | 流式增量在此线程回调，引擎转发为 ModelDelta 事件 |

- `shutdown()`：runPool 优雅等待（上限 = `toolTimeout + 30s`）后关闭 toolPool；`close()`（AutoCloseable）：`shutdownNow` 立即中断。
- 会话互斥不靠锁靠 **CAS**：IDLE→RUNNING 抢运行权；缓存淘汰与运行权在同一状态位上互斥，淘汰成功的实例永不再运行。
- `SessionState` 内部方法 synchronized，保证跨线程累计安全。

---

## 关键不变量

1. **落盘必可重放**：提交关卡拦截残缺参数（丢尾保前缀 + Nudge），账本 append-only 永不毒化。
2. **同会话至多一个 RUNNING**：CAS 抢占 + 运行中永不过期淘汰。
3. **账本是唯一真相源**：窗口可任意压缩改写，重放 + 快照可完整重建；快照不自洽自动降级全量回放。
4. **失败即结果**：工具异常/未知/超时/门禁拒绝/池关闭一律转 failure 回填模型，只有钩子 Stop 与基础设施错误终止 run。
5. **宁不压缩不丢真相**：降档失败零变更、摘要全失败退化为账本定位文案、工件落盘失败保留原文。
6. **判档与发送同口径**：水位估量 = 组装布局的同源估算（含注入段、摘要、Nudge、工具表开销）。
7. **压缩不经钩子**：压缩是组装的内置步骤；钩子治理的是模型与工具行为，两者职责分离。
8. **残缺必为后缀**：流式顺序保证工具调用参数残缺只出现在尾部，故丢尾保前缀是安全的。

---

## 构建

```bash
mvn compile       # 编译（JDK 17）
mvn test          # 测试（JUnit 5）
mvn package       # 打包
```

## 依赖清单

| 依赖 | 版本 | 用途 |
|---|---|---|
| `org.slf4j:slf4j-api` | 2.0.13 | 日志门面（实现由应用提供） |
| `com.knuddels:jtokkit` | 1.1.0 | o200k_base BPE token 估算 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.3 | 摘要原料序列化、参数骨架化 |
| `com.github.ben-manes.caffeine` | 3.3.0 | 会话 TTL/容量缓存 |
| `org.junit.jupiter:junit-jupiter` | 5.10.2 | 测试（test scope） |
