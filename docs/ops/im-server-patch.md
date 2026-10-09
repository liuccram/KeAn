# im-server 侧补丁说明（阶段 3：把实时推送切到 box-im im-server）

> **本文件只是补丁说明书，不包含任何可执行的本仓改动。**
> `im-server` 属于独立仓库（box-im），课安**不会**把它提交进本仓库；
> 本仓阶段 3 的改动全部在 `kean/` 与 `uni-kean/` 侧（见第 6 节边界）。
>
> 本文所有结论都对照 box-im `master` 分支源码逐行核对过，链接见各节。
> 标注 **【未证实】** 的地方是当前无法从源码或本仓确认的，部署前必须实测。

---

## 0. 两个队列契约（kean 写入 → im-server 消费）

这是整个阶段 3 能成立的前提，先把它讲清楚。

### 0.1 队列键：**必须带 `:{serverId}` 后缀**

| | 基键（常量，**不是**真实队列键） | 真实队列键 |
|---|---|---|
| 私聊 | `im:message:private` | `im:message:private:{serverId}` |
| 系统 | `im:message:system` | `im:message:system:{serverId}` |

依据：

* `IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE = "im:message:private"` /
  `IM_MESSAGE_SYSTEM_QUEUE = "im:message:system"`
  —— [im-common/contant/IMRedisKey.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/contant/IMRedisKey.java)
* 投递侧（im-client `IMSender.pushPrivateMessage`，见 0.3）拼的就是
  `String.join(":", IMRedisKey.IM_MESSAGE_PRIVATE_QUEUE, serverId.toString())`
* 消费侧 `AbstractPullMessageTask.generateKey()` 拼的是
  `String.join(":", super.generateKey(), IMServerGroup.serverId + "")`
  —— [im-server/task/AbstractPullMessageTask.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/task/AbstractPullMessageTask.java)
  其中 `super.generateKey()` 返回 `@RedisMQListener(queue = ...)` 的注解值（即基键）。

> **结论（最关键的一条）**：kean 侧写入时**必须**用
> `im:message:private:{serverId}` / `im:message:system:{serverId}`。
> **只写基键的消息永远不会被消费**（im-server 的 4 个拉取任务只拉带后缀的键），
> 会静默堆在 Redis 里，表现为「kean 说投递成功，客户端永远收不到」。

`serverId` 的来源：im-server 每次启动执行
`im:max_server_id` 自增（`IMServerGroup.run` → `redisMQTemplate.opsForValue().increment("im:max_server_id", 1)`）
—— [im-server/netty/IMServerGroup.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/IMServerGroup.java)。
单实例部署时它首次启动即为 `1`，重启会变成 `2`、`3`…（计数器只增不减）。

**kean 怎么知道该投给哪个 node**：读**接收方**的在线槽位键
`im:user:server_id:{userId}:{terminal}`（值就是 serverId，键不存在 = 该终端离线）。
这与 im-client 的 `IMSender` 完全一致的思路（它也做 `multiGet` 查这个键）。
`com.kean.im.ImSenderService` 正是这么实现的：三个终端都查、按 serverId 去重后各投一条。

### 0.2 消息体：`IMRecvInfo`，**不需要 `@type`**

消费链路：`RedisMQPullTask.pullBatch()` 用
`redisTemplate.opsForList().leftPop(key, batchSize)` 拉取，
再 `jsonObject.toJavaObject(type)` 反序列化成 `IMRecvInfo`
（`type` 由 `AbstractPullMessageTask<T>` 的泛型推出，私聊/系统都是 `IMRecvInfo`）
—— [im-common/mq/RedisMQPullTask.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/mq/RedisMQPullTask.java)。

`toJavaObject(Class)` **不读 `@type`**，所以 kean 用 Jackson 写同构 JSON 即可被读取
（这一点已在 `FORCE_LOGOUT` 通道上核实并复用）。

`IMRecvInfo` 字段（[IMRecvInfo.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/model/IMRecvInfo.java)）：

| 字段 | 类型 | kean 投递时填什么 |
|---|---|---|
| `cmd` | Integer | 私聊 `3`（`IMCmdType.PRIVATE_MESSAGE`）/ 系统 `5`（`SYSTEM_MESSAGE`） |
| `sender` | `IMUserInfo` | 私聊填发送方 `{id, terminal}`；系统消息填 `null` |
| `receivers` | `List<IMUserInfo>` | 见下 |
| `sendResult` | Boolean | **`false`** —— 设 `true` 时 im-server 会把回执写进 `im:result:private:{serviceName}`，而那个队列的消费者在 im-platform 里；kean 没有消费者，设 `true` 只会让 Redis 无界增长 |
| `serviceName` | String | `"kean"`（仅用于拼结果队列键，`sendResult=false` 时无副作用） |
| `data` | Object | 见 0.3 |

`IMUserInfo` = `{id: Long, terminal: Integer}`
（[IMUserInfo.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/model/IMUserInfo.java)）。
`terminal`：box `IMTerminalType` WEB=0 / APP=1 / PC=2。

`receivers` 的作用是**指定「投给谁」**，im-server 会遍历它，逐个
`UserChannelCtxMap.getChannelCtx(id, terminal)` 找连接并推送：

* **私聊**：`receivers` 就是接收方。`PrivateMessageProcessor` 只推送
  `recvInfo.getData()`，**完全不读 `data` 里的 `recvId`**，所以「投给谁」完全由
  `receivers` 决定 —— [PrivateMessageProcessor.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/processor/PrivateMessageProcessor.java)。
  kean 侧把接收方的三个终端都列上（`[{id,0},{id,1},{id,2}]`），由 im-server 自己判断哪个终端在线。
* **系统消息**：`SystemMessageProcessor` 同样遍历 `receivers`。
  `IMSystemMessage.recvIds` 的注释是「为空表示向所有在线用户广播」，但**广播的落实是靠
  im-platform 先把 recvIds 展开成在线的 receivers**（`IMSender.sendSystemMessage` 遍历
  `message.getRecvIds()` 去 `im:user:server_id` 查 serverId）——
  [IMSystemMessage.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/model/IMSystemMessage.java)、
  [IMSender.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-client/src/main/java/com/bx/imclient/sender/IMSender.java)。
  ⚠️ **`receivers` 为空列表时 im-server 不会给任何人推送**（循环体一次都不进），
  所以「广播」不是一个能直接投递的动作。kean 侧因此**只做定向系统消息**（`RealtimePublisher`
  的通知都是「给某一个人」），广播入口仅预留、当前业务未使用。

### 0.3 kean 实际写出的 JSON（与 box 的 `data` 载荷）

私聊（照抄 `PrivateMessageServiceImpl.sendMessage` 里
`PrivateMessageVO vo = BeanUtils.copyProperties(message, PrivateMessageVO.class)`，
字段见 [PrivateMessageVO.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-platform/src/main/java/com/bx/implatform/vo/PrivateMessageVO.java)）：

```json
{
  "cmd": 3,
  "sender": { "id": <发送方 userId>, "terminal": 1 },
  "receivers": [
    { "id": <接收方 userId>, "terminal": 0 },
    { "id": <接收方 userId>, "terminal": 1 },
    { "id": <接收方 userId>, "terminal": 2 }
  ],
  "serviceName": "kean",
  "sendResult": false,
  "data": {
    "localId": "<kean chat_message.local_id>",
    "seqNo": <kean chat_message.seq_no>,
    "sendId": <发送方 userId>,
    "recvId": <接收方 userId>,
    "content": "<文本，或图片的 objectKey>",
    "type": 0,
    "status": 1,
    "sendTime": 1730000000000,
    "deleted": false
  }
}
```

* `type`：box `MessageType` 数字码，`TEXT=0` / `IMAGE=1`
  —— [MessageType.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-platform/src/main/java/com/bx/implatform/enums/MessageType.java)。
* `status`：box `MessageStatus`，kean 新消息固定 `1`（已发送）。
* `sendTime`：epoch 毫秒。box 的 `PrivateMessageVO.sendTime` 带 `@JsonSerialize(using = DateToLongSerializer.class)`，
  即它自己产出的也是毫秒数，所以 kean 直接写 `Long` 与之一致。
  FastJson 把数字反序列化回 `Date` 的行为 **【未证实】**（`IMRecvInfo.data` 声明为 `Object`，
  且 im-server 只把它原样透传给客户端，不解析内部字段，所以实际风险极低）。
* kean **刻意不写 `id`**：kean 的消息主键与 `im_platform.im_private_message` 不是同一套编号，
  硬塞会让客户端拿 kean 的 id 去调 box 的历史/已读接口。

系统消息：

```json
{
  "cmd": 5,
  "sender": null,
  "receivers": [ { "id": <接收方 userId>, "terminal": 0 }, ... ],
  "serviceName": "kean",
  "sendResult": false,
  "data": { "type": "NOTICE", "noticeType": "...", "bizType": "...", "bizId": 0 }
}
```

`data` 就是课安自研 WS 上 `RealtimeEvent` 的**同一份 payload**
（`RealtimePublisher.notice` 产出的 `{type, noticeType, bizType, bizId}`），
这样客户端在 `cmd 5` 上收到的结构与既有通道完全同形，不需要第二套解析。

> ⚠️ **不要投 `data.type = "READ"`**：box 的对应物是 `MessageType.RECEIPT(12)`，
> 语义/字段都不同，镜像过去只会变成一条没有意义的通知。
> 课安侧已经在 `RealtimePublisher.mirrorToIm` 里显式跳过 `READ`。

---

## 1. 封禁校验（安全必做，2 行）

### 1.1 为什么必须在 im-server 侧加

`kean` 的封禁联动（`TokenRevokeService` / `TokenBlacklistService`）只有 kean 自己的
`JwtAuthFilter` 会读；im-server 用它自己那份 `jwt.accessToken.secret` 独立验签，
**不认识 kean 的黑名单**。已核实 im-server `master` 的
`LoginProcessor.process` / `WebSocketServer` / `IMChannelHandler` 里
**不存在任何用户状态校验**：

* `LoginProcessor` 只做两件事：`JwtUtil.checkSign` 验签、按
  `im:user:server_id:{userId}:{terminal}` 做「同终端挤下线」；
* `IMChannelHandler` 只做心跳与读写；
* `WebSocketServer` 只配 pipeline。

所以 kean 侧只能「踢掉当前连接」，无法阻止被封用户**重连**。
`com.kean.im.ImKickService` 已经写好了封禁标记键 `kean:im:banned:{userId}`（无 TTL，解封时删除），
只缺 im-server 侧的读取方 —— 就是下面这 2 行。

### 1.2 补丁（插入位置已定位到行）

文件：`im-server/src/main/java/com/bx/imserver/netty/processor/LoginProcessor.java`
（[源码](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/processor/LoginProcessor.java)）

`process` 方法现有结构（**保持缩进 8 空格**）：

```java
@Override
public void process(ChannelHandlerContext ctx, IMLoginInfo loginInfo) {
    if (!JwtUtil.checkSign(loginInfo.getAccessToken(), accessTokenSecret)) {
        ctx.channel().close();
        log.warn("用户token校验不通过，强制下线,token:{}", loginInfo.getAccessToken());
        return;
    }
    String strInfo = JwtUtil.getInfo(loginInfo.getAccessToken());
    IMSessionInfo sessionInfo = JSON.parseObject(strInfo, IMSessionInfo.class);
    Long userId = sessionInfo.getUserId();
    Integer terminal = sessionInfo.getTerminal();
    log.info("用户登录，userId:{}", userId);
    // >>>>>>>>>>>>>> 【在这里插入封禁校验】 <<<<<<<<<<<<<<
    String key = IMRedisKey.userServerIdKey(userId, terminal);
    Object serverId = redisMQTemplate.opsForValue().get(key);
    ...
    UserChannelCtxMap.addChannelCtx(userId, terminal, ctx);
    ...
}
```

插入内容（**2 行** —— 一条 `get` + 一条 `if`，逐个字节照抄即可）：

```java
        Object banned = redisMQTemplate.opsForValue().get("kean:im:banned:" + userId);
        if (banned != null) { ctx.channel().close(); log.warn("用户已被封禁,拒绝建立连接,userId:{}", userId); return; }
```

把这一行插在 `log.info("用户登录，userId:{}", userId);` **之后**、
`String key = IMRedisKey.userServerIdKey(userId, terminal);` **之前**。

* **变量名已核实**：`LoginProcessor` 里已有的 Redis 访问点是
  `private final RedisMQTemplate redisMQTemplate;`（`@RequiredArgsConstructor` 注入），
  不是 `redisTemplate`。所以补丁里必须用 **`redisMQTemplate`**，否则编译不过。
* `RedisMQTemplate extends RedisTemplate<String, Object>`
  （[RedisMQTemplate.java](https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/mq/RedisMQTemplate.java)），
  所以 `opsForValue().get(...)` 的返回类型是 `Object`，与上面 `Object banned` 匹配。
* **不要**改成 `SpringContextHolder.getBean(RedisMQTemplate.class)`：`IMChannelHandler` 这么用过，
  但 `LoginProcessor` 已经有注入字段，多此一举且引入 NPE 风险。
* 键名 `"kean:im:banned:" + userId` **必须与 kean 侧的
  `ImKickService.BANNED_KEY_PREFIX` 逐字节一致**（含 `kean:` 前缀）。

### 1.3 这一行的强度与残留风险（如实告知，别当成完整封禁）

| 项 | 结论 |
|---|---|
| 阻断**新**连接 | ✅ 生效（在 `addChannelCtx` 之前 return，不会注册 channel，也不会写 `im:user:server_id`） |
| 踢掉**已存在**连接 | 靠 kean 侧 `ImKickService.forceLogout` 投 `im:user:force_logout:{serverId}`；该队列的消费者 `PullForceLogoutTask` **在 im-server 里**（不是 im-platform），所以这条能生效 |
| 阻止被封用户拿新 token | ❌ 做不到：`GET /api/im/token` 只校验 kean 自己的 JWT 与封禁态。**【未证实】** kean 是否对 `BANNED` 用户拦截取票，需在 `ImController` 侧确认；若未拦，被封用户仍能拿到 token，只是连不上 |
| Redis 清空后 | ❌ 标记键丢失 → 校验失效（`ImKickService` 的键**无 TTL**，正常情况下不会过期，但 Redis 重启且未开持久化就会丢）。生产必须开 AOF/RDB |
| 解封 | `ImKickService.allow(userId)` 删键，之后可重新连接 |

> **结论**：这 2 行是「防重连」的必要条件，**不是**封禁的充分条件。
> 真正的封禁事实仍在 kean 的库里；im-server 侧只是读一个镜像标记。

---

## 2. 密钥：`jwt.accessToken.secret` 必须与 kean 的 `IM_JWT_SECRET` 一致且 ≥32 字节

### 2.1 当前 box-im `master` 的原值（**不能直接用**）

`im-server/src/main/resources/application.yml`
（[源码](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/resources/application.yml)）：

```yaml
jwt:
  accessToken:
    secret: MIIBIjANBgkq  # 跟im-platfrom的secret必须一致
```

`MIIBIjANBgkq` 只有 **12 字节**。

* im-server 侧用它 **不会报错**（java-jwt 的 `HMAC256` 不校验密钥长度）；
* kean 侧会因此把 IM 判为**未就绪**：`ImTokenService.validateAccessSecret` 在构造时先自己量长度，
  不足 32 字节就打 ERROR 并把 `enabled()` 置为 `false`（**刻意不抛异常**，
  否则 `Keys.hmacShaKeyFor` 的 `WeakKeyException` 会把整个 kean 启动带崩）。

所以**如果只改 im-server 不改 kean，或只改 kean 不改 im-server，表现是**：

* 两边不一致 → 客户端连上立刻被 im-server `ctx.channel().close()`，
  日志里是 `用户token校验不通过，强制下线`（kean 侧看不出问题，`GET /api/im/token` 照样返回 token）；
* kean 侧配了 12 字节 → `GET /api/im/token` 返回 `enabled=false`，客户端根本不会去连。

### 2.2 正确配置

1. 生成一个 ≥32 字节的随机密钥（**不要**用 box 示例值）：

   ```bash
   openssl rand -base64 48
   ```

2. kean 侧（环境变量，**不要**写进 `application*.yml`）：

   ```bash
   IM_JWT_SECRET=<同一个密钥>
   # 可选；不配则由上面的密钥派生（refreshToken 不参与 im-server 握手）
   IM_JWT_REFRESH_SECRET=<另一个密钥>
   ```

3. im-server 侧（`im-server/src/main/resources/application.yml`）：

   ```yaml
   jwt:
     accessToken:
       secret: <同一个密钥>   # 必须与 kean 的 IM_JWT_SECRET 逐字节一致，且 >= 32 字节
   ```

### 2.3 怎么确认两边配的是同一个值（不泄露密钥）

kean 启动日志里会打印密钥指纹（`sha256(secret)` 前 8 位十六进制，**不打印密钥本体**）：

```
[IM 未启用/原因：密钥过短] 环境变量 IM_JWT_SECRET 已配置，但只有 12 字节（96 bit）。
...
密钥指纹(sha256 前 8 位)：a1b2c3d4（不打印密钥本体）
```

`IM_JWT_SECRET` 配置**正确**时不会打印指纹。要主动比对，可在 im-server 侧临时加一行同样的
指纹计算，或在两边各跑一次 `printf '%s' "$SECRET" | sha256sum | cut -c1-8`。
**【未证实】** im-server 的启动日志里是否有等价输出 —— 没有，需要自己加。

---

## 3. Redis：kean 与 im-server **必须共用同一个实例、同一个库**

`im:user:server_id:{userId}:{terminal}`、`im:user:force_logout:{serverId}`、
`im:message:private:{serverId}`、`im:message:system:{serverId}`、`kean:im:banned:{userId}`
—— 这一整组键的语义**只在同一个 Redis 库内成立**。
分成两个实例（或同实例不同 `database`）的表现是「语法全对、逻辑全不生效」。

### 3.1 kean 侧（已核实）

`kean/src/main/java/com/kean/config/RedisConfig.java` 读的是：

| 配置项 | 说明 |
|---|---|
| `spring.data.redis.host` | 对应环境变量 `REDIS_HOST` |
| `spring.data.redis.port` | 对应环境变量 `REDIS_PORT` |
| `spring.data.redis.password` | 对应环境变量 `REDIS_PASSWORD`（可空） |

> ⚠️ kean 的 `RedisConfig` **只支持 standalone**（`RedisStandaloneConfiguration`），
> 且**只读 host/port/password**，没有 `database` 配置项。
> 也就是说 **kean 永远用 Redis 的 0 号库**。因此 im-server 侧也**不能**配
> `spring.data.redis.database`（或必须配成 `0`），否则两组键不在一个库里。

### 3.2 im-server 侧（照抄正确写法）

`im-server/src/main/resources/application-dev.yml`（**已对照 master 原文核实**，
原文就是下面这个干净的写法）：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: <与 kean 的 REDIS_PASSWORD 相同>
```

`application-prod.yml` / `application-test.yml` 在 master 上也已带 `password` 字段，
格式与上面一致（只是值不同）。**本服务器上要改的是 host**：

```yaml
spring:
  data:
    redis:
      host: <kean 的 REDIS_HOST>
      port: <kean 的 REDIS_PORT>
      password: <kean 的 REDIS_PASSWORD>
```

> ### ⚠️ 关于「port 与 password 挤在同一行」
> 任务背景里提到 box-im 的 `application-dev.yml` 疑似把 port 与 password 写在同一行。
> **我在 gitee 上核对 master 的
> [application-dev.yml](https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/resources/application-dev.yml)
> 时它只有 `host` + `port` 两行、并没有 password，格式是规范的。**
> 所以这个「挤在一行」的问题在当前 master 上**不成立**；
> 如果运维手上的是另一份拷贝/分支，请按下面这条规则自查：
>
> ```yaml
> # ❌ 错误：port 与 password 写在同一行 → YAML 解析的是字符串 "6379 password: xxx"，
> #    Spring 绑定 int 端口时失败，启动直接挂（不是"密码不生效"，是起不来）
>       port: 6379 password: PmEpfRjpBnTN6CgW
>
> # ✅ 正确
>       port: 6379
>       password: PmEpfRjpBnTN6CgW
> ```
>
> 校验命令：`python -c "import yaml,sys;yaml.safe_load(open('application-dev.yml'))"`，
> 或直接看启动日志里有没有 `Failed to bind properties under 'spring.data.redis.port'`。

### 3.3 一个必须注意的副作用：**共库 = 共用 0 号库 = 键可能被扫**

im-server / im-platform 会往同一个库写 `im:*` 键。
**【未证实】** 生产环境是否有其他进程对这个库执行 `FLUSHDB` / `KEYS *` 之类的操作 ——
上线前需确认；`FLUSHDB` 会连带清掉 kean 自身的缓存键（登录态黑名单、限流、序列表等）。

---

## 4. nginx：为 `/im` 增加 WebSocket 上游

前端连的是 `wss://<你的域名>/im`（`VITE_IM_WS_URL`）。im-server 的 WS 端口是 **8878**，
且路径**写死为 `/im`**（`WebSocketServer` 里 `new WebSocketServerProtocolHandler("/im")`），
所以反代必须**原样透传路径**，不能 rewrite。

把下面这段加进 `docs/ops/nginx.conf.example` 里那个 443 server 块
（紧挨现有的 `location /ws/ { ... }`，写法与它保持一致）：

> 📌 **现状（已同步）**：`docs/ops/nginx.conf.example` 已按线上真实拓扑重写（三域名分站 + Cloudflare 回源），
> 这段 `location /im` 已经落在该文件的 **`api.kean.college` server 块（第 3.2 节）**里，
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
# 2) 握手（期望 101 Switching Protocols；若 404 说明路径被改了，若 502 说明上游不通）
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     https://api.kean.college/im
# 3) 直连上游（排除 nginx 因素）
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     http://172.17.0.1:8878/im
```

> **注意**：`8878` 这个端口**不要**直接暴露公网 —— 它没有 TLS，也没有
> kean 的 `JwtAuthFilter`，只有 im-server 自己的 `checkSign`。走 nginx 收 TLS。
> `8879`（TCP socket，`tcpsocket.enable: false`）同样不要暴露。

---

## 5. 部署顺序与验收（阶段 3）

```
1) 生成 >=32 字节密钥，写入 kean 的 IM_JWT_SECRET
2) 起 Redis（与 kean 同一个实例、0 号库），确认双方 host/port/password 一致
3) 起 im-server（打上第 1 节补丁、改好第 2 节密钥、第 3 节 Redis）
     └─ 启动后确认：redis-cli get im:max_server_id  => 1（或更大的整数）
4) nginx 加 /im 上游并 reload；curl 验证 101
5) 先不开客户端 flag，用后端自测：kean 里投一条消息，检查
     redis-cli llen im:message:private:1   => 递增
     redis-cli lrange im:message:private:1 0 0  => 能看到 IMRecvInfo JSON（含 cmd:3）
     im-server 日志出现「接收到私聊消息，发送者:..,接收者:..,内容:..」
6) 打开客户端 VITE_IM_ENABLED=true + VITE_IM_WS_URL，灰度一批用户
7) 回归：关掉 flag，确认回到自研 /ws/chat（见第 6 节回滚）
```

**验收清单**

| # | 检查 | 期望 |
|---|---|---|
| 1 | `redis-cli get im:max_server_id` | 存在且 ≥1（不存在说明 im-server 没起来） |
| 2 | `redis-cli keys 'im:message:*'` | 至少能看到 `im:message:private:1`（**注意：生产别用 `keys`，用 `scan`**） |
| 3 | kean 日志 | `[IM 镜像投递] 已投递 N 个 im-server 队列（im:message:private:*）...` |
| 4 | im-server 日志 | `接收到私聊消息，发送者:x,接收者:y,内容:...` |
| 5 | `llen im:message:private:*` | **不再增长**（说明被 `leftPop` 消费掉了）。持续增长 = 消费端没在跑或队列键写错 |
| 6 | 客户端 | `cmd 3` 到包；未读角标刷新；`cmd 2` 弹「已退出登录」并回登录页 |
| 7 | 封禁 | 封禁用户后：在线连接被踢（`FORCE_LOGOUT`），且**重连失败**（日志 `用户已被封禁,拒绝建立连接`） |
| 8 | 关 flag | 客户端不再连 8878；`/ws/chat` 一切照旧 |

---

## 6. 边界与回滚

### 6.1 本仓（kean / uni-kean）阶段 3 的改动清单

| 文件 | 改动 | 是否可回滚 |
|---|---|---|
| `kean/src/main/java/com/kean/im/ImSenderService.java` | **新增**：投递私聊/系统消息到 box 队列 | 新文件，删掉即回滚 |
| `kean/src/main/java/com/kean/service/impl/ChatServiceImpl.java` | 构造函数加一个依赖 + `send()` 末尾**追加**一次 `sendPrivate` | 删掉那一行即回滚 |
| `kean/src/main/java/com/kean/chat/RealtimePublisher.java` | 构造函数加一个依赖 + `send()` 末尾**追加**一次 `sendSystem` 镜像 | 删掉那次调用即回滚 |
| `uni-kean/src/utils/imSocket.ts` | **追加**帧 → `RealtimeEvent` 映射 + `onMapped` 订阅 | 开关关时不生效 |
| `uni-kean/src/composables/useLiveUpdates.ts` | **追加**「flag 为真时连 IM」，既有 `onRealtime` 一行未改 | 开关关时一行不执行 |
| `kean/src/main/resources/application*.yml` | **未改动** | — |
| `uni-kean/.env*` | **未改动** | — |

既有链路（`ChatWebSocketHandler` / `WebSocketConfig` / `/ws/chat` / `ChatSessionHub`）
**一行未删、一行未改**，它仍然是兜底通道。

### 6.2 回滚（三步，从快到慢）

1. **客户端**：`VITE_IM_ENABLED=false`（或删掉该变量）→ 重新发布前端。
   立即回到只有自研 `/ws/chat` 的现状。**这是首选手段。**
2. **后端投递**：`KEAN_IM_MIRROR_ENABLED=false`（或删掉 `IM_JWT_SECRET`）
   → `ImSenderService` 全部 no-op，kean 不再往 box 队列写任何东西。
3. **im-server**：停掉 im-server 进程、nginx 移除 `location /im`。
   kean 与客户端都不依赖它。

> **回滚不需要动数据库、不需要动 Flyway、不需要改任何 `application*.yml`。**

### 6.3 已知的、**没有**在阶段 3 解决的问题（如实列出）

| 项 | 说明 |
|---|---|
| **私聊消息落不进会话** | im-server 推给客户端的 `data` 是 box 的 `PrivateMessageVO`，用 `sendId`/`recvId` 表达双方、**没有 `sessionId`**；而 `chat.vue` 的 `applyIncoming` 要求 `sessionId === 当前会话`。所以 IM 通道目前能可靠驱动的是「未读角标」与「系统通知」，私聊气泡仍靠自研通道/轮询。需要一次「peerId → sessionId」的映射才能补齐 |
| **未部署 im-platform 的后果** | kean 能投递到队列，但有 4 件事只有 im-platform 能做：① 消息落 `im_platform` 库（kean 自己落 `chat_message`，功能上不缺）；② 离线消息拉取（`loadOfflineMessage`）；③ 多端消息同步（`sendToSelf`，box 的 `IMSender` 也依赖它查 serverId）；④ **真正的广播**（把 `recvIds` 展开成在线 receivers）。kean 侧的 `sendSystem` 因此只做定向投递，广播是**空转**（只保留给将来用） |
| **多实例 im-server 的 serverId 漂移** | `im:max_server_id` 只增不减。im-server 重启会拿到新的 serverId，旧的 `im:message:*:{旧id}` 队列**永远不会再被消费**。单实例部署不受影响；多实例/频繁重启时需要清理旧队列键或改用固定 serverId |
| **`sendResult` 关闭的代价** | 没有「发送结果」回执，因此无法知道「消息是否真的投到了对端连接」。kean 侧的成功语义只是「已写进 Redis 队列」 |
| **im-server 的 WS 无 TLS** | 必须经 nginx 收 TLS；直连 8878 是明文 |
| **心跳与空闲超时** | im-server `IdleStateHandler(60, 0, 0)` 读空闲 60s 即断；客户端 20s 心跳。nginx 的 `proxy_read_timeout` 必须远大于 60s，否则会出现「nginx 先断、客户端反复重连」 |

---

## 7. 源码链接汇总（本轮核对过的原文）

| 内容 | 链接 |
|---|---|
| `IMRedisKey`（队列基键） | https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/contant/IMRedisKey.java |
| `IMRecvInfo` / `IMUserInfo` / `IMSystemMessage` / `IMPrivateMessage` / `IMSendInfo` | https://gitee.com/quanxinshijie/box-im/tree/master/im-common/src/main/java/com/bx/imcommon/model |
| `IMCmdType`（cmd 0..5） | https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/enums/IMCmdType.java |
| `RedisMQPullTask`（`leftPop` + `toJavaObject`） | https://gitee.com/quanxinshijie/box-im/blob/master/im-common/src/main/java/com/bx/imcommon/mq/RedisMQPullTask.java |
| `IMSender`（投递侧真实键拼法） | https://gitee.com/quanxinshijie/box-im/blob/master/im-client/src/main/java/com/bx/imclient/sender/IMSender.java |
| `AbstractPullMessageTask`（消费侧 `+":"+serverId`） | https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/task/AbstractPullMessageTask.java |
| `PullPrivateMessageTask` / `PullSystemMessageTask` | https://gitee.com/quanxinshijie/box-im/tree/master/im-server/src/main/java/com/bx/imserver/task |
| `IMServerGroup`（`im:max_server_id` 自增） | https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/IMServerGroup.java |
| `LoginProcessor`（补丁位置、`redisMQTemplate` 变量名） | https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/processor/LoginProcessor.java |
| `PrivateMessageProcessor` / `SystemMessageProcessor` | https://gitee.com/quanxinshijie/box-im/tree/master/im-server/src/main/java/com/bx/imserver/netty/processor |
| `WebSocketServer`（`/im`、8878、60s 空闲） | https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/java/com/bx/imserver/netty/ws/WebSocketServer.java |
| im-server `application.yml`（示例密钥 12 字节） | https://gitee.com/quanxinshijie/box-im/blob/master/im-server/src/main/resources/application.yml |
| im-server `application-dev.yml` / `application-prod.yml`（Redis 写法） | https://gitee.com/quanxinshijie/box-im/tree/master/im-server/src/main/resources |
| `PrivateMessageVO` / `MessageType` / `PrivateMessageServiceImpl` | https://gitee.com/quanxinshijie/box-im/tree/master/im-platform/src/main/java/com/bx/implatform |
