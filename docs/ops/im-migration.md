# IM 迁移说明（两个库的边界与执行方法）

本文档说明「IM 按 box-im 重做」涉及的两份 SQL 各归哪个库、怎么执行、怎么回滚。
本阶段**只交付 SQL 与文档**，未改动任何 Java / Vue / TS 代码，也未连接数据库执行。

---

## 1. 两个库的边界

| | kean 库（现有业务库） | `im_platform` 库（新建） |
|---|---|---|
| 管纳方式 | **Flyway** 版本化迁移，后端启动时自动执行 | **一次性 SQL**，手工执行 |
| 脚本位置 | `kean/src/main/resources/db/migration/`（本次落地时是 `V1`..`V34`，本文写作后已继续加到 **`V35`**） | [`docs/sql/im-platform.sql`](../sql/im-platform.sql) |
| 内容 | 课安业务表 + IM 消息能力增强（V34 只加列加索引） | box-im 自带 9 张 `im_*` 表，**原样照抄** |
| 版本记录 | `flyway_schema_history` | 无（upstream 没有这个概念） |
| 回滚手段 | 回滚应用代码即可（列可空，见第 3 节） | 整库 `DROP DATABASE im_platform` 重建 |

**为什么必须分开**：box-im 是一次性整文件 SQL（全是 `CREATE TABLE`，无 `IF NOT EXISTS`），
kean 是 Flyway 全量纳管（写本文时 `V1..V33` 已占用、V34 为本次新增，**当前已到 V35**）。
两者放同一个 schema 会互踩：
重跑 box 建表会因表已存在直接失败；Flyway 的 `clean` 会按 schema 清库，把 box 的表一并清掉；
反过来 box 的表也不在 Flyway 版本序列里，无法 validate。分开后各自独立演进、独立回滚。

---

## 2. 应用方法

### 2.1 V34：Flyway 自动执行（无需手工操作）

`kean/src/main/resources/db/migration/V34__chat_seq_and_receipt.sql`
在后端重启时由 Flyway 自动执行（与 `V1` 之前的迁移同一机制）。执行后可用下面语句核对：

```sql
SELECT version, description, success FROM flyway_schema_history WHERE version = '34';
```

> 本阶段**未执行**任何数据库操作（任务硬性要求）。V34 的落地发生在下次后端启动时。

### 2.2 `im-platform.sql`：手工一次性执行

该脚本**不含 `IF NOT EXISTS`**，属于一次性脚本，重复执行会报「表已存在」，属预期。
用服务器上的 MySQL 容器执行（脚本自带 `CREATE DATABASE IF NOT EXISTS im_platform` + `USE im_platform`）：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < docs/sql/im-platform.sql
```

执行后核对 9 张表都建出来了：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES FROM im_platform"'
```

预期输出恰好 9 行：`im_user`、`im_friend`、`im_private_message`、`im_group`、
`im_group_member`、`im_group_message`、`im_sensitive_word`、`im_file_info`、`im_message_deletion`。

### 2.3 可选：敏感词初始数据

upstream 的建表 SQL **不含**任何 seed；敏感词初始数据在 box-im 仓库的**另一个文件**
`db/敏感词库初始化.sql`（44990 字节，全是对 `im_sensitive_word` 的单行 `INSERT`：

```
INSERT INTO `im_sensitive_word` (`content`, `enabled`, `creator`) VALUES ('爱液', 1, 10000);
```

取回地址：`https://raw.githubusercontent.com/bluexsx/box-im/master/db/敏感词库初始化.sql`
（blob SHA `f6e46aa17ce4d15100bc4102008dc58857229bfd`）。

按本次任务「产出 `docs/sql/im-platform.sql`」的边界，**未**把这份 seed 合并进建表文件。
需要时先用同样方式手工执行该文件（它依赖 `im_sensitive_word` 表，须先执行 2.2）。

---

## 3. 回滚

### 3.1 V34（kean 库）

V34 **只新增列和索引，未改任何现有列的类型、未删列**，且新增列**全部允许 NULL**：

| 表 | 新增列 | 允许 NULL | 新增索引 |
|---|---|---|---|
| `chat_message` | `seq_no` | 是 | `uk_chat_message_session_seq (session_id, seq_no)` 唯一 |
| `chat_message` | `local_id` | 是 | `uk_chat_message_sender_local (sender_id, local_id)` 唯一 |
| `chat_message` | `status` | 是 | — |
| `chat_message` | `read_at` | 是 | — |
| `chat_session` | `last_seq_no` | 是 | — |
| `chat_session` | `a_read_seq` | 是 | — |
| `chat_session` | `b_read_seq` | 是 | — |

**安全回退方式：只回滚应用代码，不必删列。**
因为列可空，旧代码的 `INSERT` 不写这些列也不会失败，历史行值为 `NULL`，
不参与任何业务逻辑，唯一的副作用只是表上多了几个空列和两个唯一索引。

若确实要清干净（**非必需**，属可选的彻底回退，按相反顺序执行）：

```sql
ALTER TABLE chat_session
    DROP COLUMN b_read_seq,
    DROP COLUMN a_read_seq,
    DROP COLUMN last_seq_no;

ALTER TABLE chat_message
    DROP INDEX uk_chat_message_sender_local,
    DROP INDEX uk_chat_message_session_seq,
    DROP COLUMN read_at,
    DROP COLUMN status,
    DROP COLUMN local_id,
    DROP COLUMN seq_no;
```

> 同时需要从 `flyway_schema_history` 删除 version=34 的记录，否则 Flyway 会因
> checksum/版本缺失报错。这一步**不要手工做**，建议的做法是回退代码后保留列不动。

### 3.2 `im_platform` 库

整库删除即可完全回滚，不影响 kean 库：

```bash
docker exec -i mysql_nzpx-mysql_nzPX-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS im_platform"'
```

需要重新建时再执行第 2.2 节的命令即可（建表脚本幂等于「空库 → 9 张表」）。

---

## 4. 与 box-im 上游的版本对应

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/bluexsx/box-im` |
| 分支 | `master` |
| 文件 | `db/im-platform.sql` |
| 取回地址 | `https://raw.githubusercontent.com/bluexsx/box-im/master/db/im-platform.sql` |
| **文件 blob SHA** | `74e22fd2c8b8a541abc2f9dc3667b159ade6abf4` |
| 最后改动该文件的 commit | `d9e7781c898319b478e5175bc03bddfb20602f5b`（"增加会话置顶功能"，2026-08-13T09:05:31Z） |
| 原文大小 | 8501 字节 |
| 落地位置 | [`docs/sql/im-platform.sql`](../sql/im-platform.sql) |
| 可选的敏感词 seed | `db/敏感词库初始化.sql`，blob SHA `f6e46aa17ce4d15100bc4102008dc58857229bfd`，44990 字节（**未**合并进建表文件） |

kean V34 是**课安自己的**增强迁移，不对应 box-im 某个具体 SQL 文件；它借用的是 box-im
私聊消息的语义约定（`im_private_message` / `im_group_message` 的 `local_id`、`seq_no`、
`status`，以及会话维度已读位点），但落在课安现有的 `chat_message` / `chat_session` 表上。

---

## 5. 环境适配清单（针对 `docs/sql/im-platform.sql`）

1. 文件头新增 `CREATE DATABASE IF NOT EXISTS im_platform` + `USE im_platform`，
   使整文件可一次性执行（upstream 无这两句，默认你已手选库）。
2. 字符集：upstream 写 `charset = utf8mb4` 未指定 collation，本环境显式统一补
   `collate = utf8mb4_general_ci`（与服务器实测默认排序规则一致，不改变既有行为）。
3. 引擎：upstream 已是 `engine = innodb`，保持原样。
4. 其余一律逐字照抄：表名、列名、列顺序、类型与长度、`default`、`comment`、
   索引名与索引列、索引大小写风格（`unique key` / `key` 小写），未做任何
   「优化 / 改名 / 合并 / 补列 / 补索引」。

**上游原文没有**外键（`FOREIGN KEY`）与任何 seed 语句，`im-platform.sql` 里也没有
`IF NOT EXISTS` / `DROP TABLE`。以上均按原样保留。

---

## 6. 阶段 3 启用步骤：把实时推送切到 box-im im-server

> ### 当前状态（逐步更新）
>
> | 阶段 | 内容 | 状态 |
> |---|---|---|
> | ① | 连接层骨架（token 取票 / WS 客户端 / 帧映射） | ✅ 已完成 |
> | ② | 消息模型对齐（`IMRecvInfo` / `PrivateMessageVO` 同构 + 课安补充字段） | ✅ 已完成 |
> | ③ | 推送切 box 队列（`im:message:private\|system:{serverId}`） | ✅ 已完成 |
> | ④ | **im-server 已部署并实测握手 101 通过** | ✅ 已完成（本轮） |
> | ⑤ | **上游自带封禁/踢线，无需补丁** | ✅ 已澄清（本轮，见 §6.5） |
> | ⑥ | 客户端开关 `VITE_IM_ENABLED` | ⏳ **尚未打开**（下一步） |
> | ⑦ | 端到端联调（私聊进气泡 / 队列不堆积 / 封禁踢线 + 阻止重连） | ⏳ **尚未做** |
>
> ④ 的实测记录：官方 master 4.0.0 已在服务器上编译
> （`mvn -pl im-server -am -DskipTests package` → BUILD SUCCESS）、以 systemd `im-server.service`
> 跑起来（`IMServerApp v4.0.0` ✓、`websocket server 初始化完成,端口：8878` ✓）、
> Redis 用 kean 共用的实例（`127.0.0.1:26739`、0 号库 ✓ 已写 `im:max_server_id`）、
> 经 nginx `location /im` 反代到 `172.17.0.1:8878` 的 WS 握手返回
> **`HTTP/1.1 101 Switching Protocols`** ✓；kean 的 `IM_JWT_SECRET` 与 im-server 的
> `jwt.accessToken.secret` 的 **sha256 前 16 位一致（`1aa7a016cacd65ad`）** ✓。
> ⚠️ 这并不等于端到端通了 —— 还没有真实客户端在线，⑦ 仍未做。
>
> ### ⚠️ 当前状态提醒：后端**已经在往 box 队列镜像投递**（空转）
>
> `kean.im.mirror-enabled` **默认 `true`** ✓（`@Value("${kean.im.mirror-enabled:true}")`），
> 而 `IM_JWT_SECRET` **已配置** ✓ ⇒ 生效条件是 `mirrorEnabled && imTokenService.enabled()`，
> 两个都满足 —— 因此**即便客户端开关 `VITE_IM_ENABLED` 还没打开，后端也已在向 box 队列投递**。
>
> * 这是**空转**：无害（投递失败只记 WARN，业务不受影响），但**没有意义**
>   —— 没有客户端挂在 im-server 上，投进去也没人收；
> * 更需要注意的是：**若 im-server 停机，这些队列无人消费会无界增长 ✗**（Redis 内存风险）。
> * 所以本轮已把 **`KEAN_IM_MIRROR_ENABLED=false`** 写入服务器上的 `/opt/kean/.env.prod`，
>   等开客户端开关时**再一起打开**。
>   **【未能证实】** 本仓库内的 `.env.prod` 里并没有 `IM_JWT_SECRET` /
>   `KEAN_IM_MIRROR_ENABLED` 这两个变量，所以这条只能以服务器上那份文件为准，本仓无法交叉验证。
>
> ### 行为边界
>
> * 默认配置下（`IM_JWT_SECRET` 未配置 + `VITE_IM_ENABLED` 未设置）行为与本阶段之前**完全一致**。
> * 双通道并存：课安自研 `ws://<host>/ws/chat`（**永远在，一行没删**）
>   + box-im im-server `ws://<host>/im`。
> * 出问题的回滚手段就是关 flag —— 见 [6.3](#63-回滚)。

### 6.1 环境变量清单

| 变量 | 在哪一侧 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `IM_JWT_SECRET` | **kean 后端** | 启用 IM 时必填 | 空 | box-im 兼容 token 的签名密钥。**必须与 im-server 的 `jwt.accessToken.secret` 逐字节一致，且 ≥32 字节**。不配置 / 不足 32 字节 → 记日志且 IM 判为未就绪（**不崩、不报错**），`GET /api/im/token` 返回 `enabled=false` |
| `IM_JWT_REFRESH_SECRET` | kean 后端 | 否 | 由 `IM_JWT_SECRET` 派生 | refreshToken 密钥。不参与 im-server 握手，仅在 im-platform 上线后换票用 |
| `KEAN_IM_MIRROR_ENABLED` | kean 后端 | 否 | `true` | 是否把消息镜像投递到 box 队列（Spring relaxed binding → `kean.im.mirror-enabled`）。设 `false` = 「只关投递、不动 token」。**总开关仍是 `IM_JWT_SECRET`**。⚠️ **当前建议设 `false`**：默认 `true` + `IM_JWT_SECRET` 已配置 ⇒ 客户端开关还没开时后端也会投递（空转；im-server 停机时队列会无界增长），详见本节开头的「当前状态提醒」 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | kean 后端 | 沿用现状 | 沿用现状 | ⚠️ **必须与 im-server 用同一个 Redis 实例、同一个库**。kean 的 `RedisConfig` 只支持 standalone 且**只读 host/port/password，永远用 0 号库**（不读 `database`）；线上实测端口 `26739` |
| `VITE_IM_ENABLED` | **uni-kean 客户端** | 启用时必填 | 未设置 = 关闭 | 客户端唯一总开关。只有 `true` / `1` / `on` / `yes`（大小写不敏感）才开。**不设置时下面两个地址变量填了也没用** |
| `VITE_IM_WS_URL` | uni-kean 客户端 | 二者至少一个 | 空 | im-server 的 WS 地址，形如 `wss://<你的域名>/im`。**路径必须是 `/im`**（im-server 写死了 `WebSocketServerProtocolHandler("/im")`），写成 `/ws` 会握手 404 |
| `VITE_IM_BASE_URL` | uni-kean 客户端 | 二者至少一个 | 空 | 只写源站也行（如 `https://<你的域名>`），客户端会自动补 `/im` 并把 `http(s)` 归一化成 `ws(s)`。`VITE_IM_WS_URL` 优先 |

> ⚠️ **不要把这些写进 `kean/src/main/resources/application*.yml`，也不要写进 `uni-kean/.env*`**
> —— 本阶段的边界就是「一个新配置项都不加」。前端两个 `VITE_IM_*` 由**构建/部署时的环境变量**注入
> （Vite 在构建时静态替换 `import.meta.env.*`），后端那几个由 `DotEnvLoader` 读仓库根 `.env`
> 或直接由进程环境变量注入。
>
> ⚠️ 另外两个**不在本表里但也有要求**的配置：`im-server` 侧的 `jwt.accessToken.secret`
> 与 `spring.data.redis.*`，以及 nginx 的 `location /im` 与 **ufw 放行 8878**
> —— 全部在 [`docs/ops/im-server-patch.md`](./im-server-patch.md) 里逐行给了。
> ✅ 再次强调：**im-server 不需要任何代码改动**（上游自带封禁校验）。

### 6.2 部署顺序

严格按这个顺序，每一步都有可验证的检查点（详细命令见
[`docs/ops/im-server-patch.md` §3](./im-server-patch.md#3-部署步骤本轮已实测走通一遍-)）。
**第 1–4 步本轮已实测走通一遍 ✅**：

```
1. 生成密钥            openssl rand -base64 48  →  写入 kean 的 IM_JWT_SECRET
                       同时写入 im-server 的 jwt.accessToken.secret（同一个值、>=32 字节）
                       检查点：两边 sha256 前 16 位一致（实测 1aa7a016cacd65ad）
2. Redis 对齐          确认 kean 与 im-server 的 host/port/password 指向同一个实例、0 号库
                       （实测 127.0.0.1:26739）
3. 起 im-server        ✅ 无需任何补丁（只有 §2 密钥 + §3 Redis 两处配置）
                       mvn -pl im-server -am -DskipTests package → systemctl start im-server
                       检查点：日志 IMServerApp v4.0.0 + websocket server 初始化完成,端口：8878
                               redis-cli get im:max_server_id  → 存在且 >= 1
4. nginx 加 /im        （im-server-patch.md §3.3 的片段）→ nginx -t && reload
                       ⚠️ 先 ufw allow from 172.17.0.0/16 to any port 8878 proto tcp（漏了会 504）
                       检查点：curl 握手返回 101 Switching Protocols  ✅ 实测通过
5. 后端自测（不开前端 flag）
                       kean 里发一条私聊消息 →
                       redis-cli llen im:message:private:1 递增
                       im-server 日志出现「接收到私聊消息，发送者:x,接收者:y,内容:...」
                       再确认 llen 不再增长（说明被 leftPop 消费掉了）
6. 打开客户端 flag     VITE_IM_ENABLED=true + VITE_IM_WS_URL=wss://<域名>/im → 重新构建发布
                       ⚠️ 同时把 KEAN_IM_MIRROR_ENABLED 放回 true（见本节开头的状态提醒）
7. 灰度                先放一小批用户（按设备/账号灰度，不要一次全量）
8. 观察                见下面的观察项；无异常再逐步放大
```

**观察项（灰度期间）**

| 现象 | 说明 |
|---|---|
| kean 日志 `[IM 镜像投递] 已投递 N 个 im-server 队列` | 每次发消息 / 每次通知都会有一条；`N=0` 表示「接收方不在 im-server 上」 |
| kean 日志 `[IM 镜像投递] ...投递失败` | 单条 WARN，业务不受影响（投递是尽力而为） |
| im-server 日志 `用户token校验不通过，强制下线` | **两边密钥不一致**（最常见）。kean 侧看不出问题，必须去 im-server 看 |
| im-server 日志 `用户不可用，拒绝连接,userId:…` | **上游自带的封禁校验**生效（`LoginProcessor.java:51` 读到 `im:user:denied:{userId}`）。⚠️ 不是「我们打的补丁」——课安没有补丁 |
| `llen im:message:private:*` 持续增长不下降 | 消费端没跑，或**队列键写错**（少了 `:{serverId}` 后缀 / serverId 不是当前那个） |
| 客户端连上立刻断 | 同上密钥问题；或 nginx `/im` 路径被 rewrite |
| nginx 反代 `/im` 返回 **504** | **ufw 没放行 8878**（kean 的 8080 放行了也没用）。`ufw allow from 172.17.0.0/16 to any port 8878 proto tcp` |

### 6.3 回滚

三步，从快到慢，**任何一步都不需要动数据库 / Flyway / `application*.yml`**：

| 步骤 | 动作 | 效果 |
|---|---|---|
| 1（首选） | 客户端 `VITE_IM_ENABLED=false` 或删除该变量 → 重新发布前端 | 立即回到**只有自研 `/ws/chat`** 的现状。`imSocket.connect()` 里第一行 `if (!isImEnabled()) return;`，一行后续代码都不执行 |
| 2 | 后端 `KEAN_IM_MIRROR_ENABLED=false`（或删掉 `IM_JWT_SECRET`） | `ImSenderService` 全部 no-op，kean 不再往 box 队列写任何东西。`GET /api/im/token` 返回 `enabled=false`。**本轮已把 `KEAN_IM_MIRROR_ENABLED=false` 写入服务器 `/opt/kean/.env.prod`**，即当前就处于这一步 |
| 3 | 停 im-server 进程（`systemctl stop im-server`）；nginx 移除 `location /im` | kean 与客户端都不依赖它 |

> **双通道并存是刻意的设计**：`ChatWebSocketHandler` / `WebSocketConfig` / `ChatSessionHub` /
> `RealtimePublisher` 的既有推送路径**一行未删、一行未改**，所以第 1 步的回滚是**秒级**的，
> 不需要等后端发布。自研链路的退役是再下一阶段的事。

### 6.4 本阶段改了哪些文件（全部是「新增」或「追加」）

| 文件 | 类:方法 | 改什么 |
|---|---|---|
| `kean/src/main/java/com/kean/im/ImSenderService.java` | `ImSenderService`（新） | **新增**。`sendPrivate(recvId, sendId, sessionId, msgType, content, localId, seqNo, createdAt)` 写 `im:message:private:{serverId}`；`sendSystem(recvIds, data)` 写 `im:message:system:{serverId}`。IM 未启用时全部 no-op；每个方法各自 try/catch，只 `log.warn`，绝不外抛。私聊 `data` 是「box `PrivateMessageVO` + kean 客户端认的 `sessionId`/`senderId`/`msgType`/`createdAt`」的并集（见 6.6） |
| `kean/src/main/java/com/kean/service/impl/ChatServiceImpl.java` | `ChatServiceImpl.send()` | 构造注入 `ImSenderService`；在**既有 `chatWebSocketHandler.pushMessage(...)` 之后**追加一行 `imSenderService.sendPrivate(peerId, userId, session.getId(), type, text, idemKey, seqNo, message.getCreatedAt())`。既有推送、落库、幂等、seq 分配**一行未改** |
| `kean/src/main/java/com/kean/chat/RealtimePublisher.java` | `RealtimePublisher.send()` | 构造注入 `ImSenderService`；在**既有 `chatSessionHub.sendTo(...)` 之后**追加一次 `mirrorToIm(...)` → `sendSystem(userId, data)`。`notice()` / `read()` 的 payload 构造一行未改；`READ` 事件**刻意不镜像**（见下） |
| `uni-kean/src/utils/imSocket.ts` | `toRealtimeEvent` / `onMapped` / `normalizePrivateMessage`（新）；`CONNECT_THROTTLE_MS`（10s → 1s） | **追加**把 box 下行帧映射成既有 `RealtimeEvent` 形状：`cmd 3 → MESSAGE`、`cmd 5 → NOTICE`、`cmd 2 →` 直接调用既有 `handleSessionEnded`（清登录态 + 弹窗 + 回登录页）、`cmd 4`（群聊）忽略。连接/登录/心跳/重连逻辑未改（仅把「两次连接之间的节流」从 10s 调到 1s，否则 `onHide` 断开后 `onShow` 要等 10 秒才会重连） |
| `uni-kean/src/composables/useLiveUpdates.ts` | `useLiveUpdates()` / `resetImChannel()`（新） | **追加**「`isImEnabled()` 为真时才连接 IM 通道并订阅映射事件」；既有 `onRealtime(handler)` 一行未改。flag 关时整块跳过 |

**「开关关时零变化」的论证**

> ⚠️ **注意时效**：下面第 2 条的前提是「`IM_JWT_SECRET` 未配置」，
> 而**当前服务器上 `IM_JWT_SECRET` 已经配好了** —— 所以第 2 条现在**不成立**：
> 后端已经在投递（空转），这正是 §6 开头要求把 `KEAN_IM_MIRROR_ENABLED` 设为 `false` 的原因。
> 前端第 1 条（`VITE_IM_ENABLED` 未设置）**仍然成立**，客户端的零变化没有变。

1. 客户端：`VITE_IM_ENABLED` 未设置 → `isImEnabled()` 为 `false`
   → `useLiveUpdates.bindIm()` 第一行 `if (!isImEnabled()) return;`
   → 不注册 `onMapped`、不调 `connectIm()`、不发 `GET /api/im/token`、不开 socket、不设定时器。
   自研通道的 `onRealtime(handler)` 与 `pollMs` 轮询**与改动前逐字节一致**。
2. 后端：`IM_JWT_SECRET` 未配置 → `ImTokenService.enabled()` 为 `false`
   → `ImSenderService.enabled()` 为 `false` → `sendPrivate` / `sendSystem` **第一行就 `return 0`**，
   不读 Redis、不写 Redis、只留一行 DEBUG。因此 `ChatServiceImpl.send()` 与
   `RealtimePublisher.send()` 的**可观测行为**（DB 写入、`/ws/chat` 推送、返回值、异常集合）
   与新增之前完全相同。
3. 后端异常面：`ImSenderService` 的公开方法**签名上不抛受检异常，实现上把所有异常吞成 `log.warn`**。
   即使 Redis 不可用，也不会让调用方的 `@Transactional` 事务回滚 —— 这一点是刻意的设计约束，
   改动 `ImSenderService` 时必须保持。
4. `application*.yml` / `.env*` **零改动**；`SecurityConfig` / `JwtService` / `JwtAuthFilter` /
   `AccountBanServiceImpl` / `NotificationServiceImpl` **零改动**。

**本阶段刻意没做的事**（以及为什么）

| 没做 | 原因 |
|---|---|
| 不镜像 `READ` 事件到 box | box 的对应物是 `MessageType.RECEIPT(12)`，字段与语义都要另外对齐；现在硬镜像只会产生一条没有意义的通知。留到下一阶段 |
| ~~不让 box 通道的私聊消息直接落进 `chat.vue` 的会话~~ **已补齐（2026-02）** | 原因曾成立：im-server 推的 `data` 是 box 的 `PrivateMessageVO`，用 `sendId`/`recvId` 表达双方、**没有 `sessionId`**，而 `chat.vue` 的 `applyIncoming` 要求 `sessionId === 当前会话`。补齐方式**不需要**再做一次「peerId → sessionId」映射：这份 `data` 本来就是我们自己构造的，直接在镜像的 `data` 里补 `sessionId`（外加 kean 客户端映射需要的 `senderId`/`msgType`/`createdAt`）即可 —— im-server 对 `data` **只透传不解析**，多出来的字段对 box 侧无害。详见 6.6 |
| 不删 `ChatWebSocketHandler` / `WebSocketConfig` / `ChatSessionHub` | 它们是兜底通道，退役是再下一阶段的事 |
| 不加数据库迁移、不加 Maven/npm 依赖、不改 `application*.yml` / `.env*` / `web-kean/` | 本阶段边界 |

### 6.5 im-server 侧要做的事：**零代码改动**（只有配置与运维）

> ✅ **结论：上游 box-im master 4.0.0 自带封禁校验，课安不需要给 im-server 打任何补丁。**
> 本节此前写的是「要打封禁 2 行补丁」✗ —— 那是基于较旧的镜像仓库得出的**错误结论**，
> 已按官方 `bluexsx/box-im` master（commit `4ebfb0a`）实测澄清。

**全部细节在 [`docs/ops/im-server-patch.md`](./im-server-patch.md)**
（im-server 是独立仓库，课安不会提交进去）。要点：

1. **封禁 / 踢线：无需适配** ✓
   * 封禁校验是**上游自带**的 —— `LoginProcessor.java:51` 在验签后读
     `im:user:denied:{userId}`，命中就 `ctx.channel().close()`，
     **连接不会注册、也不会写在线槽位键** → 天然「阻止重连」；
   * 强制下线的消费者 `PullForceLogoutTask.java:16` **在 im-server 进程里**
     （不是 im-platform）→ **只要 im-server 在跑，踢线就生效**；
   * 课安侧**唯一要做的就是写对键名**：`im:user:denied:{userId}`
     （`ImKickService.BANNED_KEY_PREFIX`），**不得自造键**。
2. **密钥**：`jwt.accessToken.secret` 必须与 kean 的 `IM_JWT_SECRET` 完全一致且 **≥32 字节**。
   box 示例值 `MIIBIjANBgkq` 只有 12 字节 —— im-server 侧不报错，
   但会让 kean 卡在 `enabled=false`。
   **本轮实测**：两边 `sha256` 前 16 位一致（`1aa7a016cacd65ad`）。
3. **Redis**：必须与 kean 共用同一个实例、**同一个库**（kean 永远是 0 号库，
   所以 im-server 不要配 `spring.data.redis.database`）。
   **本轮实测**：`127.0.0.1:26739` / 0 号库。
4. **nginx + ufw**：加 `location /im` → `172.17.0.1:8878`，
   `Upgrade`/`Connection`/`proxy_read_timeout 3600s` 与既有 `location /ws/` 一致；
   **路径不能 rewrite**。⚠️ 还必须 **`ufw allow from 172.17.0.0/16 to any port 8878 proto tcp`**
   —— 本轮实测：漏了这条，nginx 反代 `/im` 返回 **504**（kean 的 8080 当时已放行）。
   片段与校验命令见 [`im-server-patch.md` §3.3](./im-server-patch.md)；
   **已同步落到** [`nginx.conf.example`](./nginx.conf.example) 的 `api.kean.college` server 块（3.2 节）
   与第 5 节 ufw 规则，两份文件里的 `/im` 写法与 ufw 规则必须保持一致。

### 6.6 2026-02 复核修正（4 项，只修 bug 与补做，未新增功能阶段）

复核 box-im master 上游源码后，对本阶段的 4 处实现做了修正。**开关关闭时的零影响结论不变**，
既有链路（HTTP / `/ws/chat` / 库表 / `application*.yml` / `.env*`）依然零改动。

**① `im:user:state` 语义写反 + 写错键 → 删除该写入**

| 项 | 事实（box-im master） |
|---|---|
| 定义 | im-platform `RedisKey.IM_USER_STATE = "im:user:state"`，注释是「**用户状态 无值:空闲 1:正在忙**」——与「在线/离线」无关 |
| 真实键 | `UserStateUtils` 用 `StrUtil.join(":", RedisKey.IM_USER_STATE, userId)` 拼键，即 **`im:user:state:{userId}`**，值恒为 `1`，**TTL 30 秒** |
| 读取方 | `WebrtcPrivateServiceImpl.call()` 的 `userStateUtils.isBusy(uid)` —— 忙则抛「对方正忙」 |

原实现写的是**不带 userId 后缀的裸键** `im:user:state`，封禁写 `1`、解封写 `0`：裸键**没有任何读写方**
（垃圾键，且解封写的 `0` 无 TTL），而语义上「被封禁」也**不是**「正在通话」。
若真写进正确键，反而会把用户误判为通话中、挡掉他 30 秒内的正常呼叫，而封禁一点也不会生效。

**处理**：`ImKickService` **彻底不再写这枚键**；封禁时改为**删掉**该用户可能残留的忙线键
（`im:user:state:{userId}`，纯粹是清理 box 自己的临时状态，避免「被踢下线后别人打给他仍提示对方正忙」）。
封禁态的唯一权威仍是 **`im:user:denied:{userId}`**（上游 `IMRedisKey.IM_USER_DENIED` 的键名，
`ImKickService.BANNED_KEY_PREFIX`，**行为未动**）与 `sys_user.status`。

> 🔁 **键名更正（本轮）**：这里原先写的是自造键 `kean:im:banned:{userId}` ✗。
> 已核实：**上游 im-server 自带封禁校验**，读的就是 `im:user:denied:{userId}`
> （`LoginProcessor.java:51`），自造键 im-server 完全不认。
> kean 代码里已改为上游键名（`ImKickService.BANNED_KEY_PREFIX = "im:user:denied:"`），
> 本轮文档也已全部同步 —— 详见 [`im-server-patch.md` §0](./im-server-patch.md#0-事实更正曾经的错误结论-vs-实测事实)。

**② `GET /api/im/token` 补封禁前置校验**

`ImController` 注入既有 `TokenRevokeService`，取票前判断 `isBanned(userId)`，命中则抛
`BizException(ErrorCode.ACCOUNT_BANNED, …)` → 由 `GlobalExceptionHandler` 转成
**403 / `Result` 信封（code 40301）**，与 `JwtAuthFilter` 拦住被封用户时**同码同形状**。
封禁口径复用既有的 `kean:user:banned:{userId}`，**没有另造一套**，`AccountBanServiceImpl` **零改动**。
顺序是先判 `enabled()` 再判封禁（IM 未启用时返回 `enabled=false` 空壳，不抛 403）。

> ⚠️ **别把两个封禁键搞混**（本轮特地澄清）：
> * `kean:user:banned:{userId}` —— **课安自己的**封禁口径（`TokenRevokeService.BAN_PREFIX`），
>   由 `JwtAuthFilter` / `ChatAuthService` / `ImController.isBanned()` 读，管的是**课安 HTTP 接口**；
> * `im:user:denied:{userId}` —— **给 im-server 看的镜像键**（上游 `IMRedisKey.IM_USER_DENIED`），
>   由 `LoginProcessor` 读，管的是**能不能连 im-server 的 WS**。
>
> 两者都由 `AccountBanServiceImpl` 在封禁时写入、解封时删除，但**语义与读取方完全不同**，不可互换。

> 注：`/api/im/token` 不在 `JwtAuthFilter` 的匿名放行清单里，所以当前**过滤器已先拦一道**；
> 本项是**纵深防御**（票源不流出），也是对将来任何放行路径变化的兜底。

**③ 私聊镜像 `data` 补 `sessionId`（等字段）→ 气泡能显示了**

`ImSenderService.privateRecvInfoJson` 的 `data` 现在除 box `PrivateMessageVO` 字段外，额外带：

| 字段 | 用途 |
|---|---|
| `sessionId` | **关键**。`chat.vue` 的 `applyIncoming()` 第一句就是 `Number(payload.sessionId \|\| 0) !== 当前会话 → return`，缺它气泡一定不显示 |
| `senderId` | box 用 `sendId`，kean 客户端用 `senderId`，两个都给 |
| `msgType` | kean 的字符串类型（`TEXT`/`IMAGE`，已归一化），与 box 数字型 `type` 并存 |
| `createdAt` | kean 客户端 `Date.parse(createdAt)` 用；与 `sendTime` 同为 **ISO-8601 字符串**（`ChatMessageItem.createdAt` 声明是 string，写 epoch 数字会让 `Date.parse` 得到 `NaN`） |

`createdAt` 由 `ChatServiceImpl.send()` 把**落库后的** `message.getCreatedAt()`
（`AuditMetaObjectHandler` 填充）传进来，与 HTTP 路径返回的 `ChatMessageVO.createdAt` 同源同格式。
im-server 对 `IMRecvInfo.data` **只透传不解析**，所以多出来的字段对 box 侧无害。

**④ 删除死代码**

`ImSenderService` 里无人调用的 `broadcast(...)` 已删除（全仓 grep 确认零调用；
`RealtimePublisher` 上那个**同名但不同类**的 `broadcast` 未动）。
`scanKnownServerIds()` 与其常量 `MAX_BROADCAST_SERVER_ID` 保留 —— 它们服务于
`sendSystem(null, data)` 这条**仍在使用**的广播分支。

### 6.7 封禁键的序列化差异（现在够用，将来要改）

`im:user:denied:{userId}` 这枚键，**上游与我们写进去的「值类型」不一样**：

| | 上游 im-platform | 课安 kean |
|---|---|---|
| 写入方 | `UserBannedConsumerTask:38` | `ImKickService.markBanned` |
| Redis 模板 | `RedisTemplate<String,Object>` | `StringRedisTemplate` |
| **写入的值** | **数字**（`AuthInterceptor:51` 读取时会 `(Integer)` 强转） | **字符串 `"1"`** |

**对当前部署形态完全够用 ✓**：im-platform **未部署**，读这枚键的只有 im-server，
而 `LoginProcessor.java:51` 用的是 `redisMQTemplate.hasKey(...)` —— **只判断键是否存在，不看值**。

> ⚠️ **将来若部署 im-platform，必须改用 `RedisTemplate<String,Object>` 写数字**，
> 否则 im-platform 取票/鉴权会抛**类型转换异常**（`String` 无法强转成 `Integer`）。
> 这段事实同样写在 `ImKickService.markBanned` 的方法注释里，文档与此保持一致。
>
> 📌 还有一条**无关**但容易连带踩的坑：`ImKickService` 注入的是
> `StringRedisTemplate`，所以它写的所有值都是字符串。
> 目前它只写这一枚键（外加 `im:user:force_logout:{serverId}` 的 String JSON），
> 两者都不涉及数字强转，所以当前没有其它风险点。

