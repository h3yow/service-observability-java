# Service Observability Java（本地可验证的服务可观测性）

在一个最小 Spring Boot 3.4 / Java 21 服务上，补齐**关联标识、事件时间线、进程内聚合指标**
三套能力，并通过**本地故障注入**复现慢调用、失败重试、超时与业务拒绝。
全程不依赖外部服务、真实凭据或付费组件；唯一运行时依赖是 Spring Web，验证只用 JUnit 5。

## 1. 端点总览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ping` | 稳定基线，始终 200，用于健康对照与无流量场景 |
| GET | `/api/process` | 业务端点，支持通过请求头注入故障 |
| GET | `/observability/health` | 进程内健康状态（含时间线存量） |
| GET | `/observability/metrics` | 按 端点×结果类别×耗时区间 聚合的指标快照 |
| GET | `/observability/timelines/{correlationId}` | 按关联标识查询有序事件时间线 |
| POST | `/observability/admin/reset` | 清空内存时间线与指标（仅本地验证/测试用） |

## 2. 关联标识传递规则

### 2.1 归一化口径（`correlation.CorrelationIds`）

请求头 `X-Correlation-Id` 的判定结果确定、可区分，共三类，
并通过响应头 `X-Correlation-Id` / `X-Correlation-Id-Source` 原样回填：

| 情况 | source | 生效标识 | HTTP 结果 |
| --- | --- | --- | --- |
| 头缺失或空白 | `generated` | 本服务生成 `gen-<32位hex>` | 正常处理 |
| 8–64 位且字符集 `[A-Za-z0-9._-]` | `provided` | **原样保留**调用方值（大小写敏感，不改写） | 正常处理 |
| 非空但长度越界/含非法字符 | `rejected` | 另生成一个 `gen-...` 作为**审计标识** | **400 INVALID_REQUEST**，不进入控制器 |

> 注意区分两类 `gen-` 开头的标识：缺失是 `generated`，非法是 `rejected`。
> 非法请求也有审计标识，因此其时间线、日志、指标同样可查，但它不消耗任何业务调用。

### 2.2 传递路径

1. **入站**：`ObservabilityFilter`（最高优先级）在所有业务逻辑之前完成归一化，
   把 `CorrelationContext` 放入 `ThreadLocal` 并写入 SLF4J MDC
   （日志字段 `cid=`、`src=`）。
2. **跨线程**：业务工作被提交到固定 8 线程的有界线程池。
   - `ContextPropagatingExecutor` 在**提交时**（而非执行时）捕获调用线程的标识快照
     **与 MDC**，工作线程执行前恢复、执行后还原/清理；
   - `RequestScope`（时间线所在）由业务代码在提交前显式捕获、工作线程内显式恢复并还原。
   这样即使线程被大量复用，每个任务看到的都是自己请求的标识与时间线，不串号、不泄漏。
3. **本地模拟下游**：`FaultyDownstreamClient` 全程在进程内执行，
   直接读取当前线程的 `RequestScope` 记录事件，无网络、无外部依赖。
4. **出站边界**：过滤器 `finally` 移除 MDC 与 ThreadLocal；
   传播执行器在任务结束后把工作线程的标识与 MDC 还原为任务前状态，
   防止把它们留给下一个复用任务。

### 2.3 并发不串号的保证

- 标识判定是纯函数：同输入同结果；生成值使用随机 UUID，不同请求必然不同。
- 每请求数据（上下文、时间线、结果类别）都挂在请求作用域上，不使用任何静态可变状态。
- `ContextPropagatingTest`（400 任务 / 2 线程强制复用）与
  `ConcurrentRequestIntegrationTest`（24 个真实并发 HTTP 请求）固化了这些性质。

### 3. 事件时间线与错误分类口径

### 3.1 有序事件流

每个请求生成一条 `RequestTimeline`，按请求内**严格递增序号**排列
（`AtomicLong` 分配序号 + 同步追加，跨线程可见且有序），并带相对开始时刻的微秒偏移。
有界事件名见 `timeline.TimelineEventName`，例如：

```
REQUEST_RECEIVED → WORK_DISPATCHED → DOWNSTREAM_CALL_STARTED(attempt=1)
  → DOWNSTREAM_CALL_RETRY → DOWNSTREAM_CALL_STARTED(attempt=2)
  → DOWNSTREAM_CALL_SUCCEEDED → (audit 嵌套调用 STARTED/SUCCEEDED)
  → REQUEST_COMPLETED
```

- **重试**：系统类失败最多尝试 3 次，每次尝试各自产生 STARTED/(FAILED) 事件，
  另产生 2 次 RETRY 事件，时间线完整呈现，但聚合计数始终每请求一次。
- **嵌套调用**：主调用成功后产生一次 `audit` 嵌套调用，事件进入同一条时间线，
  不产生额外聚合计数。
- **去重**：业务拒绝只记一次 `BUSINESS_REJECTED`（不会被错误地记成下游失败）；
  终态事件（COMPLETED/FAILED）统一由过滤器追加一次，异常处理器只标记类别、不重复记事件。
- 存储为容量 1000 的有界 LRU（`InMemoryTimelineStore`），超出淘汰最旧，避免无界增长。

### 3.2 错误分类口径（有界枚举 `OutcomeCategory`）

| 类别 | 触发条件 | HTTP | 是否重试 |
| --- | --- | --- | --- |
| `SUCCESS` | 2xx | 200 | — |
| `BUSINESS_REJECTION` | `BusinessRejectionException`（模拟业务规则拒绝） | 422 | **否** |
| `BUDGET_EXHAUSTED` | 请求级总时间预算耗尽（见第 4 节） | 504 | **否** |
| `INVALID_REQUEST` | 关联标识非法（过滤器处终止）/ 其他 4xx | 400 | 否 |
| `SYSTEM_ERROR` | 下游 IO 失败、超时、截止时间超时、未预期异常 | 500 | 是（业务拒绝除外） |

### 3.3 根因不被覆盖

- IO 失败：`DownstreamException(cause=java.io.IOException)`；
- 下游自身超时：`DownstreamTimeoutException`；
- 服务侧截止时间超时：`DownstreamException(cause=java.util.concurrent.TimeoutException)`，
  并通过 `Future.cancel(true)` 中断工作线程的慢调用。
- 重试用尽抛出的仍是**最后一次尝试的原始异常**；
  全局异常处理器在错误体返回 `rootCauseType` 与 `causeChain`，日志以 ERROR 打印完整堆栈。
  原始根因不会被重试包装或兜底处理器覆盖、静默丢弃。

## 4. 请求级总时间预算（`X-Budget-Millis`）

调用方可以给**整条请求**一个总耗时上限。它与单次调用的超时不同：
预算是整个请求共享的一份，重试和嵌套调用都从同一份里扣，
不会每换一次调用、每换一个线程就重新起算。

### 4.1 取值规则（`budget.Budgets`，确定且可区分）

| 情况 | source | 生效预算 | 说明 |
| --- | --- | --- | --- |
| 头缺失或空白 | `default` | 2000ms | 与服务侧默认截止时间一致，**老调用方行为完全不变** |
| 非数字 / ≤0 | `invalid` | 2000ms | 回落默认，但来源与 `default` 可区分 |
| 超出 [10, 10000]ms | `clamped` | 裁剪到边界值 | 10ms 以下无工程意义，10000ms 以上防止等待无界放大 |
| 范围内合法值 | `provided` | 原样采用 | — |

解析是纯函数、不抛异常；预算头本身不参与业务错误分类。
判定结果在请求开始处写入时间线（`BUDGET_DECIDED` 事件，每请求恰好一条）
并打印日志 `budget decision rawHeader=... source=... effectiveMillis=... basis=...`，
成功响应体也回带 `budgetSource / budgetMillis / budgetRemainingMillis`。

### 4.2 共享与扣减口径

- 预算在 `ObservabilityFilter` 里用与请求时间线**同一时间原点**创建一次
  （`RequestBudget`，截止时刻 = 请求开始 + 预算），之后不可变；
  重试、嵌套 `audit` 调用、跨线程派工都读取同一个截止时刻。
- 每次下游尝试（含重试）和嵌套调用前都有预算闸门：
  已耗尽则不再发起新调用，直接以预算耗尽收尾。
- 等待上限 = `min(单次截止时间, 预算剩余量)`：预算先到时整条链路在预算时刻停下
  （`Future.cancel(true)` 中断慢调用），不会继续等满单次超时。

### 4.3 与超时、重试、指标的配合

- **结果可区分**：预算耗尽返回 **504 + `BUDGET_EXHAUSTED`**，
  单次下游超时仍是 500 + `SYSTEM_ERROR`（根因 `TimeoutException` /
  `DownstreamTimeoutException`），两者在时间线、指标、错误体上都可区分。
  预算耗尽由等待上限被预算截断（而非由单次超时截断）来判定，
  不依赖毫秒截断后的纳秒级再比较，判定确定。
- **不重试、不吞根因**：`BudgetExhaustedException` 与业务拒绝一样不被重试循环捕获；
  错误体保留 `causeChain`（如 `BudgetExhaustedException -> TimeoutException`），
  失败不会被层层包装吞掉。
- **时间线**：新增有界事件 `BUDGET_DECIDED`（每请求一次）与 `BUDGET_EXHAUSTED`；
  事件序号、相对耗时、每请求一次聚合计数等既有口径不变。
- **指标**：`BUDGET_EXHAUSTED` 是 `OutcomeCategory` 的一个有界枚举值，
  标签维度仍是 端点×类别×耗时桶，预算数值不会成为新标签，维度有界。
- **并发隔离**：预算挂在每请求的 `RequestScope` 上，无静态可变状态；
  跨线程通过显式捕获/恢复传播，线程池复用下不串号、不串预算，
  一个请求不可能用到别的请求的剩余预算。

## 5. 进程内指标语义

- 聚合器 `metrics.MetricsRegistry`：`ConcurrentHashMap` + `LongAdder`，
  记录每请求一次的 `count` 与 `totalMillis`。
- **维度（标签全部有界）**：
  - `endpoint`：使用 Spring 路由模板（如 `/api/process`），不使用原始 URL；
    含数字段/查询串/为空的路径统一归一为 `-`；未匹配路由为 `/unmapped`。
  - `category`：上表 5 个枚举之一。
  - `bucket`：固定 5 档耗时区间 `<50ms / 50-100ms / 100-250ms / 250-500ms / >=500ms`
    （下界含、上界不含，最后一档无上界）。
- 快照按 端点→类别→桶 稳定排序；长时间无流量时快照为空且不产生幽灵数据。
- “每请求恰好一次”：记录只发生在过滤器 `finally`；
  `RequestScope.markOutcome` 用 CAS 保证只归类一次，重试/嵌套/异常分支都无法重复计数。

`GET /observability/metrics` 示例：

```json
{
  "totalRequests": 3,
  "points": [
    {"endpoint": "/api/process", "category": "SUCCESS", "bucket": "250-500ms", "count": 1, "totalMillis": 305},
    {"endpoint": "/api/process", "category": "SYSTEM_ERROR", "bucket": "<50ms", "count": 1, "totalMillis": 210},
    {"endpoint": "/api/process", "category": "BUSINESS_REJECTION", "bucket": "<50ms", "count": 1, "totalMillis": 12}
  ]
}
```

## 6. 本地故障注入

`GET /api/process` 通过请求头控制（解析确定、非法值回落默认、数值有界裁剪）：

| 请求头 | 取值 | 含义 |
| --- | --- | --- |
| `X-Fault-Mode` | `none/slow/fail/timeout/reject` | 故障类型；非法/缺失→`none` |
| `X-Fault-Delay-Millis` | 0–5000（超出裁剪） | `slow` 模式真实延迟 |
| `X-Fault-Fail-Attempts` | 0–3（超出裁剪） | `fail` 模式前 N 次失败（0=每次失败，N+1 次恢复） |
| `X-Timeout-Millis` | 服务侧截止时间（最大 2000） | 超时后中断慢调用并快速失败 |
| `X-Budget-Millis` | 10–10000（缺失/非法回落 2000，越界裁剪） | 整条请求共享的总时间预算，见第 4 节 |

## 7. 本地验证方法

### 7.1 自动化测试（无需任何外部服务）

```bash
mvn -o test          # 离线跑全部测试；去掉 -o 可联网
```

测试分层：

| 测试类 | 覆盖点 |
| --- | --- |
| `CorrelationIdsTest` | provided 原样保留、缺失生成、非法拒绝（长度/字符集/中文/空格）、确定性；日志打印原始输入与判定依据 |
| `LatencyBucketTest` | 耗时区间边界（下界含上界不含、超长收敛、负值处理） |
| `MetricsRegistryTest` | 24 线程×200 次并发写入计数精确、空流量快照稳定、排序稳定、高基数路径归一 |
| `InMemoryTimelineStoreTest` | 有界 LRU 淘汰、重存变最新、缺失安全 |
| `FaultSpecTest` | 故障头非法回落与数值裁剪 |
| `BudgetsTest` | 预算头归一化：缺失/非法/越界/合法四类来源可区分、边界值、确定性；日志打印原始输入与判定依据 |
| `RequestBudgetTest` | 共享预算单调消耗不重新起算、耗尽收敛为 0、跨线程读取一致、多预算实例并发隔离 |
| `BudgetIntegrationTest` | 预算在重试中途耗尽（504 可区分、快速收尾、每请求计一次）、非法/越界预算兜底、预算充足时行为不变、16 并发请求预算隔离 |
| `ContextPropagationTest` | 400 任务/2 线程复用不串号不泄漏、restore 语义 |
| `FaultInjectionIntegrationTest` | 三类标识、慢调用落桶、两类超时根因、重试恢复/用尽根因、业务拒绝不重试、嵌套调用、时间线有序、计数一次 |
| `ConcurrentRequestIntegrationTest` | 24 真实并发请求的标识隔离与按类别精确计数（8+8+4+4=24） |

### 7.2 手动 curl 验证

```bash
mvn -o spring-boot:run        # 启动在 8080

# 1) 调用方提供合法标识：回显 provided
curl -i -H 'X-Correlation-Id: req-manual-0001' http://localhost:8080/api/process

# 2) 缺失标识：回显 generated（gen-...）
curl -i http://localhost:8080/api/process

# 3) 非法标识：确定性 400 + 审计 gen- 标识
curl -i -H 'X-Correlation-Id: bad id!!' http://localhost:8080/api/process

# 4) 慢调用 300ms（落 250-500ms 桶）
curl -s -H 'X-Fault-Mode: slow' -H 'X-Fault-Delay-Millis: 300' \
  -H 'X-Correlation-Id: req-slow' http://localhost:8080/api/process

# 5) 服务侧截止时间超时（200ms 截止，下游慢 1500ms），根因 TimeoutException
curl -s -H 'X-Fault-Mode: slow' -H 'X-Fault-Delay-Millis: 1500' \
  -H 'X-Timeout-Millis: 200' -H 'X-Correlation-Id: req-deadline' \
  http://localhost:8080/api/process

# 6) 前两次失败第三次成功：结果 SUCCESS，attempts=3，指标只 +1
curl -s -H 'X-Fault-Mode: fail' -H 'X-Fault-Fail-Attempts: 2' \
  -H 'X-Correlation-Id: req-retry' http://localhost:8080/api/process

# 7) 每次都失败：重试用尽，根因链 DownstreamException -> IOException
curl -s -H 'X-Fault-Mode: fail' -H 'X-Fault-Fail-Attempts: 0' \
  -H 'X-Correlation-Id: req-fail' http://localhost:8080/api/process

# 8) 业务拒绝：422，不重试
curl -i -H 'X-Fault-Mode: reject' -H 'X-Correlation-Id: req-rej' \
  http://localhost:8080/api/process

# 9) 请求级总预算 100ms，慢调用 1500ms：504 BUDGET_EXHAUSTED（区别于单次超时的 500）
curl -i -H 'X-Fault-Mode: slow' -H 'X-Fault-Delay-Millis: 1500' \
  -H 'X-Budget-Millis: 100' -H 'X-Correlation-Id: req-budget' \
  http://localhost:8080/api/process

# 10) 预算在重试中途耗尽：timeout 模式每次尝试停顿 20ms，预算 45ms
curl -i -H 'X-Fault-Mode: timeout' -H 'X-Budget-Millis: 45' \
  -H 'X-Correlation-Id: req-budget-retry' http://localhost:8080/api/process

# 11) 非法预算：回落默认 2000ms，响应体 budgetSource=invalid（与 default 可区分）
curl -s -H 'X-Budget-Millis: abc' -H 'X-Correlation-Id: req-budget-bad' \
  http://localhost:8080/api/process

# 查询有序事件时间线 / 指标 / 健康（预算判定见时间线 BUDGET_DECIDED / BUDGET_EXHAUSTED 事件）
curl -s http://localhost:8080/observability/timelines/req-budget-retry
curl -s http://localhost:8080/observability/timelines/req-retry
curl -s http://localhost:8080/observability/metrics
curl -s http://localhost:8080/observability/health
curl -s -X POST http://localhost:8080/observability/admin/reset
```

控制台日志每行带 `cid=` 与 `src=`，可用 `grep cid=req-retry` 串起单个请求的全部日志；
请求开始处会打印 `correlation decision rawHeader=... source=... effectiveId=... basis=...`
与 `budget decision rawHeader=... source=... effectiveMillis=... basis=...`，
即原始输入与归一化判定依据。

## 8. 设计边界

- 时间线与指标均为**进程内**实现：重启清空、不跨实例，这是本地可验证方案的有意取舍。
- 指标/事件的 detail 只放有界信息（模式名、异常简单类名、尝试序号），不放原始用户输入，
  避免日志注入与高基数。
- 观测端点（`/observability/**`）自身也会被计数；验证业务口径时按 `endpoint=/api/process` 过滤。
