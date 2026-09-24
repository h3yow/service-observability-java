# service-observability-java

Spring Boot 服务的进程内可观测性能力：关联标识传递、请求事件时间线、聚合指标与健康查询。
不依赖任何外部服务、真实凭据或付费组件，全部能力可在本地验证。

## 运行与验证

```bash
mvn spring-boot:run          # 启动服务（默认 8080）
mvn test                     # 运行全部测试（23 个用例）
```

## 关联标识传递规则

- 请求头：`X-Correlation-Id`；响应头回写 `X-Correlation-Id` 与 `X-Correlation-Id-Status`。
- 合法标识：8~64 字符，仅含 `[A-Za-z0-9_-]`（`CorrelationIds.isValid`）。
- 处理结果确定且可区分（`X-Correlation-Id-Status`）：
  - `provided`：调用方提供了合法标识，原样保留；
  - `generated-missing`：未提供标识，服务端生成；
  - `generated-invalid`：提供了非法标识，丢弃并生成新标识。
- 传递链路：`CorrelationIdFilter`（入站）→ `CorrelationContext`（ThreadLocal）→
  `CorrelationPropagatingExecutor`（跨线程，提交时捕获快照、执行后清理）→
  `DownstreamClient`（本地模拟下游，从上下文取标识）。
- 线程复用安全：过滤器 `finally` 中清理上下文；执行器包装任务执行后恢复/清理，
  并发请求不串号、不丢失（`CorrelationPropagationTest`、并发集成测试覆盖）。

## 错误分类口径

每个请求最终结果归入三类之一（`ResultCategory`）：

| 类别 | 判定 | HTTP 状态 |
|---|---|---|
| `SUCCESS` | 正常完成（2xx/3xx） | 200 |
| `BUSINESS_REJECTION` | 业务规则拒绝 / 4xx 客户端错误（含 404） | 422 / 4xx |
| `SYSTEM_ERROR` | 未预期异常（5xx），含下游失败与超时 | 500 |

- 分类由 `ObservabilityFilter` 在响应完成后确定；`GlobalExceptionHandler`
  可通过请求属性写入更精确的类别，过滤器只读取不覆盖。
- 原始根因沿 `cause` 链取最深处（`GlobalExceptionHandler.rootCause`），
  写入响应体 `rootCause` 字段与时间线 `root-cause` 事件，不被覆盖或静默丢弃。
- 每个请求只计数一次：`RequestTimeline.finish` 只允许生效一次，
  重试、嵌套调用、超时路径的重复结束都会被拒绝。

## 事件时间线

- 每个请求一条时间线（`TimelineStore`，容量上限 1000 条，超出淘汰最旧）。
- 事件含单调递增序号、时间戳、类型与详情，可查询：
  - `GET /observability/timeline/{correlationId}`：单条时间线；
  - `GET /observability/timelines?limit=20`：最近时间线列表。
- 下游调用的每次尝试（`downstream-attempt`/`downstream-failure`/`downstream-success`/
  `downstream-timeout`）都会写入时间线，重试过程完整可见。

## 指标语义

- `GET /observability/metrics`：进程内聚合计数，key 形如
  `端点|结果类别|耗时区间`，例如 `GET /api/demo/ok|SUCCESS|LE_10MS`；`total` 为请求总数。
- 耗时区间固定 6 档：`LE_10MS / LE_100MS / LE_500MS / LE_1S / LE_5S / GT_5S`。
- 标签有界：端点使用 Spring 路由模板（如 `/observability/timeline/{correlationId}`），
  未匹配路由归为 `GET /**`，不会随请求参数/标识产生高基数。
- 并发写入使用 `LongAdder`，各桶之和恒等于 `total`；无流量时快照保持稳定。
- `GET /observability/health`：`status=UP`、运行时长、累计请求数、空闲内存。

## 本地故障注入

`/api/demo/downstream` 支持参数化故障注入（不发起真实网络调用）：

| 参数 | 语义 |
|---|---|
| `failAttempts=N` | 前 N 次尝试注入失败；最多尝试 3 次（`MAX_ATTEMPTS`），N≥3 时最终失败 |
| `latencyMs=M` | 每次尝试睡眠 M 毫秒；M>2000（`TIMEOUT_MILLIS`）时抛出超时异常 |

演示端点：`/api/demo/ok`（成功+跨线程校验）、`/api/demo/reject`（业务拒绝）、
`/api/demo/error`（三层异常链）。

示例：

```bash
curl -H 'X-Correlation-Id: my-valid-id-01' http://localhost:8080/api/demo/ok
curl "http://localhost:8080/api/demo/downstream?failAttempts=2&latencyMs=50"   # 重试后成功
curl "http://localhost:8080/api/demo/downstream?failAttempts=5"                # 最终失败
curl "http://localhost:8080/api/demo/downstream?latencyMs=5000"                # 超时
curl http://localhost:8080/observability/metrics
```

## 测试

| 测试类 | 覆盖 |
|---|---|
| `CorrelationIdsTest` | 合法/缺失/非法标识判定，日志打印原始输入与归一化判定依据 |
| `CorrelationPropagationTest` | 跨线程传播、线程复用清理、提交线程不受污染 |
| `TimelineAndMetricsTest` | 事件有序、finish 一次性、耗时桶边界、并发计数一致、空闲快照稳定 |
| `ObservabilityIntegrationTest` | 端到端：三种标识处理、24 路并发隔离、异常链保留、重试不重复计数、慢调用/失败/超时故障注入、健康查询 |
