# IM 镜像投递监控与告警（队列堆积 / 投递失败 / 残留队列）

> 适用版本：本次改动（Spring Boot 3.3.13 + Redis + MySQL，**未引入任何新依赖**）。
> 相关背景文档：[`im-migration.md`](./im-migration.md)（两个库的边界）、
> [`im-server-patch.md`](./im-server-patch.md)（im-server 对接与运维，见其 §2.3 / §2.6 / §6）。

---

## 0. 一句话背景：为什么必须补监控

IM 采用「**课安数据库存消息 ✓ box-im im-server 只负责实时推送**」的双通道设计，
镜像投递是**尽力而为的旁路**：

* `ImSenderService.sendPrivate` / `sendSystem` 把异常吞成 `log.warn`；
* 这**是刻意的** ✗ —— 它在 `ChatServiceImpl.send()` 的 `@Transactional` 方法体内被调用，
  上抛异常会把**已经成功的消息写入一起回滚**。

代价是：**「消息已写入数据库」与「实时推送成功」是两回事，而后者在业务层完全不可见**。
Redis 挂了、队列堆到几万条、投递一直失败 —— 过去只有零散日志，**没有任何人会知道**。

本次补齐五件事（只做监控告警，**未改任何业务语义**）：

| # | 能力 | 落地位置 |
|---|---|---|
| ① | 投递计数器（attempt / pushed / failed / skipped）+ 只读快照 | `ImSenderService` / `ImDeliveryStats` |
| ② | 60 秒定时巡检（`SCAN` 队列长度 + 残留队列检测） | `ImQueueMonitorService` |
| ③ | 邮件告警（复用 `MailService`，带 30 分钟冷却）+ 内存告警历史 | `ImAlertService` / `ImAlertRecord` |
| ④ | `/health/ready` 追加 `im` 字段（**不影响 HTTP 状态码**） | `HealthController` |
| ⑤ | 管理端只读看板 + 告警历史 + **受控的**残留队列清理 | `AdminImController` / `AdminImService`（见 §6） |

---

## 1. 指标含义（第 ① 项）

`ImSenderService` 用四个 `java.util.concurrent.atomic.LongAdder` 计数，
通过 `snapshot()` 暴露成不可变 record：

```java
public record ImDeliveryStats(long attempts, long pushed, long failed, long skipped)
```

| 字段 | 含义 | **粒度（别混着看）** | 计数点 |
|---|---|---|---|
| `attempts` | 尝试投递的消息条数 | **消息**：一次 `sendPrivate` / `sendSystem` 调用 = 1 | `ImSenderService` `sendPrivate` 的 try 前、`sendSystem(List,Object)` 的 try 前 |
| `pushed` | 成功写入队列的条数 | **队列写入**：同一条消息可能写多个 `serverId` 队列（多端在线），每个 +1 | `ImSenderService.push()` 循环成功分支（`add(pushed)`） |
| `failed` | 投递异常次数 | **队列写入**：单条消息的多个 `serverId` 可部分成功、部分失败 | `push()` 的逐队列 catch、`sendPrivate` 的 catch、`sendSystem` 的 catch |
| `skipped` | IM 未启用导致的 no-op 次数 | **消息**：`enabled() == false` 的直接返回 | `sendPrivate` / `sendSystem` 的 `!enabled()` 分支 |

**因此 `attempts != pushed + failed`**：

* 接收方离线（`im:user:server_id:*` 全空）→ 不投递、不计数（既不是成功也不是故障）；
* 参数非法（`recvId == null`、`content` 为空）→ 刻意不计数，避免脏调用把失败率打起来；
* `skipped` **只统计「IM 未启用」**，不统计离线（离线是正常现象，不是故障）。

设计约束（都已满足）：

* **不影响发送路径**：`LongAdder.increment()` / `add()` 是 CAS 无锁操作，**不抛异常**
  （溢出只是静默回绕），计数逻辑不可能成为新的投递失败来源；
* **不改变「吞异常」行为**：计数只加在既有分支上，公开方法仍然 `log.warn` + `return 0`，绝不外抛；
* `LongAdder.sum()` 不是跨字段原子快照，但只用于「本轮增量是否超阈值」这种量级判断，误差无害。

跨轮比较用 `ImDeliveryStats.since(previous)`（逐字段做差）；进程重启导致计数器归零时，
该差值按「以当前值为增量」处理，**不会出现负数**，也不会漏掉重启后立刻累积的失败。

---

## 2. 定时巡检（第 ② 项）

`com.kean.im.ImQueueMonitorService`，`@Scheduled(fixedDelayString = "${kean.im.monitor-interval-ms:60000}")`
（**默认 60 秒**，属性带默认值，**刻意不写进 `application*.yml`** —— 与项目既有风格一致）。

* 用 `fixedDelay` 而不是 `fixedRate`：巡检耗时取决于键数量，慢轮次自然错开，不会堆积执行；
* 加 `@SchedulerLock(name = "im.queueMonitor", lockAtMostFor = "PT2M")`（与 `TaskScheduleService`
  同一套 JdbcTemplate 锁，`shedlock` 表见 `V26__shedlock.sql`）：多实例下同一时刻只有一个实例在巡检，
  避免重复发告警邮件；
* 整个方法体包在 try/catch 里：巡检自身出错只记 `log.warn`，下一轮重试。

### 2.1 为什么用 `SCAN` 而不是 `KEYS` ✗

* `KEYS` 是 **O(N) 全键遍历且阻塞 Redis 的单线程**：键多时会把**所有客户端一起卡住**，
  包括 im-server 正在跑的 `leftPop`（消息拉取会直接停摆）→ 线上禁用；
* `SCAN` 是**游标式增量遍历**，每次只返回一小批（`COUNT` 只是提示），
  在大库上也不会长时间占用事件循环；游标在同一个 `RedisCallback` 里消费完并 `close()`；
* 额外加了硬封顶 `kean.im.scan-max-keys`（默认 2000，单前缀单轮）：
  万一键空间被异常写入撑爆，巡检自己也不会失控（达到封顶会打 WARN）。

巡检只发三类**只读**命令：`SCAN` / `GET im:max_server_id` / `LLEN`。
**它从不执行 `DEL` / `LTRIM` / `SET`。**

### 2.2 残留队列判定（只报告，**不自动删** ✗）

im-server 每次启动都会从 `im:max_server_id` 自增领取一个新的 `serverId`，
而队列键是 `im:message:*:{serverId}`（见 `im-server-patch.md` §2.4 / §6）。
**重启 / 缩容后，旧编号的队列永远不会再有人消费**，消息一直躺在 Redis 里直到把内存吃满。

判据（命中任一即记为「残留」）：

| # | 判据 | 说明 |
|---|---|---|
| ① | `serverId > im:max_server_id` | 该编号不可能对应任何**现存**的 im-server 实例（计数器被重置或写入了脏值） |
| ② | 队列长度 > 0 **且连续 `kean.im.residue-stagnant-rounds`（默认 5）轮长度完全没变** | 「长期无消费」的启发式：正常消费下长度会持续波动 |

* 读不到 `im:max_server_id`（im-server 从未启动过 / 键被清）时，**只按判据 ② 判残留**，
  不会因为缺这个键就把所有队列都当成越界残留；
* 后缀不是数字的键（不是 im-server 的队列键型）只记长度，不参与残留判定；
* 残留**只报告**：告警正文里给出 `redis-cli` 的 `LLEN` / `LRANGE` / `DEL` 命令，
  由运维确认队列内容后再决定要不要删 —— `DEL` 会**永久丢弃**尚未推送的消息，程序绝不代劳。

### 2.3 阈值（全部有默认值，可配）

| 配置项 | 默认 | 触发条件 | 动作 |
|---|---|---|---|
| `kean.im.monitor-interval-ms` | `60000` | —— | 巡检周期（毫秒） |
| `kean.im.queue-warn-threshold` | `1000` | 单队列长度 **> 1000** | **WARN 日志** + 状态 `DEGRADED` |
| `kean.im.queue-error-threshold` | `5000` | 单队列长度 **> 5000** | **ERROR 日志 + 邮件告警** |
| `kean.im.delivery-failure-threshold` | `20` | **最近一个巡检周期内**投递失败增量 **> 20** | **ERROR 日志 + 邮件告警** |
| `kean.im.residue-stagnant-rounds` | `5` | 长度连续 N 轮不变（约 N 分钟） | **WARN 日志 + 邮件告警**（带清理命令） |
| `kean.im.scan-count` | `200` | —— | `SCAN` 每批建议条数 |
| `kean.im.scan-max-keys` | `2000` | —— | 单前缀单轮最多检查的键数（防御性封顶） |

所有配置项都**只在这里有默认值**，`application*.yml` / `.env*` **一行未改**：
要覆盖时用环境变量或启动参数即可（Spring relaxed binding）。

「最近一个巡检周期内」= 本轮快照 − 上一轮快照，所以阈值比较的是**增量**而不是累计值。

### 2.4 IM 未启用时：整个巡检静默跳过

`ImSenderService.enabled() == false`（`IM_JWT_SECRET` 未配置 / 不足 32 字节 /
`KEAN_IM_MIRROR_ENABLED=false`）时：

* **不连 Redis、不判残留、不发告警**；
* 状态置 `DISABLED`（`/health/ready` 的 `im` 字段）；
* **只在「启用 → 未启用」的第一次记一行 DEBUG**（`AtomicBoolean` 去重），
  **不会每 60 秒刷日志**。

---

## 3. 邮件告警（第 ③ 项）

### 3.1 收件人与开关

| 配置项 | 默认 | 说明 |
|---|---|---|
| `KEAN_IM_ALERT_TO` | 回落 `MAIL_USERNAME` | 告警收件人（新环境变量）。两者都为空 → **只记 ERROR 日志**，不抛异常 |
| `KEAN_IM_ALERT_ENABLED` | `true` | `false` 时降级为纯 ERROR 日志（每轮都会打，关掉告警的人必须能从日志里看到问题） |
| `kean.im.alert-cooldown-minutes` | `30` | 同一类告警的最小发信间隔 |

**复用现有邮件通道**：`MailService` 新增的 `sendAlert(to, subject, text)` 与验证码邮件
共用同一个 `JavaMailSender` Bean 与同一份 `kean.mail.*`（`from` / `from-name` / `ready` 判定），
**没有新建第二套 SMTP 配置或通道**。

发信前的三道前置检查（任一不满足都只记 ERROR 日志，**不抛异常**）：
① 告警开关；② 收件人非空；③ `mailService.ready()`（`kean.mail.enabled` + `kean.mail.from` + `JavaMailSender` 存在）。
**注意**：`kean.mail.enabled` 在 dev / test 默认 `false`，prod 默认 `true`
（`application-dev.yml:44` / `application-prod.yml:57`）—— 本地开发不会真发邮件。

### 3.2 冷却（必须有 ✗）

* 粒度是**告警类别**（三类）：`queue-backlog`（队列堆积）/ `delivery-failure`（投递失败）/
  `queue-residue`（残留队列）；
* 同一类别 30 分钟内**只发一次**；冷却期内无论「恢复」还是「再次触发」都**只记日志**；
* 实现：进程内 `ConcurrentHashMap<String, Long>`（类别 → 上次**尝试发送**的时间戳），
  零依赖、零 Redis 写入（Redis 本身可能就是出问题的那一环，把冷却放 Redis 会在最需要告警时失效）；
* **先记冷却、再发信**：SMTP 抖动时宁可 30 分钟内不再重试，也不要每 60 秒重发造成邮件风暴。

**取舍（重要，已写进类注释）**：

* ⚠️ **多实例部署时每个实例各有一份冷却表，同一类告警最多可能重复发 N 份（N = 实例数）**。
  当前是单实例形态，且巡检已被 `@SchedulerLock` 收敛成「同一时刻只有一个实例在跑」，
  实际重复概率很低；若将来要多实例且要求精确去重，应把冷却位点挪到 Redis（键另议），而不是在这里加锁；
* ⚠️ 进程重启会清空冷却表（可能立刻补发一封）—— 可接受：重启后重新确认状态比漏报好；
* ⚠️ 队列堆积在 WARN→ERROR 升级时，若还在冷却期内**不会再补发一封**（ERROR 日志照常打），
  冷却结束后的下一封反映的是当时的（更严重的）状态。这是「三类冷却」这一约定的直接结果。

### 3.3 「告警失败绝不影响业务」的保证

* `ImAlertService.alert(...)` 的**整个方法体**包在一个大 try/catch 里：
  收件人为空、邮件未配置、SMTP 超时、正文拼接出错……一律只落 `log.error` / `log.warn`，**绝不外抛**；
* 本类**不参与任何业务事务**，**从不写 Redis、从不写数据库**，
  因此不可能影响 `ChatServiceImpl.send()` 这条主链路；
* 巡检调用它时不需要再包 try/catch（它自己保证不抛）。

### 3.4 告警正文：可操作

每封告警都包含四要素：

1. **问题类型**（队列堆积 / 投递失败 / 残留队列）与严重级别；
2. **当前值 / 阈值 / 相关配置项名**；
3. **相关 Redis 键名**（例如 `im:message:private:{serverId}`、`im:max_server_id`）；
4. **建议的处理命令**（`redis-cli -n 0 LLEN/LRANGE/DEL ...`、`INFO memory`、`grep` 日志关键字），
   残留队列的邮件还带完整的「看长度 → 看内容 → 确认无人消费 → 再 DEL」步骤。

⚠️ 正文与日志里**不含任何凭据**：没有 Redis 密码、SMTP 密码、`IM_JWT_SECRET`；
建议的 `redis-cli` 命令**刻意不带 `-a`**，请用 `REDISCLI_AUTH` 之类的受控方式提供认证。

> Redis 库号固定 0：`RedisConfig` 只支持 standalone，且 `im-server` 与 kean 必须共用 **0 号库**
> （见 `im-server-patch.md` §2.3），所以命令统一写 `redis-cli -n 0`。

### 3.5 告警历史（内存态，重启即丢）

* 每一条**检出的问题**都会记进 `ImAlertService` 进程内的环形缓冲
  （`ArrayDeque` + `synchronized`，容量 `kean.im.alert-history-size`，**默认 50 条**）；
* ⚠️ **包含被冷却抑制、没发出去的那种记录** —— 这样管理端看板在冷却期内也能看到
  「问题仍在持续」，而不是一片空白（该条记录里 `mailSent=false` + `mailNote` 说明原因）；
* ⚠️ **不落库、不加表、不加迁移**；进程重启 / 重新部署后历史清空。
  这是本轮刻意接受的取舍：需要长期留存请查运维邮箱，或 kean 日志里的 `[IM 告警]` 关键字；
* 记录字段（时间 / 类别 / 级别 / 问题类型 / 当前值 / 阈值 / 键名 / 是否已发邮件 / 未发原因）
  通过 `GET /api/admin/im/alerts` 暴露（见 §6.2）。

---

## 4. `/health/ready` 的 `im` 字段（第 ④ 项）

### 4.1 改前 / 改后

**改前**（`GET /health/ready`，一切正常时）：

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"status":"UP","db":"UP","redis":"UP"}
```

**改后**（同样一切正常、且 IM 已启用且巡检无异常）：

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"status":"UP","db":"UP","redis":"UP","im":"UP"}
```

字段与取值：

| 取值 | 含义 |
|---|---|
| `DISABLED` | IM 未启用：`IM_JWT_SECRET` 未配置/不合格，或 `KEAN_IM_MIRROR_ENABLED=false`（**没有监控组件时按此处理**，例如单元测试） |
| `UP` | 已启用，且最近一次巡检无异常（进程刚起、还没跑过第一轮巡检时也按 `UP`） |
| `DEGRADED` | 已启用，但存在**队列长度 > WARN 阈值** / **周期内投递失败 > 阈值** / **残留队列** |

### 4.2 为什么 `im` 不影响 HTTP 状态码 ✗

* `status` / HTTP 码仍然**只由 `db` + `redis` 决定**，既有字段的格式与语义**一行未改**；
* 现有 curl 验证（`{"status":"UP","db":"UP","redis":"UP"}` 必须出现）继续通过 —— 只是**多了**一个字段；
* **不让 IM 判 DOWN 的理由**：IM 是旁路。队列堆到几万条时 kean 的其余接口**完全可用**，
  把整站判成不健康（503）会让负载均衡把所有实例摘走 → 真正的雪崩。
  需要按 IM 告警的编排系统请**读 `im` 字段本身**，而不是看状态码。

实现细节：`HealthController` 用 `@Autowired(required = false)` 的 **setter** 注入
`ImQueueMonitorService`（不是构造参数）—— 既有单测 `HealthControllerTest` 直接调用两个构造函数，
改签名会破坏它；且 IM 组件缺失时探针必须照常工作。`imStatus()` 只读 volatile 字段、不做 I/O，
不会拖慢探针。

### 4.3 验证命令

```bash
# 只看状态码与整体（既有验证，必须继续通过）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/health/ready   # 期望 200
curl -s http://127.0.0.1:8080/health/ready                                    # 期望 status/db/redis + im

# 只看 im 字段
curl -s http://127.0.0.1:8080/health/ready | grep -o '"im":"[A-Z]*"'
```

---

## 5. 运维处置步骤（收到告警后怎么做）

### 5.1 「队列堆积（ERROR）」—— 单队列 > 5000

1. 确认 im-server 进程：`systemctl status im-server`（WS 8878，路径 `/im`）；
2. 确认共用 Redis 与 **0 号库**（`im-server-patch.md` §2.3）；
3. 看键与长度：
   ```bash
   redis-cli -n 0 GET im:max_server_id
   redis-cli -n 0 LLEN im:message:private:1
   redis-cli -n 0 LRANGE im:message:private:1 0 2
   ```
4. 若 `serverId` 仍是 im-server 的活跃编号 → 说明**消费跟不上或被卡住**，
   查 im-server 日志与 Redis 是否 `maxmemory` 触发（`redis-cli -n 0 INFO memory`）；
5. 若 `serverId` 是**旧编号**（重启导致漂移）→ 队列永远不会被消费，按 5.3 处理。

> ⚠️ 队列堆积本身**不影响 kean 业务**（消息已落数据库，HTTP 链路正常）——
> 它的真正风险是 **Redis 内存被无界队列吃满**，最终影响**所有**依赖 Redis 的功能。

### 5.2 「投递失败次数超阈值（ERROR）」—— 一个周期内 > 20

1. `redis-cli -n 0 PING`、`redis-cli -n 0 INFO memory`；
2. kean 日志（关键字，源文件 `ImSenderService.push`）：
   ```bash
   grep "\[IM 镜像投递\] 写入队列" /path/to/kean.log | tail -20
   ```
3. 常见原因：Redis 不可用 / 连接被防火墙切断 / `maxmemory` 拒绝写入 / 网络抖动（抖动会自愈，
   下一轮巡检失败数回落）；
4. 失败只在**镜像投递**上发生：业务消息仍然正常入库，用户只是可能收不到实时推送
   （`/health/ready` 的 `im` 会显示 `DEGRADED`）。

### 5.3 「疑似残留队列（WARN）」

```bash
# 1) 看长度与内容（只读）
redis-cli -n 0 GET im:max_server_id
redis-cli -n 0 LLEN im:message:private:3
redis-cli -n 0 LRANGE im:message:private:3 0 2

# 2) 确认该 serverId 不会再被任何 im-server 消费（实例数 / 重启历史 / 当前 max_server_id）

# 3) ⚠️ 确认无人依赖后再删（DEL 会永久丢弃这些未推送的消息，程序不会代劳）
redis-cli -n 0 DEL im:message:private:3
```

判定与阈值见 §2.2 / §2.3；**只报告不自动删**是刻意的：
残留队列里可能是尚未推送的真实消息，删掉就永久丢失。
确认要清理时，除了下面的 `redis-cli`，也可以走管理端那条带二次校验的接口（§6.3）。

### 5.4 冷却期内的再次触发

同类别告警 30 分钟内只发一封邮件；期间问题仍在发生时会继续打
`[IM 告警][冷却中，不发邮件]` 的 WARN 日志（含完整问题详情）。
想立刻收到下一封的临时办法：重启 kean（冷却表在内存里，会清空）——
**不要**为了收邮件把 `kean.im.alert-cooldown-minutes` 调成 0（会变成邮件风暴）。

---

## 6. 管理端接入说明（接口已就位，`web-kean` 尚未部署）

> 本轮**不动前端**（约定：不碰 `web-kean/`）：三个接口先在后端就位，等 `web-kean` 部署时
> 前端可以直接照本节实现，不需要再回来补后端。

### 6.0 共同约定

* **路径前缀 `/api/admin/im`**，全部走项目统一的 `Result` 信封：
  `{"code":0,"message":"ok","data":{...}}`（`ErrorCode.SUCCESS = (0, "ok")`；失败时 `code` 是业务码、`data` 省略，
  见 `Result` / `ErrorCode` / `GlobalExceptionHandler`）；
* **权限**：`SecurityConfig` 的 `.requestMatchers("/api/admin/**").hasRole("ADMIN")` 一视同仁 ——
  未登录 `401`、非管理员 `403`（返回体同样是 `Result` 信封，由 `JwtAuthFilter` /
  `AccessDeniedHandler` 写出）。前端沿用现有管理端的 token 与拦截器即可，**不需要新增权限判断**；
* **时间字段**：`LocalDateTime` 按项目统一配置序列化成 ISO-8601 字符串
  （`spring.jackson.serialization.write-dates-as-timestamps: false`），前端按现有管理端页面的时间格式化逻辑处理；
* **不含任何凭据**：三个接口的返回与日志里都没有 Redis 密码、SMTP 密码、`IM_JWT_SECRET`、连接串；
* **读写区分**：前两个 GET 是**只读**；第三个 POST 是**写操作（会永久删除 Redis 里的消息）**，
  前端必须做成二次确认 + 危险按钮（详见 §6.3）。

### 6.1 `GET /api/admin/im/stats` —— 看板（只读）

数据来自**最近一次巡检**的不可变快照（默认 60 秒一轮），不是每次请求实时 `SCAN` —— 这样管理端刷新
不会给 Redis 加压，也保证看板、告警邮件、`/health/ready` 三处口径一致。

返回样例（IM 启用、巡检正常）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "status": "UP",
    "enabled": true,
    "redisAvailable": true,
    "message": "本轮巡检正常：无队列堆积、无残留队列，周期内投递失败未超阈值",
    "counters": { "attempts": 1284, "pushed": 1301, "failed": 3, "skipped": 0 },
    "queues": [
      { "key": "im:message:private:1", "length": 12, "serverId": 1, "residue": false, "residueReason": null },
      { "key": "im:message:private:3", "length": 6200, "serverId": 3, "residue": true,
        "residueReason": "长度 6200 已连续 7 轮未变化，疑似无消费者" }
    ],
    "queueTotal": 6212,
    "queueCount": 2,
    "residueFound": true,
    "residueKeys": ["im:message:private:3"],
    "maxServerId": 4,
    "failedInLastCycle": 0,
    "lastInspectionAt": "2026-02-18T10:21:30",
    "queriedAt": "2026-02-18T10:21:45"
  }
}
```

`status` 与 `/health/ready` 的 `im` 字段**完全同源**（同一个 `healthStatus()`，取值
`DISABLED` / `UP` / `DEGRADED`）。

前端建议渲染：

| 字段 | 建议 |
|---|---|
| `status` | 顶部状态胶囊：`UP` 绿 / `DEGRADED` 黄红 / `DISABLED` 灰（`DISABLED` 时其余数据无意义，可整块置灰） |
| `enabled` | 与 `status=DISABLED` 配合显示「IM 未启用（未配置 IM_JWT_SECRET 或镜像投递已关闭）」 |
| `redisAvailable` + `message` | `false` 时显示「数据不可用」提示条并**禁用**队列表格的删除建议；`message` 直接展示 |
| `counters` | 四个数字卡片（attempt / pushed / failed / skipped），并注明「累计值，进程重启归零」「pushed/failed 按队列写入计，attempt 按消息计，因此 attempts ≠ pushed + failed」 |
| `queues[]` | 表格：键名、长度、serverId、是否残留（残留行高亮 + `residueReason` 悬浮说明）；长度可加阈值进度/颜色（> WARN 黄、> ERROR 红） |
| `queueTotal` / `queueCount` | 概览行：「当前 N 个队列，积压 M 条」 |
| `residueFound` / `residueKeys` | 有残留时在表格上方给一条告警提示，并提供「去清理」按钮（跳到 §6.3） |
| `maxServerId` | 展示 `im:max_server_id` 当前值（排查 serverId 漂移的关键数字） |
| `failedInLastCycle` | 展示「最近一个周期失败 N 次」；> 20 时标红（与告警阈值一致） |
| `lastInspectionAt` / `queriedAt` | 页面右上角显示「数据时间：{lastInspectionAt}（查询于 {queriedAt}）」；`lastInspectionAt` 为 `null` 时提示「IM 未启用 / 尚未完成第一次巡检」 |

⚠️ `lastInspectionAt` 落后 `queriedAt` 是**正常的**（最多约 60 秒），不要因此判定「后端卡住」。
若长时间不更新且 `redisAvailable=false`，说明巡检读不到 Redis。

`redisAvailable=false` 时的返回样例（Redis 不可用，接口仍是 `200` 而不是 `500`）：

```json
{ "code": 0, "message": "ok",
  "data": { "status": "UP", "enabled": true, "redisAvailable": false,
            "message": "Redis 不可用，队列与残留数据不可用（状态沿用上一轮结论）",
            "counters": { "attempts": 42, "pushed": 40, "failed": 25, "skipped": 0 },
            "queues": [], "queueTotal": 0, "queueCount": 0,
            "residueFound": false, "residueKeys": [], "maxServerId": null,
            "failedInLastCycle": 12, "lastInspectionAt": "2026-02-18T10:20:30",
            "queriedAt": "2026-02-18T10:21:45" } }
```

（注意：`counters` 与 `failedInLastCycle` 来自 JVM 内存，**Redis 挂了也照常准确** ——
这正是「Redis 故障但业务仍在投递」时最有价值的两个数字。）

### 6.2 `GET /api/admin/im/alerts?limit=50` —— 告警历史（只读，**内存态**）

* `limit` 可选，默认 50，会被历史缓冲容量（`kean.im.alert-history-size`，默认 50）截断；
* ⚠️ **内存态：进程重启 / 重新部署即清空**（`data.note` 里也照实写了这句话，前端应把它展示出来）；
  需要长期留存请看运维邮箱或 kean 日志里的 `[IM 告警]`。

返回样例：

```json
{
  "code": 0, "message": "ok",
  "data": {
    "list": [
      { "time": "2026-02-18T10:20:30", "kind": "queue-backlog", "level": "ERROR",
        "summary": "镜像投递队列长度超过 ERROR 阈值（im-server 消费不动或已停）",
        "currentValue": "超过阈值的队列：[im:message:private:3 长度=6200]",
        "threshold": "ERROR > 5000 条（kean.im.queue-error-threshold）",
        "keys": ["im:message:private:3"],
        "mailSent": true, "mailNote": null },
      { "time": "2026-02-18T10:19:30", "kind": "queue-residue", "level": "WARN",
        "summary": "疑似残留队列（旧 serverId，无人消费）",
        "currentValue": "im:message:private:3（长度 6200 已连续 7 轮未变化，疑似无消费者）",
        "threshold": "长度 > 0 且连续 5 轮不变，或 serverId > im:max_server_id（kean.im.residue-stagnant-rounds）",
        "keys": ["im:message:private:3"],
        "mailSent": false, "mailNote": "冷却中（同类告警 30 分钟内只发一次，剩余 1742 秒）" }
    ],
    "size": 2, "limit": 50, "capacity": 50,
    "note": "告警历史保存在 kean 进程内存中（最多 50 条），进程重启或重新部署即清空；需要长期留存请查运维邮箱，或 kean 日志里的 [IM 告警] 关键字。"
  }
}
```

前端建议渲染：

| 字段 | 建议 |
|---|---|
| `kind` | 三个固定值 → 中文标签：`queue-backlog` 队列堆积 / `delivery-failure` 投递失败 / `queue-residue` 残留队列 |
| `level` | `ERROR` 红、`WARN` 黄 |
| `time` | 时间列（新的在前，接口已排序） |
| `summary` / `currentValue` / `threshold` | 「问题 / 当前值 / 阈值」三列或展开行 |
| `keys[]` | 键名列表（等宽字体，可复制）；**不要**在这些键上做「一键删除」，删除走 §6.3 |
| `mailSent` | 是否已发邮件（✓/✗）；`false` 时把 `mailNote` 作为悬浮说明展示（冷却中 / 告警关闭 / 无收件人 / 邮件未就绪 / 发送失败） |
| `note` | 页面底部固定提示（内存态、重启丢失、替代来源） |
| 空列表 | `list: []` 且 `size: 0` —— 显示「暂无告警（或进程刚重启，历史已清空）」，**不要**显示成错误 |

### 6.3 `POST /api/admin/im/queues/clean` —— 清理残留队列（**写操作 ✗**）

> ⚠️ **这是本组接口里唯一的写操作，而且不可逆**：`DEL` 会把该队列里**尚未推送**的消息永久丢弃。
> 前端必须做成危险操作（二次确认弹窗、默认禁用按钮、显示将要删除的键与长度），
> 后端也另有四道闸门（确认串 / 只删巡检判定的残留 / 活跃 serverId 二次校验 / 删前留痕）。

请求：

```jsonc
// 最小请求：确认串必填（缺了会被 400 校验拦下）
{ "confirm": "CLEAN_RESIDUE" }

// 收窄范围：只清理指定的 serverId（可选，一次最多 64 个）
{ "confirm": "CLEAN_RESIDUE", "serverIds": [3, 7] }
```

校验失败（缺少 / 写错确认串）走项目统一的参数校验响应：

```json
{ "code": 40000, "message": "确认串必须为 CLEAN_RESIDUE" }
```

正常返回（逐键结果；**被拒绝也是正常结果**，不是错误）：

```json
{
  "code": 0, "message": "ok",
  "data": {
    "requested": 2, "deleted": 1, "skipped": 1,
    "results": [
      { "key": "im:message:private:3", "serverId": 3, "length": 6200, "deleted": true,
        "reason": "已删除（丢弃未推送消息 6200 条）" },
      { "key": "im:message:system:2", "serverId": 2, "length": 15, "deleted": false,
        "reason": "已拒绝：serverId=2 仍在活跃集合中（有用户挂在该实例上，或它就是最新实例），删除会丢失在线消息" }
    ],
    "cleanedAt": "2026-02-18T10:25:01",
    "note": "部分完成：删除 1 个，未删除 1 个（原因见逐键结果）"
  }
}
```

拒绝的常见原因（都会出现在 `results[].reason` 与 `note` 里）：

| 原因 | 含义 / 处理 |
|---|---|
| `活跃 serverId 集合不可用或不完整` | 在线槽位键数超过 `kean.im.scan-max-keys` 封顶，或 `im:max_server_id` 读不到 —— 一律拒绝删除，需人工确认（可临时调大封顶） |
| `serverId=N 仍是最新实例编号` | 它可能就是当前在跑的 im-server，删了会丢在线消息 |
| `Redis 不可用` | 未执行任何删除，刷新后重试 |
| `键已不存在` | 已被消费或人工删除（`DEL` 返回 0），无需处理 |
| `尚无巡检结果 / 未读到 Redis` | 没有判定依据，`requested=0`；等一轮巡检（≤60 秒）后重试 |

前端建议：候选列表直接用 `stats.residueKeys`（或 `queues[].residue`），
按钮文案「清理残留队列（不可逆）」，点击后弹窗列出将删除的键与当前长度，要求输入/确认
`CLEAN_RESIDUE` 后才发请求；返回后按 `results[]` 逐行展示成功/拒绝原因，并重新拉一次 `stats`。

---

## 7. IM 未启用时：全部静默

| 组件 | 行为 |
|---|---|
| 投递计数 | 只累加 `skipped`，其余不动 |
| 巡检 | 整轮跳过：不读 Redis、不判残留、不告警，只在首次记一行 DEBUG |
| 告警 | 不会被调用（巡检直接返回） |
| `/health/ready` | `im: DISABLED`（HTTP 码与其余字段不受影响） |
| 邮件 | 不会发出（也不会因为「收件人未配置」刷日志，因为根本不会走到告警） |
| `GET /api/admin/im/stats` | `status: DISABLED`、`redisAvailable: false`、`message` 说明原因；接口仍 `200` |
| `GET /api/admin/im/alerts` | 正常返回（通常是空列表，除非「曾启用过」的历史还在内存里） |
| `POST /api/admin/im/queues/clean` | 拒绝：`requested=0`，`note` 说明「尚无巡检结果，没有可清理的判定依据」，**不删任何键** |

判据只有一条：`ImSenderService.enabled() == mirrorEnabled && ImTokenService.enabled()`。

---

## 8. 配置项总表（**都不需要写进 `application*.yml` / `.env*`**）

| 配置项 | 环境变量写法 | 默认 | 作用 |
|---|---|---|---|
| `kean.im.monitor-interval-ms` | `KEAN_IM_MONITOR_INTERVAL_MS` | `60000` | 巡检周期（毫秒） |
| `kean.im.queue-warn-threshold` | `KEAN_IM_QUEUE_WARN_THRESHOLD` | `1000` | 队列 WARN 阈值 |
| `kean.im.queue-error-threshold` | `KEAN_IM_QUEUE_ERROR_THRESHOLD` | `5000` | 队列 ERROR 阈值（+ 告警） |
| `kean.im.delivery-failure-threshold` | `KEAN_IM_DELIVERY_FAILURE_THRESHOLD` | `20` | 周期内失败次数阈值（+ 告警） |
| `kean.im.residue-stagnant-rounds` | `KEAN_IM_RESIDUE_STAGNANT_ROUNDS` | `5` | 残留判定所需连续不变轮数 |
| `kean.im.scan-count` | `KEAN_IM_SCAN_COUNT` | `200` | `SCAN` 每批条数 |
| `kean.im.scan-max-keys` | `KEAN_IM_SCAN_MAX_KEYS` | `2000` | 单前缀单轮检查键数封顶（**也是「活跃 serverId 集合」能否用于删除的判据**） |
| `kean.im.alert-cooldown-minutes` | `KEAN_IM_ALERT_COOLDOWN_MINUTES` | `30` | 同类告警冷却分钟数 |
| `kean.im.alert-history-size` | `KEAN_IM_ALERT_HISTORY_SIZE` | `50` | 告警历史（内存环形缓冲）容量 |
| `KEAN_IM_ALERT_ENABLED` | 同名 | `true` | 告警邮件总开关 |
| `KEAN_IM_ALERT_TO` | 同名 | 回落 `MAIL_USERNAME` | 告警收件人 |

---

## 9. 关键代码位置

| 关注点 | 文件 |
|---|---|
| 四种计数 + `snapshot()` | [`kean/src/main/java/com/kean/im/ImSenderService.java`](../../kean/src/main/java/com/kean/im/ImSenderService.java) |
| 快照 record 与跨轮增量 | [`kean/src/main/java/com/kean/im/ImDeliveryStats.java`](../../kean/src/main/java/com/kean/im/ImDeliveryStats.java) |
| 巡检（SCAN / 阈值 / 残留 / 状态 / 活跃 serverId 集合） | [`kean/src/main/java/com/kean/im/ImQueueMonitorService.java`](../../kean/src/main/java/com/kean/im/ImQueueMonitorService.java) |
| 巡检结果的不可变快照（同源数据源） | [`kean/src/main/java/com/kean/im/ImQueueInspection.java`](../../kean/src/main/java/com/kean/im/ImQueueInspection.java) |
| 告警（收件人 / 开关 / 冷却 / 历史 / 正文） | [`kean/src/main/java/com/kean/im/ImAlertService.java`](../../kean/src/main/java/com/kean/im/ImAlertService.java)、[`ImAlertRecord.java`](../../kean/src/main/java/com/kean/im/ImAlertRecord.java) |
| 邮件通道（新增 `sendAlert`，与验证码共用） | [`kean/src/main/java/com/kean/service/MailService.java`](../../kean/src/main/java/com/kean/service/MailService.java)、[`MailServiceImpl.java`](../../kean/src/main/java/com/kean/service/impl/MailServiceImpl.java) |
| `im` 字段 | [`kean/src/main/java/com/kean/controller/HealthController.java`](../../kean/src/main/java/com/kean/controller/HealthController.java) |
| 管理端三接口 | [`kean/src/main/java/com/kean/controller/AdminImController.java`](../../kean/src/main/java/com/kean/controller/AdminImController.java)、[`AdminImService.java`](../../kean/src/main/java/com/kean/service/AdminImService.java)、[`AdminImServiceImpl.java`](../../kean/src/main/java/com/kean/service/impl/AdminImServiceImpl.java) |
| 管理端 VO / DTO | [`AdminImMonitorVO.java`](../../kean/src/main/java/com/kean/vo/AdminImMonitorVO.java)、[`AdminImAlertsVO.java`](../../kean/src/main/java/com/kean/vo/AdminImAlertsVO.java)、[`AdminImCleanResultVO.java`](../../kean/src/main/java/com/kean/vo/AdminImCleanResultVO.java)、[`AdminImCleanRequest.java`](../../kean/src/main/java/com/kean/dto/AdminImCleanRequest.java) |

---

## 10. 已确认的事实 / 未证实的点

**已确认（本轮逐文件核对）**

* `kean/pom.xml` **没有** `spring-boot-starter-actuator`、**没有** `micrometer`：
  因此计数器用 `LongAdder` 自己实现，**本轮未新增任何依赖**；
* 定时任务基础设施已存在：`KeanApplication` 上有 `@EnableScheduling`，
  `SchedulerLockConfig` 提供 JdbcTemplate 锁，`shedlock` 表由 `V26__shedlock.sql` 建立；
* 邮件通道已存在且可复用：`MailProperties`（`kean.mail.enabled` / `from` / `from-name`）；
  `application-dev.yml:44` 与 `application-test.yml:45` 里 `MAIL_ENABLED` 默认 `false`，
  `application-prod.yml:57` 默认 `true`；
* Redis 是 standalone 且未配 `database` → **0 号库**（与 `im-server` 共用）；
* `HealthControllerTest` 直接调用 `HealthController(DataSource, StringRedisTemplate)` 与
  `(DataSource, StringRedisTemplate, long)` 两个构造函数，且断言用 `containsEntry`
  → 追加 `im` 字段不影响它（这也是 IM 状态改用 setter 注入的原因）；
* 管理端**没有 `controller/admin/` 子包**：项目实际约定是 `com.kean.controller.Admin*Controller`
  + `@RequestMapping("/api/admin/...")`（见 `AdminDashboardController` / `AdminSystemController`），
  所以 `AdminImController` 放在 `com.kean.controller` 并沿用同一风格；
* 管理端写操作会记录操作日志（`OperationLogService`，`operationType=IM_QUEUE_CLEAN`，
  `targetType=IM_QUEUE`，`targetId=键名`），该实现自带 try/catch（写日志失败只 warn）；
* `AdminImServiceImpl#cleanResidue` **刻意不加 `@Transactional`**：唯一的数据库写入是操作日志，
  给它套事务反而会在日志插入失败时抛 `UnexpectedRollbackException`（把「写日志失败」升级成 500）。

**未证实（如实列出）**

* 本轮**没有编译、没有跑测试、没有连真实 Redis/邮件**（任务约定：不跑构建，由提出方统一验证）；
  `SCAN` + `Cursor<byte[]>` 的 API 用法按 Spring Data Redis 的稳定签名书写，
  且已在 `scanKeys` 内用 `catch (Exception)` 兜住 `Cursor` 版本间可能不一致的
  `close()` throws 声明（即使 `close()` 声明受检异常也能编译）；
* 残留判据 ②（长度连续 N 轮不变）是**启发式**：若某个队列恰好长时间长度不变但确实有人消费
  （例如消费端在等外部条件），会被误报为残留 —— 误报的代价只是一封 WARN 邮件，判据可在
  `kean.im.residue-stagnant-rounds` 调整；
* 「多实例冷却重复发信」这一取舍只在**单实例**形态下验证过（当前部署形态）；
* 60 秒巡检周期下的实际 Redis 负载未实测（键数 = im-server 实例数 × 2，通常只有个位数）；
* 管理端三接口未跑过集成测试，也未接入前端（`web-kean` 未部署）；
* 「活跃 serverId 集合」= 在线槽位值 ∪ 最新实例，是**当前上游能给出的最可靠口径**，
  但它依赖 `im:user:server_id:*` 的键格式（与 `ImSenderService` / `ImKickService` 一致）。
  若将来 box-im 改变槽位键结构，清理接口的校验会退化成「集合不完整 → 一律拒绝」，
  表现为**拒绝删除而不是误删**（fail-safe 方向）。
