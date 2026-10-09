# im-server 运维与对接说明（阶段 3：把实时推送切到 box-im im-server）

> ## ✅ 结论先行：**上游 im-server 不需要任何代码改动，课安不需要给它打补丁**
>
> box-im 官方 `master`（4.0.0，commit `4ebfb0a`，Spring Boot 3.3.1）**自带**封禁校验、
> 强制下线、集群路由、消息队列消费。课安侧唯一要做的是**写对键名、对齐密钥、共用 Redis、放行端口**。
>
> **本文件保留旧文件名 `im-server-patch.md` 是为了不动其它文件的引用**
> （`README.md`、`docs/ops/im-migration.md`、`docs/ops/nginx.conf.example`，
> 以及 `kean/src/main/java/com/kean/im/ImKickService.java` 的类注释里也提到了这个路径）。
> 文件名里的 "patch" 是历史包袱 —— **本文档不再描述任何补丁**，只描述「运维 + 对接契约」。
>
> `im-server` 属于独立仓库（box-im），课安**不会**把它提交进本仓库。
> 本文所有结论都对照 box-im `master` 源码与**一次真实部署实测**核对过，逐条标注了来源。
> 标注 **【未能证实】** 的地方是当前无法从源码或本仓确认的。

---

## 0. 事实更正：曾经的错误结论 vs 实测事实

**这是本文档最有价值的部分 —— 请先读完这一节再动手，可以少走我们走过的全部弯路。**

早期调研基于一份**较旧的 box-im 镜像仓库**（`quanxinshijie/box-im`），据此写下的几条结论
**全部是错的**。现以官方 `bluexsx/box-im` **master 4.0.0**（commit `4ebfb0a`）源码为准更正：

| # | 此前的错误说法 ✗ | 实测事实 ✓（源码文件:行） |
|---|---|---|
| 1 | 「im-server 没有封禁校验，必须自己给它打补丁」 | ❌ **上游自带**：`im-server/src/main/java/com/bx/imserver/netty/processor/LoginProcessor.java:51` 在验签之后就有 `if (Boolean.TRUE.equals(redisMQTemplate.hasKey(StrUtil.join(":", IMRedisKey.IM_USER_DENIED, userId)))) { ctx.channel().close(); return; }` → **kean 不需要给 im-server 打任何补丁** |
| 2 | 「`im:user:denied` 这个键不存在」 | ❌ **存在**：`im-common/src/main/java/com/bx/imcommon/contant/IMRedisKey.java:62` `IM_USER_DENIED = "im:user:denied"`；上游 `im-platform` 的 `UserBannedConsumerTask:38` 封禁时写它、`UserServiceImpl:70,105` 解封/登录时删它、`AuthInterceptor:51` 读它 |
| 3 | 「`PullForceLogoutTask` 的消费者在 im-platform」 | ❌ **在 im-server**：`im-server/src/main/java/com/bx/imserver/task/PullForceLogoutTask.java:16`（`@RedisMQListener(queue = IMRedisKey.IM_USER_FORCE_LOGOUT_QUEUE)`）→ **只要 im-server 在跑，踢线就生效，不需要 im-platform** |
| 4 | 「box-im 的 `application-dev.yml` 里 port 与 password 挤在同一行」 | ❌ master 上不成立（dev 只有 host+port；prod/test 是规范三行） |

> ⚠️ **本地代码里还留着过期的注释**：`kean/src/main/java/com/kean/im/ImKickService.java`
> 的类级 Javadoc（约 47-56 行）仍写着「im-server 侧并不存在任何拒绝封禁用户的逻辑」、
> 「`IMRedisKey` 里没有 `im:user:denied`」——**这两句与上面的事实相反，是尚未清理的过期文字**。
> 该类的**常量与实现已经是正确的**（`BANNED_KEY_PREFIX = "im:user:denied:"`，见下），
> 只有注释没跟上。按本轮「只改文档、不改任何代码」的约束**未修改该文件**，
> 请以本文档与代码常量/实现为准，不要相信那段旧注释。

### 0.1 两条已核实的正向事实（与旧文档保持一致，仍然成立）

1. **消息队列键必须带 serverId**：`im:message:private:{serverId}` / `im:message:system:{serverId}`
   —— **只写基键的消息永远无人消费且不报错**（详见 §2.4）。
2. **队列消息体是 `IMRecvInfo`**（`cmd`/`sender`/`receivers`/`sendResult`/`serviceName`/`data`），
   消费端用 FastJson `toJavaObject` 反序列化，**不需要 `@type`**；
   `data` 里放 box 的 `PrivateMessageVO` 同构对象 + 课安额外补的
   `sessionId`/`senderId`/`msgType`/`createdAt`（详见 §2.8）。

---

## 1. 【上游 box-im 已提供，课安无需适配】

这一节里的能力**全部由上游实现**，课安侧不要重复造、也不要写补丁；
列在这里是为了让运维知道「哪些行为是箱子自带的、出了问题该去哪里看」。

### 1.1 封禁校验 —— 上游自带 ✅ `im:user:denied:{userId}`

* 位置：`LoginProcessor.java:51`（`im-server` 模块）。验签通过、解析出 `userId` 之后立即判断：
  该键**存在**就 `ctx.channel().close()` 并 return，**连接根本不会注册进
  `UserChannelCtxMap`，也不会写 `im:user:server_id:{userId}:{terminal}`**。
* 键名来源：`IMRedisKey.IM_USER_DENIED = "im:user:denied"`（`IMRedisKey.java:62`）。
* 上游写入方：
  * 封禁：`im-platform` `UserBannedConsumerTask:38` 写这枚键；
  * 解封：`UserServiceImpl:70` 删它；
  * 登录/鉴权：`UserServiceImpl:105`、`AuthInterceptor:51` 读它。
* **课安侧要做的只有一件事：写对这个键名**（见 §2.1）。课安是**用自己这一套**
  封禁事实（`sys_user.status` + 这枚 Redis 镜像键）驱动它，不依赖 im-platform。

### 1.2 强制下线 / 踢线 —— 消费者在 im-server ✅

* **投递键**：`im:user:force_logout:{serverId}`（Redis List，`rightPush`）。
* **消费者**：`im-server` 的 `PullForceLogoutTask.java:16`
  （`@RedisMQListener(queue = IMRedisKey.IM_USER_FORCE_LOGOUT_QUEUE)`）
  → **只要 im-server 进程在跑，踢线就生效，不需要 im-platform**。
* **消息体**：`IMForceLogoutInfo` 的 JSON —— `userId`(Long) / `terminal`(Integer) / `devId`(String)。
  `devId` 传**空串**即走「强制下线」分支（`ForceLogoutProcessor` 判断
  `StrUtil.isEmpty(info.getDevId()) || !info.getDevId().equals(devId)`）。
* `serverId` 从 `im:user:server_id:{userId}:{terminal}` 读出（见 1.5）；该键不存在 = 该终端离线。
* **课安侧实现**：`com.kean.im.ImKickService.forceLogout(userId, reason)`（三个终端全查、逐个投递）。

### 1.3 WS 路径写死 `/im`

`im-server` 的 `WebSocketServer` 里是 `new WebSocketServerProtocolHandler("/im")`
—— **路径写死**。所以 nginx 反代 `/im` 时**必须原样透传，绝对不能 rewrite**，
改成 `/ws` 或 `/` 会直接握手 404（详见 §3.3 / §4）。

### 1.4 60s 读空闲 + 客户端心跳

* im-server 侧 `IdleStateHandler(60, 0, 0)`：**读空闲 60s 即断开**。
* 客户端 **20s 一次心跳**（`uni-kean/src/utils/imSocket.ts`）。
* 因此 nginx 的 `proxy_read_timeout` 必须**远大于 60s**（本仓示例用 `3600s`），
  否则表现为「nginx 先断、客户端反复重连」。

### 1.5 同终端挤下线（按 `devId`）+ 集群化路由

* 在线槽位键：`im:user:server_id:{userId}:{terminal}`（值 = im-server 的 serverId）。
  用 `{userId}` 花括号是 Redis Cluster 的 hash tag 写法，**逐字节照抄，别「顺手统一」前缀**。
* 同一 `(userId, terminal)` 再来一条连接时，`LoginProcessor` 会按 `devId` 判断
  「是不是同一个设备」：同设备 = 挤掉旧连接；不同设备 = 给旧 server 投一条
  `im:user:force_logout:{serverId}`。
* `serverId` 由 im-server 每次启动时 `im:max_server_id` **自增**得到
  （`IMServerGroup`）：单实例首次为 `1`，重启变 `2`、`3`…**计数器只增不减**
  （见 §6「已知遗留」里的 serverId 漂移）。

### 1.6 消息队列消费

im-server 进程内有 4 个拉取任务（私聊/系统 × base/suffix 两种键），
用 `leftPop` 批量拉取 + FastJson `toJavaObject` 反序列化成 `IMRecvInfo`，
再遍历 `receivers` 逐个 `UserChannelCtxMap.getChannelCtx(id, terminal)` 推送。
**课安侧不需要消费者、不需要 im-platform**（详见 §2.4、§2.7）。

---

## 2. 【课安自身需要适配】

这一节是**真正会出错的地方** —— 每一条都对应一次线上故障或一次「语法全对、逻辑全不生效」。

### 2.1 必须写对键名：`im:user:denied:{userId}`，不得自造 ✗

| | 键 | 说明 |
|---|---|---|
| ✅ 当前（正确） | `im:user:denied:{userId}` | 上游 `IMRedisKey.IM_USER_DENIED`，`LoginProcessor:51` 会读它 → **能阻止重连** |
| ❌ 历史（错误） | `kean:im:banned:{userId}` | 课安早期自造的键，**im-server 完全不认**，写了等于没写 |

* 课安实现：`kean/src/main/java/com/kean/im/ImKickService.java`
  的 `BANNED_KEY_PREFIX = "im:user:denied:"`（已核对该文件确认）。
  值写字符串 `"1"`、**无 TTL**（语义是「永久直到解封」），解封时 `allow(userId)` 删键。
* 曾经出现在文档里的 `kean:im:banned:{userId}` **已全部改为上游键名**
  （`docs/` 下已无残留，见 §7）。

### 2.2 密钥两边逐字节一致，且 ≥32 字节

* kean 侧：环境变量 `IM_JWT_SECRET`；im-server 侧：`jwt.accessToken.secret`。
* **box 的示例值 `MIIBIjANBgkq` 只有 12 字节 ✗**：
  * im-server 侧**不报错**（java-jwt 的 `HMAC256` 不校验长度）；
  * kean 侧会因此把 IM 判为**未就绪**：`ImTokenService.validateAccessSecret`
    在构造时先自己量长度，不足 32 字节就记 ERROR 并把 `enabled()` 置为 `false`
    （**刻意不抛异常**，否则 `Keys.hmacShaKeyFor` 的 `WeakKeyException`
    会把整个 kean 启动带崩）→ `GET /api/im/token` 返回 `enabled=false`，客户端根本不会去连。
* **两边不一致的表现**：客户端连上立刻被 im-server `ctx.channel().close()`，
  im-server 日志是 `用户token校验不通过，强制下线`（kean 侧看不出问题，取票接口照样 200）。
* 生成：`openssl rand -base64 48`。kean 侧走**环境变量**，
  不要写进 `kean/src/main/resources/application*.yml`。
* **怎么确认两边是同一个值（不泄露密钥）**：kean 的 `ImTokenService.fingerprint()`
  在**密钥过短**时会打印 `sha256(secret)` 的十六进制前 8 位（**不打印密钥本体**）；
  也可以在两边各跑一次 `printf '%s' "$SECRET" | sha256sum | cut -c1-6` 比对。
  * ✅ **本轮真实部署实测**：kean 的 `IM_JWT_SECRET` 与 im-server 的
    `jwt.accessToken.secret` 的 **sha256 前 16 位一致：`1aa7a016cacd65ad`**。
  * **【未能证实】** im-server 侧是否有等价的密钥指纹日志（上游没有，需要自己加一行）。

### 2.3 Redis 必须共用，且只能用 **0 号库**

`im:user:server_id:{userId}:{terminal}`、`im:user:force_logout:{serverId}`、
`im:message:private:{serverId}`、`im:message:system:{serverId}`、`im:user:denied:{userId}`
—— 这一整组键的语义**只在同一个 Redis 库内成立**。
分成两个实例（或同实例不同 `database`）的表现是「语法全对、逻辑全不生效」。

* **kean 侧（已核实代码）**：`kean/src/main/java/com/kean/config/RedisConfig.java`
  只构造 `RedisStandaloneConfiguration(host, port)`（+ 可选的 `setPassword`），
  只读 `spring.data.redis.host` / `port` / `password`
  —— **不读 `database`，因此 kean 永远用 Redis 的 0 号库**（也不支持 cluster/sentinel）。
* **所以 im-server 侧不要配 `spring.data.redis.database`**（或必须配成 `0`）。
* im-server 的 Redis 写法（host + port + password，**规范三行**）：

  ```yaml
  spring:
    data:
      redis:
        host: 127.0.0.1        # 本服务器上填 kean 用的那个实例
        port: 26739            # 与 kean 的 REDIS_PORT 相同
        password: <与 kean 的 REDIS_PASSWORD 相同>
  ```

* ✅ **本轮真实部署实测**：im-server 用的是 **kean 共用的那个 Redis 实例
  `127.0.0.1:26739`、0 号库**，启动后已正常写入 `im:max_server_id`。
* ⚠️ **共库的副作用**：im-server / im-platform 会往同一个库写 `im:*` 键。
  **【未能证实】** 生产环境是否有其它进程对这个库执行 `FLUSHDB` / `KEYS *` ——
  `FLUSHDB` 会连带清掉 kean 自身的缓存键（登录黑名单、限流、序列表），上线前需确认。

### 2.4 队列键必须带 `:{serverId}`

| | 基键（常量，**不是**真实队列键） | 真实队列键 |
|---|---|---|
| 私聊 | `im:message:private` | `im:message:private:{serverId}` |
| 系统 | `im:message:system` | `im:message:system:{serverId}` |

* 投递侧（上游 im-client `IMSender.pushPrivateMessage`）拼的是
  `String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString())`；
  消费侧 `AbstractPullMessageTask.generateKey()` 拼的是
  `String.join(":", super.generateKey(), IMServerGroup.serverId + "")`。
* > ⚠️ **只写基键的消息永远不会被消费**（im-server 的拉取任务只拉带后缀的键），
  > 会静默堆在 Redis 里，表现为「kean 说投递成功、客户端永远收不到」。
* **kean 怎么知道投给哪个 node**：读**接收方**的在线槽位键
  `im:user:server_id:{userId}:{terminal}`（值就是 serverId，键不存在 = 该终端离线）。
  `com.kean.im.ImSenderService` 正是这么实现的：三个终端都查、按 serverId 去重后各投一条。

### 2.5 ufw 必须放行 `172.17.0.0/16 → 8878` ✗（本轮踩到的坑）

线上 nginx 跑在**容器**里、im-server 跑在**宿主机**上，容器访问宿主机要走 docker0 网桥
`172.17.0.1`。如果 ufw 默认 deny incoming，这条会被挡掉。

* **本轮真实踩坑**：kean 的 **8080 当时已放行、8878 是漏的** →
  nginx 反代 `/im` 返回 **504**（不是 502，很容易误判成 im-server 没起）。
* **修复（已在服务器上执行）**：

  ```bash
  ufw allow from 172.17.0.0/16 to any port 8080 proto tcp comment 'nginx container -> kean backend'
  ufw allow from 172.17.0.0/16 to any port 8878 proto tcp comment 'nginx container -> im-server'
  ```

  该规则已同步落到 [`docs/ops/nginx.conf.example`](./nginx.conf.example) 第 5 节。
* ⚠️ **8878 不要对公网开放**：它没有 TLS、也没有 kean 的 `JwtAuthFilter`，
  只有 im-server 自己的 `checkSign`。对外一律走 nginx（§3.3）。
  `8879`（TCP socket，im-server 的 `tcpsocket.enable: false`）同样不要暴露。

### 2.6 `kean.im.mirror-enabled` —— 镜像投递总闸

* 配置：`kean.im.mirror-enabled`（环境变量 `KEAN_IM_MIRROR_ENABLED`，Spring relaxed binding），
  **默认 `true`**（已核对 `ImSenderService` 的 `@Value("${kean.im.mirror-enabled:true}")`）。
* 生效条件是**两个一起**：`mirrorEnabled && imTokenService.enabled()`
  —— 也就是说 **`IM_JWT_SECRET` 才是总开关**，`mirror-enabled` 只关「投递」这一半。
* **当前状态提醒（重要）**：`kean.im.mirror-enabled` 默认 `true`，而 `IM_JWT_SECRET` **已配置** ⇒
  **即便客户端开关 `VITE_IM_ENABLED` 还没打开，后端也已经在往 box 队列镜像投递**。
  * 现状是「**空转**」：无害，但没有意义（没有客户端在 im-server 上，投进去也没人收）；
  * 若 im-server 停机，队列**无人消费会无界增长** ✗（Redis 内存风险）。
  * 因此本次已把 `KEAN_IM_MIRROR_ENABLED=false` 写入服务器上的 `/opt/kean/.env.prod`，
    等开客户端开关时**再一起打开**。
    **【未能证实】** 本仓库内的 `.env.prod` 里没有这两个变量（IM_JWT_SECRET 与
    KEAN_IM_MIRROR_ENABLED 都不在），所以这条只能以服务器上的那份为准，本仓无法交叉验证。

### 2.7 键值的序列化差异（现在够用，将来要改）

| | 上游 im-platform | 课安 kean |
|---|---|---|
| 谁写 `im:user:denied:{userId}` | `UserBannedConsumerTask` | `ImKickService.markBanned` |
| 模板 | `RedisTemplate<String,Object>` | `StringRedisTemplate` |
| **值** | **数字**（`AuthInterceptor` 会 `(Integer)` 强转读取） | **字符串 `"1"`** |

* 对**当前部署形态**（im-platform 未部署、im-server 只用 `hasKey` 判断存在性）**完全够用** ✓
  —— im-server 根本不看值。
* ⚠️ **将来若部署 im-platform，必须改用 `RedisTemplate<String,Object>` 写数字**，
  否则 im-platform 取票/鉴权时会抛**类型转换异常**。
  （这段事实同样写在 `ImKickService.markBanned` 的方法注释里，文档与此保持一致。）
* 因此本节的结论是：**序列化差异是「将来要注意」的项，不是当前故障源**；
  当前形态下 im-server 只看键是否存在，`"1"` 与 `1` 对它没有区别。

### 2.8 消息体契约：`IMRecvInfo`，不需要 `@type`（已核实，保持）

消费链路：`RedisMQPullTask.pullBatch()` 用 `redisTemplate.opsForList().leftPop(key, batchSize)`
拉取，再 `jsonObject.toJavaObject(type)` 反序列化成 `IMRecvInfo`
（`type` 由 `AbstractPullMessageTask<T>` 的泛型推出，私聊/系统都是 `IMRecvInfo`）。
`toJavaObject(Class)` **不读 `@type`**，所以 kean 用 Jackson 写同构 JSON 即可被读取
（这一点已在 `FORCE_LOGOUT` 通道上核实并复用）。

`IMRecvInfo` 字段与 kean 的填法：

| 字段 | 类型 | kean 投递时填什么 |
|---|---|---|
| `cmd` | Integer | 私聊 `3`（`IMCmdType.PRIVATE_MESSAGE`）/ 系统 `5`（`SYSTEM_MESSAGE`） |
| `sender` | `IMUserInfo` | 私聊填发送方 `{id, terminal}`；系统消息填 `null` |
| `receivers` | `List<IMUserInfo>` | 见下 |
| `sendResult` | Boolean | **`false`** —— 设 `true` 时 im-server 会把回执写进 `im:result:private:{serviceName}`，而那个队列的消费者在 im-platform 里；kean 没有消费者，设 `true` 只会让 Redis 无界增长 |
| `serviceName` | String | `"kean"`（仅用于拼结果队列键，`sendResult=false` 时无副作用） |
| `data` | Object | 见下 |

`IMUserInfo` = `{id: Long, terminal: Integer}`；box `IMTerminalType` WEB=0 / APP=1 / PC=2。

`receivers` 的作用是**指定「投给谁」**，im-server 会遍历它逐个找连接推送：

* **私聊**：`receivers` 就是接收方。`PrivateMessageProcessor` 只推送 `recvInfo.getData()`，
  **完全不读 `data` 里的 `recvId`**，所以「投给谁」完全由 `receivers` 决定。
  kean 侧把接收方的三个终端都列上（`[{id,0},{id,1},{id,2}]`），由 im-server 自己判断谁在线。
* **系统消息**：`SystemMessageProcessor` 同样遍历 `receivers`。
  `IMSystemMessage.recvIds` 的注释是「为空表示向所有在线用户广播」，但**广播的落实是靠
  im-platform 先把 recvIds 展开成在线的 receivers**。⚠️ **`receivers` 为空列表时
  im-server 不会给任何人推送**（循环体一次都不进），所以「广播」不是一个能直接投递的动作。
  kean 侧因此**只做定向系统消息**，广播入口仅预留、当前业务未使用。

**kean 实际写出的私聊 JSON**（`data` = box `PrivateMessageVO` 同构 + 课安补的 4 个字段）：

```json
{
  "cmd": 3,
  "sender": { "id": 0, "terminal": 1 },
  "receivers": [
    { "id": 0, "terminal": 0 },
    { "id": 0, "terminal": 1 },
    { "id": 0, "terminal": 2 }
  ],
  "serviceName": "kean",
  "sendResult": false,
  "data": {
    "localId": "<kean chat_message.local_id>",
    "seqNo": 0,
    "sendId": 0,
    "recvId": 0,
    "content": "<文本，或图片的 objectKey>",
    "type": 0,
    "status": 1,
    "sendTime": "2026-01-01T00:00:00",
    "deleted": false,
    "sessionId": 0,
    "senderId": 0,
    "msgType": "TEXT",
    "createdAt": "2026-01-01T00:00:00"
  }
}
```

* `type`：box `MessageType` 数字码，`TEXT=0` / `IMAGE=1`（由 `msgType` 归一化而来）。
* `status`：box `MessageStatus`，kean 新消息固定 `1`（已发送）。
* 课安**额外补的 4 个字段**（box 侧没有，im-server 对 `data` **只透传不解析**，所以无害）：
  * `sessionId` —— **关键**。客户端 `chat.vue` 的 `applyIncoming()` 第一句就是
    `Number(payload.sessionId || 0) !== 当前会话 → return`，缺它气泡一定不显示；
  * `senderId` —— box 用 `sendId`、kean 客户端用 `senderId`，两个都给；
  * `msgType` —— kean 的字符串类型（`TEXT`/`IMAGE`），与 box 数字型 `type` 并存；
  * `createdAt` —— 客户端 `Date.parse(createdAt)` 用，**ISO-8601 字符串**格式
    （写 epoch 数字会得到 `NaN`）。
* kean **刻意不写 `id`**：kean 的消息主键与 `im_platform.im_private_message` 不是同一套编号，
  硬塞会让客户端拿 kean 的 id 去调 box 的历史/已读接口。
* ⚠️ **不要投 `data.type = "READ"`**：box 的对应物是 `MessageType.RECEIPT(12)`，
  语义/字段都不同。课安侧已在 `RealtimePublisher.mirrorToIm` 里显式跳过 `READ`。

**系统消息**的 `data` 就是课安自研 WS 上 `RealtimeEvent` 的**同一份 payload**
（`RealtimePublisher.notice` 产出的 `{type, noticeType, bizType, bizId}`），
这样客户端在 `cmd 5` 上收到的结构与既有通道完全同形，不需要第二套解析。

---

## 3. 部署步骤（**本轮已实测走通一遍** ✅）

> 本节是「已验证的步骤」：官方 master 4.0.0 已在本服务器上按下列顺序部署成功，
> `mvn -pl im-server -am -DskipTests package` → BUILD SUCCESS；
> systemd `im-server.service` 跑起来；WS 握手经 nginx 返回 `HTTP/1.1 101 Switching Protocols`。
> 前置条件：`docs/ops/im-migration.md` §6.1 的环境变量表。

```
1) 生成 >=32 字节密钥，写入 kean 的 IM_JWT_SECRET；im-server 的 jwt.accessToken.secret 填同一个值
     └─ 校验：两边 sha256 前 16 位一致（本轮实测 1aa7a016cacd65ad）
2) 确认 Redis 是 kean 那个实例、0 号库；im-server 只配 host/port/password，不配 database
     └─ 本轮实测：127.0.0.1:26739 / db0
3) 编译并启动 im-server（**不需要任何补丁**）
     mvn -pl im-server -am -DskipTests package
     systemctl start im-server            # 生产建议用 systemd 托管，别用裸 nohup
     └─ 看启动日志三件事：
          IMServerApp v4.0.0                                    ✅ 版本对
          websocket server 初始化完成,端口：8878                  ✅ 端口起来了
          redis-cli get im:max_server_id  => 1（或更大的整数）    ✅ Redis 通了、serverId 分配了
4) nginx 加 /im 上游（§3.3 的片段）→ nginx -t && reload
     └─ 先放行 ufw：ufw allow from 172.17.0.0/16 to any port 8878 proto tcp   ← 漏了这步会得到 504
5) 握手校验（必须 101，见 §3.3 的命令）
6) 先不开客户端 flag，用后端自测投递：
     kean 里发一条私聊消息 → redis-cli llen im:message:private:1 递增
                              redis-cli lrange im:message:private:1 0 0 → 能看到 IMRecvInfo JSON（含 cmd:3）
                              im-server 日志出现「接收到私聊消息，发送者:..,接收者:..,内容:..」
7) 打开客户端 VITE_IM_ENABLED=true + VITE_IM_WS_URL=wss://api.kean.college/im，灰度一批用户
     └─ ⚠️ 别忘了同时把 KEAN_IM_MIRROR_ENABLED 放回 true（见 §2.6）
8) 回归：关掉 flag，确认回到自研 /ws/chat（见 §5 回滚）
```

### 3.1 im-server 侧要改的两个文件（**只有配置，没有代码**）

| 文件 | 改什么 |
|---|---|
| `im-server/src/main/resources/application.yml` | `jwt.accessToken.secret` → 与 kean 的 `IM_JWT_SECRET` **逐字节一致且 ≥32 字节** |
| `im-server/src/main/resources/application-dev.yml`（或 prod/test） | `spring.data.redis.host/port/password` → kean 那个实例；**不要**配 `database` |

> ❌ **不要动 `LoginProcessor.java`**。上游 `:51` 已经有封禁校验了；
> 早期文档教人在那里插 2 行补丁，**是错的**，照做只会制造一个与上游重复且容易冲突的改动。

### 3.2 启动与托管（推荐 systemd）

```ini
# /etc/systemd/system/im-server.service（示例，按实际路径调整）
[Unit]
Description=box-im im-server (kean)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/im-server
EnvironmentFile=/opt/kean/.env.prod        # 复用 kean 的环境文件，保证 Redis/密钥一致
ExecStart=/usr/bin/java -jar /opt/im-server/im-server.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now im-server
journalctl -u im-server -n 100 --no-pager
```

> 📌 上例里的路径（`/opt/im-server`、`/opt/kean/.env.prod`）是**建议形态**，
> 不是从本仓核对出来的值 —— 本仓无法读取服务器文件系统，**请按实际部署路径填写**。
> 【未能证实】im-server 的密钥与 Redis 到底是从 `application*.yml` 读的还是从环境变量注入的
> （两种都能生效，因为 Spring 的环境变量优先级高于 yml）；关键是**值必须与 kean 一致**。
> ⚠️ 若按上例用 `EnvironmentFile /opt/kean/.env.prod`：该文件里的变量名是给 kean 用的
> （`REDIS_HOST` / `REDIS_PORT` / `IM_JWT_SECRET` …），**im-server 的 Spring 属性名不同**
> （`spring.data.redis.host` / `jwt.accessToken.secret`），不会自动对上 ——
> 要么在 im-server 自己的 yml 里写死值，要么在启动脚本里显式做变量名映射。

> ⚠️ `Restart=always` 有个副作用要知道：**每次重启都会把 `im:max_server_id` 加一**，
> 重启前投进 `im:message:*:{旧serverId}` 的消息**永远不会被消费**（见 §6）。

### 3.3 nginx：为 `/im` 增加 WebSocket 上游

前端连的是 `wss://api.kean.college/im`（`VITE_IM_WS_URL`）。im-server 的 WS 端口是 **8878**，
路径**写死为 `/im`**，所以反代必须**原样透传路径**，不能 rewrite。

> 📌 **现状（已同步）**：这段 `location /im` 已经落在
> [`docs/ops/nginx.conf.example`](./nginx.conf.example) 的 **`api.kean.college` server 块（3.2 节）**里，
> 上游同样是 `http://172.17.0.1:8878`、同样 `proxy_read_timeout 3600s`。
> 两边**必须保持一致**：以后改 `/im` 的写法，改完这份文档就顺手同步 `nginx.conf.example`。
> ⚠️ 线上真正生效的文件是服务器上的 `/root/nginx/conf/nginx.conf`，改的应该是那一份。

```nginx
    # ------------------------------------------------------------------
    # box-im im-server 的 WebSocket（阶段 3）
    # 前端连的是 wss://api.kean.college/im（线上；换成你自己的域名即可）
    # ⚠️ im-server 的 WS 路径写死为 /im（WebSocketServerProtocolHandler("/im")），
    #    必须原样透传，不能 rewrite 成 /ws 或 / —— 否则握手 404。
    # ⚠️ 上游地址：线上 nginx 是容器、im-server 跑在宿主机，所以用 docker0 网桥地址
    #    172.17.0.1（`ip addr show docker0` 可确认）；若 nginx 与 im-server 同机裸跑则用 127.0.0.1。
    # ⚠️ 还要 ufw 放行 172.17.0.0/16 → 8878，漏了会得到 504（见 §2.5）。
    # ------------------------------------------------------------------
    location /im {
        proxy_pass http://172.17.0.1:8878;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host              $host;
        # 线上走 Cloudflare 回源，真实客户端 IP 在 CF-Connecting-IP 里；
        # 若不经 Cloudflare，改回 $remote_addr / $proxy_add_x_forwarded_for。
        proxy_set_header X-Real-IP         $http_cf_connecting_ip;
        proxy_set_header X-Forwarded-For   $http_cf_connecting_ip;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 长连接：im-server 侧 IdleStateHandler 是 60s 读空闲，
        # 客户端 20s 一次心跳；nginx 默认 60s 读超时会与心跳赛跑，
        # 这里放宽到 3600s，与既有 /ws/ 保持一致。
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
```

配套校验：

```bash
# 1) 语法（容器里跑 nginx 时：docker exec <nginx容器名> nginx -t）
nginx -t
# 2) 握手（期望 101 Switching Protocols；404 = 路径被改了，502 = 上游不通，504 = ufw 没放行）
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     https://api.kean.college/im
# 3) 直连上游（排除 nginx 因素；同样期望 101）
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     http://172.17.0.1:8878/im
```

> ✅ **本轮实测**：第 2 条命令经 nginx 返回 **`HTTP/1.1 101 Switching Protocols`**。
> ⚠️ **8878 不要直接暴露公网**（无 TLS、无 kean 鉴权）；`8879` 同样不要暴露。

---

## 4. 验收清单

| # | 检查 | 期望 |
|---|---|---|
| 1 | im-server 启动日志 | `IMServerApp v4.0.0` + `websocket server 初始化完成,端口：8878` ✅ 本轮已过 |
| 2 | `redis-cli get im:max_server_id` | 存在且 ≥1（不存在说明 im-server 没起来或没连上这个 Redis） |
| 3 | kean 与 im-server 密钥指纹 | `sha256` 前 16 位一致（本轮实测 `1aa7a016cacd65ad`） |
| 4 | `curl` 握手经 nginx | **`101 Switching Protocols`** ✅ 本轮已过（504 = ufw 没放行 8878） |
| 5 | kean 日志 | `[IM 镜像投递] 已投递 N 个 im-server 队列（im:message:private:*）...` |
| 6 | im-server 日志 | `接收到私聊消息，发送者:x,接收者:y,内容:...` |
| 7 | `llen im:message:private:*` | **不再增长**（说明被 `leftPop` 消费掉了）。持续增长 = 消费端没在跑，或**队列键写错**（少了 `:{serverId}` / serverId 不是当前那个） |
| 8 | 客户端 | `cmd 3` 到包且能进气泡（依赖 `data.sessionId`）；未读角标刷新；`cmd 2` 弹「已退出登录」并回登录页 |
| 9 | 封禁 | 封禁用户后：在线连接被踢（`im:user:force_logout:{serverId}`），且**重连失败** —— im-server 日志出现 `用户不可用，拒绝连接,userId:…`（**上游自带**，不是我们打的补丁） |
| 10 | 解封 | `im:user:denied:{userId}` 被删，用户重新取票后可再连 |
| 11 | 关 flag | 客户端不再连 8878；`/ws/chat` 一切照旧 |
| 12 | ufw | `ufw status` 里能看到 `8080` **与** `8878` 两条 `172.17.0.0/16` 规则 |

> ⚠️ 生产别用 `keys im:message:*`，用 `scan`。

---

## 5. 回滚

三步，从快到慢，**任何一步都不需要动数据库 / Flyway / `application*.yml`**：

| 步骤 | 动作 | 效果 |
|---|---|---|
| 1（首选） | 客户端 `VITE_IM_ENABLED=false`（或删掉该变量）→ 重新发布前端 | 立即回到**只有自研 `/ws/chat`** 的现状（秒级，不用等后端发布） |
| 2 | 后端 `KEAN_IM_MIRROR_ENABLED=false` | `ImSenderService` 全部 no-op，kean 不再往 box 队列写任何东西（**本次已处于这个状态**，见 §2.6） |
| 3 | `systemctl stop im-server`；nginx 移除 `location /im` | kean 与客户端都不依赖它 |

> 既有的 `ChatWebSocketHandler` / `WebSocketConfig` / `ChatSessionHub` / `/ws/chat`
> **一行未删、一行未改**，它仍然是兜底通道。
> 既有的 `docs/sql/im-platform.sql`（`im_platform` 库）**不影响回滚**：kean 不读它。

---

## 6. 已知遗留（如实列出）

| 项 | 说明 |
|---|---|
| **`ImKickService` 类注释过期** | 该文件顶部 Javadoc（约 47-56 行）仍写着「im-server 没有任何封禁校验」「`im:user:denied` 不存在」，与本轮实测事实相反。常量与实现是正确的，**只有注释没跟上**。本轮约束是「只改文档、不改代码」，故未修改 —— **下次改这个文件时顺手删掉那段** |
| **客户端开关尚未打开** | `VITE_IM_ENABLED` 未设置 ⇒ 客户端仍在用自研 `/ws/chat`；im-server 目前只有握手验证过，**没有真实客户端在线** |
| **端到端联调尚未做** | 「私聊进气泡 / 队列不堆积 / 封禁踢线 + 阻止重连」这三条**只看过日志与握手**，没有走完整链路 |
| **未部署 im-platform 的后果** | kean 能投递到队列，但有 4 件事只有 im-platform 能做：① 消息落 `im_platform` 库（kean 自己落 `chat_message`，功能上不缺）；② 离线消息拉取（`loadOfflineMessage`）；③ 多端消息同步（`sendToSelf`）；④ **真正的广播**（把 `recvIds` 展开成在线 receivers）。kean 侧的 `sendSystem` 因此只做定向投递 |
| **序列化差异（将来会踩）** | kean 用 `StringRedisTemplate` 写字符串 `"1"`，而上游 im-platform 用 `RedisTemplate<String,Object>` 存数字、`AuthInterceptor` 会 `(Integer)` 强转。**当前形态够用**（im-server 只看 `hasKey`），一旦部署 im-platform 必须改成写数字，否则取票/鉴权抛类型转换异常（见 §2.7） |
| **多实例 / 频繁重启的 serverId 漂移** | `im:max_server_id` 只增不减；每次重启都换新 serverId，旧 `im:message:*:{旧id}` 队列**永远不会再被消费**（静默堆积）。`Restart=always` 会放大这个问题。单实例稳定运行不受影响 |
| **`sendResult=false` 的代价** | 没有「发送结果」回执，因此无法知道消息是否真的投到了对端连接。kean 侧的成功语义只是「已写进 Redis 队列」 |
| **im-server 的 WS 无 TLS** | 必须经 nginx 收 TLS；直连 8878 是明文 |
| **Redis 清空后封禁标记丢失** | `im:user:denied:{userId}` **无 TTL**（正常不会过期），但 Redis 重启且未开持久化就会丢 → 被封用户可重连。生产必须开 AOF/RDB |
| **`receivers` 为空 = 不推送** | 「广播」不是一个能直接投递的动作；业务上只用定向投递（见 §2.8） |

---

## 7. 源码链接汇总

> 早期版本这里指向的是**较旧的镜像仓库** `gitee.com/quanxinshijie/box-im`。
> 现已统一改为官方仓库 `github.com/bluexsx/box-im`（**master / 4.0.0 / commit `4ebfb0a`**）。
> ⚠️ 本轮改动时**网络不可用，未逐条重新打开这些链接**；行号（如 `LoginProcessor.java:51`、
> `IMRedisKey.java:62`、`PullForceLogoutTask.java:16`、`UserBannedConsumerTask:38`）
> 来自本轮任务给出并经真实部署佐证的核对结果，**未在本地二次抓取**。

| 内容 | 链接 |
|---|---|
| `IMRedisKey`（队列基键、`IM_USER_DENIED`、`IM_USER_FORCE_LOGOUT_QUEUE`） | https://github.com/bluexsx/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/contant/IMRedisKey.java |
| `IMRecvInfo` / `IMUserInfo` / `IMSystemMessage` / `IMForceLogoutInfo` | https://github.com/bluexsx/box-im/tree/master/im-common/src/main/java/com/bx/imcommon/model |
| `IMCmdType`（cmd 0..5） | https://github.com/bluexsx/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/enums/IMCmdType.java |
| `RedisMQPullTask`（`leftPop` + `toJavaObject`） | https://github.com/bluexsx/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/mq/RedisMQPullTask.java |
| `LoginProcessor`（**上游自带封禁校验，`:51`**） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/processor/LoginProcessor.java |
| `PullForceLogoutTask`（**消费者在 im-server**） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/java/com/bx/imserver/task/PullForceLogoutTask.java |
| `AbstractPullMessageTask`（消费侧 `+":"+serverId`） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/java/com/bx/imserver/task/AbstractPullMessageTask.java |
| `PullPrivateMessageTask` / `PullSystemMessageTask` | https://github.com/bluexsx/box-im/tree/master/im-server/src/main/java/com/bx/imserver/task |
| `IMServerGroup`（`im:max_server_id` 自增） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/IMServerGroup.java |
| `PrivateMessageProcessor` / `SystemMessageProcessor` / `ForceLogoutProcessor` | https://github.com/bluexsx/box-im/tree/master/im-server/src/main/java/com/bx/imserver/netty/processor |
| `WebSocketServer`（`/im` 写死、8878、60s 读空闲） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/ws/WebSocketServer.java |
| `IMSender`（投递侧真实键拼法） | https://github.com/bluexsx/box-im/blob/master/im-client/src/main/java/com/bx/imclient/sender/IMSender.java |
| `UserBannedConsumerTask` / `UserServiceImpl` / `AuthInterceptor`（`im:user:denied` 的写/删/读） | https://github.com/bluexsx/box-im/tree/master/im-platform/src/main/java/com/bx/implatform |
| im-server `application.yml`（示例密钥 12 字节 ✗） | https://github.com/bluexsx/box-im/blob/master/im-server/src/main/resources/application.yml |
| im-server `application-dev.yml` / `application-prod.yml`（Redis 写法，规范三行） | https://github.com/bluexsx/box-im/tree/master/im-server/src/main/resources |
| `PrivateMessageVO` / `MessageType` / `PrivateMessageServiceImpl` | https://github.com/bluexsx/box-im/tree/master/im-platform/src/main/java/com/bx/implatform |

### 7.1 课安侧对应实现（本仓库）

| 关注点 | 文件 |
|---|---|
| 封禁标记键 + 强制下线投递 | [`kean/src/main/java/com/kean/im/ImKickService.java`](../../kean/src/main/java/com/kean/im/ImKickService.java) |
| 私聊/系统消息镜像投递 + `data` 契约 | [`kean/src/main/java/com/kean/im/ImSenderService.java`](../../kean/src/main/java/com/kean/im/ImSenderService.java) |
| 密钥校验 / 指纹 / 取票 | [`kean/src/main/java/com/kean/im/ImTokenService.java`](../../kean/src/main/java/com/kean/im/ImTokenService.java)、[`ImController.java`](../../kean/src/main/java/com/kean/im/ImController.java) |
| Redis 只支持 standalone + 0 号库 | [`kean/src/main/java/com/kean/config/RedisConfig.java`](../../kean/src/main/java/com/kean/config/RedisConfig.java) |
| 客户端开关 / 连接 / 帧映射 | [`uni-kean/src/utils/imFlag.ts`](../../uni-kean/src/utils/imFlag.ts)、[`imSocket.ts`](../../uni-kean/src/utils/imSocket.ts) |
| nginx 与 ufw 示例 | [`docs/ops/nginx.conf.example`](./nginx.conf.example) |
| 阶段状态与剩余工作 | [`docs/ops/im-migration.md`](./im-migration.md) §6 |
