# 迁移 box-im `im-platform` 到课安（IM 业务层 + 账号体系迁移方案与执行手册）

> **适用对象**：没有参与过本项目、第一次拿到这份文档的运维 / 后端 / 前端同学。
> **本文档只做一件事**：把「**逐步迁移到全量换成 box-im**」讲清楚、拆成可执行的阶段、
> 给出每阶段的验收与回滚方式。
>
> ### ⭐ 先读这两句，否则会读错整份文档
>
> > **终态**：**全量换成 box-im** —— IM 能力与**账号（认证）**最终都归 box。
> > **路径**：**逐步** —— 六阶段 **A（地基）→ B（双写灰度）→ C（IM 全量切换）→ D（能力解锁）→ E（账号灰度切换）→ F（收尾）**。
>
> ⚠️ **本文档经历过一次定性升级，读的时候请注意**：
> 本文件**第 1 版**是按「**只迁 IM 业务层、用户体系不动**」写的 —— 那是**路径（A~D）**，**不是终态**。
> 现在终态已明确为**全量**，因此新增了 **§1.5（终态与路径 —— 必读）**、**§3 阶段 E（账号灰度切换）**、
> **§3 阶段 F（收尾）** 与 **§10.5（终态对照表）**。**第 1 版的边界表述仍保留**（它们是「路径期禁令」，
> 现在仍然有效），但**每一条都补了「终态口径」**。
>
> ### 阅读前必须知道的三条边界
>
> 1. **终态是「全量换引擎 + 全量换认证」，但业务概念永远留在课安** —— 学校 / 校区 / 任务 / 申请 / 评价 / 举报 /
>    通知**全部留在课安（kean）自己的库与接口里，一行不迁**，因为 **box 里根本没有这些概念**。
>    终态的两种走法与为什么选 E2，见 **§1.5.2**；每个能力最终归谁，见 **§10.5 终态对照表**。
> 2. **本文档写作时未改动任何代码、配置、数据库**，也未执行任何上线动作。文中出现的 `systemctl` / `docker exec` /
>    `curl` 命令都是**给人照着执行**的，不是已经执行过的。
> 3. 本文**不重复设计已经完成的东西**。已完成清单见 §0.2；上游事实与来源见 §0.1 与 §13。
>    **未证实的点**统一收在 §12（含本轮新增的 **U16~U25**）—— **不要把未证实当事实用**。
>
> ### 本轮（终态全量版）新增/改动的章节索引
>
> | 位置 | 内容 | 为什么必须看 |
> |---|---|---|
> | **§1.4** | 旧的「不在本方案内」5 条 **+ 逐条补的终态口径** | 区分「路径期禁令」与「终态」 |
> | **§1.5** ⭐ | **终态与路径**（E1/E2 两种终态 · 铁律 L1/L2/L3 · L1 守卫 SQL） | **整份文档的地基** |
> | **§2.3** | 同 id 复用（升格说明） | 与 L1 呼应 |
> | **§2.5 / §2.5.1** | nginx 要拒绝的 box 接口 **新增 3 条**（`/modifyPwd`、`/refreshToken`、`/user/update`）+ 逐条理由 | 三条都是终态新增的安全项（尤其 `/refreshToken` 可被用来绕过封禁） |
> | **§3.0** ⭐ | 六阶段总览 + **每阶段的「终态位置」** | 排期与「谁归谁」一表看清 |
> | **§3 阶段 A** | 影子用户 **id 必须同值** 的硬约束 | 终态前置条件 |
> | **§3 阶段 E** ⭐⭐ | **账号灰度切换**（box 有哪些账号接口 · 密码哈希 · 灰度 · JWT 双轨 · 封禁/注销/改密一致性 · 验收 · 回滚） | **全站最危险的一段** |
> | **§3 阶段 F** ⭐ | 收尾 + **「仍然留在 kean 的 12 项」清单** + 可删项 | 终态固化 |
> | **§8.1.1 / §8.2 / §8.3** | 新增高危 **H14~H16**、回滚 **R8~R10**、阶段 E 回滚步骤 | 风险与回滚 |
> | **§9** ⭐ | 六阶段重估工作量 + **§9.1.1 E 阶段分解** + §9.3.1 E 阶段放量顺序 | 排期 |
> | **§10** ⭐ | 阶段 A 的最小可执行清单（含本轮新增的 **A-3b 同 id 复用硬校验**，4 条） | 下一步就能跑 |
> | **§10.5** ⭐⭐ | **终态对照表**（32 项能力的归属 + 9 处高危重复实现） | 「全量换」到底换走了什么 |

---

## 0. 事实基线与结论速览

### 0.1 本文引用的 box-im 上游事实（全部已核对，带出处）

核对对象：`https://github.com/bluexsx/box-im`，`master` 分支，版本 **4.0.0**
（与本仓既有文档 `docs/ops/im-server-patch.md` 使用的 commit `4ebfb0a` 同一分支；本文的源码引用为
**本轮重新抓取**，见 §13 的 URL 清单）。

| # | 事实 | 出处（`[VERIFIED]` = 本轮真的读到了源码原文） |
|---|---|---|
| F1 | `im-platform` 的 **REST 鉴权不查 `im_user` 表**：`AuthInterceptor` 只做「取 `accessToken` 头 → `JwtUtil.checkSign` 验签 → `JSON.parseObject(JwtUtil.getInfo(token), UserSession.class)` → 读一次 Redis 封禁键」，**全程没有任何数据库查询** | `[VERIFIED]` `im-platform/.../interceptor/AuthInterceptor.java` |
| F2 | 用户身份（`userName`/`nickName`）**来自 JWT 的 `info` 声明本身**，不是从库里查的 | `[VERIFIED]` `[.../session/UserSession.java]` + F1 |
| F3 | REST 鉴权读的封禁键是 **`im:user:denied:{userId}`**，且**强转成 `Integer`**：`Integer type = (Integer) redisTemplate.opsForValue().get(...)` | `[VERIFIED]` `AuthInterceptor.java` |
| F4 | im-platform 的封禁**值语义**（`IMForceLogoutType`）：`UNREG` → 「账号已注销」，其它非空值 → 「账号已被封禁」 | `[VERIFIED]` `AuthInterceptor.java` |
| F5 | `PrivateMessageService.loadOfflineMessage(Long minId)` 的真实行为：`id > minId` **且** `send_time >= now - 60 天` **且**（`send_id = 我` 或 `recv_id = 我`），按 `id desc` 取 **最多 10000 条**；取满则额外补每个会话的最后一条；**顺带把「收到的、status=PENDING(0) 的」消息置为 `DELIVERED(1)`**；最后按 `id` 升序返回 | `[VERIFIED]` `[.../service/impl/PrivateMessageServiceImpl.java]` |
| F6 | `Constant.MAX_OFFLINE_MESSAGE_DAYS = 60L`、`MAX_OFFLINE_MESSAGE_SIZE = 10000L`、`MAX_MESSAGE_LENGTH = 1024L`、`MAX_IMAGE_SIZE = MAX_FILE_SIZE = 20MB`、`MAX_FILE_NAME_LENGTH = 128L` | `[VERIFIED]` `[.../contant/Constant.java]` |
| F7 | **`sendToSelf` 不是一个 service 方法**，而是 `IMPrivateMessage` / `IMBatchPrivateMessage` 上的一个 `Boolean` 字段，语义在 `IMSender` 里：为 `true` 时把同一条 `data` **只投给发送者自己的「其它终端」**（`IMTerminalType.codes()` 里**跳过** `sender.getTerminal()`），并且这些「同步给自己」的投递 **`sendResult` 强制为 `false`** | `[VERIFIED]` `im-client/.../sender/IMSender.java`（`sendBatchPrivateMessage`） |
| F8 | `MessageType` 全量数值：`TEXT=0`、`IMAGE=1`、`FILE=2`、`AUDIO=3`、`VIDEO=4`、`RECALL=10`、`READED=11`、`RECEIPT=12`、`TIP_TIME=20`、`TIP_TEXT=21`、`LOADING=30`、`ACT_RT_VOICE=40`、`ACT_RT_VIDEO=41`、`GROUP_BANNED=51`、`GROUP_UNBAN=52`、`FRIEND_*=80..84`、`GROUP_*=90..93`、`RTC_*=100..107`、`RTC_GROUP_*=200..211`。注释写明分段：0-9 真消息（落库）、10-19 状态类、20-29 提示类、30-39 UI 交互、40-49 操作交互、50-60 后台操作、80-89 好友变化、90-99 群聊变化、100-199 单人 RTC、200-299 多人 RTC | `[VERIFIED]` `[.../enums/MessageType.java]` |
| F9 | `MessageStatus`：`PENDING(0,"等待推送")`、`DELIVERED(1,"已送达")`、`RECALL(2,"撤回")`、`READED(3,"已读")` | `[VERIFIED]` `[.../enums/MessageStatus.java]` |
| F10 | **`seq_no` 在 Redis 上分配**：`getNextSeqNo(convKey)` 对 `im:message:private:max_seq:{convKey}` 做 `increment`；首次（返回 ≤1）时删除该键、取 Redisson 锁 `im:lock:message:private:max_seq`、回查库中该 `convKey` 的最大 `seq_no` 作为种子。**保存消息本身也走 Redisson 锁**（`@RedisLock(prefixKey="im:lock:message:private:save", key="#message.convKey")`，注释写明「加分布式锁是为了让数据库自增 id 和 seq_no 保持同序」） | `[VERIFIED]` `PrivateMessageServiceImpl.java` |
| F11 | **「已读」用两套完全不同的机制**：① 会话已读位点存 **Redis**，键 `im:readed:private:position:{sendId}:{recvId}`，值是**最大已读的 `id`**（不是 `seq_no`），TTL 60 天，回源则是「查该方向 status=READED 的最大 `id`」；② 同时把 `send_id=对方, recv_id=我, id <= messageId, status != RECALL(2)` 的行 `UPDATE` 成 `status=READED(3)` | `[VERIFIED]` `PrivateMessageServiceImpl.java`（`readedMessage` / `getMaxReadedId`） |
| F12 | 发消息时业务侧校验 **「是不是好友」**：`sendMessage` 里 `friendService.isFriend(...)` 为假则抛「您已不是对方好友，无法发送消息」 | `[VERIFIED]` `PrivateMessageServiceImpl.java`（`sendMessage`） |
| F13 | `FriendServiceImpl.bindFriend` 会**查 `im_user`**：`User friendInfo = userMapper.selectById(friendId); friend.setFriendHeadImage(friendInfo.getHeadImageThumb());` —— **`im_user` 里没有这一行就会 NPE** | `[VERIFIED]` `[.../service/impl/FriendServiceImpl.java]` |
| F14 | `isFriend` / `findFriendIds` 只查 `im_friend`，**不查 `im_user`** | `[VERIFIED]` `FriendServiceImpl.java` |
| F15 | 文件 URL 是**拼出来的永久公开 URL**，不是预签名 URL：`FileServiceImpl.generUrl` = `StrUtil.join("/", minioProps.getDomain(), minioProps.getBucketName(), getBucketPath(fileType), fileName)`，形如 `{domain}/{bucket}/{image|file|video}/{uuid.ext}`；`im_file_info.file_path` 存的就是这个完整 URL | `[VERIFIED]` `[.../service/impl/FileServiceImpl.java]` |
| F16 | 启动时 `FileServiceImpl.@PostConstruct init()`：**桶不存在就建桶，然后 `setBucketPublic(bucketName)`** —— 上游**假设桶是公开可读**的 | `[VERIFIED]` `FileServiceImpl.java` |
| F17 | MinIO 配置前缀是 **`minio.*`**：`endpoint`（内网 S3 地址）、`domain`（对外访问地址）、`accessKey`、`secretKey`、`bucketName`、`imagePath`(默认`image`)、`filePath`(默认`file`)、`videoPath`(默认`video`)、`expireIn`(默认 180 天) | `[VERIFIED]` `[.../resources/application-dev.yml]` + `application-prod.yml` |
| F18 | **过期文件会被真的删掉**：`FileExpireTask`（`@Scheduled(cron = "0 0 3 * * ?")`）每天 03:00 扫 `im_file_info`，把 `is_permanent = false` 且 `upload_time <= now - expireIn 天` 的记录从对象存储**删除**并删掉 `im_file_info` 行 | `[VERIFIED]` `[.../task/schedule/FileExpireTask.java]` |
| F19 | ⚠️ **`FileExpireTask` 对 URL 形状有硬假设**：`url.substring(filePath.indexOf(bucketName))` → `split("/")` → 取 `arr[1]`/`arr[2]`/`arr[3]`。**只要 `file_path` 不是 `{domain}/{bucket}/{path}/{name}` 这个形状（例如换成课安 `/api/files/...` 代理 URL），这个任务就会抛 `ArrayIndexOutOfBoundsException` 或删错对象** | `[VERIFIED]` `FileExpireTask.java` |
| F20 | 上传接口是 `POST /image/upload`（返回 `{originUrl, thumbUrl, width, height}`，参数 `file`、`isPermanent` 默认 **true**、`thumbSize` 默认 50KB）与 `POST /file/upload`（返回 URL 字符串） | `[VERIFIED]` `[.../controller/FileController.java]` |
| F21 | `auth-interceptor.exclude-paths` 里有 **`/*/upload`** 这一条 —— 命中的话上传接口就**不需要 token**。⚠️ `*` 能不能跨 `/` 匹配（即 `/image/upload` 是否真的被放行）**未证实**，见 §12，**必须实测** | `[VERIFIED]`（配置原文）/ 语义 `[NOT VERIFIED]` |
| F22 | `im-platform` 的 `server.port` = **8888**，无 `context-path`；`spring.profiles.active` 默认 `dev` | `[VERIFIED]` `[.../resources/application.yml]` |
| F23 | **数据库名在两份 profile 里不一致**：`application-dev.yml` 用 `im_platform_open`，`application-prod.yml` 用 `im_platform` | `[VERIFIED]` 两份 yml 原文 |
| F24 | `jwt.accessToken.expireIn=1800`、`jwt.refreshToken.expireIn=604800`，示例密钥 `MIIBIjANBgkq`（12 字节）/ `IKDiqVmn0VFU` | `[VERIFIED]` `application.yml` |
| F25 | `UserServiceImpl.login()` 成功后**会删除** `im:user:denied:{userId}`（解封态的清理在登录/刷新 token 时发生） | `[VERIFIED]` `[.../service/impl/UserServiceImpl.java]` |
| F26 | `register` 要求 `user_name` 唯一（`unique key idx_user_name`）、密码走 `PasswordEncoder` 加密、用户名/昵称要过敏感词 | `[VERIFIED]` `UserServiceImpl.register` + `docs/sql/im-platform.sql` |
| F27 | im-platform 侧的 Redis 键**全部是 `im:` 前缀**（`im:user:state`、`im:readed:private:position`、`im:message:private:max_id`、`im:message:private:max_seq`、`im:lock:*`、`im:cache:friend`、`im:group:member:max_version`、`im:friend:max_version`、`im:queue:user:banned` …） | `[VERIFIED]` `[.../contant/RedisKey.java]` |
| F28 | 前端 `im-uniapp/.env.js`：`BASE_URL = http://127.0.0.1:8888`（H5 改为 `"/api"` 走代理）、`WS_URL = ws://127.0.0.1:8878/im` | `[VERIFIED]` `im-uniapp/.env.js` |
| F29 | `im-web` 与 `im-uniapp` 是**同一个仓库里的目录**（不是两个独立仓库）；管理后台 `box-im-admin` 才是独立仓库 | `[VERIFIED]` `README.md` + GitHub contents API |
| F30 | 上游 README 的「本地启动」写的是「创建名为 `im_platform` 的数据库，并执行 `db/im_platfrom.sql`」—— 文件名拼写有误，实际文件是 `db/im-platform.sql` | `[VERIFIED]` `README.md` |

### 0.2 已经完成、本文**不再重复设计**的部分

以下均为**已完成并实测**的事实，本文只引用、不重新设计：

| 已完成项 | 事实 |
|---|---|
| `im-server` | Netty **8878**、WS 路径**写死 `/im`**；已部署；**实测**：握手 101 ✓、登录 `{"cmd":0}` ✓、在线槽位 `im:user:server_id:{userId}:{terminal}` ✓、私聊投递 `{"cmd":3}` ✓、踢线 `{"cmd":2}` ✓、封禁键 `im:user:denied:{userId}` 使连接被拒 ✓ |
| kean → im-server 队列投递 | 键**必须带 serverId**：`im:message:private:{serverId}` / `im:message:system:{serverId}`；实测队列被消费干净 ✓ |
| kean 侧适配层 `com.kean.im` | `ImTokenService`（签 box 兼容 token；**必须显式 HS256**，否则 64 字节密钥会被 jjwt 自动选成 HS512 而全线连不上）、`ImSenderService`（镜像投递 + 计数）、`ImKickService`（封禁联动）、`ImQueueMonitorService`（60s 巡检 + 邮件告警） |
| 消息模型对齐 | `chat_message` 已有 `seq_no` / `local_id` / `status(0未读/1已发送/2撤回/3已读)` / `read_at`；`chat_session` 已有 `last_seq_no` / `a_read_seq` / `b_read_seq`（Flyway **V34**，另有 V35） |
| box 建表 SQL | `docs/sql/im-platform.sql`（9 张 `im_*`，取自官方 master，blob SHA `74e22fd2c8b8a541abc2f9dc3667b159ade6abf4`），**独立库 `im_platform`** |
| 服务器现状 | Ubuntu 22 / 3.8G 内存（kean ≈276MB、im-server 上限 512m）；MySQL 8.4 容器；Redis 7.2.5 容器；RustFS（S3 兼容、桶 `kean`、**私有桶 + kean 的 `/api/files/**` 鉴权代理**）；nginx 容器；**kean 后端只监听 `172.17.0.1:8080`** |
| 已上线站点 | H5 `kean.college` ✓、下载站 `www` ✓、API `api.kean.college` ✓、管理端 `admin.kean.college` ✓ |

> 📌 **`docs/ops/im-migration.md` 与 `docs/ops/im-server-patch.md` 是既有事实文档**（im-server 的对接契约、
> 队列键、封禁键、部署与回滚），本文与它们**不冲突、不重复**。本文只讲 **`im-platform` 的上线**。

---

## 1. 目标与边界：迁什么、不迁什么

### 1.1 一句话结论

> **把 IM 的「会话 / 消息 / 未读 / 离线拉取 / 多端同步 / 群聊 / 富媒体」交给 box-im 的 `im-platform`
> 与其 `im_*` 表（REST + 表）；把「用户是谁」和 kean 的业务概念留在 kean。**

这是**换 IM 引擎的业务层**，不是换产品。用户不会感觉到「换了个 App」，只会感觉到「离线消息更全了、
多端更同步了、能发语音/文件/视频了」。

### 1.2 迁移范围表

| 能力 | 迁到 box？ | 归属 | 说明 |
|---|---|---|---|
| 会话（一对一会话） | **是** | `im_platform.im_friend`（+ `im_private_message.conv_key`） | box 的私聊「会话」是**双方 userId 拼出来的 `conv_key`**，不是一张会话表；会话列表靠 `im_friend` + 未读推导 |
| 消息收发与落库 | **是** | `im_platform.im_private_message` | 新消息一律走 `im-platform` 的 REST 与表 |
| 未读 / 已读 | **是** | 消息 `status(0/1/2/3)` + Redis `im:readed:private:position:*` | ⚠️ 「未读计数」box 侧**没有会话表计数器**，是客户端按「未读消息条数」算的。见 §6.3 |
| 离线消息拉取 | **是** | `loadOfflineMessage(minId)` | 这是**目标①的主要收益来源** |
| 多端同步 | **是** | `sendToSelf`（`IMTerminalType` 三终端 + `devId`） | 见 §6.4 与 §6.5 的冲突处理 |
| 群聊 | **是（阶段 D）** | `im_group` / `im_group_member` / `im_group_message` | kean **完全没有群概念**，属纯新增能力，不阻塞 A/B/C |
| 富媒体（语音/文件/视频） | **是** | `im_file_info` + `minio.*` 配置 | 见 §5 |
| **`sys_user`（用户）** | **否** | kean | box 的 `im_user` 只作为**影子用户**存在（见 §2） |
| 学校 / 校区 | **否** | kean | box **完全没有这些概念** |
| 任务 / 履约 / 申请 | **否** | kean | 同上 |
| 评价 / 举报 / 申诉 | **否** | kean | 同上 |
| 通知（公告类） | **否** | kean | 继续走 kean 的通知接口 + `RealtimePublisher` |
| 管理端 `web-kean` | **否** | kean | 管理端 IM 看板继续读 kean 的 `/api/admin/im/*` |
| kean 的业务 API | **否** | kean | 一行不改 |

### 1.3 驱动目标 → 本方案如何满足

| 目标 | 由谁满足 | **在哪一阶段兑现** | 验收方式 |
|---|---|---|---|
| ① 离线消息与多端同步更可靠 | `loadOfflineMessage`（按 `id` 游标 + 60 天窗口 + 「每个会话至少补一条」）+ `sendToSelf`（三终端同步） | **C**（写/读路径切换） | §3 阶段 C 的验收清单 ①⑤（即 §3.C.1 第 3、4 条） |
| ② 语音 / 文件 / 视频等富媒体 | `im_file_info` + `MessageType.AUDIO(3)/FILE(2)/VIDEO(4)` + 现有 RustFS | **D1** ⭐（**完全不在 A/B/C**） | §5 的验收清单 |

> ⭐ **排期要点（容易搞错）**：**用户的两项目标不是在 A/B/C 一次性交付的**。
> **①** 的能力在 **C** 提供，但要等**客户端放量完成**用户才真正用得上；
> **②** **整块都在 D1**，C 阶段一点都没有。
> 所以**不要用「目标已达成」去验收阶段 C** —— 那会漏掉 D 的排期（见 §3.0 的阶段总览）。
> 另外：**「全量换成 box」这个终态本身不增加任何新能力**，它增加的是「**账号也归 box**」（E 阶段）。

### 1.4 明确**不在**本方案内的事（避免误解）

> ⚠️ **本小节是「路径」的边界，不是「终态」的边界 —— 两者不同，务必先读 §1.5。**
> 下面 5 条描述的是 **6 个阶段（A~F）走完之前**不许顺手做的事，**不是**「永远不做」。
> 其中第 1、2 条会在 **F 阶段收尾**时被**有区别地推翻**：认证（登录/改密/找回/注销）**最终会迁走**，
> 而学校 / 任务 / 申请 / 评价 / 举报 / 通知与 `sys_user` 的业务字段**永远留在 kean**。
> 终态归属与「最终归谁」的完整清单见 **§10.5 终态对照表**。

- ❌ 不迁 `sys_user`、不做用户表合并、不做双写用户表（见 §2 的推荐方案）。
  > ⚠️ **路径期禁令（A~D 阶段有效）**：**不把 `sys_user` 的业务字段迁进 `im_user`**，也不做用户表的双写同步器。
  > ✅ **终态（F 阶段之后）**：`im_user` 会持有**认证字段**（账号 / 密码哈希）；`sys_user` 继续持有**业务字段**（学校 / 校区 / 角色 / 计数 / `status`）。
  > 两者靠 **「同一个 id」** 缝合，**不是**靠「同一张表」。详见 §10.5 与 §1.5 的 **L1 / L2 铁律**。
- ❌ 不改 kean 的登录 / JWT / `JwtAuthFilter` / `SecurityConfig`。
  > ⚠️ **路径期禁令（A~D 阶段有效）**：A~D 阶段**一行都不动**（这正是「IM 先行、认证殿后」的价值）。
  > ✅ **终态（E 阶段才会动）**：登录 / 改密 / 找回 / 注销会逐步挪到 box 的接口，**但必须按账号/比例灰度，
  > 且 kean 的登录要保底可用直到 100% 切换**。详见 §3 阶段 E 与 §12.1 的 U16~U25。
- ❌ 不把 `im_platform` 的表并进 kean 库（Flyway 与一次性 SQL 会互踩，见 `docs/ops/im-migration.md` §1）。
  > ✅ **这条终态也不变**：`im_platform` **永远是独立库**，跨库只用「同一个 id」对齐，**不建外键、不建映射列**。
- ❌ 不删 kean 的历史 `chat_message`（见 §4）。
  > ✅ **这条终态也不变**：历史消息**永远留在 `Kean.chat_message`**，只读保留 + 客户端两段式读取。
- ❌ 不替换 kean 的 `ws://<host>/ws/chat` 自研通道 —— 它在阶段 A/B 期间**继续存在**，退役是阶段 C 之后的事。
  > ⚠️ 补充终态口径：**退役的是 kean 侧「实时聊天 WS」这一段业务**；`/ws/chat` 上跑的**非聊天推送**
  > （`RealtimePublisher` 的 BANNED / NOTICE / 通知等）**没有 box 对等物，必须保留**（见 §10.5 的「通知 / 公告」行）。

---

### 1.5 ⭐ 终态与路径（**本节定义整份文档的终点，其余各节都是通往它的路径**）

> **用户已澄清的终态（原文）**：
> > **「目前是逐步迁移，目标是全量换成 box im。」**
>
> 即：**路径是逐步的（6 个阶段），终态是全量的**。
> 上一轮的文档按「**只迁 IM 业务层、用户体系不动**」写，**那是路径（A~D），不是终态**。
> 本节把终态讲清楚，其余各节都要按本节重新对齐。

#### 1.5.1 终态到底长什么样：「全量换成 box-im」的三层含义

「全量」不是一句话，要拆成三层，否则必然误判工作量：

| 层 | 内容 | 终态归属 | 是否需要动 kean 的业务表 |
|---|---|---|---|
| **第 1 层：IM 能力** | 会话 / 消息 / 未读 / 离线 / 多端 / 群聊 / 音视频 / 文件 | ✅ **全部归 box** | ❌ 不需要（A~D 阶段完成） |
| **第 2 层：认证能力** | 注册 / 登录 / 改密 / 找回 / 换邮箱 / 注销 / 封禁态判定 / token 签发 | ✅ **全部归 box**（**F 阶段之后账号真相在 box**） | ❌ **不需要 —— 前提是 id 复用（见 1.5.2）** |
| **第 3 层：业务能力** | 学校 / 校区 / 任务 / 履约 / 申请 / 评价 / 举报 / 申诉 / 通知 / 管理端 / 钱包 | ❌ **永远归 kean**（box 里**完全没有**这些概念） | —— |

> ⚠️ **核心矛盾（必须先承认它，方案才有意义）**：
> **box 完全没有「学校 / 校区 / 任务 / 申请 / 评价 / 举报 / 通知」这些概念。**
> 所以「全量换成 box im」**不可能**字面理解成「把课安的业务也换成 box 的」——
> box 那边**没有任何东西可以换**。真实的终态只有两种，见下一小节。

#### 1.5.2 ⚠️ 「全量换」必然面临的两种终态：E1 与 E2

| | **E1（不推荐 ✗）** | **E2（推荐 ⭐，本方案的终态）** |
|---|---|---|
| 一句话 | 业务表的用户外键**全部**从 `sys_user` 迁到 `im_user` | `im_user.id === sys_user.id` **同 id 复用**，业务表**一个外键都不用改** |
| 要动什么 | **全站数据要动一遍**：`chat_session`、`chat_message`、任务、申请、评价、举报、通知、管理端查询……凡是带 `user_id` 的表与索引、视图、JOIN、报表 | **一行都不动**。业务表继续 `JOIN sys_user`，而 `sys_user.id` 与 `im_user.id` 是同一个数 |
| 可逆性 | ⚠️ **不可逆**：全站外键搬完，回退要再搬一遍，且窗口内的新数据会分叉 | ✅ 天然可逆：因为没有「搬」这个动作，回退只是把认证入口切回 kean |
| 风险面 | 中高风险：任何一张表漏迁 = 该功能整体失效（权限、归属、统计全错） | ✅ 低：风险被限制在「认证入口」这一处，业务查询完全不受影响 |
| 谁当账号真相 | 必须是 box（否则两套 id 无解） | ✅ **谁当都无所谓** —— 这就是 id 复用的最大价值 |
| 与 §2 推荐的关系 | ❌ **与本仓既有实现直接冲突**（`ImTokenService` 现在就把 kean 的 userId 写进 `info.userId`/`aud`） | ✅ **与既有实现完全一致**，是现有做法的自然延伸 |

> ⭐ **结论：终态取 E2。** 它的含义是 ——
> **账号真相在 box，业务数据的外键仍指向 `sys_user.id`，而这两个 id 是同一个值。**
> 于是「全量换成 box im」这件事，**在数据层只发生了一次真正的切换：认证入口**，
> 而不是「全站数据搬一遍」。

#### 1.5.3 ⭐⭐ 三条铁律（**违反了任何一条，终态成本立刻翻倍**）

**🔒 铁律 L1：`im_user.id === sys_user.id`；不建映射表、不加映射列 —— 这是「全量迁移的前置条件」，不只是「推荐」**

> §2.3 已经把「同 id 复用」写成**推荐**；在「全量换成 box」的终态下，它要**升格为铁律**。理由：
>
> - **一旦引入映射表或映射列，终态就必须迁全站业务外键。**
>   因为业务表里的 `user_id` 此时指向的是 `sys_user.id`，而 box 的一侧认的是 `im_user.id`；
>   两者不再相等 ⇒ **每一个 JOIN、每一条按 userId 的查询、每一处权限判定都要过一次翻译**
>   ⇒ 就等于走进了 **E1**，而 E1 是「全站数据动一遍 + 不可逆」。
> - **id 复用让你「永远不需要做 E1」**：业务表一个外键都不用改，认证入口却已经可以交给 box。
> - **id 复用让任何一侧都能当账号真相**：这是它最被低估的价值 —— 它把「谁是真账号」从
>   一个**不可逆的数据决策**降级成一个**可随时回退的入口决策**。
>
> **落地含义（写进代码评审清单）**：
> - ❌ 禁止在 `sys_user` 上加 `im_user_id` / `box_user_id` 这类列；
> - ❌ 禁止新建 `user_id_mapping` / `im_user_map` 这类表；
> - ❌ 禁止让 `im_user` 自己分配 id（`AUTO_INCREMENT` 必须抬到 `sys_user.max(id)` 之上，§2.3）；
> - ❌ 禁止任何「按 `user_name` 反查 userId」的逻辑来当身份桥（`kean_{id}` 只是**展示名**，不是身份）；
> - ✅ 影子行的 `id` **必须**显式等于 `sys_user.id`，且这一步要有**校验**（见 **§10 的 A-3b**）。

**🔒 铁律 L2：认证必须最后迁（IM 先行、认证殿后）**

| 出问题的是谁 | 用户感知的后果 | 影响面 |
|---|---|---|
| **认证**（登录 / token / 改密 / 注销） | **整个 App 不可用** —— 进不去、什么都干不了 | ⚠️⚠️ **全站** |
| **IM**（会话 / 消息 / 离线 / 群聊） | **只有聊天不可用**，任务、申请、评价、通知、管理端全部照常 | ✅ 局部 |

> 因此「逐步迁移」的**正确顺序只有一个**：
> **IM 能力先行（A~D）→ 认证殿后（E）→ 收尾（F）**。
> 把认证放进早期阶段 = 用「整个 App 停摆」的风险去换一个「本来可以晚两个月再拿」的收益。

**🔒 铁律 L3：认证的灰度必须是「按账号 / 按比例」的，且 kean 登录保底可用直到 100% 切换**

- 认证切换**不允许**做「一次性全量切」：不允许「改配置即全量生效」这种粒度。
- 必须同时具备：**账号白名单**（先只切内部账号）、**比例灰度**（1% → 10% → 50% → 100%）、**单账号强制回退开关**。
- **kean 的登录链路（`AuthServiceImpl` + `JwtAuthFilter` + `SecurityConfig`）在 100% 切换完成之前不许删、不许停**；
  它就是认证的「保底通道」。详见 §3 阶段 E。

#### 1.5.4 ✅ 铁律检查（**任何一次发版前都能跑，30 秒出结论**）

> ⚠️ **先理解一个反直觉的点**：在**同 id 复用**下，「两侧 id 对不上」这件事**根本没有表达方式** ——
> 影子行的 `id` 就是我们插进去的那个 `sys_user.id`，不存在第二套编号，所以**没有「逐行比对」可做**。
> 因此守卫的目标不是「比对」，而是守着下面两件**可能被破坏**的事：
> ① **不许出现课安不认识的 id**（= box 的 `/register` 被打开了）；② **不许出现映射类结构**（= 有人加了映射列/表）。

```sql
-- =====================================================================
-- L1 守卫 A：影子行数量 <= sys_user 行数（物化是「按需」的，所以「<=」是正常的）
--              但**绝不允许出现 sys_user 里不存在的 id**（那就是映射被破坏了）
-- =====================================================================
-- ① 影子用户里有没有「课安没有的 id」？方法：把 im_user 的 id 导出，与 sys_user 比对。
--    服务端一条命令即可（不需要跨库 JOIN 权限）：
--      mysql -N -e "SELECT id FROM im_platform.im_user WHERE id <> 0" > /tmp/box_ids.txt
--      mysql -N -e "SELECT id FROM Kean.sys_user"                      > /tmp/kean_ids.txt
--      comm -23 <(sort /tmp/box_ids.txt) <(sort /tmp/kean_ids.txt)     # 期望：无输出
--    ⚠️ 有任何输出 = 影子用户里有课安不认识的 id（多半是 box 的 /register 没被关掉，见 §2.5 / H9）。

-- =====================================================================
-- L1 守卫 B：绝不存在「映射类」的列或表（结构性守卫，一条 grep 就能查）
-- =====================================================================
-- 在 kean 库执行（期望：无输出）
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema = 'Kean'
  AND (column_name LIKE '%im_user_id%' OR column_name LIKE '%box_user_id%' OR column_name LIKE '%im_u_id%');
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'Kean'
  AND (table_name LIKE '%user_map%' OR table_name LIKE '%user_mapping%' OR table_name LIKE '%id_map%');
```

> ✅ **把「守卫 B」加进 CI 或每次发版前的人工检查**：它是**成本最低、收益最高**的一条 ——
> 一旦有人顺手加了映射列，**在 A~D 阶段它完全没有症状**（因为那时不需要翻译），
> 但等走到 F 阶段就会突然变成「全站数据要动一遍」。**这种「无症状的架构债」必须靠规则拦截，不能靠人记得。**

#### 1.5.5 ✅ 与 §2 / §4 / §5 的三个既有推荐如何共存（**已逐条核对：不矛盾**）

| 既有推荐 | 与终态 E2 的关系 | 结论 |
|---|---|---|
| **§2 方案 B（kean 兼发 box 兼容 token + 影子用户按需物化）** | ✅ **完全一致，且更被强化**：`im_user.id === sys_user.id` 正是 E2 的落地方式。E 阶段把「谁签发 token」从 kean 挪到 box 时，**token 里的 userId 一个字都不用变** | **保留，升格为铁律 L1 的实现方式** |
| **§4 历史消息不搬（原表只读 + 客户端两段式读取）** | ✅ **完全一致**：终态「全量换成 box」指的是**能力与认证**全量归 box，**不等于要把历史行搬过去**。历史行的 `user_id` 本来就是 `sys_user.id`，与 box 侧同值，两段式读取**永远成立** | **保留，终态也不搬** |
| **§5 媒体统一走 RustFS + kean 签名 URL** | ✅ **一致**：媒体/对象存储与「账号真相在谁」**正交**。即使 F 阶段之后账号真相在 box，**签名 URL 仍应由 kean 签发**（因为桶是私有的、`FileUrlSigner`/`FileAccessGuard` 在 kean 里） | **保留，终态不变** |

> ⚠️ **唯一需要写清楚、否则会矛盾的一条**：终态里 **kean 仍然会是「媒体 URL 的签发方」，而 box 是「消息与账号的归属方」**。
> 这不是妥协，而是 §5.2 的直接推论（RustFS 私有桶 + box 只会拼永久公开 URL）。
> 详见 **§10.5 终态对照表**的「富媒体 / 对象存储」行 —— 那里标为 **「两者」**。

#### 1.5.6 一句话总结本节

> **终态 = 能力全量归 box + 认证全量归 box + 业务数据永远归 kean，**
> **靠「`im_user.id === sys_user.id` 同 id 复用」这一条把三者缝合起来；**
> **路径 = IM 先行（A~D）→ 认证殿后（E，按账号灰度）→ 收尾（F）。**

---

## 2. ⭐ 用户身份映射（最关键的一节）

> ⚠️ **读这一节之前请先读 §1.5**：本节的核心结论（**同 id 复用**）在终态里已经**从「推荐」升格为「铁律 L1」**。
> 本节给出的是**怎么做**（候选方案对比、SQL、联动矩阵），§1.5.3 给出的是**为什么它同时也是全量迁移的前置条件**。

### 2.0 问题陈述

box 的 `im-platform` 有自己的用户表 `im_user`（`id` 自增、`user_name` 唯一、`password` 必填）。
课安的用户在 `kean.sys_user` 里，`id` 是另一套编号。

**要回答的问题**：让 `im-platform` 认识「课安的用户」，代价最小的做法是什么？

### 2.1 三个候选方案对比

#### 方案 A：fork `im-platform`，把 `UserService` / `LoginController` 改成读 `kean.sys_user`

| 维度 | 评估 |
|---|---|
| 改动面 | **大**。至少 `UserServiceImpl`（`login` / `register` / `update` / `findUserById` / `search` 全部依赖 `im_user`）、`User` 实体、`UserMapper`、`FriendServiceImpl`（它 `userMapper.selectById` 查 `im_user`，见 F13）、`GroupMemberServiceImpl`，外加一个「课安侧用户查询接口」。 |
| 风险 | 中高。改的是**上游核心**，每次同步上游都要重新解冲突。 |
| 一致性 | **最好**。单一用户事实来源，不存在影子表不同步。 |
| 回滚 | 回到未 fork 的上游 jar 即可（但 fork 的代码要单独维护）。 |
| 长期成本 | 高。需要持续跟踪上游（作者明确要求 PR 提到 `v_4.0.0` 分支，见 README；master 会持续前进）。 |

#### 方案 B：**kean 兼发 box 兼容 token + 按需在 `im_user` 建影子用户**（本次已在用这条路，扩展到「让它认识这些用户」）

| 维度 | 评估 |
|---|---|
| 改动面 | **小**。kean 侧已有 `ImTokenService` 在签 token（已完成）；本方案只需新增「影子用户按需物化」+「friend 投影」两个小服务，以及 `ImKickService` 的封禁值类型修正。**`im-platform` 侧零代码改动**。 |
| 风险 | 中。多了一张影子表，存在「影子行与 `sys_user` 不一致」的窗口，需要靠「按需物化 + 单一写入方」把它压到最小。 |
| 一致性 | 好（**如果**坚持「影子行只在 kean 写、且只写一次」）。关键约束：**永远不要用 `im-platform` 自己的 `/register` 建用户**（会造出 kean 不认识的 id）。 |
| 回滚 | **最好**：删掉影子行即可，`im-platform` 本身不用回退。 |
| 可行性依据 | **F1 是本方案的基石**：`im-platform` 的 REST 鉴权**根本不查 `im_user`**，所以「没有影子行」也不会导致 401/403；影子行只为**少数几个确实会查库的路径**（加好友 F13、群成员、`/user/info`）而存在。 |

#### 方案 C：双写同步 `sys_user` → `im_user`

| 维度 | 评估 |
|---|---|
| 改动面 | 中（kean 侧加一个同步器或 CDC）。 |
| 风险 | **最高**。要处理：全量 + 增量、改名 / 换头像 / 封禁 / 注销的传播、失败重试、顺序、网络分区下的补偿。 |
| 一致性 | **最难**。双写必然有中间态；且 `im_user.user_name` 有唯一约束、`password` 非空，`sys_user` 的字段未必能直接填。 |
| 回滚 | 中（停同步器即可，但要清理已写脏的影子行）。 |

### 2.2 ✅ 推荐：方案 B（kean 兼发 token + 影子用户按需物化，`im_user.id === sys_user.id`）

**理由（按重要性排序）**

1. **有源码级证据支撑，不需要猜。** F1 已证实：`im-platform` 的鉴权层**完全不碰数据库**。这意味着
   「让 im-platform 认识这些用户」的**真实工作量只在少数几条查库的路径上**，而不是整个用户体系。
   方案 A 要 fork 的那一大片（`login`/`register`/`update`/`search`）**其实根本不在鉴权链路上**。
2. **改动面最小、且全部落在我们已经拥有并测过的代码里**（`com.kean.im` 包）。`im-platform` 侧**零改动**，
   上游升级 = 换 jar，没有合并冲突。
3. **回滚最干净**：影子表是可丢弃的派生数据。出问题删行即可，不牵动 `im-platform` 与 kean 主流程。
4. **一致性可以用结构性约束压住**，不需要分布式事务：
   影子行**只有一个写入方**（kean 的物化服务）、**只写一次**（幂等 `INSERT ... ON DUPLICATE KEY UPDATE` 且只补不覆盖业务字段）。
5. **`im_user.id === sys_user.id` 是「零映射成本」方案**：所有 box 的 REST、队列、Redis 键里的 `userId`
   与 kean 的 `sys_user.id` **同值同义**，双方任何一侧都不需要翻译表。本次已经在签的 token 就是这么做的
   （`info.userId` 与 `aud` 都写 kean 的 userId，`ImTokenService.issue` 已实现），继续沿用它是最小改动。

**为什么不用方案 A（尽管它一致性最好）**：F1 已经把方案 A 的「一致性优势」变成了「用不上的优势」——
鉴权不需要 `im_user`，所以 fork 换来的收益是**零**，代价却是永久的合并负担。**不要为了不需要的东西去 fork。**

**为什么不用方案 C**：F13 说明只有 `bindFriend` 这类边缘路径真的需要 `im_user` 行，
为这几条路径去建「双写同步器」（要处理改名/换头像/封禁/注销的全量传播与失败补偿）是**明显过度工程**。

**方案 C 唯一值得借鉴的部分**：它提醒我们「影子行确实要和 `sys_user` 对齐」。本方案用两条轻量规则达成同样的效果：
- **改名 / 换头像**：在 kean 改名的同一个方法里，顺手 `UPDATE im_user SET nick_name=?, head_image=? WHERE id=?`（一次主键更新，失败只记 WARN，不影响 kean 主流程）；
- **封禁 / 注销**：在 `ImKickService.deny/allow` 里顺手写 `is_banned` 与 Redis 键（见 §2.4）。

### 2.3 `sys_user.id` ↔ `im_user.id` 的映射怎么存

**推荐：同 id 复用（不建映射表、不加映射列）。**

> ⭐⭐ **终态升级说明（本轮新增）**：在「**全量换成 box im**」的终态下，下面这张表的第 2、3、4 行
> **不再只是「不推荐」，而是禁止**（**铁律 L1**，见 §1.5.3）。
> 根本原因：**一旦引入映射表或映射列，终态就必须把全站业务外键从 `sys_user` 迁到 `im_user`**，
> 也就是退化成 **E1**（全站数据动一遍、不可逆，见 §1.5.2）。
> 而 id 复用让你**永远不需要做 E1**，并且让「谁当账号真相」降级为一个**可随时回退的入口决策**。
> **发版前用 §1.5.4 的 L1 守卫 A / B 做结构性检查。**

| 做法 | 评价 |
|---|---|
| **✅ 同 id 复用**：`im_user.id = sys_user.id`，显式指定主键插入 | **推荐**。零翻译成本；token / 队列 / Redis 键天然一致；不需要在 kean 任何表上加列（`sys_user` **一行不改**）。 |
| ❌ 加列（`sys_user.im_user_id`） | 不要。给 kean 核心表加一个「只有 IM 用」的列，会把 IM 的耦合写进用户表，且列值需要回填与维护。 |
| ❌ 建独立映射表 | 不要。多一张表、多一次查询、多一处不一致的可能，而收益为零。 |
| ❌ 让 `im_user` 自己分配 id | **绝对不要**。一旦两边 id 不一致，token 里的 `userId`、在线槽位键、队列 `receivers`、`conv_key` 全部要翻译，是整个方案里最容易出错的地方。 |

**同 id 复用的必做动作 —— 把 `im_user` 的 `AUTO_INCREMENT` 抬到 `sys_user` 之上**

`im_user.id` 是 `bigint not null auto_increment primary key`。即使你显式插入了 `id=12345`，
MySQL 的 `AUTO_INCREMENT` 计数器**会**被抬到 `12346`（InnoDB 在显式插入更大值时会推进计数器），
但为了**万无一失**（例如某些批量导入 / 恢复场景），物化服务启动时执行一次：

```sql
-- 幂等；在 im_platform 库执行
INSERT INTO im_platform.im_user
    (id, user_name, nick_name, head_image, head_image_thumb, password, sex, is_banned, reason, type, signature)
VALUES
    (0, '__kean_system__', '系统', '', '', '{noop}__disabled__', 0, 0, '', 1, '')
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name);

-- 把自增计数器抬到 sys_user 的最大 id 之上，给影子行留出空间
-- ⚠️ 把 <N> 换成「kean.sys_user 的 max(id) + 10000」这个具体数字，SQL 不接受子查询
ALTER TABLE im_platform.im_user AUTO_INCREMENT = <N>;
```

> `id=0` 那一行对应上游的 `Constant.SYS_USER_ID = 0L`（系统用户），先占位可以避免它被自增分配出去。
> `password` 填 `{noop}__disabled__`：`type=1`（普通用户）且密码不可用 ⇒ **这个影子账号永远无法用 box 的 `/login` 登录**
> （`PasswordEncoder.matches` 必然失败）。这是刻意的：影子行只是「名片」，**认证只走 kean 签发的 token**。

**影子行怎么写（幂等，只补不覆盖）**

```sql
INSERT INTO im_platform.im_user
    (id, user_name, nick_name, head_image, head_image_thumb, password, sex, is_banned, reason, type, signature)
VALUES
    (:userId, :userName, :nickName, :headImage, :headThumb, '{noop}__disabled__', :sex, 0, '', 1, '')
ON DUPLICATE KEY UPDATE
    nick_name        = VALUES(nick_name),
    head_image       = VALUES(head_image),
    head_image_thumb = VALUES(head_image_thumb);
```

- `user_name` **必须全局唯一**（`unique key idx_user_name`）。推荐写成 `kean_{userId}`，
  天然唯一、可读、且**不可能与任何真实 box 用户名冲突**（前提：不开放 box 的 `/register`，见下）。
- 昵称 / 头像取 `sys_user` 的对应字段。
- ⚠️ **一个字都不能改的约束**：`ON DUPLICATE KEY UPDATE` 里**绝不要**更新 `password` / `is_banned` / `type` ——
  前者会破坏「影子账号不可登录」，后者会把封禁状态覆盖掉。

### 2.4 封禁 / 注销 / 改名 / 换头像怎么联动

**权威口径**：`sys_user.status` + kean 自己的 `kean:user:banned:{userId}` 是**唯一权威**；
`im_platform.im_user.is_banned` 与 `im:user:denied:{userId}` 都是**镜像**。
`docs/ops/im-server-patch.md` §2.7 已经指出这两个键不可互换，这里再补一条**部署 im-platform 才会出现的新约束**。

#### ⚠️ 部署 im-platform 的**前置硬要求**：封禁键的值类型必须改成数字

| | 上游 im-platform | 课安 kean（现状） |
|---|---|---|
| 写入方 | `UserBannedConsumerTask` | `ImKickService.markBanned` |
| Redis 模板 | `RedisTemplate<String,Object>` | `StringRedisTemplate` |
| **写入的值** | **数字**（`AuthInterceptor:51` 会 `(Integer)` 强转，见 F3） | **字符串 `"1"`** |
| 部署 im-platform 后 | `Integer type = (Integer) ...get(key)` → 读到 `String` → **`ClassCastException`** | —— |

**这是阶段 A 的第一个必做项**（`im-server` 只看 `hasKey`，所以现在没事；im-platform 一看值就炸）：

- 把 `ImKickService.markBanned` 改为用 `RedisTemplate<String,Object>` 写入 **`Integer 1`**（封禁）
  与 **`Integer 2`**（注销，对应 `IMForceLogoutType.UNREG`，让 im-platform 回「账号已注销」而不是「账号已被封禁」）；
- 改完后**必须**验证：`redis-cli -n 0 --raw GET im:user:denied:<userId>` 拿到的值能被 im-platform 读成 `Integer`。
  最可靠的验证不是看 Redis，而是**用被封用户调一次 im-platform 的 REST**，期望拿到「账号已被封禁」而不是 500。

#### 联动矩阵

| kean 侧事件 | 现在（无 im-platform） | **上了 im-platform 之后要追加的动作** | 是否已有代码 |
|---|---|---|---|
| **封禁** `AccountBanServiceImpl` | `ImKickService.deny()`：写 `im:user:denied:{userId}`（**值要改成 Integer**）+ 清 `im:user:state:{userId}` + 投 `im:user:force_logout:{serverId}` | 追加 `UPDATE im_user SET is_banned=1, reason=? WHERE id=?`；如果该用户有 `im_user` 行**但从来没连过 im-server**，也要写 `im:user:denied`（否则他能调 im-platform 的 REST） | 已有 `deny()`，只需扩展 + 改值类型 |
| **解封** | `ImKickService.allow()`：删 `im:user:denied:{userId}` | 追加 `UPDATE im_user SET is_banned=0, reason='' WHERE id=?` | 已有 `allow()`，只需扩展 |
| **注销** | 目前无 IM 侧动作 | 写 `im:user:denied:{userId} = Integer 2`（`UNREG` 语义）+ `UPDATE im_user SET is_banned=1, reason='账号已注销'` | **需要新增**（建议挂在 kean 现有的注销流程里，与封禁同一处调用风格） |
| **改名 / 换头像** | 无 | `UPDATE im_user SET nick_name=?, head_image=?, head_image_thumb=? WHERE id=?` | **需要新增**（一次主键更新，try/catch 吞异常） |
| **登录 kean** | 无 | 可选：顺手物化影子行（登录是最自然的「用户真的活着」时机） | 需要新增（或在「首次取 IM token」时物化，见 §3 阶段 A） |

> ⚠️ **`im_user.is_banned` 不是唯一的拦截点**：`AuthInterceptor` 只用 `im:user:denied`，
> 而 `is_banned` 只在 `UserServiceImpl.login()/refreshToken()`（即 box 自己的登录链路）里被检查。
> 因为**我们不走 box 的登录链路**，所以 **`im:user:denied` 才是真正生效的那一道**，
> `im_user.is_banned` 是纵深防御 + 让 box 管理后台看得见。两者都要写。

### 2.5 必须同步做的一件事：**关闭 im-platform 的自助注册**

`im-platform` 自带 `POST /register`（`LoginController`，且在 `exclude-paths` 里**不需要 token**，F21）。
如果它可从公网访问：

- 任何人可以建一个 `im_user`，拿到一个 `im_user.id`，**这个 id 会撞上课安用户的 id**（同 id 复用的代价）；
- 或者用 `user_name = kean_12345` 抢占影子用户名，导致我们的物化 `INSERT` 行为异常。

**处理（推荐，零代码）**：在 nginx 层把注册与 box 自身登录取票的路径**拒绝掉**：

```nginx
# 放在 location /im-api/ 之前，且必须用 = 精确匹配（见 §7.2 的完整片段）
location = /im-api/register     { return 404; }
location = /im-api/login        { return 404; }
location = /im-api/logout       { return 404; }
location = /im-api/modifyPwd    { return 404; }   # ⭐ 本轮新增
location = /im-api/refreshToken { return 404; }   # ⭐ 本轮新增（原因见下）
location = /im-api/user/update  { return 404; }   # ⭐ 本轮新增（原因见下）
```

> 我们**不需要** box 的 `/login`（token 由 kean 的 `GET /api/im/token` 签），也不需要 `/register`。
> 关掉它们不损失任何能力，只损失「被抢注」和「id 撞车」两个风险。
> `modifyPwd`（`/modifyPwd`）、`refreshToken` 也建议一并拒绝：密码归 kean 管，refresh 由 kean 签。

#### 2.5.1 ⭐ 本轮（终态全量版）新增的三条拒绝，及其理由

| 新增拒绝的路径 | 为什么**必须**拒绝（三条都是「终态引入的新风险」） | 注意 |
|---|---|---|
| `PUT /im-api/refreshToken` | ⚠️ **它有一个「解封」副作用**：源码里 `refreshToken` 成功后**会 `redisTemplate.delete("im:user:denied:{userId}")`**。双轨期若允许客户端直接调它，**被封禁的账号只要 refreshToken 没过期，就能把封禁键删掉** ⇒ 封禁被绕过（详见 §3.E.5 原则 3） | ⚠️ 它同时会先查 `im_user.is_banned`（若为 1 则抛错、不删键）；但**不要依赖这条纵深防御**，直接关门更可靠 |
| `PUT /im-api/modifyPwd` | 密码是**两侧各存一份**的（`sys_user.password_hash` / `im_user.password`）。若 box 能独立改密，会与 kean 的改密**各改一份** ⇒ 直接制造「旧密码还能登录」的漏洞（§3.E.2 P5 / §3.E.5 原则 5） | **E 阶段**若要把改密灰度到 box，必须是**「kean 代理 + 双写两侧哈希」**，而不是放开这个原生接口 |
| `PUT /im-api/user/update` | box 的 `update` 会**级联更新 `im_friend` 的好友昵称头像与 `im_group_member` 的群内昵称头像** ⇒ 这是一条**绕过 kean 的改名通路**；开了它，昵称/头像的真相就被撕成两半（§3.E.6 第 1 条） | **终态也保持拒绝**：昵称/头像的真相**永远在 kean 的 `sys_user`**，由 kean **单向投影**到 `im_user`（§2.4） |

> ✅ **一句话记法（双轨期通用）**：**「box 的写接口，除了 IM 消息，一律在 nginx 关门。」**
> 调不到的接口就不会「一侧生效」，这是**成本最低、收益最高**的一条安全措施。

---

## 3. 阶段划分（六阶段：A 地基 / B 双写灰度 / C 全量切换 / D 能力解锁 / **E 账号灰度切换** / F 收尾）

> **贯穿六阶段的一条铁律**：每一阶段结束时，**kean 的全部既有功能必须仍然可用**。
> 任何一步让 kean 的登录 / 通知 / 任务 / 文件读取受影响，都属于**该阶段失败**，按该阶段的回滚执行。
>
> ### 3.0 阶段总览与「每阶段的终态位置」⭐
>
> 下表的 **「终态位置」** 一列是关键：它回答「这一阶段做完之后，**这一块能力在终态里归谁**」。
> 归 box 的，做完就**不再回头**；归 kean 的，做完就**永远留在 kean**（不要再去动它）。
>
> | 阶段 | 名称 | 做什么 | **终态位置** | 可回滚性 | 人天 |
> |---|---|---|---|---|---|
> | **A** | 地基 | 部署 `im-platform` + `im_platform` 库 + 影子用户（**同 id**）+ 好友双向投影 | `im_platform` 库与 `im_user` **归 box**；**kean 库一行不动** | ✅ 最容易（停进程 + 摘 nginx） | 3 ~ 5 |
> | **B** | IM 双写灰度 | 写两处、读一处（读仍全在 kean） | 消息的**写入通路**开始由 box 承担，**读取与用户感知仍在 kean** | ✅ 容易（一个环境变量） | 5 ~ 8 |
> | **C** | IM 全量切换 | 读也切到 box；**自研 WS 聊天退役** | ✅ **会话 / 消息 / 未读 / 读路径全部归 box**（此后不再回头） | ⚠️ 最难（1~2 小时，且**必须先反向回填**） | 8 ~ 14 |
> | **D** | 能力解锁 | D1 富媒体（语音/文件/视频）→ D2 群聊 | ✅ **富媒体与群聊归 box**；⚠️ 媒体 **URL 的签发仍归 kean**（§5.3 / §10.5） | ✅ 容易（关前端入口） | D1 3 ~ 6；D2 10+ |
> | **E** ⭐ | **账号灰度切换（新增）** | 注册 / 登录 / 改密 / 找回 / 注销**逐步挪到 box 的接口**，按账号灰度 | ✅ **认证归 box**（终态账号真相在 box）；⚠️ **业务字段与 `status` 的权威仍在 kean** | ⚠️⚠️ **最危险**（全站入口；必须能**按账号**秒级回退） | **10 ~ 18** |
> | **F** | 收尾 | 账号真相在 box；业务仍在 kean（同 id，**无需改任何外键**） | ✅ 固化终态；列出**仍然留在 kean 的清单** | ✅ 容易（F 本身是「冻结」，不是「切换」） | 3 ~ 5 |
>
> **⭐ 用户提出的两项目标分别在哪一阶段兑现（不要记混）**：
> - **目标①（离线消息与多端同步）**：能力由 **C** 提供（`loadOfflineMessage` / `sendToSelf` 的验收在 §3.C.1 第 3、4 条），
>   **客户端放量完成**后用户才真正用得上；
> - **目标②（语音 / 文件 / 视频）**：**整块都在 D1**，**C 阶段一点都没有**。
>
> 所以**不要在阶段 C 就用「目标已达成」去验收** —— 那会漏掉 D 的排期。
> **「全量换成 box」这个终态本身不增加任何新能力**，它增加的是「**账号也归 box**」（E 阶段）。
>
> **⭐ 顺序铁律（§1.5 的 L2）在阶段表上的体现**：**A~D 都是 IM，E 才是认证**。
> 这不是排期偏好，而是风险排序：`A~D 出错 = 只有聊天不可用`，`E 出错 = 整个 App 不可用`。

### 阶段 A：地基（部署 im-platform，不改 kean 业务代码）

> 📍 **本阶段的终态位置**：`im_platform` 库、`im_user` 影子表、`im_friend` 投影**从此永久归 box**；
> **kean 库一行不动**，且**永远不会有「迁 kean 库」这一步**（因为终态取 E2，见 §1.5.2）。

- **要部署什么**：`im-platform` 用 systemd 跑起来（jar 由 `mvn -pl im-platform -am -DskipTests package` 产出），
  只监听内网，经 nginx 暴露在 `https://api.kean.college/im-api/`。
- **要改哪些代码**（本阶段**只有 2 处是"改已有文件"，其余是新增类**；其中第 1 处是**为了防炸**的必做项）：
  | 文件 | 类:方法 | 改什么 | 为什么 |
  |---|---|---|---|
  | `kean/src/main/java/com/kean/im/ImKickService.java` | `ImKickService:markBanned`（+ 注入改为 `RedisTemplate<String,Object>`） | 把封禁值从字符串 `"1"` 改成 **`Integer 1`**，并支持 `Integer 2`（注销） | F3：`AuthInterceptor` 会 `(Integer)` 强转，字符串会让 im-platform **每一个 REST 调用都抛 `ClassCastException`** |
  | `kean/src/main/java/com/kean/im/ImKickService.java` | 新增 `markUnregistered(Long)` | 写 `Integer 2` | F4：注销的提示语与封禁不同 |
  | `kean/src/main/java/com/kean/im/` | **新增** `ImShadowUserService` | 影子用户按需物化 + 改名/头像同步（§2.3 的 SQL）。**阶段 A 就要接上调用点**：`ImController` 取票时调一次（见下） | F13：box 的 `bindFriend` / `/user/info` 等路径会查 `im_user`，不物化就会 NPE 或查不到人 |
  | `kean/src/main/java/com/kean/im/ImController.java` | 取票接口 | **追加一行**（吞异常）：签发 token 之后调 `imShadowUserService.ensureShadowUser(userId)` | 让「取票」这个天然时机顺带把用户物化进 `im_user`；同时这也是**阶段 A 验收第 8 条**的验证手段 |
  | `kean/src/main/java/com/kean/im/` | **新增** `ImFriendProjectionService` | 把 kean 的会话关系投影进 `im_friend`（双向），并实现 `isFriend` 的判定口径 | F12：box 的 `sendMessage` 会查好友关系；F13：`bindFriend` 会查 `im_user` |
- **数据要不要动**：**要，但只在 `im_platform` 库**。
  ① 确认 `im_platform` 库与 9 张表已建（`docs/ops/im-migration.md` §2.2）；
  ② 执行 §2.3 的 `id=0` 占位行与 `AUTO_INCREMENT`；
  ③ `im_user` 影子行**本阶段只建「将要灰度的那批用户」**，不做全量。
  **kean 库一行不动**（不加表、不加列、不加迁移）。
- **⚠️ 本阶段的硬约束（§1.5 铁律 L1，终态的前置条件）**：**影子用户的 `id` 必须显式等于 `sys_user.id`**。
  具体到实现，下面三条**都不是「建议」而是验收项**：
  1. 物化 SQL **必须显式写出 `id` 列**（`INSERT INTO im_user (id, ...) VALUES (:userId, ...)`），
     **绝不**允许依赖 `AUTO_INCREMENT` 自己分配（那种写法在本地测试「看起来也对」，只有在 id 错位后才暴露）；
  2. `im_user.id = 0` 的系统占位行**必须**在本阶段先建（§2.3），否则自增可能把 0 分配出去；
  3. `AUTO_INCREMENT` **必须**抬到 `sys_user.max(id)` 之上（§2.3），并**留下实测记录**。
  > ❌ 同时**禁止**：给 `sys_user` 加 `im_user_id` 列、新建任何映射表、按 `user_name` 反查身份。
  > ✅ 发版前跑一次 §1.5.4 的 **L1 守卫 A / B**（一个 `comm`、两条 `information_schema` 查询）。
- **验收清单**（全部可 curl / 可查库，见 §3.A.1）
- **回滚**：停 `im-platform` + nginx 摘掉 `/im-api`；`im:user:denied` 的值类型改动**必须一起回退**
  （否则 im-server 仍能读，但 im-platform 若已被停掉就无影响 → 实际上**这一步单独回退是安全的**，
  因为 im-server 只看 `hasKey`）。kean 库无需回滚。
- **预估人天**：**3 ~ 5 人天**（其中部署 + 存储打通 ≈2 人天，影子用户与 friend 投影 ≈2 人天，联调 ≈1 人天）。

#### 3.A.1 阶段 A 的验收清单（可直接粘贴）

```bash
# ---- 0) 前置：这些名字要先确认（不同部署可能不同）----
docker ps --format '{{.Names}}' | grep -Ei 'mysql|redis|rustfs|nginx'
# 本仓 compose 里的名字是 kean-mysql / kean-redis / kean-rustfs；
# 但 docs/ops/im-migration.md（已实测）用的是 mysql_nzpx-mysql_nzPX-1。
# ⚠️ 以 docker ps 的真实输出为准。下面统一用这三个变量，请先赋值：
export MYSQL_CTR=$(docker ps --format '{{.Names}}' | grep -Ei 'mysql' | head -1)
export REDIS_CTR=$(docker ps --format '{{.Names}}' | grep -Ei 'redis' | head -1)
export REDIS_PASSWORD='<Redis 密码，取自 /opt/kean/.env.prod 的 REDIS_PASSWORD>'
export NX_CTR=$(docker ps --format '{{.Names}}' | grep -Ei 'nginx' | head -1)
echo "$MYSQL_CTR / $REDIS_CTR / $NX_CTR"

# ---- 1) 9 张表在（应恰好 9 行）----
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES FROM im_platform"'

# ---- 2) im-platform 进程活着 ----
# ⚠️ im-platform 的 pom **没有** spring-boot-starter-actuator（已核对 im-platform/pom.xml），
#    所以它**没有 /health、没有 /actuator/**。用下面这条判断"服务在不在"：
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8888/user/self
#    期望 401/403（= Tomcat 在跑、AuthInterceptor 拦下了），
#    000 = 进程没起或端口不对；404 = context-path/前缀写错（见 §12 U8）
#    进程级判据（更直接）：
systemctl is-active im-platform        # 期望 active

# ---- 3) 经 nginx 的路径通（期望 401/403，说明"服务在、鉴权在"，不是 502/504）----
curl -s -o /dev/null -w '%{http_code}\n' https://api.kean.college/im-api/user/self

# ---- 4) 注册与 box 登录取票已被关掉（期望全 404）----
for p in register login logout modifyPwd refreshToken user/update; do
  printf '%-14s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code}' -X POST https://api.kean.college/im-api/$p)"
done
# 期望：**六个全部 404**。
#   ⚠️ 前三个（register/login/logout）是防「抢注 + id 撞车」；
#   ⭐ 后三个（modifyPwd/refreshToken/user/update）是终态新增的**安全项**，理由见 §2.5.1 ——
#      ⚠️ 其中 refreshToken 若开放，被封禁账号可借它删掉 im:user:denied 键（绕过封禁）。
#   ⚠️ 任何一个返回 200/400/405 都说明 location 的 `=` 精确匹配写错了（见 §7.2）。

# ---- 5) 上传接口**必须**要 token（如果返回 200，说明 exclude-paths 的 /*/upload 生效了，见 F21/§12）----
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.kean.college/im-api/image/upload
# 期望 401/403。⚠️ 若返回 400/500 这类"不是鉴权拦下的"码，去 im-platform 日志确认原因。

# ---- 6) 用 kean 签的 token 调一次受保护接口（期望 200）----
#   先从 kean 取票（需要 kean 的登录态；下面 <KEAN_TOKEN> 是课安自己的 access token）
curl -s -H "Authorization: Bearer <KEAN_TOKEN>" https://api.kean.college/api/im/token
#   把返回的 data.accessToken 拿去调 im-platform（注意头名是 accessToken，不是 Authorization）
curl -s -H "accessToken: <BOX_ACCESS_TOKEN>" https://api.kean.college/im-api/user/self

# ---- 7) 封禁键的值是数字（F3 的前置）----
#   先封禁一个测试账号（走 kean 的管理端或既有封禁流程），然后：
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 OBJECT ENCODING im:user:denied:<被封的userId>
#   期望 "int"（而不是 "embstr"/"raw"）。这是 im-platform 不会抛 ClassCastException 的直接证据。
#   ⚠️ 若还是 "embstr"/"raw"，说明第 1 处代码改动没生效 —— **不要继续**，见 §8.1 H3。

# ---- 8) 影子用户已物化，且 id 与 kean 一致 ----
#   触发方式：用测试账号正常登录 kean，然后调一次取票接口（这会触发 ensureShadowUser）
curl -s -H "Authorization: Bearer <KEAN_TOKEN>" https://api.kean.college/api/im/token >/dev/null
#   然后查库：
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, user_name, nick_name, is_banned FROM im_platform.im_user ORDER BY id LIMIT 20"'
#   同时抽查 kean 侧同一个 id：
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, nickname FROM Kean.sys_user WHERE id = <上一步看到的某个 id>"'
#   期望：① 该用户的行存在；② 两边 id 相同；③ 昵称相同；④ user_name 形如 kean_<userId>。
#   ⚠️ 第 ② 条是 §1.5 铁律 L1 的验收点，必须**显式看到「同一个数」**，不接受「看起来差不多」。
#      更强的写法（不用肉眼比对）——把两侧 id 各导一份再比对：
#        docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
#          "SELECT id FROM im_platform.im_user WHERE id <> 0"' | sort > /tmp/box_ids.txt
#        docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
#          "SELECT id FROM Kean.sys_user"' | sort > /tmp/kean_ids.txt
#        comm -23 /tmp/box_ids.txt /tmp/kean_ids.txt      # 期望：**无输出**
#      「有输出」= 影子表里有课安不认识的 id ⇒ 多半是 box 的 /register 没被关掉（见 §2.5 / H9），**停下排查**。
#   再验证幂等（重复取票不应产生第二行、也不应报错）：
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) AS rows_for_user FROM im_platform.im_user WHERE user_name = \"kean_<userId>\""'
#   期望：1
```

> ⚠️ 第 5 条是**本阶段最容易被忽略的安全项**。F21 的 `/*/upload` 是个**过宽的排除规则**，
> 而且 `*` 在 Spring 的 `AntPathMatcher` 里通常不跨 `/`（**未证实**，见 §12）。
> 无论结论如何，**都建议在 nginx 层把上传路径显式限制为「必须带 accessToken」或干脆只允许 kean 的后端调用**。

---

### 阶段 B：双写灰度（写两处，读一处 —— 读仍然全部走 kean）

> 📍 **本阶段的终态位置**：双写本身**不是终态**（终态里没有「双写」这个概念，只有 box 一处写）。
> 它的终态价值在于：**把「kean 侧适配层」这套代码与观测打磨到位**，让阶段 C 的切换只改「谁读」。
> ⚠️ ⚠️ **双写必须在阶段 C 结束时彻底关掉**（`KEAN_IM_PLATFORM_ENABLED=false`），
> 否则终态会长期挂着一条「两处写、一处读」的冗余通路 —— 它没有收益，却持续制造分叉风险。

**原则：kean 仍是唯一的事实来源与唯一的读路径；im-platform 只是「影子」，写进去但没人读。**
这样做的价值是：**把「写」的通路先在真实流量下跑通并暴露问题，而任何时候回滚都不影响用户。**

- **要部署什么**：kean 后端（包含下面这些代码改动）；`im-platform` 保持阶段 A 的状态。
- **要改哪些代码**：
  | 文件 | 类:方法 | 改什么 |
  |---|---|---|
  | **新增** `kean/.../im/ImPlatformWriter.java` | `ImPlatformWriter` | **直连 `im_platform` 库的写通道**（kean 的一个独立 `DataSource`，只写不读）。方法 `writePrivateMessage(seqNo, localId, sendId, recvId, convKey, content, type, status, sendTime)` —— **显式指定 `seq_no`**，从而跳过 im-platform 自己的 `getNextSeqNo`。**这是 §6.3.2 第 1 步的落地方式**：不 fork im-platform、不用它的 `POST /private/message`（那条路径会自己分配序号，必然与 kean 分叉） |
  | **新增** `kean/.../im/ImPlatformClient.java` | `ImPlatformClient` | **读/辅助通道**的 HTTP 客户端（内网 `http://127.0.0.1:8888`）：用于已读上报（`readedMessage`）与将来阶段 C 的读代理。头 `accessToken` = `ImTokenService` 刚签的 token。**所有异常吞成 `log.warn`，绝不外抛**（与 `ImSenderService` 同一约束） |
  | `kean/.../im/ImSenderService.java` | `ImSenderService.sendPrivate` | 在**既有**「写 box 队列」之后**追加**一次 `imPlatformWriter.writePrivateMessage(...)`（**复用已传入的 `seqNo` / `localId` 参数**，所以 `ChatServiceImpl.send` 一行不用改）；新增计数器 `platformPushed` / `platformFailed`，并纳入 `snapshot()` |
  | `kean/.../service/impl/ChatServiceImpl.java` | `ChatServiceImpl.send` | **不改**。双写由 `ImSenderService` 内部完成，保持「调用方只调一次」 |
  | `kean/.../service/impl/ChatServiceImpl.java` | `ChatServiceImpl.markRead` | 在既有 READ 推送之后**追加**一次 `imPlatformClient.readed(sessionId, maxSeq)`（映射到 im-platform 的已读接口）。同样吞异常 |
  | `kean/.../im/ImShadowUserService.java` | `ensureShadowUser(Long)` | 物化影子行（§2.3 的幂等 SQL）。**调用点固定在两处**：`ImController`（取 IM token 时）+ `ChatServiceImpl.open`（建立会话时） |
  | `kean/.../service/impl/ChatServiceImpl.java` | `ChatServiceImpl.open` | **追加一行** `imShadowUserService.ensureShadowUser(userId); ensureShadowUser(peerUserId);`（在既有 `blacklistService.assertCanInteract` 之后） |
  | `kean/.../im/ImController.java` | `ImController`（取票接口） | 在**签发 token 之后**追加一次 `imShadowUserService.ensureShadowUser(userId)`（吞异常）。**这也让阶段 A 可以用「取一次票」来验证物化链路** |
  | **新增**配置项 `kean.im.platform-enabled` | 环境变量 `KEAN_IM_PLATFORM_ENABLED`（默认 **false**） | **双写总闸门**。⚠️ 它与既有的 `KEAN_IM_MIRROR_ENABLED` **不是一回事**：后者管「kean → im-server 队列」，本项管「kean → im-platform 双写」。两者互相独立，回滚时**只需关本项** |
  | **新增**配置项 `kean.im.platform-percent` | 环境变量 `KEAN_IM_PLATFORM_PERCENT`（默认 **0**） | 百分比灰度（0~100）。**两个闸门都满足才双写** |
- **数据要不要动**：**只增不改**。写进 `im_private_message` / `im_friend` / `im_user`；
  **不回填历史**、**不修改 kean 任何表**、**不修改任何已有行**。
- **验收清单**（§3.B.1）
- **回滚**：`KEAN_IM_PLATFORM_ENABLED=false` → 立刻停止一切双写（秒级，不用改代码、不用回退版本）。
  `im_platform` 库里已有的影子数据**可以原样留着**（没人读），也可以清空（见 §3.B.2）。
- **预估人天**：**5 ~ 8 人天**（客户端 HTTP + 字段映射 ≈2，已读映射 ≈1，影子/好友投影 ≈2，灰度开关与观测 ≈1，联调 ≈2）。

#### 3.B.1 阶段 B 的验收清单

```bash
# ---- 1) 双写真的发生了（发一条私聊后，im_private_message 应多一行）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, local_id, seq_no, send_id, recv_id, conv_key, type, status, send_time
  FROM im_platform.im_private_message ORDER BY id DESC LIMIT 5"'

# ---- 2) 关键：双写的 seq_no 与 kean 侧**逐条一致**（不允许分叉）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT c.id AS kean_id, c.seq_no AS kean_seq, c.local_id,
         p.id AS box_id,  p.seq_no AS box_seq
  FROM Kean.chat_message c
  JOIN im_platform.im_private_message p ON p.local_id = c.local_id
  WHERE c.seq_no IS NOT NULL
  ORDER BY c.id DESC LIMIT 20"'
#   期望：kean_seq == box_seq 的比例 = 100%。⚠️ 任何一条不等都必须在放量前查清（见 §8 高危项）

# ---- 3) local_id 没有被截断（kean 的兜底 localId 恰好 32 位）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT MAX(CHAR_LENGTH(local_id)) AS max_len FROM im_platform.im_private_message"'
#   期望 <= 32（建表是 varchar(32)）。超过说明被数据库截断，会破坏幂等。

# ---- 4) 好友投影是双向的 ----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) FROM im_platform.im_friend WHERE user_id=<A> AND friend_id=<B> AND deleted=0"'
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) FROM im_platform.im_friend WHERE user_id=<B> AND friend_id=<A> AND deleted=0"'
#   期望：都是 1

# ---- 5) 双写失败不影响业务（关键回归）----
#   把 im-platform 停掉，然后在客户端连续发 5 条消息：
systemctl stop im-platform
#   期望：客户端**全部发送成功**、kean 的 chat_message 全部入库、/ws/chat 气泡正常、
#         kean 日志有 [IM 平台双写] ... 失败 的 WARN，但**没有任何 500**
systemctl start im-platform

# ---- 6) 灰度闸门生效 ----
#   把 KEAN_IM_PLATFORM_ENABLED=false 后发消息，im_private_message **不应**新增行
```

#### 3.B.2 阶段 B 的清理（清空影子数据）

```bash
# ⚠️ 只在「确认没人读 im_platform」的阶段 B 使用。阶段 C 之后执行这个 = 删用户消息！
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  DELETE FROM im_platform.im_private_message;
  DELETE FROM im_platform.im_group_message;
  DELETE FROM im_platform.im_friend;   -- 影子好友关系，可由 kean 重新投影
  -- im_user 建议**保留**（昵称/头像已物化，重建成本虽低但没必要）
  -- 同时清掉 box 的序号与位点缓存，避免与库不一致：
  "'
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 --scan --pattern 'im:message:private:max_seq:*' | \
  xargs -r docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 DEL
# ⚠️ 不要用 KEYS（阻塞 Redis 单线程，会卡住 im-server 的 leftPop），见 docs/ops/im-monitoring.md §2.1
```

#### 3.B.3 ✅ B-3 已实现：未读来源可切换 + markRead 双写 + 投影回 kean + 比对告警

> 📍 **本小节的定位**：它是 §6.3.3「未读以谁为准」在**阶段 B 内部的提前落地**，
> 但**不改变**「同一时刻只有一个未读**展示**来源」这条原则 ——
> 只是把展示来源从 kean 的计数器换成了 box 的消息 `status`，切换靠一个开关。

**做了什么（四处，全部只动 `kean/`，接口形状与客户端一字未改）**

| # | 内容 | 文件:类:方法 |
|---|---|---|
| ① | **未读来源可切换**：新增 `kean.im.unread-source`（`kean` 默认 / `box`）。值为 `box` 时，`listMine` / `detail` / `unreadCount` 的未读数改由「box 的消息 `status`」推导 | **新增** `kean/.../im/ImUnreadQueryService.java`:`ImUnreadQueryService.unreadCount / unreadCounts` |
| ② | **`markRead` 双写 + 投影回 kean**：先写 box（权威），再照旧写 kean 的位点、`chat_message.status=3`、`a_unread/b_unread`，READ 事件形状不变 | `kean/.../service/impl/ChatServiceImpl.java`:`ChatServiceImpl.markRead`（第 0 步）+ `ImUnreadQueryService.markReadInBox` |
| ③ | **kean 的 `a_unread`/`b_unread` 继续写**（原则 2）：`box` 模式下它们不再是展示来源，但**一个字段都没少写** —— 回退时数据是热的 | `ChatServiceImpl.send` / `markRead`（**未改动**，只是语义从「唯一来源」变成「回退储备」） |
| ④ | **比对器扩展**（只读、默认关闭、跟随既有 compare 开关）：追加「未读数比对」，对最近活跃会话比 kean 的计数器与 box 推导值，不一致即 WARN + 邮件告警 | `ImMessageMirrorService.runCompareRound` → `compareUnread` / `alertCompareUnreadMismatch` |

**box 未读的确切口径（本轮实现所用的定义）**

```sql
-- 「我」在某个会话里的未读数（conversation key = min(userA,userB) + '_' + max(...)，与 ConvUtil.buildConvKey 同源）
SELECT COUNT(*) FROM im_platform.im_private_message
 WHERE recv_id = :me AND conv_key = :convKey AND status < 3;
-- 已读回写（markRead）：
UPDATE im_platform.im_private_message
   SET status = 3
 WHERE recv_id = :me AND conv_key = :convKey AND seq_no <= :maxSeq AND status < 3;
```

- `status`：**0 PENDING / 1 DELIVERED / 2 RECALL / 3 READED**（已核实）⇒ 未读 = `status < 3`；
- **没有 `read_time` 列**（已核实）⇒ 已读只有 `status = 3` 这一个标记；
- 维度选 **`(recv_id, conv_key)`** 两列一起：`recv_id` 排掉「我发出的消息」，`conv_key` 把范围锁在本会话；
- ⚠️ **未证实项**：`/opt/boxim-src/box-im/.../PrivateMessageServiceImpl.java` 不在本机，
  因此「box 自己是不是也按 `status < 3` 算未读」这一点**未逐字核对**，是按 kean 现有语义 + 已核实的
  `status` 取值实现的。若拿到源码后口径不同，**只需改 `ImUnreadQueryService` 里的 `UNREAD_PREDICATE` 一处**。

**怎么切、怎么回退**

```bash
# ⚠️ 硬门槛（先跑，再切）：执行 §3.B.4 的「两边未读一致性」SQL，必须返回 0 行。
#    若历史回填把 status 写错了（现象见 §3.B.4），先用那里的一次性对齐开关跑一次，再复核一遍。

# 切到 box（未读展示改由 box 的消息 status 说话）——前面必须先开着 B-1 的 message-mirror
KEAN_IM_UNREAD_SOURCE=box      # 等价配置项 kean.im.unread-source=box；默认 kean（不配即此）
systemctl restart kean

# 回退（把开关设回 kean 并重启即可；kean 侧的 a_unread/b_unread 与 chat_message.status
#       一直在写，所以回退后不需要任何回填/重放）
KEAN_IM_UNREAD_SOURCE=kean
systemctl restart kean
```

**启动日志（照项目风格，明说「到底生效了没有」）**

```text
[IM 未读来源] 配置 kean.im.unread-source=「box」→ 解析结果=true（未读取自 box 的消息 status），
实际生效=true；box 读通道=可用，box 写事务=REQUIRES_NEW（独立事务）；目标表=im_platform.im_private_message
（主 DataSource，跨库全限定名，不新建 DataSource）；未读判据=status < 3；已读回写=...；
⚠️ 未读来源=box 时 kean 的 a_unread/b_unread 仍继续维护（回退要用），
回退方式=把 kean.im.unread-source 设回 kean 并重启 kean
```

**验收（切换后必看的三条）**

```bash
# 1) 接口口径一致：会话列表每条的 unreadCount 与总数接口对得上（都由 box 推导）
curl -s -H "Authorization: Bearer $KEAN_TOKEN" http://127.0.0.1:8080/api/chats | head -c 500
curl -s -H "Authorization: Bearer $KEAN_TOKEN" http://127.0.0.1:8080/api/chats/unread-count

# 2) 与 box 直接推导的结果逐会话比对（期望：两侧数字相同）
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT conv_key, recv_id, COUNT(*) AS box_unread
  FROM im_platform.im_private_message WHERE status < 3 GROUP BY conv_key, recv_id"'

# 3) 点开会话后，box 侧那批行确实变成 3（且 kean 的 a_unread/b_unread 也归零）
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT seq_no, send_id, recv_id, status FROM im_platform.im_private_message
  WHERE conv_key = \"<小id>_<大id>\" ORDER BY seq_no DESC LIMIT 10"'
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT a_unread, b_unread, a_read_seq, b_read_seq FROM Kean.chat_session WHERE id = <sessionId>"'
```

**故障时的行为（三条，都是刻意设计的）**

1. **box 查不通 / 表没了**：未读查询只 `log.warn`（同一原因只提示一次），**逐个会话回退**到
   kean 的 `a_unread/b_unread` —— 聊天页照常打开，**不会 500、不会白屏**；
2. **box 已读回写失败**：kean 侧的位点、`chat_message.status=3`、未读清零**照旧执行**
   ⇒ 用户点开会话的体验不受影响，且回退数据仍然是热的；
3. **事务隔离**：box 的写走 `REQUIRES_NEW` 的独立事务（与 B-1 的 `ImMessageMirrorService` 同一套做法），
   box 的 SQL 错误**不可能**把 `ChatServiceImpl.markRead` 的 kean 事务标记成 `rollbackOnly`。

> ⚠️ 与 §6.3.3 表格的关系：那张表说的「阶段 C 时 kean 的 `a_unread`/`b_unread` 必须停止 +1 并清零」
> **仍然成立且未被本小节改变** —— 本小节只是让**阶段 B 期间的展示口径**提前收敛到 box，
> 并且**刻意保留** kean 计数器的写入，作为一键回退的储备。到阶段 C 再按 §6.3.3 把它们停下。

#### 3.B.4 ⚠️ 已知坑（**真实发生，线上实测**）：历史回填把消息一律写成 `status = 1`，切到 box 后凭空多出未读

> 📍 **性质**：这是 B-1（双写）与 B-3（未读切源）之间的**数据面耦合缺陷** ——
> 代码不报错、比对不报警（条数一致），但**用户一切换就看到假未读**。
> 它同时也是「为什么切源前必须先做一次全量数据比对」这个硬门槛的直接来历。

**① 现象（实测数据）**

```text
kean 会话2：a_unread=0   b_unread=0   a_read_seq=49   b_read_seq=54   last_seq=54
box  表   ：按 status < 3 数出来 → 用户3 未读 = 24 ✗   用户5 未读 = 30 ✗
```

- kean 侧两边未读都是 **0**（消息确实都被读过了），box 侧却算出 **24 / 30**；
- **没有任何报错**：B-1 的条数比对一致、日志无 WARN、无告警邮件；
- 只有当 `kean.im.unread-source` 被切成 `box` 时才会暴露 ⇒ 两个用户会**突然看到 24 / 30 条未读**。

**② 根因**

- `ImMessageMirrorService` 的补偿器 `compensate()` 在回填「kean 有、box 没有」的**历史消息**时，
  把 `im_private_message.status` **无条件写成常量 `1`**（`DELIVERED` = 已送达/未读），
  **完全不管这条消息在 kean 侧是否早已被读**；
- 于是 box 里 `status < 3` 的条数凭空变大。实测那一轮：60 条历史消息全被写成 `1`。

**③ 修复（已在 `kean/` 代码内）**

| 动作 | 位置 | 说明 |
|---|---|---|
| 插入时**按接收方的会话已读位点判定** | `kean/.../im/ImMessageMirrorService.java`:`buildArgs` → `boxStatusOf` | `seq_no <= 接收方在该会话的已读位点`（`user_a_id`→`a_read_seq` / `user_b_id`→`b_read_seq`）⇒ 写 **`3`**（READED），否则写 **`1`**（DELIVERED）。位点 `NULL`、`seq_no` `NULL`、或推不出接收方 ⇒ **一律当未读（`1`）**；判据与 B-3 的 `markReadInBox`（`recv_id=本人 AND seq_no <= 位点 AND status < 3`）**同源**，不会出现「算得出、清不掉」 |
| 正写路径（新消息）同样走这一处判定 | 同上（`mirrorPrivate` → `buildArgs`） | 新消息的 `seq_no` 必然大于位点 ⇒ 仍然是 `1`，与改动前一致 |
| **幂等重放绝不覆盖** `status` | 同上 `UPDATE_CLAUSE` | `ON DUPLICATE KEY UPDATE` **刻意不含** `status`/`seq_no`/`content` ⇒ 重复补写不会把 box 侧已推进的已读状态改回去（这是「插入时算对、重复时不动」的分工） |
| 已写错的历史行**一次性对齐** | 同上 `repairReadStateFromKean()`（开关 `kean.im.message-mirror-repair-read-state-enabled`，**默认 false**） | 与运维手工执行的那条 SQL 等价、**幂等**、**只把未读改成已读**（`AND status < 3`，绝不反向）。⚠️ 它复用主写通道，所以**必须先开着** `kean.im.message-mirror-enabled`；跑完请把开关改回 `false` |

```bash
# 一次性对齐（只跑一次；跑完立刻改回 false 并重启）
KEAN_IM_MESSAGE_MIRROR_ENABLED=true
KEAN_IM_MESSAGE_MIRROR_REPAIR_READ_STATE_ENABLED=true      # 等价配置项 kean.im.message-mirror-repair-read-state-enabled
KEAN_IM_MESSAGE_MIRROR_REPAIR_READ_STATE_DAYS=30           # 默认 30：只扫「最近 N 天内更新过」的会话
systemctl restart kean
# 启动日志会明确写出：一次性已读对齐 repairReadState enabled=true → 实际会执行=true；窗口 30 天 ...
# 跑完日志：一次性已读对齐完成：扫描会话 N 个 → 有改动的会话 X 个、共 Y 行由「未读」改成 status=3
# 然后立刻改回去（对齐是幂等的，留着也只会是 0 行，但不要让它常驻）：
KEAN_IM_MESSAGE_MIRROR_REPAIR_READ_STATE_ENABLED=false
systemctl restart kean
```

**④ ⭐ 验收命令：一条 SQL 比对两边未读（切换前必须跑，必须 0 行）**

```bash
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
SELECT * FROM (
  SELECT s.id AS session_id, s.user_a_id AS user_id,
         COALESCE(s.a_unread, 0) AS kean_unread,
         (SELECT COUNT(*) FROM im_platform.im_private_message p
           WHERE p.recv_id = s.user_a_id AND p.status < 3
             AND p.conv_key = CONCAT(LEAST(s.user_a_id, s.user_b_id), \"_\", GREATEST(s.user_a_id, s.user_b_id))
         ) AS box_unread
    FROM Kean.chat_session s
  UNION ALL
  SELECT s.id, s.user_b_id, COALESCE(s.b_unread, 0),
         (SELECT COUNT(*) FROM im_platform.im_private_message p
           WHERE p.recv_id = s.user_b_id AND p.status < 3
             AND p.conv_key = CONCAT(LEAST(s.user_a_id, s.user_b_id), \"_\", GREATEST(s.user_a_id, s.user_b_id))
         )
    FROM Kean.chat_session s
) t
WHERE t.kean_unread <> t.box_unread"'
#   期望：**0 行**（每个会话 × 每个人都一致）。
#   ⚠️ 有输出 ⇒ 【不要】切 kean.im.unread-source=box；先按 ③ 的表格排查：
#      kean_unread < box_unread  ⇒ ① 多半就是本节的「回填写 1」坑（跑一次 ③ 的一次性对齐再复核）；
#                                  ② 也可能是「不带 maxSeq 的旧客户端 markRead」：kean 只清了计数器、
#                                     没推进位点，于是 box 侧那批行永远停在未读（见 §3.B.3 第 0 步说明）；
#      kean_unread > box_unread  ⇒ 消息根本没双写过去 / 补偿器没跑到 / 该消息被 skip（先看 B-1 的条数比对）。
```

> ⭐⭐ **硬门槛（照抄进变更单）**：**切到 box 未读来源前，必须确认两边未读一致（含历史回填后的数据）** ——
> 上面这条 SQL 必须 **0 行**，且必须是**回填跑过之后**的结果（回填前的比对不算数）。
> 这条门槛**不能**用「先切上去看看」替代：未读是**用户直接可见**的数字，切源即生效，
> 而且这个缺陷**不产生任何错误日志或告警**，只能靠这条比对发现。

#### 3.B.5 ✅ C-2 / C-3 已实现（默认关闭）：离线增量读来源可切换 + 多端自我同步

> 📍 **本小节的定位**：这是**阶段 C 的前两件事**（§1.3 目标①的第 5、6 条），
> 但都以「**默认关闭的开关**」形式落地 —— **不配这两个开关 = 行为与实现之前逐字节一致**。
> 本轮**不动客户端**、**不改接口形状**、**不退役自研 WS**（那是 C-4）、**不动 im-platform 一行代码**。

**做了什么（三处 + 一处共用抽取，全部只动 `kean/`）**

| # | 内容 | 文件:类:方法 |
|---|---|---|
| ① | **C-2 离线增量的读来源可切换**：新增 `kean.im.read-source`（`kean` 默认 / `box`）。值为 `box` 时，`GET /api/chats/{id}/messages?afterSeq=N` 的增量分支改从 box 的 `im_private_message` 读（按 box 的 `id` 游标语义），失败/缺数据一律回退到 kean 既有实现 | **新增** `kean/.../im/ImOfflineQueryService.java`:`ImOfflineQueryService.catchUpAfterSeq` + `ChatServiceImpl.catchUp`（先问 box、`null` 即回退） |
| ② | **C-3 多端自我同步**：新增 `kean.im.multi-terminal-echo-enabled`（默认 `false`）。打开后，`send()` 落库成功之后追加一次「按 box 的 `sendToSelf` 语义投给发送者自己的**其它**终端」 | **新增** `kean/.../im/ImMultiTerminalEchoService.java`:`echoAfterSend` + `ChatServiceImpl.send`（B-1 镜像调用之后追加）+ `ImSenderService.pushPrivateToTerminals` |
| ③ | **终端推断规则抽成一处**（无行为变化）：取 box token 与 C-3 的「跳过当前终端」必须用**同一个** terminal，所以把 `ImController` 的两个私有方法原样搬进公用类，`ImController` 改为调用它 | **新增** `kean/.../im/ImTerminalResolver.java`；`ImController` 的 `resolveTerminal`/`parseTerminal` 已删除并改为 `ImTerminalResolver.resolve(...)` |
| ④ | **在线槽位读法暴露一处**（无行为变化）：C-3 要按终端判断在线，直接复用 `ImKickService` 里既有的 `multiGet` 读法，**不新写一套拼键逻辑** | `ImKickService.onlineServerIds(Long)`（内部仍是原来的私有 `readServerIds`） |

**C-2：`afterSeq` ↔ box 的 `id` 是怎么对应的（§6.3.2 第 2 步的「翻译」）**

```text
① 客户端传进来的 afterSeq = 它在这个会话里已经见过的最大 seq_no
② 在 box 的表里找「本会话中 seq_no 最小的、且 seq_no > afterSeq」的那一行，取它的 id 记作 firstId
     SELECT MIN(id) FROM im_platform.im_private_message
      WHERE conv_key = :convKey AND seq_no > :afterSeq
③ 真正的游标 = firstId - 1，随后按 id 升序取：
     SELECT ... FROM im_platform.im_private_message
      WHERE conv_key = :convKey AND id > :cursor AND send_time >= :nowMinus60d
      ORDER BY id ASC LIMIT 200
```

- **为什么用 `MIN(id)` 而不是 `MAX(id)` / 不用「`seq_no = afterSeq` 那一行的 id」**：
  `seq_no` 在 box 的表里**不是全局单调**的（它是 kean 的 `ChatSeqService` 按会话分配的号，
  一个冷会话恢复后完全可能拿到「很大的号」而落在一个「很小的 id」上）。
  用 `MAX` 或「等号那一行」当上界，都会把「`seq_no` 大但 `id` 小」的未读消息**永久跨过去**；
  用 `MIN(id)` 则**不可能漏**（任何 `seq_no > afterSeq` 的行，其 id 都 ≥ 这个 `MIN(id)`）。
- **多取的部分为什么安全**：只有「`id` 更大但 `seq_no ≤ afterSeq`」的稀疏场景才会多取；
  客户端 `uni-kean/src/utils/chatMerge.ts` 的 `dedupeKey()` 是 **`localId` 优先**，
  而 kean 每条消息都有 `localId`（客户端生成或服务端补 32 位 UUID）且 B-1 已把它写进 box 的 `local_id`
  ⇒ 无论从哪条通道拿到同一条消息，客户端的去重键都是同一个，最坏只是「重复到的已读气泡被合并掉」。
- **⚠️ 一个已知边界**：box 表里 `local_id` **为空**的行，客户端只能用服务端 id 去重，
  而本通道给出的是 **box 的 id**（与 WebSocket 推送里的 kean id 不同）⇒ 那种行**可能**重复显示一次。
  B-1 的镜像器对缺 `local_id` 的行是「拒绝镜像」的，所以正常运维下该集合为空。
- **⭐ 缺行护栏（本实现刻意加的一条）**：返回之前会与 kean 交叉核对一次
  「本会话 `seq_no > afterSeq` 的条数」（`kean` 侧 `COUNT(*)`，走会话索引）。
  若 `box 行数 < min(kean 条数, 200)` ⇒ 判定 box 不完整，**整批回退到 kean**。
  理由：客户端会把游标推到本批的最大 `seq_no`，box 缺的那几条会被**永久**越过
  （现象是「用户少看到一条消息」）。代价是切到 `box` 之后每次轮询多一次 kean 的 `COUNT`
  —— 换来的是「不会因 box 缺数据而静默丢消息」。
- **⚠️ 返回形状**：`ChatMessageVO` 的**每个字段都在**（客户端零改动），但与 kean 通道有两处**已知差异**：
  ① `id` 是 **box 的 id**（见上）；② **`readAt` 恒为 `null`**（box 的表**没有 `read_time` 列**，
  已核实；客户端的已读勾看的是 `status == 3`，不看 `readAt`，所以勾的显示不受影响）。
- **⚠️ 未证实项（按语义实现、未读源码）**：`/opt/boxim-src/box-im` **不在本机**，
  因此下面四点是从 §6.1 的摘录 + 本轮已核实的「按 `id` 游标」语义实现的，**不是现场读的源码**：
  60 天窗口（`OFFLINE_WINDOW_DAYS`）、「跨会话 vs 按会话」（本轮按会话，因为接口形状不改）、
  触顶时「每个会话至少补一条」（本轮不实现，kean 的客户端带游标翻页）、
  `loadOfflineMessage` 的写副作用（`status` 0→1，本轮**刻意不做**，保持 GET 只读）。
  **改动点集中在 `ImOfflineQueryService` 一个类**（逐条对应表见其类注释）。

**C-3：怎么「跳过当前终端」与怎么判断「是否有其它终端在线」**

- **当前终端**：`ImTerminalResolver.currentRequestTerminal()` 从当前请求的 `X-Kean-Device`
  推断（与 `GET /api/im/token` 写进 JWT 的 `info.terminal` **同一份规则、同一个类**）。
  ⚠️ 这是本功能最关键的一条依赖：两处规则一旦漂移，就会出现
  「跳过的是 APP、实际连的是 WEB」⇒ **自己收到自己的消息（重复气泡）**。
- **两重跳过**（缺一条都可能重复）：① 不猜终端（用取票同一套规则）；
  ② `otherTerminals(current)` 显式排除当前终端码，且投递时 `receivers` 里**只列**这些终端。
- **⚠️ 刻意不做的一件事（实现过程中改回来过）**：曾经加过「目标 `serverId` 减去 peer 的 `serverId`」
  的去重，理由是「`im:message:private:{serverId}` 是按实例的队列，别写两条」。**那是错的**：
  反例是「我是 WEB(在 server#1)，对方的 APP 在 server#3，我的 PC 也在 server#3」——
  peer 那条投递的 `receivers` **只有对方本人**，它不会把消息送给我的 PC；
  若此时把 server#3 减掉，我的 PC 就**收不到**这条自我同步（= 多端同步静默失效）。
  现在**不做**任何 serverId 级去重：最坏只是同一实例上多入队一条，客户端按 `localId` 幂等去重。
- **在线判据**：box 的在线槽位键 `im:user:server_id:{userId}:{terminal}`（键不存在 = 离线），
  读法复用 `ImKickService.onlineServerIds`（一次 `multiGet` 拿三个键）。
  **单端在线时什么都不做**（目标集合为空 ⇒ 直接返回 0，不查 peer 槽位、不序列化、不写队列）
  —— 绝大多数请求走的就是这一条。
- **失败策略**：只 `log.warn` + 计数，**绝不外抛**（它在 `send()` 的 `@Transactional` 里，
  异常穿出去会连累用户已经落库的消息）。

**两个开关：名字、默认值、开启与回退**

```bash
# ---- 默认值（不配即此，行为与实现之前逐字节一致）----
# kean.im.read-source=kean                        （增量拉取仍读 kean 的 chat_message）
# kean.im.multi-terminal-echo-enabled=false       （不做多端自我同步）

# ---- 开启 C-2（离线增量改读 box）----
# ⚠️ 前置：B-1 的 KEAN_IM_MESSAGE_MIRROR_ENABLED=true 且已跑过一段（box 里得有近期的消息），
#          否则缺行护栏会一直把请求打回 kean（表现为「开了但看不出效果」，日志里有
#          [IM 读取来源] box 侧数据不完整 ... 的 WARN）
KEAN_IM_READ_SOURCE=box
systemctl restart kean

# ---- 开启 C-3（多端自我同步）----
# ⚠️ 前置：IM_JWT_SECRET 已配置（否则 im-server 通道不可用，启动日志会 WARN）；
#          且客户端按真实终端取票（GET /api/im/token?terminal=0|1|2）效果才准确，见 §6.2
KEAN_IM_MULTI_TERMINAL_ECHO_ENABLED=true
systemctl restart kean

# ---- 回退（各自独立，秒级）----
KEAN_IM_READ_SOURCE=kean              # C-2 回退：kean 的 chat_message 一直在写，无需回填/重放
KEAN_IM_MULTI_TERMINAL_ECHO_ENABLED=false   # C-3 回退：另一台设备回到「靠 8 秒增量拉取追」
systemctl restart kean
```

**启动日志（照项目风格，明说「到底生效了没有」）**

```text
[IM 读取来源] 配置 kean.im.read-source=「kean」→ 解析结果=false（增量拉取仍读 kean 的 chat_message），
实际生效=false；box 读通道=可用；目标表=im_platform.im_private_message（主 DataSource，跨库全限定名，
不新建 DataSource）；游标映射=afterSeq→MIN(id) WHERE conv_key=? AND seq_no>?，再按 id 升序取；
时间窗口=60 天；单次上限=200 条；失败策略=返回 null 由 ChatServiceImpl 回退到 kean 的 afterSeq 实现
（只 WARN 一次）；回退方式=把 kean.im.read-source 设回 kean 并重启 kean（kean 侧数据一直是热的，无需回填）

[IM 多端同步] 配置 kean.im.multi-terminal-echo-enabled=「false」→ IM 通道就绪（IM_JWT_SECRET 已配置且
≥32 字节）=true，实际生效=false；语义=box 的 sendToSelf（只投给发送者自己的【其它】终端，强制跳过当前
终端，永不回投当前终端）；在线判据=im:user:server_id:{userId}:{terminal}，单端在线时为 0 开销；
失败策略=只 WARN + 计数，绝不影响发送；回退方式=把 kean.im.multi-terminal-echo-enabled 设为 false 并重启 kean
```

**验收（切 C-2 / 开 C-3 各一条最小验证）**

```bash
# C-2：确认增量真的由 box 说话（kean 日志的 DEBUG 行；把日志级别开到 com.kean.im=DEBUG）
#   期望每 8 秒一次：[IM 读取来源] box 增量拉取：sessionId=... convKey=... afterSeq=N → firstId-1=M, 返回 K 条
#   对照接口（形状必须与切之前完全一样：list/total/page/size 同形）
curl -s -H "Authorization: Bearer $KEAN_TOKEN" \
  "http://127.0.0.1:8080/api/chats/<sessionId>/messages?afterSeq=<N>" | head -c 600
# ⚠️ 若日志里反复出现 [IM 读取来源] box 侧数据不完整 ⇒ 说明 box 比 kean 少行（多半 B-1 没开或补偿没跑），
#    此时**请求仍然是正确的**（已回退到 kean），但不要继续把 C-2 当作「已切成功」。

# C-2 的 SQL 侧对照：box 里该会话 seq_no > N 的最小 id 与行数
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT MIN(id) AS first_id, COUNT(*) AS cnt FROM im_platform.im_private_message
   WHERE conv_key = \"<小id>_<大id>\" AND seq_no > <N>"'
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) AS kean_cnt FROM Kean.chat_message WHERE session_id = <sessionId> AND seq_no > <N>"'
#   期望：cnt >= LEAST(kean_cnt, 200)（否则护栏会回退，见上）

# C-3：同一账号在 H5(terminal=0) + App(terminal=1) 同时在线，从 App 发一条
#   期望：H5 **立刻**出现这条消息，且 App 上只出现一次（自己不会收到自己的）
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 --scan --pattern 'im:user:server_id:{<userId>}:*'
#   两个槽位都在 ⇒ 该走自我同步；只有一个 ⇒ 单端在线，本功能什么都不做（正确行为）
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 LLEN im:message:private:<serverId>
#   观察队列长度：自我同步会给「其它终端所在的 serverId」再入一条（receivers 只含其它终端）
# ⚠️ 验证「不走 sendToSelf 的路径」：在 H5 与 App 都发一条，确认两台设备各自只看到一次（无重复气泡）。
```

**故障时的行为（四条，都是刻意设计的）**

1. **box 查不通 / 权限不足 / 表名变了**：`ImOfflineQueryService` 只 `log.warn`（同一原因只提示一次），
   返回 `null` ⇒ `ChatServiceImpl.catchUp` 走**一行未改**的 kean 既有实现 —— 聊天页照常打开，**不会 500、不会白屏**；
2. **box 少给了消息**：缺行护栏把该次请求整批打回 kean（见上），并在 `statsSnapshot()` 的 `short` 位计数
   （与「查询抛异常」的 `fallbacks` **分开计数**，因为排查方向不同）；
3. **多端同步失败**（Redis 抖动 / 槽位脏值）：`echoAfterSend` 内部吞掉异常，只 `log.warn` + 计数，
   **本次发送照常成功**（连 kean 的落库事务都不受影响）；
4. **默认关闭时的严格零影响**：`read-source=kean` 时 `ChatServiceImpl.catchUp` 连一次
   `chatSessionMapper.selectById` 都不会多做（先判 `active()` 再查会话）；
   `multi-terminal-echo-enabled=false` 时 `echoAfterSend` 第一行返回 0，**不读 Redis、不写队列**。
   两者都不新增任何 DataSource、依赖、Flyway 或 `application*.yml` 改动。

> ⚠️ 与 §6.3.2 第 2 步的关系：那里描述的终态是「**客户端游标一次性重基到 box 的全局 `id`**」，
> 本小节**没有**做那件事（那要改客户端，属阶段 C 的后续项）。
> 本小节做的是重基之前的**过渡形态**：接口形状、客户端游标仍是 kean 的 `afterSeq`，
> 只有「这批消息从哪里读出来」被换掉了，box 的 `id` 只在**服务端内部**当游标用。

---

### 阶段 C：全量切换（读也切到 im-platform —— **IM 侧的不可逆点**）

> 📍 **本阶段的终态位置**：**会话 / 消息 / 未读 / 离线 / 多端同步的「归属」在本阶段永久交给 box**。
> 此后不再有「切回 kean 的读路径」这个终态选项 —— 回滚只是**应急通道**，不是终态。
> ⚠️ 但请记住：**本阶段不是「全量换成 box」的终点** —— 认证（账号）此时 100% 仍在 kean，
> 目标①（离线/多端）与目标②（富媒体）分别要到 **C 的验收** 与 **D** 才真正兑现。

> ⚠️ **进入本阶段前必须先通过 §3.B.1 的第 2 条（seq_no 逐条一致），否则不要开始。**
> 本阶段是整份文档里**唯一一个真正的不可逆点**：客户端在阶段 B 里用的是 **kean 的会话内 `seq_no` 游标**，
> 而阶段的终态用的是 **box 的全局 `id` 游标**（§6.3.2），两者**不能互相赋值**（§6.3.1）。
> 游标一旦切换，回退就要连带处理「切换窗口内只存在于 box 的消息」，见下面的回滚说明。

- **要部署什么**：kean 后端（读路径改造）+ `uni-kean` 前端（切接口与游标）。
- **要改哪些代码**：
  | 文件 | 类:方法 | 改什么 |
  |---|---|---|
  | `kean/.../controller/ChatController.java` + `ChatServiceImpl` | `listMine` / `messages` / `send` / `markRead` / `unreadCount` | 提供**新的读路径**：由 `ImPlatformClient` 代理到 im-platform 的对应 REST（`/message/private/loadOfflineMessage`、`/message/private/history`、`/message/private/readed` 等），或把 im-platform 的数据**读进 kean 的 VO 形状**再返回。**推荐后者**（保持 kean 的响应契约不变，前端改动最小） |
  | `kean/.../service/impl/ChatServiceImpl.java` | `send` | **把写路径的所有权交给 im-platform**：改为调 im-platform 的 `POST /private/message`（`ImPlatformClient`），**不再**在 kean 侧分配 `seq_no`（`ChatSeqService.allocateNext` 对新消息的调用**删除**，不是注释掉）。⚠️ 此时 `seq_no` 由 im-platform 用自己的 `getNextSeqNo`（Redis `INCR` + Redisson 锁，F10）分配 —— **这就是「H4：两套分配器」的消解方式：同一时刻只有一个分配器** |
  | `kean/.../service/impl/ChatServiceImpl.java` | `messages` | 游标语义切换：`afterSeq` 分支**不再**用 box 的 `seq_no` 作为客户端游标，而是用 **box 的 `id`**（见 §6.3.2 第 3 步）；历史分页分支继续读 kean 的只读老表（§4 的两段式读取） |
  | `uni-kean/src/api/chat.ts` | `listChatMessagesAfter` / `sendChatMessage` / `markChatRead` | 游标语义切换；`sendChatMessage` 的 `localId` 必须**客户端生成并稳定重试**（box 要求 `localId` 必填，F 见 `PrivateMessageDTO.@NotEmpty`） |
  | `uni-kean/src/pages/message/chat.vue` | `applyIncoming` / 增量拉取 / 已读上报 | 接 box 的下行帧（`IMRecvInfo.data` = `PrivateMessageVO`）；⚠️ box 的 `PrivateMessageVO` **没有 `sessionId`**，需要用「对方 userId ↔ 本地会话」的映射（kean 侧已有 `peerUserId`，见 `docs/ops/im-migration.md` §6.6 ③ 的同类问题） |
  | `uni-kean/src/utils/chatSync.ts` / `chatMerge.ts` / `chatStore.ts` | 游标与合并 | 见 §6.3 的收敛方案 |
- **数据要不要动**：**要，且必须是幂等的一次性脚本**：
  ① 全量物化影子用户（`im_user`）与好友投影（`im_friend`）；
  ② **游标交接**：把每个会话的 `last_seq_no` / `a_read_seq` / `b_read_seq` 与 box 侧的 `seq_no` 做一次核对（§3.C.1 第 2 条）；
  ③ **未读口径统一**（§6.3）：kean 的 `a_unread`/`b_unread` 与 box 的未读**不能同时生效**，切换时必须**明确选一个**并把另一个清零/停用。
  ④ **不搬历史消息**（§4 的明确建议）。
- **验收清单**（§3.C.1）
- **回滚**：**这是最贵的一步**。回滚 = `KEAN_IM_PLATFORM_ENABLED=false` + 前端回退上一版 + 把 kean 的读路径切回去。
  ⚠️ **切换期间产生的新消息只存在于 box 的 `im_private_message`**，kean 的 `chat_message` 里没有 ——
  回滚后这些消息会**在客户端消失**。因此必须在阶段 C 开一个 **`backfill` 反向写入**（把 box 的新消息回写 kean 的 `chat_message`），
  否则「回滚」等于「丢掉切换窗口内的消息」。**这个反向写入是阶段 C 的前置条件，不是可选项。**
- **预估人天**：**8 ~ 14 人天**（读路径代理 ≈3，游标与未读统一 ≈3，反向回填 ≈2，前端 ≈3，联调回归 ≈3）。

#### 3.C.1 阶段 C 的验收清单

```bash
# ---- 1) 客户端游标与 box 的 seq_no 对得上（抽一个活跃会话）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT conv_key, COUNT(*) AS cnt, MIN(seq_no) AS min_seq, MAX(seq_no) AS max_seq,
         COUNT(DISTINCT seq_no) AS distinct_seq
  FROM im_platform.im_private_message
  GROUP BY conv_key ORDER BY cnt DESC LIMIT 10"'
#   期望：cnt == distinct_seq（无重复序号）；max_seq 单调递增
#   ⚠️ 允许有空洞（并发失败会浪费号，见 ChatSeqService 的接口注释），**不允许有重复**

# ---- 2) kean 与 box 的位点交接核对（切换前必须全绿）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT s.id AS session_id, s.last_seq_no AS kean_last_seq,
         MAX(m.seq_no) AS box_max_seq,
         s.a_read_seq, s.b_read_seq
  FROM Kean.chat_session s
  LEFT JOIN im_platform.im_private_message m
         ON m.conv_key = CONCAT(LEAST(s.user_a_id, s.user_b_id), '_', GREATEST(s.user_a_id, s.user_b_id))
  GROUP BY s.id
  HAVING kean_last_seq IS NOT NULL AND kean_last_seq <> box_max_seq
  LIMIT 50"'
#   期望：**0 行**。有任何一行都说明双写期间分叉了，必须先查清再继续。
#   ⚠️ conv_key 的拼法以 ConvUtil.buildConvKey 为准；上面写的是「小的在前、用 _ 连接」，
#      动手前请对照 im-platform/.../util/ConvUtil.java 确认（§12 标注未证实）。

# ---- 3) 离线消息真的能拉到（目标①）----
#   ⚠️ 服务端侧的「kean 读路径改从 box 读」已按 §3.B.5 落地（开关 kean.im.read-source=box，默认关）。
#      所以本条的**服务端部分**现在就能验（开那个开关 + 看 [IM 读取来源] 的 DEBUG 行），
#      而**客户端部分**（游标重基到 box 的全局 id，§6.3.2 第 2 步）仍属阶段 C 的后续项，本轮未做。
#   用 A 登录，关掉 A 的客户端 → 用 B 给 A 发 3 条 → 打开 A：
#   期望：A 一次性收到 3 条，且 status 从 0(等待推送)→1(已送达)
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, seq_no, send_id, recv_id, status FROM im_platform.im_private_message
  WHERE recv_id = <A> ORDER BY id DESC LIMIT 5"'

# ---- 4) 多端同步（目标①）----
#   ⚠️ 服务端侧的「按 box 的 sendToSelf 语义投给自己其它终端」已按 §3.B.5 落地
#      （开关 kean.im.multi-terminal-echo-enabled，默认 false）。打开后本条即可直接验；
#      关闭时行为与实现之前完全一致（多端同步仍靠 8 秒增量拉取追）。
#   同一账号在「H5(terminal=0) + App(terminal=1)」同时在线，从 App 发一条：
#   期望：H5 立刻出现这条消息（sendToSelf），且 App 自己不重复显示
#   验证：H5 上该消息只出现一次；App 上只出现一次

# ---- 5) 未读只由一套口径说话（§6.3）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, a_unread, b_unread FROM Kean.chat_session WHERE id = <sessionId>"'
#   期望：切换后这两个计数器**已停止更新/已清零**，未读由 box 侧消息 status 推导

# ---- 6) 反向回填是活的（阶段 C 的前置条件）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) AS box_newer FROM im_platform.im_private_message p
  LEFT JOIN Kean.chat_message c ON c.local_id = p.local_id
  WHERE c.id IS NULL"'
#   期望：这个数字**不随时间增长**（说明回填在跟进）。它持续增长 = 回滚会丢消息。
```

#### 3.C.2 ⭐ C-4 已实现（**默认 true，保持现状**）：自研 WS 实时推送可退役 —— 服务端与协议侧准备

> 📍 **本小节的定位**：C-4 是**阶段 C 的最后一件服务端准备** —— 让「`ws://<host>/ws/chat` 上的**聊天实时推送**」
> 变得**可关**（一个开关），从而具备「自研 WS 可退役」的能力。本轮**只做服务端与协议侧**：
> **不动客户端**（`VITE_IM_ENABLED` 等由客户端侧另行决定）、**不改任何接口形状**（无新增/删除字段）、
> **不碰 im-platform 一行代码**、**不加依赖 / 不加 Flyway / 不改 `.env*` 与 `application*.yml`**。
>
> ⚠️ **本开关默认 `true`**，所以**不配它 = 行为与新增之前一字不差**。这与本项目其它
> 「默认关闭」的开关**方向相反是刻意的**，理由见下一节。

##### ⚠️ 已实测的硬事实：**box 通道不产生 READ 事件**（这就是 C-4 不能简单关掉自研 WS 的原因）

| 事实 | 内容 | 影响 |
|---|---|---|
| **F-READ-1**（已实测） | 客户端的 `imSocket` 只能映射 box 下行里的 **`MESSAGE` / `NOTICE`** 两种事件，**没有 `READ`**。box 的 `/im` 通道实测握手 101 ✓ / 心跳 ✓ / 重连 ✓，但**不推 READ** | ⚠️ 「对方已读」的**实时**推送，目前**唯一**来源就是自研 WS 上的 `READ` 事件 |
| **F-READ-2** | kean 的 `RealtimePublisher.read(...)` 推的 `{type:"READ",sessionId,maxSeq,readerId}` **没有 box 对等物**（box 的对应物是 `MessageType.RECEIPT(12)`，字段与语义都要另外对齐，本轮**刻意不做**） | 关掉自研 WS 的 READ 推送 **≠** 已读功能消失，而是**从「实时」降级为「≤8 秒」** |

> ⭐ **结论**：`kean.im.legacy-ws-enabled` 的**默认值必须是 `true`** ——
> 因为自研 WS 现在是 READ 的**唯一实时来源**，默认成 `false` 会**立刻去掉一条用户可见的能力**，
> 违反本项目「全部默认关闭则零影响」的既定模式（这里是「默认保持现状则零影响」）。

##### 关掉之后「谁在干活」（替代路径必须逐条落实，不能只说「box 会管」）

| 实时职责 | 默认（`legacy-ws-enabled=true`） | 关掉后（`=false`） | 依据 |
|---|---|---|---|
| **新消息实时到达** | 自研 WS 的 `MESSAGE` **+** box 的 `/im`（两条通道都在推） | ✅ **box 的 `/im` 独立承担**（kean → `im:message:private:{serverId}` → im-server 下行） | 客户端**已经**同时跑两条通道（实测），关掉自研那条不影响 box 那条 |
| **未读角标** | 按 `kean.im.unread-source` | 不变（与本开关**正交**） | B-3 |
| **对方已读（双钩）实时性** | 自研 WS 的 `READ`（实时） | ⚠️ **没有实时推送** → **靠客户端轮询（每 ≤8 秒一次增量拉取）+ 数据推导** | 见下「⭐ 增量拉取里到底有没有已读状态」 |
| **「对方已读」的展示（本轮变更）** | ⚠️ **客户端已移除双勾**（气泡只剩 发送中 / 单勾 / 失败），`READ` 事件**无消费方** | 同上（本来就不展示了）⇒ 这条**不再是「能力」**，`READ` 的开关（`read-receipt-enabled`）因此**关掉也不会少任何东西** | `uni-kean/src/utils/chatSync.ts` 的 `outgoingStatus` / `outgoingStatusLabel`；`chat.vue` 已删除 `applyReadReceipt` / READ 分支 |
| **通知 / 公告 / 封禁提示**（`NOTICE`） | 自研 WS | ✅ **照旧推**（**本开关不关它**） | 它们没有 box 对等物，§1.4 明确要求保留 |
| **`AUTH` / `PING`·`PONG` / 握手 / 连接** | 自研 WS | ✅ **完全不动** | 本开关**不是**「停服」，客户端仍可连着，只是收不到聊天推送 |

##### ⭐⭐ 增量拉取的返回里到底有没有「已读状态」（本轮**已核实**：**有**，不需要补字段）

> **核实结论：`ChatMessageVO` 里已经有 `status`（0 未读 / 1 已发送 / 2 撤回 / **3 已读**）与 `readAt`，
> 两条读路径都带。所以本轮【没有新增任何字段】，接口形状一字未改。**

| 读路径 | 代码位置 | 返回的 `status` 从哪来 | 谁来写这个 3 |
|---|---|---|---|
| `read-source=kean`（默认） | `ChatServiceImpl.toMessageVo` | `chat_message.status` | ✅ `ChatServiceImpl.markRead` **无条件**写（不看任何 IM 开关）⇒ **一定推得出来** |
| `read-source=box`（C-2） | `ImOfflineQueryService.toMessageVos` | `im_private_message.status` | ⚠️ **只有** `kean.im.unread-source=box` 时的 `ImUnreadQueryService.markReadInBox` 会写 |

> ⚠️⚠️ **由此推出一个必须写进变更单的陷阱（本轮新发现）**：
> **`read-source=box` + `unread-source=kean` + `legacy-ws-enabled=false`** 三条同时成立时：
> box 侧的 `status` **永远是 1**（没人把它改成 3），轮询拉回的 `status` 也永远是 1，
> 而自研 WS 的 `READ` 又已关 ⇒ **旧客户端的「对方已读」双钩将永远不出现，且不报任何错**。
> ⚠️ **本轮起这只对「回退到旧客户端」有意义**：新客户端已移除双勾（气泡不再展示「对方已读」），
> 因此该组合**不再产生任何用户可见的缺陷**；下面那条 WARN 也只在 `read-receipt-enabled=true`
> （即 READ 仍在推）时才会出现，见「本轮新增：`kean.im.read-receipt-enabled`」。
> 启动时会有一条 **WARN** 把这件事喊出来（见下面的「实时职责汇总」），
> 切换前请确认 `kean.im.unread-source` 也已切到 `box`（或本开关保持 `true`）。

##### 服务端推自研 WS 的**确切位置**（事件级共 4 处，本轮**包住了 2 处**）

> 底层的原语只有一个：`ChatSessionHub.sendTo(Long userId, String payload)`。
> 全仓（`kean/src`）**只有 3 个调用点**：`ChatWebSocketHandler.pushMessage`、
> `RealtimePublisher.send`、`AccountBanServiceImpl.pushBanned`。
> 本开关包住的是**事件级**的两条聊天推送（下表 ①②），**刻意不包** ③④。

| # | 位置（文件 : 类 : 方法） | 事件 | 怎么关 | 本轮动作 |
|---|---|---|---|---|
| ① | `kean/.../chat/ChatWebSocketHandler.java` : `ChatWebSocketHandler` : **`pushMessage(Long, ChatMessageVO)`** | `MESSAGE` | `legacy-ws-enabled=false` → **跳过**（第一行返回，只记一次计数） | ✅ 已包住（C-4） |
| ② | `kean/.../chat/RealtimePublisher.java` : `RealtimePublisher` : **`read(Long, Long, Long, Long)`** | `READ` | **① `read-receipt-enabled=false`**（本轮新增，更窄）→ **跳过**；② `legacy-ws-enabled=false` → 同样跳过（第二道闸） | ✅ **本轮加了一道更窄的闸** |
| ③ | `kean/.../chat/RealtimePublisher.java` : `RealtimePublisher` : **`notice(...)`**（经 `send(...)`） | `NOTICE` | 两个开关**都不关它** | ❌ 刻意不包（无 box 对等物，§1.4 要求保留） |
| ④ | `kean/.../service/impl/AccountBanServiceImpl.java` : `AccountBanServiceImpl` : **`pushBanned(...)`**（直接调 `ChatSessionHub.sendTo`） | `BANNED` | 两个开关**都不关它** | ❌ 刻意不包（封禁提示不是聊天推送） |

> 说明：`ChatWebSocketHandler.handleTextMessage` 里回的 `PONG` **不算推送**（它是连接级心跳应答），
> 与 `AUTH` 帧、握手、鉴权一样**一律不受这两个开关影响**。
> ② 的「提前返回」是**行为等价**的：`READ` 的**唯一**出口就是 `RealtimePublisher.send(...)` 里的
> `chatSessionHub.sendTo`，而 `send(...)` 里的 im-server 镜像对 `READ` **本来就是跳过的**
> （`systemMessageData` 对 `"READ"` 返回 `null`）—— 所以提前返回**不会少推任何一条 box 消息**。

##### ⭐ 本轮新增：`kean.im.read-receipt-enabled`（**默认 `true` = 保持现状**）

> **为什么要多这一把闸**：客户端本轮**已移除双勾**（气泡不再展示「对方已读」，见
> `uni-kean/src/utils/chatSync.ts` 的 `outgoingStatus` / `outgoingStatusLabel`），
> 于是 `READ` 事件**没有任何消费方**；而 `legacy-ws-enabled` 是一把同时管 `MESSAGE` 的总闸，
> 用它关 `READ` 会**连带关掉消息实时推送**。两件事的退役节奏不同 ⇒ 拆出一把**只管 `READ`** 的窄闸。
>
> **优先级**：`RealtimePublisher.read` 先判 `read-receipt-enabled`，为 `true` 时才回落到
> `legacy-ws-enabled` —— 所以两把闸任意一把为 `false`，`READ` 都不再推。

```bash
# ---- 默认值（不配即此，与新增本开关之前【一字不差】：READ 照推）----
# kean.im.read-receipt-enabled=true
#   （等价环境变量 KEAN_IM_READ_RECEIPT_ENABLED；Spring relaxed binding）

# ---- 让自研 WS 不再推 READ（客户端已不看双勾，无消费方）----
KEAN_IM_READ_RECEIPT_ENABLED=false
systemctl restart kean
#    期望日志：READ推送=关（不再推 READ；markRead 的位点/状态/未读清零照旧）（kean.im.read-receipt-enabled=false）
#    ⚠️ 只有这一把为 false 时不会出现 [IM 实时职责] 的 WARN（READ 已无消费方，双钩不出现不是缺陷）

# ---- ✅ 一键恢复 ----
KEAN_IM_READ_RECEIPT_ENABLED=true      # 或直接删掉这一行（默认就是 true）
systemctl restart kean
```

> ⚠️ **默认值纪律**：本开关默认 **`true`**（= 仍然推），所以**部署本身不改变任何线上行为**；
> 真正的切换由部署后改环境变量 + 重启完成。这与 `legacy-ws-enabled` / `unread-source` /
> `read-source` 的既有做法完全一致，出问题一条命令即可回退。
>
> ⚠️ **`false` 时到底少了什么**：**只少推一个 `READ` 事件**。
> `ChatServiceImpl.markRead` 的 box 已读回写、`a_read_seq/b_read_seq` 位点、
> `chat_message.status=3`、`a_unread/b_unread` 清零，以及客户端每一次已读**上报**
> （`createReadReporter` → `POST /api/chats/{id}/read`）**全都照旧执行**
> ⇒ **「自己的未读角标」不受任何影响**，也**不需要回填**。

##### 开关：名字 / 默认值 / 开启与回退（`legacy-ws-enabled`，C-4 既有）

```bash
# ---- 默认值（不配即此，行为与实现之前【一字不差】）----
# kean.im.legacy-ws-enabled=true
#   （等价环境变量 KEAN_IM_LEGACY_WS_ENABLED；Spring relaxed binding）

# ---- C-4：让自研 WS 不再承担实时推送职责（⚠️ 先做完下面的验收清单）----
# ⚠️ 前置（三条，缺一条都可能让某条能力静默失效）：
#   ① box 的 /im 通道在客户端上是活的（实测：握手 101 / 心跳 / 重连）——
#      否则关掉自研 WS 之后新消息将【没有任何】实时推送；
#   ② kean.im.read-source 与 kean.im.unread-source 都应为 box
#      （只切 read-source 不切 unread-source ⇒ 旧客户端的双钩永远不出现，见上；
#        客户端本轮已不看双勾，所以这一条只对「回退到旧客户端」有意义）；
#   ③ IM_JWT_SECRET 已配置（否则 kean.im.mirror-enabled 生效不了，box 队列收不到消息）。
# ⚠️ 若只想关掉 READ（本轮的目标），用上面那把更窄的 KEAN_IM_READ_RECEIPT_ENABLED=false，
#    【不要】用这一把 —— 它会连 MESSAGE 一起关掉。
KEAN_IM_LEGACY_WS_ENABLED=false
systemctl restart kean

# ---- ✅ 一键恢复（秒级，无需回填 / 无需重放）----
KEAN_IM_LEGACY_WS_ENABLED=true      # 或直接删掉这一行（默认就是 true）
systemctl restart kean
```

> **为什么恢复「无需回填」**：这两个开关**只控制「推不推」**，不控制任何**写入** ——
> kean 的消息、已读位点、`chat_message.status=3`，以及 B-3 对 box 的已读回写，
> 都**一直在照常执行**（`markRead` 的逻辑一行未改）。所以关掉期间 kean 侧的数据始终是**热的**，
> 开关设回 `true` 后自研 WS 的 `MESSAGE` / `READ` 推送**立刻恢复**。

##### ⭐ 切换前的验收清单（**逐条都要过；任何一条不过 → 立即把开关改回 `true`**）

```bash
# 0) 先看启动日志里的「实时职责汇总」，确认三条前置都满足（见下一节的样例行）
journalctl -u kean --since '5 min ago' | grep '\[IM 实时职责\]'

# ---- ① 关掉开关并重启 ----
KEAN_IM_LEGACY_WS_ENABLED=false
systemctl restart kean
#    期望日志：自研WS实时推送=关（只保留连接/鉴权/PING，不再推 MESSAGE/READ）
#    ⚠️ 若同时出现 [IM 实时职责] 的 WARN ⇒ 前置条件没满足，**不要继续**，直接改回 true

# ---- ② 发一条消息，确认【仍能实时到达】（由 box 的 /im 承担）----
#    两账号各自登录（H5 + App 皆可），A 给 B 发一条：
#    期望：B 在【1 秒内】看到气泡（不是 8 秒后的轮询），且 B 的客户端上【没有】重复气泡
#    观察（服务端侧）：消息确实进了 box 的队列并被消费
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 --scan --pattern 'im:message:private:*'
#    期望：队列最终被消费干净（长度回到 0）；若一直不降 ⇒ box 通道没在消费，立即回退

# ---- ③ 让对方读一下，确认【已读位点仍然被推进】（⚠️ 本轮起不再看「双钩」）----
#    ⚠️ 客户端本轮已移除双勾：A 的界面上【本来就不会】再出现「对方已读」的任何标记，
#       所以旧版清单里「双钩在 ≤8 秒内出现」这一条【已不适用】——不要照它判定失败。
#    改为核对【数据面】（这才是真正会被后续依赖的东西）：
#    B 打开会话（触发 markRead）→ 在服务端核对下面两条 SQL。
#    服务端侧对照（确认 box 侧的 status 真的被推进到 3）：
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, seq_no, send_id, recv_id, status FROM im_platform.im_private_message
   WHERE conv_key = \"<小id>_<大id>\" ORDER BY id DESC LIMIT 5"'
#    期望：B 读过的那些行 status=3（若恒为 1 ⇒ 未读来源没切到 box；这只影响数据面，
#          不再影响任何界面显示，但会让「未读口径收敛到 box」这件事不成立）
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, seq_no, sender_id, status FROM Kean.chat_message
   WHERE session_id = <sessionId> ORDER BY seq_no DESC LIMIT 5"'
#    期望：与上面对应（kean 侧 markRead 无条件写，这条【一定】是 3）
#    ✅ 另外必须确认「自己的未读角标仍然会清零」（这条才是本轮改动的命脉）：
#       B 打开会话后，B 自己的消息 Tab 角标应当减少/消失（靠的是已读【上报】，与双钩无关）

# ---- ④ 确认没有重复气泡 ----
#    在 ②③ 的基础上，A 与 B 各发 3 条、互相读一遍，确认：
#      · 每条消息【只出现一次】（两条通道都关掉了自研的那条推送，正常只会更少不会更多）
#      · 多端（同一账号 H5 + App）不出现「自己收到自己的」重复
#    原理：客户端的 chatMerge.dedupeKey() 以 localId 优先去重，关掉一条通道不会改变去重键。

# ---- ⑤ 任何一条不过 ⇒ 立即改回 true ----
KEAN_IM_LEGACY_WS_ENABLED=true
systemctl restart kean
```

> ⚠️ **本轮【没有】执行上面任何一条命令**（用户明确要求不部署、不跑构建、不做验证）：
> 这份清单是**给人照着执行**的，不是已经跑过的结果。见 §12 的未证实项。

##### 「实时职责汇总」日志（③ 的交付物：一行 INFO，一眼看出「现在到底谁在干活」）

**默认态（不配任何开关）—— 期望看到的样子：**

```text
[IM 实时职责] 消息实时推送=box（im-server 8878 的 /im；kean→Redis 队列 im:message:private:{serverId}）；已读实时=自研WS（/ws/chat 的 READ 事件；box 不推 READ，故这是唯一实时来源）；未读来源=kean（kean.im.unread-source；实际生效=false）；读取来源=kean（kean.im.read-source；实际生效=false）；自研WS实时推送=开（保持现状）（kean.im.legacy-ws-enabled=true）；READ推送=开（保持现状，仍推 READ）（kean.im.read-receipt-enabled=true）；被挡掉的自研WS推送=0；被挡掉的READ推送=0；回退方式=把 kean.im.legacy-ws-enabled / kean.im.read-receipt-enabled 设为 true（或删掉对应那行）并重启 kean（纯推送开关，无需回填）
```

**本轮推荐态（只关 READ：`KEAN_IM_READ_RECEIPT_ENABLED=false`，`legacy-ws-enabled` 仍为 `true`）—— 期望看到的样子：**

```text
[IM 实时职责] 消息实时推送=box（im-server 8878 的 /im；kean→Redis 队列 im:message:private:{serverId}）；已读实时=无实时推送（READ 推送已由 kean.im.read-receipt-enabled 关闭；客户端本轮起已不展示「对方已读」，故无消费方。⚠️ kean 侧已读位点与 chat_message.status=3 照旧写入，可直接回退）；未读来源=kean（kean.im.unread-source；实际生效=false）；读取来源=kean（kean.im.read-source；实际生效=false）；自研WS实时推送=开（保持现状）（kean.im.legacy-ws-enabled=true）；READ推送=关（不再推 READ；markRead 的位点/状态/未读清零照旧）（kean.im.read-receipt-enabled=false）；被挡掉的自研WS推送=0；被挡掉的READ推送=N；回退方式=把 kean.im.legacy-ws-enabled / kean.im.read-receipt-enabled 设为 true（或删掉对应那行）并重启 kean（纯推送开关，无需回填）
```
> ⚠️ 这一态**不会**触发 `[IM 实时职责]` 的 WARN：READ 已无消费方，「双钩不出现」不是缺陷。

**关掉之后（`KEAN_IM_LEGACY_WS_ENABLED=false`，且前置条件都满足）—— 期望看到的样子：**

```text
[IM 实时职责] 消息实时推送=box（im-server 8878 的 /im；kean→Redis 队列 im:message:private:{serverId}）；已读实时=无实时推送 → 靠客户端轮询（≤8s）+ 数据推导（box 的 im_private_message.status=3，由 markReadInBox 写入）；未读来源=box（kean.im.unread-source；实际生效=true）；读取来源=box（kean.im.read-source；实际生效=true）；自研WS实时推送=关（只保留连接/鉴权/PING，不再推 MESSAGE/READ）（kean.im.legacy-ws-enabled=false）；READ推送=开（保持现状，仍推 READ）（kean.im.read-receipt-enabled=true）；被挡掉的自研WS推送=0；被挡掉的READ推送=0；回退方式=把 kean.im.legacy-ws-enabled / kean.im.read-receipt-enabled 设为 true（或删掉对应那行）并重启 kean（纯推送开关，无需回填）
```

**危险组合 —— 会额外多一条 WARN（这就是这个汇总行最大的价值：把「静默失效」变成日志里看得见的一行）：**

```text
[IM 实时职责] 已读实时【推不出来】：读取来源=box 但未读来源≠box，而 box 的 im_private_message.status=3 只由 kean.im.unread-source=box 时的 ImUnreadQueryService.markReadInBox 写 ⇒ 轮询拉回的 status 永远是 1，自研 WS 的 READ 又已关（kean.im.legacy-ws-enabled=false） ⇒ 旧客户端的对方已读双钩将【永远不出现】（新客户端已不展示双钩，无影响）。修法：把 kean.im.unread-source 也设为 box，或把 kean.im.legacy-ws-enabled 设回 true。⚠️ 以上均【不报错】，只表现为用户侧静默失效；如需立刻恢复原状，把 kean.im.legacy-ws-enabled 设回 true 并重启 kean。
[IM 实时职责] 消息实时推送【没有替代通道】：自研 WS 已关，而 box 通道未就绪（IM_JWT_SECRET 未配置/不足 32 字节，或 kean.im.mirror-enabled=false） ⇒ 新消息将没有【任何】实时推送，只能靠客户端轮询（≤8s）追。⚠️ 以上均【不报错】，只表现为用户侧静默失效；如需立刻恢复原状，把 kean.im.legacy-ws-enabled 设回 true 并重启 kean。
```
> ⚠️ 上面第一条 WARN 只在 `read-receipt-enabled=true`（仍推 READ）时才会出现；
> 本轮推荐的 `READ推送=关` 一态下**不会再喊它**（那种组合下双钩不出现是预期结果，喊了反而是噪音）。

> 📌 汇总行的**唯一文案来源**是 `ImRealtimeRoleService.summary()`（启动日志读它）。
> 将来若要在管理端看板上展示，直接读同一个方法即可 ——**不要**在别处另写一份判断，
> 否则就会出现「日志说 A、看板说 B」的分叉（本项目已经因为这种分叉踩过坑）。
> 本轮**刻意不改** `AdminImMonitorVO` / `/api/admin/im/stats` 的形状（遵守「不改现有接口形状」的约束）。

##### 默认 `true` 时为什么零影响（逐条论证）

1. **不产生任何 I/O**：`ImRealtimeRoleService` 不持有连接、不读 Redis、不发 SQL、不新建 DataSource；
   构造期只读几个已经算好的布尔量（`imSenderService.enabled()` / `configuredSource()` / `active()`）。
2. **被包住的两个方法是「纯推送」方法**：`true` 时执行路径就是**原来那段代码**，
   只多了一次布尔判断（`if (!enabled) {...}` 的分支不进入）。
3. **签名 / 参数顺序 / 返回类型全部未改**：`ChatWebSocketHandler.pushMessage(Long, ChatMessageVO)` 与
   `RealtimePublisher.read(Long, Long, Long, Long)` 一个字没动 ⇒ **所有调用点一行都不用改**
   （`ChatServiceImpl` 的两处调用完全没碰）。
4. **只新增一个 Bean 与一行 INFO**：`ImRealtimeRoleService` 是 `@Service`，
   唯一的新输出是启动日志（与 `[IM 镜像投递]` / `[IM 未读来源]` / `[IM 读取来源]` / `[IM 多端同步]` 同一风格）。
5. **配置面零改动**：只用 `@Value("${kean.im.legacy-ws-enabled:true}")` 与
   `@Value("${kean.im.read-receipt-enabled:true}")` 的默认值，
   **不动 `.env*` / `application*.yml`**；无 Flyway、无新依赖、无 im-platform 改动。
6. **不关 NOTICE / BANNED**：通知、公告、封禁提示继续走自研 WS（§1.4 的明确要求），
   所以「非聊天推送」这一类**行为没有任何变化**。

##### ⭐⭐ 自研 WS 退役评估（本轮结论：**不能完整退役**，还差 `NOTICE`）

> 自研 WS 目前承担 **4 类事件**。逐类核对「box 有对等物吗 / 退役后靠什么」：

| 事件 | 推送点 | box 有对等物吗 | 退役后的替代路径 | 结论 |
|---|---|---|---|---|
| `MESSAGE` | `ChatWebSocketHandler.pushMessage` | ✅ 有（box `/im`，kean → `im:message:private:{serverId}`） | box 的 `/im` 独立承担 | ✅ 可退役（自有开关 `legacy-ws-enabled`） |
| `READ` | `RealtimePublisher.read` | ❌ 没有（box 实测不推 READ） | **本轮决策：不再需要**（客户端双勾已移除，无消费方） | ✅ 可退役（新增窄闸 `read-receipt-enabled`） |
| `NOTICE` | `RealtimePublisher.notice`（全仓 **30 个** `notificationService.notifyUser(...)` 调用点最终都汇到这里） | ⚠️ **部分**：`RealtimePublisher.send` 会把它镜像成 box 的 `SYSTEM_MESSAGE(5)`，**但前提是 `IM_JWT_SECRET` 已配置且 `kean.im.mirror-enabled` 生效** | ①（已有）客户端轮询兜底：`useLiveUpdates` 默认 **8 秒**一次 → `refreshMessageBadge()` + 消息页 `load(true)`；②（已有）`pages/message/index.vue` 的 `onShow` 全量刷新；③（已有）`RealtimePublisher.send` 的 box 镜像 | ⚠️ **尚不能完整退役**：8 秒轮询对「通知红点 / 列表」可接受，但**首页 `pages/home/index.vue` 显式传 `pollMs=0`（不轮询）**，它靠 `NOTICE` 事件刷「任务/申请」列表 ⇒ 关掉自研 WS 后那两块只能靠回到页面/下拉刷新 |
| `BANNED` | `AccountBanServiceImpl.pushBanned`（直接 `ChatSessionHub.sendTo`） | ✅ 有（box `FORCE_LOGOUT(2)` + kean 心跳 40301 / `handleAccountBanned`） | box 的强制下线 + 既有 40102 口径 | ✅ 可退役（本条**不需要**新开关，它本来就绕开 `RealtimePublisher`） |

> **结论（本轮交付）**：
> - `READ`：**本轮已经做到「可以被关掉且关掉后功能不缺」**（`kean.im.read-receipt-enabled`，默认 `true`）。
> - `MESSAGE`：C-4 已做到同一件事（`kean.im.legacy-ws-enabled`）。
> - `BANNED`：本来就有 box 对等物（`FORCE_LOGOUT` + 心跳 40301），不需要自研 WS。
> - ⚠️ **`NOTICE` 是唯一还不能退役的一类**：它的替代路径只有「客户端轮询（≤8 秒）」，
>   而 `pages/home/index.vue` 是 `useLiveUpdates(..., 0)`（**0 = 不轮询**）⇒
>   真正停掉自研 WS 之前，**必须先给首页补一条 `NOTICE` 的替代刷新路径**
>   （把 `pollMs` 从 `0` 改成 8000，或改成在 `onShow` 里补一次 `loadList/loadOngoing` 的兜底）。
>   本轮**刻意不动**那两处（属「只动必要处」的边界，且会影响首页请求频率）。
> - 📌 因此本轮**不删自研 WS 的任何代码**：目标只是「**可以被关掉且关掉后功能不缺**」。
>   达到该目标的是 `READ`（本轮）与 `MESSAGE`（C-4）；`NOTICE` 还差上面那一步。

---

### 阶段 D：解锁能力（富媒体 → 群聊，按需）

> 📍 **本阶段的终态位置**：**富媒体与群聊永久归 box**（`im_file_info` / `im_group*` 全家桶）。
> ⚠️ 但**媒体 URL 的签发永久归 kean**（§5.3 的签名 URL 方案）—— 这一条在终态也不变，
> 见 §10.5 终态对照表的「富媒体 / 对象存储」行（标为「**两者**」）。
>
> ⭐⭐ **用户提出的两项目标在本阶段兑现，不在 A/B/C**：
> - **目标①（离线消息与多端同步）**：能力由 C 阶段的 `loadOfflineMessage`（F5）+ `sendToSelf`（F7）提供，
>   **验收在 §3.C.1 第 3、4 条**。→ 属于 **C**，但**用户真正「用得上」要等客户端放量完成**。
> - **目标②（语音 / 文件 / 视频）**：**完全在本阶段（D1）兑现**，C 阶段一点都没有。
> - **D2（群聊）在终态里是「纯新增能力」**，与「换 IM 引擎」无关；**不做群聊也不影响终态成立**。

- **要部署什么**：无新服务（复用 A/B/C 的部署）。
- **D1 富媒体（建议紧跟 C，与目标②直接相关）**：`MessageType.FILE(2)` / `AUDIO(3)` / `VIDEO(4)` 全链路。
  代码改动集中在客户端与 kean 的上传场景（详见 §5.5），服务端 im-platform 无需改动。
- **D2 群聊（按业务需要，可无限期推迟）**：`im_group` / `im_group_member` / `im_group_message`。
  ⚠️ **这是纯新增产品能力**：kean 没有任何群概念，需要新增管理界面、成员规则、与任务/学校的关系定义。
  在业务方没有明确需求前，**不要为了「把 box 用全」而做群聊**。
- **数据要不要动**：D1 需要给 `im_file_info` 写入（自动）；D2 需要建群、加成员。
- **验收 / 回滚**：D1/D2 都是纯增量功能，回滚 = 前端关掉入口。
- **预估人天**：D1 **3 ~ 6 人天**；D2 **10 人天以上**（且需要产品定义，不是纯技术工作）。

---

### 阶段 E ⭐：账号灰度切换（**新增 —— 全站最危险的一段，必须单独排期与回滚演练**）

> 📍 **本阶段的终态位置**：**认证能力永久归 box**。E 阶段走完，**账号真相在 box**，
> `im_user` 不再只是「名片」，而是**真正的账号表**（持有账号名与密码哈希）。
> ⚠️ 但 **`sys_user` 的业务字段与 `status` 的权威仍在 kean**（见 §10.5）。
>
> ⚠️ **为什么认证必须最后迁（§1.5 铁律 L2）**：
> **认证是全站入口，出问题 = 整个 App 不可用；IM 出问题 = 只有聊天不可用。**
> A~D 的全部工作都建立在「kean 的登录永远可用」这个前提上；E 阶段第一次动摇这个前提，
> 所以它必须**最后做**、**最慢做**、**最可回退地做**。

#### 3.E.1 box 侧到底有哪些账号接口（**已核对，这决定了「账号真相归 box」的边界**）

| box 的接口 | 方法 | 作用 | 我们 E 阶段要不要用 |
|---|---|---|---|
| `/login` | POST | `UserServiceImpl.login`：`findUserByUserName` → 查 `is_banned` → `passwordEncoder.matches` → **删 `im:user:denied:{id}`** → 签 accessToken + refreshToken | ✅ **要用**（这是「账号真相归 box」的正主）；⚠️ 它**只认 `im_user.user_name`**，所以影子行的 `user_name` 必须能被用户输入 |
| `/refreshToken` | PUT | 校验 refreshToken 签名 → `getById(userId)` → 查 `is_banned` → 删 `im:user:denied` → 重新签发两个 token | ✅ **要用**（双轨期的续期通道）；⚠️ 见 3.E.4 的 JWT 双轨设计 |
| `/modifyPwd` | PUT | 需要 `accessToken`；`matches(旧密码)` → `encode(新密码)` → `updateById` | ✅ **要用**（改密迁到 box） |
| `/register` | POST | 建 `im_user`，**id 自增分配** | ❌ **绝对不用**（同 id 复用下它必然造出课安不认识的 id，见 §2.5 / H9）；且**从 A 阶段起就在 nginx 拒绝** |
| `/logout` | — | ⚠️ **上游 `LoginController` 里根本没有实现 `logout`**（`exclude-paths` 列了它，但没有对应方法 ⇒ 当前是 404） | 不用（无对等物） |
| **注销账号（delete account）** | — | ⚠️ **box 没有任何「注销账号」接口**：`controller/` 下 10 个类（File / Friend / Group / GroupMessage / Login / PrivateMessage / System / User / WebrtcPrivate）里**没有删除账号的入口**；`User` 实体也**没有 `deleted` 字段** | ❌ **box 不提供注销** ⇒ **注销永远由 kean 承载**（见 3.E.5） |
| `/user/update` | PUT | 允许用户改自己的 `nickName` / `headImage` / `sex` / `signature`（`UserServiceImpl.update` 里还**级联更新好友昵称头像与群成员昵称头像**） | ❌ **A~F 全程都在 nginx 拒绝**；否则它会**覆盖 kean 同步过去的昵称/头像**，并与 kean 的改名联动打架（见 3.E.6） |

> ⚠️ **一个必须记住的推论**：**「账号真相在 box」≠「box 能提供账号的全部生命周期」。**
> box 提供的是：**登录、token 续期、改密**。
> box **不提供**：**注册**（我们**故意**不用，因为它会自增 id）、**注销**、**找回密码 / 换邮箱**（依赖邮箱与验证码，box 完全没有这个概念）。
> 所以 F 阶段的终态是 **「认证的入口归 box，认证的账号生命周期仍有一部分在 kean」** —— 这不是没能力换，而是 **box 里没有这些东西可换**。

#### 3.E.2 密码哈希：**两侧装的都是 BCrypt，但「能验证」不等于「直接可用」**（附兼容方案）

**已核对的事实（两侧源码都读到了）**：

| | kean | box（`im-platform`） |
|---|---|---|
| 编码器 | `SecurityConfig#passwordEncoder()` → **`new BCryptPasswordEncoder()`**（无参构造 ⇒ 强度 **10**） | `MvcConfig#passwordEncoder()` → **`new BCryptPasswordEncoder()`**（**同样的无参构造 ⇒ 强度 10**）；密码列 `im_user.password varchar(255) not null` |
| 存储形状 | `sys_user.password_hash`，BCrypt 字符串（`$2a$10$...` 60 字符） | `$2a$10$...` 60 字符 |
| 校验 | `passwordEncoder.matches(raw, hash)` | `passwordEncoder.matches(dto.getPassword(), user.getPassword())` |

> ✅ **结论（可以依赖）**：两侧都是 **Spring Security 的 `BCryptPasswordEncoder`，默认强度都是 10**，
> 生成的是**同一种 BCrypt 文本**（都是 `$2a$` 前缀）。**因此 kean 的 `password_hash` 可以直接被 box 的
> `passwordEncoder.matches` 验证通过** —— 这意味着**不需要任何「密码迁移/重加密」动作**，
> 用户**改密码前就能在 box 侧登录**，双轨期的用户体验是连续的。
>
> ⚠️ **仍必须实测（不要凭「都是 BCrypt」就上线）**，因为下面几条会让「理论可行」变成「实际不可行」：

| # | 风险 | 为什么 | 兼容方案 |
|---|---|---|---|
| P1 | **前缀不是 `$2a$`**（理论上还可能是 `$2b$` / `$2y$`） | ⚠️ **本条的严重程度已下调**：Spring Security 的 `BCrypt.checkpw` 会**按哈希自带的前缀选算法版本**，`$2a$`/`$2b$`/`$2y$` **都在支持范围内**（见 §12.1 **U25**，此点本文**未实测**，只作为「无需处理」的**待证结论**）。⚠️ **真正会让 `matches` 直接失败**的是「**根本不是 BCrypt 文本**」的历史行（例如带 `{bcrypt}` 前缀、或早期明文/其他算法的残留） | 迁移前跑一次**形状普查**（见下面的 SQL），把**非常规形状**的行**列出来**；对它们单独处理（重加密或强制重置，见 P3） |
| P2 | **kean 有「不可用密码」的故意写法** | `AuthServiceImpl` 注销账号时把 `password_hash` 写成 **`passwordEncoder.encode(UUID.randomUUID().toString())`**（即一个**随机密码的合法 BCrypt 哈希**） | ✅ **这个设计对我们是好消息**：它是**合法 BCrypt**，box 的 `matches` 只会「永远失败」，**不会抛异常**。注销用户的语义（不能登录）**天然被继承** |
| P3 | **强度不同 / 未来提强度** | 若将来把某一侧提到 12，`matches` **仍然可用**（BCrypt 哈希自带 cost，`matches` 按哈希里的 cost 校验） | 采用**登录时重加密（rehash-on-login）**：认证在 box 侧成功后，若发现 `im_user.password` 的 cost 低于目标值，**顺手 `updateById` 写一份新哈希**。⚠️ 这要求**改 box 的代码或加一个钩子**，属于「可选优化」，**不是 E 阶段的前置条件** |
| P4 | **`$2y$` 前缀要不要改写** | ⚠️ **本文此前的建议是「把 `$2y$` 改成 `$2a$`」，但那属于多余动作**：Spring 能直接校验 `$2y$`。⚠️ **而改写前缀本身是「动了哈希文本」的操作**，一旦改错就是**该账号密码永久失效**，风险大于收益 | ✅ **改为：不改前缀**。普查时**只统计、不改写**；若真的遇到 `$2y$`，先用**一个测试账号实测** `matches` 是否通过（大概率直接通过），通过就**原样复制** |
| P5 | **box 的 `password` 列 `not null`，且影子行写的是 `{noop}__disabled__`** | A~B 阶段我们刻意让影子行**不可用 box 登录**（§2.3） | **E 阶段的关键动作**：对**被灰度到 box 认证的账号**，把 `im_user.password` **从 `{noop}__disabled__` 换成 `sys_user.password_hash` 的原值**。⚠️ **这一步必须按账号做（不能全量做）**，否则等于「提前把全站登录入口打开」 |

```sql
-- =====================================================================
-- E 阶段前置：密码哈希形状普查（**只读，安全**；期望看到 100% 是 $2a$10$ 开头）
-- =====================================================================
SELECT LEFT(password_hash, 7) AS prefix, CHAR_LENGTH(password_hash) AS len, COUNT(*) AS cnt
FROM Kean.sys_user
GROUP BY prefix, len ORDER BY cnt DESC;
-- 期望：只有一行 ($2a$10$, 60)。出现别的形状 ⇒ 先处理 P1/P4，**不要开始切认证**。

-- 有没有「不可用密码」的账号（注销过的）？这些账号切到 box 后**同样登不进去**，是符合预期的
SELECT COUNT(*) FROM Kean.sys_user WHERE status = 'DELETED';   -- 状态常量名以 SysUser/UserStatus 为准

-- =====================================================================
-- ⚠️ 灰度开启时：把某批账号的密码从占位换成真哈希（**务必带 WHERE，一次一批**）
-- =====================================================================
UPDATE im_platform.im_user u
JOIN Kean.sys_user s ON s.id = u.id
SET u.password = s.password_hash
WHERE u.id IN (<本批灰度账号的 id 列表>)
  AND u.password = '{noop}__disabled__';      -- 幂等：已经是真哈希的行不会被覆盖
-- ⚠️⚠️ 这条 SQL 是**整个 E 阶段最敏感的一步**：一旦把某个账号的真哈希写进 box，
--       该账号就**可以被 box 的 /login 登录**了。所以它必须与「灰度名单」**同一个事务/同一个脚本**，
--       不允许「先把真哈希全量刷进去、灰度名单以后再补」——那等于全站开放了 box 登录入口。
```

> 🔴 **必须写进变更单的一条**：**「`im_user.password` 是真哈希的账号集合」= 「可以用 box 登录的账号集合」。**
> 这个集合的外延**必须**等于「E 阶段灰度名单」。任何让两者不一致的操作都是**高危**。

#### 3.E.3 灰度怎么做（开关粒度 / 白名单 / 比例）

**核心设计：认证入口的灰度必须是「按账号」的，而且是「服务端判定」的**（不能让客户端自己选）。

| 粒度 | 做法 | 评价 |
|---|---|---|
| **✅ 账号白名单（主力手段）** | `kean.auth.box-login-allowlist` = 一组 userId（或一张 `auth_graylist` 表）。命中 ⇒ 该账号的**客户端被引导到 box 的登录接口** | **推荐**。可控、可解释、可按客服工单单点处理（「把某个用户挪出去」= 删一行） |
| **✅ 比例灰度（辅助手段）** | `kean.auth.box-login-percent`（0~100），对 `hash(userId) % 100 < percent` 的账号启用 | 推荐与白名单**叠加**（白名单永远优先命中） |
| **✅ 单账号强制回退开关** | `auth_box_disabled_{userId}`（Redis 键，或一张 `auth_graylist` 的 `force_kean` 列） | **必需**：出问题时**不必改比例、不必重启**，单点把某个账号按回 kean |
| **❌ 全局一次性开关** | 一个 `KEAN_BOX_AUTH_ENABLED=true` 就全量切 | **禁止**作为唯一手段（违反 §1.5 铁律 L3）。可以作为「总闸」，但必须**与白名单/比例串联** |
| **❌ 客户端自己选** | 客户端 `if (flag) 调 box 登录` | **禁止**。旧版本客户端会永远走老路，灰度不可控、不可观测 |
| ⚠️ 场景化差异 | **登录**是「入口」；**改密 / 找回 / 注销**是「登录后动作」 | 建议**分开灰度**：先切「登录」（收益最大、最可观测），再切「改密」（可单独回退），**注销最后**（一旦切错就是账号状态不一致，见 3.E.5） |

```java
// 灰度判定的形状（示意，落地时按本仓风格实现；**不是**已存在的代码）
boolean useBoxAuth(Long userId) {
    if (boxAuthDisabled(userId)) return false;                 // ① 单账号强制回退，永远优先
    if (boxAuthAllowlist.contains(userId)) return true;        // ② 白名单
    return Math.floorMod(Objects.hash(userId), 100) < boxAuthPercent();  // ③ 比例
}
```

> ⚠️ **可观测性要求（缺一个都不许放量）**：`boxAuthAttempt` / `boxAuthSuccess` / `boxAuthFail` /
> `boxAuthFallbackToKean` 四个计数器，**按 userId 可查**。特别是 `boxAuthFallbackToKean` ——
> **它的突增就是「box 认证出问题、系统正在自动兜底」的唯一早期信号**。

#### 3.E.4 JWT 双轨期怎么衔接（**这是最容易写出安全漏洞的地方之一**）

**先看两套 token 的真实差异（已核对源码）**：

| | **kean 自己的 token** | **box 的 token** |
|---|---|---|
| 签发者 | `AuthServiceImpl` + `SecurityConfig` 的 kean JWT | `UserServiceImpl.login` → `JwtUtil.sign(...)`（`im-common`） |
| 用途 | **kean 全站 API** 的凭证（`JwtAuthFilter`） | **im-server 的 WS 连接** +（可选）`im-platform` 的 REST |
| 密钥 | kean 自己的签名密钥 | `jwt.accessToken.secret` / `jwt.refreshToken.secret`（`application.yml` 默认 `MIIBIjANBgkq` / `IKDiqVmn0VFU`） |
| 有效期 | 以 kean 的配置为准 | `accessToken.expireIn=1800`（30 分钟）/ `refreshToken.expireIn=604800`（7 天）（**F24**） |
| 载荷 | kean 自己的 claims | **`Subject` + `info`（`UserSession` 的 JSON）**；`info.userId`、`aud` 由调用方决定 |
| 关键既成事实 | —— | ⭐ **本仓的 `ImTokenService` 已经在「用 kean 的 `IM_JWT_SECRET` 签发 box 兼容的 token」**，并且**必须显式 HS256**（§0.2 已完成项） |

> ⭐ **双轨期的核心结论：不要试图「让两套 token 合一」，而是「让 kean 继续兼发 box token，直到认证完全归 box」。**
> 理由：**今天就有可用的桥**（`ImTokenService`），而「合一」需要改 box 的鉴权与 im-server 的登录处理，
> 收益为零、风险最高。

| 项 | 设计 | 理由 / 风险 |
|---|---|---|
| **客户端先拿哪个** | 双轨期客户端**先走 kean 的登录**拿到 kean token；再按需调 `GET /api/im/token` 换 box token 连 WS。**这与今天的行为完全一致** | ✅ **零客户端改动**（A~D 阶段已经这样跑）。E 阶段只是**新增**一条「灰度账号走 box 登录」的路径 |
| **box 登录成功后，kean 的 API 怎么办** | ⚠️ **必须解决**：box 签的 token **不是** kean 的 API 凭证，`JwtAuthFilter` **不认**它 | ✅ **三个可选方案**（推荐第 2 个）：<br>① 让 box 登录接口**由 kean 代理**（客户端只调 kean 的 `/api/auth/login`，kean 转发给 box，再把 box 的 LoginVO **换成 kean 自己的 token**）→ **推荐，客户端零改动**；<br>② kean 增加一个「认 box refreshToken」的兑换接口（客户端拿 box token 换 kean token）；<br>③ 让 `JwtAuthFilter` 也认 box 的 token（**不推荐**：把 box 的密钥与 kean 的鉴权链耦合，且密钥一旦轮换就全站失联） |
| **过期衔接** | ⚠️ **两套 token 的生命周期不同**（kean 的与 box 的 1800s/604800s 各自独立） | ✅ **必须处理「一侧过期、另一侧还有效」**：客户端遇到 **kean token 过期** → 先尝试**续期（kean 或 box，按灰度名单决定）** → 续期失败才跳登录页。⚠️ **绝不允许**「box token 还有效就连 WS，但 kean API 401 之后直接把人踢到登录页」—— 用户会看到「刚登录就被踢」 |
| **`refreshToken` 走谁** | 双轨期**以 kean 的续期为主**；灰度账号可以走 box 的 `/refreshToken` | ⚠️ box 的 `/refreshToken` 会**顺带检查 `is_banned` 并删除 `im:user:denied`**（源码已核对）⇒ 见 3.E.5 的一致性陷阱 |
| **旧的 box 兼容 token 与新的 box 真 token** | 二者**同密钥、同形状**（`info.userId` = kean userId），所以 **im-server 与 im-platform 区分不了它们** | ✅ 这是**优点**：token 来源切换对 IM 侧**完全透明**。⚠️ 但也是**缺点**：**无法通过 token 形状判断「这是哪条认证链路签的」**，所以观测必须靠**服务端计数器**，不能靠解 token |

#### 3.E.5 注销 / 封禁 / 改密在双轨期的一致性（**最容易出安全漏洞的地方**）

> ⚠️ **一句话风险**：**「一侧生效、另一侧不生效」= 漏一处即失效。**
> 用户在 box 侧被封禁，却在 kean 侧照常登录；或者在 kean 侧改了密码，box 侧还是旧密码 ——
> **这两件事都不报错、都只有用户能发现，而攻击者正好可以利用它们。**

**必须遵守的设计原则（按优先级）**：

| # | 原则 | 落地 |
|---|---|---|
| **1** | **⭐ 双轨期「写权威」永远只有一个：kean** | 封禁 / 解封 / 注销 / 改密**全部**先写 kean，再由 `ImKickService` 与同步服务**投影**到 box。**绝不允许**让 box 成为封禁的写入方（因为 box 没有管理端接进我们的权限体系） |
| **2** | **⭐ 双轨期禁止调用 box 的「写账号」接口** | 具体：`/register`、`/logout`、`/modifyPwd`、`/user/update` **在 nginx 层全部 `return 404`**（A 阶段就做，E 阶段**除了「改密灰度」之外仍然保持拒绝**）。**这是最省事、最可靠的一条** —— 只要客户端调不到，就不存在「一侧生效」 |
| **3** | ⚠️ **box 的 `/refreshToken` 有一个「解封」副作用** | 源码：`refreshToken` 成功后会 `redisTemplate.delete("im:user:denied:{userId}")`。**如果**双轨期允许客户端直接调 box 的 `/refreshToken`，那么**一个被封禁的账号只要它的 refreshToken 还没过期，就能靠刷新 token 把 `im:user:denied` 删掉** ⇒ **封禁被绕过**。⚠️ **缓解**：① box 的 `refreshToken` 会先查 `im_user.is_banned`，所以**只要 `is_banned` 也是 1，它就抛「已被封禁」**（不会删键）⇒ **`im_user.is_banned` 的同步因此从「纵深防御」升级为「必需项」**；② 若不放心，**在 nginx 层连 `/refreshToken` 一起拒绝**，双轨期的续期**全部走 kean**。⭐ **推荐 ②，因为它把不确定性彻底删掉** |
| **4** | **注销（delete account）只有 kean 有** | 已在 3.E.1 证实 box **没有**注销接口 ⇒ 注销**永远由 kean 执行**。执行时必须**同时**：① 写 `im:user:denied = Integer 2`（`UNREG` 语义，F4）；② `UPDATE im_user SET is_banned=1, reason='账号已注销'`；③ 把 `im_user.password` 换成**随机 UUID 的 BCrypt 哈希**（与 kean 侧的做法一致，见 3.E.2 的 P2）。**三条缺一不可**，且**都在同一个失败可重试的流程里** |
| **5** | **改密必须在两侧同时生效** | kean 侧改密成功后，**必须**追加 `UPDATE im_user SET password = <kean 的新哈希> WHERE id = ?`。因为两侧哈希格式相同（3.E.2），**这一条是「直接赋值」而不是「重新加密」**，实现成本极低。⚠️ **漏掉的后果**：用户在 kean 改了密码，**旧密码仍能在 box 侧登录**（若该账号已被灰度到 box 认证）—— **这是最典型的「改密不同步」漏洞** |
| **6** | **封禁/解封必须同时写 4 处**（沿用并与 H3 合并） | ① `sys_user.status`（权威）；② `kean:user:banned:{userId}` + `TokenRevokeService`；③ `im:user:denied:{userId}`（**Integer**，im-server 与 im-platform 都看它）；④ `im_platform.im_user.is_banned` + `reason`（**E 阶段起它不再只是「给人看的」，而是 box 登录与 refreshToken 的判定依据**） |
| **7** | **双轨期的「一致性测试」是必做项，不是可选项** | 见 3.E.7 的测试矩阵 —— **每一条都要能自动化跑**，否则「漏一处」只能靠用户投诉发现 |

> 🔴 **E 阶段最重要的单条结论**：
> **双轨期最大的安全风险不是「token 被伪造」，而是「同一个账号状态在两套系统里不一致」。**
> 前者有签名保护，后者**没有任何机制会提醒你**。

#### 3.E.6 E 阶段必须一并处理的两件小事（不做会留下长期隐患）

1. **`/user/update` 必须在 nginx 长期拒绝**（不只是 A~D 阶段）：
   box 的 `UserServiceImpl.update` 会**级联更新 `im_friend.friend_nick_name/friend_head_image` 与
   `im_group_member.user_nick_name/head_image`** —— 这是一条**绕过 kean 的改名通路**。
   一旦打开，用户改一次昵称，box 侧的好友/群成员昵称就与 kean 侧**永久分叉**（下次 kean 改名又会覆盖回来，形成来回打架）。
   > ✅ 终态的正确做法：**昵称/头像的真相永远在 kean 的 `sys_user`**，由 kean 单向投影到 `im_user`（§2.4 的联动矩阵）。
2. **`/user/find/{id}` 与 `/user/search` 的信息暴露面要评估**：
   `UserController.findSelfInfo` **不判空**（`getById` 可能返回 `null` ⇒ 返回 `data: null`），
   而 `/user/search` 可以**按昵称 LIKE 搜出任意用户**。二者在 kean 的 `sys_user` 上**都有对应的权限边界**
   （学校/校区隔离、封禁态、敏感词）。E 阶段若把用户信息读取也迁到 box，**必须先把这些边界补上**；
   **若不做，就保持「用户信息仍由 kean 提供」**（推荐，见 §10.5）。

#### 3.E.7 E 阶段的验收清单（**每条都必须自动化**；这是「漏一处即失效」的唯一防线）

```bash
# ---- 1) 灰度名单生效：名单内账号走 box 认证，名单外账号仍走 kean ----
#   名单内（灰色账号）：
curl -s -X POST https://api.kean.college/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"<灰度账号>","password":"<正确密码>"}' | head -c 300
#   期望：200 + 拿到 kean 的会话凭证（**由 kean 代理/兑换，客户端无感**，见 3.E.4 方案①）
#   名单外（对照账号）：
curl -s -X POST https://api.kean.college/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"<名单外账号>","password":"<正确密码>"}' | head -c 300
#   期望：同样 200，但日志里这条**走的是 kean 的原路径**（用 §3.E.4 的计数器区分）

# ---- 2) ⭐ 密码哈希兼容性实测（**不要凭「都是 BCrypt」跳过**）----
#   取一个「已把真哈希刷进 im_user」的灰度账号，用 box 的 /login 直接验一次：
curl -s -X POST https://api.kean.college/im-api/login \
     -H 'Content-Type: application/json' \
     -d '{"userName":"kean_<userId>","password":"<该账号的真实密码>","terminal":1}' | head -c 300
#   期望：200 + accessToken/refreshToken（**证明 kean 的 BCrypt 哈希能被 box 验证**）
#   ⚠️ 若返回「密码错误」⇒ 立刻停止灰度，回到 3.E.2 逐条排查 P1~P4。
#   ⚠️ 测完**立刻**把 /im-api/login 在 nginx 重新 404 掉（见原则 2），或只在内网调。

# ---- 3) ⭐ 封禁一致性（四侧都要查；期望四侧同时为「封禁」）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT status FROM Kean.sys_user WHERE id = <U>;
  SELECT is_banned, reason FROM im_platform.im_user WHERE id = <U>"'
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 --raw GET "kean:user:banned:<U>"
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 OBJECT ENCODING "im:user:denied:<U>"
#   期望：status=BANNED、is_banned=1、kean 键存在、im:user:denied 的编码是 **int**（F3）
#   然后**用 box 的 /login 试一次**（在内网直连 8888）：期望被拒（「已被封禁」）

# ---- 4) ⭐ 改密一致性（**最容易漏的一条**）----
#   在 kean 改密成功后，**旧密码必须两处都登不进去、新密码必须两处都能登进去**：
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, LEFT(password_hash,7) AS kean_prefix FROM Kean.sys_user WHERE id=<U>;
  SELECT id, LEFT(password,7)       AS box_prefix  FROM im_platform.im_user WHERE id=<U>"'
#   期望：两边的**哈希字符串完全相等**（因为格式相同，改密时是直接赋值，见原则 5）
#   ⚠️ 不等 ⇒ 「改密不同步」漏洞已存在，**立即停止灰度并修**。

# ---- 5) 注销一致性（box 没有注销接口 ⇒ 注销后 box 侧必须三个条件都满足）----
docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT is_banned, reason FROM im_platform.im_user WHERE id=<U>"'
#   期望：is_banned=1、reason='账号已注销'
docker exec -i $REDIS_CTR redis-cli -a "$REDIS_PASSWORD" -n 0 --raw GET "im:user:denied:<U>"
#   期望：2（= IMForceLogoutType.UNREG ⇒ im-platform 回「账号已注销」而不是「已被封禁」）

# ---- 6) ⭐ 单账号强制回退可用（**演练项，必须真跑一次**）----
#   对一个「已灰度到 box 认证」的账号打开 force_kean 开关，然后：
#   期望：下一次登录**立刻**回到 kean 链路（**不重启服务、不改比例**），且登录成功
#   ⚠️ 这条演练没做过，就不许把比例往上调。

# ---- 7) 双轨期不许出现的调用（**安全基线**）----
for p in /im-api/register /im-api/logout /im-api/modifyPwd /im-api/user/update /im-api/refreshToken; do
  printf '%-28s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code}' -X POST https://api.kean.college$p)"
done
#   期望：全部 404（或 405）。**任何一个返回非 404，都说明「一侧生效」的通道还开着。**
```

#### 3.E.8 E 阶段的回滚（**必须能在 5 分钟内完成，且不需要重新发布**）

| 优先级 | 动作 | 耗时 | 效果 |
|---|---|---|---|
| 1 | **把比例调回 0**（`kean.auth.box-login-percent=0`） | < 1 分钟（配置热更新）或 ~1 分钟（重启） | 所有账号回到 kean 认证；**`im_user.password` 里已有的真哈希不必清理**（它们只是「可以被 box 登录」，而 box 的 `/login` 在 nginx 是 404，所以**没有任何可利用的路径**） |
| 2 | **给报障账号打 `force_kean`** | 秒级 | 单点恢复，不影响其他人 |
| 3 | **nginx 把 `/im-api/login`、`/im-api/refreshToken` 重新 404** | < 5 分钟 | 即使灰度名单还在，**box 的认证入口也彻底关闭**（回到 A~D 的状态） |
| 4 | ⚠️ **不要**去批量恢复 `im_user.password` 为 `{noop}__disabled__` | —— | **不必要且有风险**：3 的动作已经让这些哈希**不可达**。批量改密码列是**最容易改错**的操作（`UPDATE` 漏 `WHERE` 就等于废掉全站密码） |

> ⚠️ **E 阶段的回滚比 A~D 简单，但比它们危险** —— 因为回滚的**判定**很难：
> 「登录失败率上升」既可能是 box 认证的问题，也可能是网络、也可能只是用户在输错密码。
> 所以 E 阶段的**第一优先观测指标不是「登录成功率」，而是「`boxAuthFallbackToKean` 的突变」**（3.E.3 的计数器）。

---

### 阶段 F：收尾（**终态固化：账号真相在 box，业务仍在 kean，且无需改任何外键**）

> 📍 **本阶段的终态位置**：这就是 §1.5 定义的终态。**F 阶段不切换任何东西**，它只做三件事：
> **① 确认 E 已 100%；② 把「谁归谁」写成清单并冻结；③ 把只为双轨期存在的代码/开关/通道删干净。**

**F-1 达到「账号真相在 box」的判据（全部满足才算到 F）**：

- [ ] 认证灰度 **100%**，且**观察 14 天**没有回退到 kean 的记录（`boxAuthFallbackToKean` 平直）；
- [ ] `im_user.password` 对**全部有效账号**都是真哈希（除注销账号是随机哈希）；
- [ ] **注册**仍由 kean 承担（box 不提供注册，**这是终态的一部分，不是遗留问题**）；
- [ ] **注销**仍由 kean 承担（同理，见 3.E.1）；
- [ ] `/im-api/register`、`/im-api/logout`、`/im-api/user/update` 在 nginx **永久 404**；
- [ ] `/im-api/login`、`/im-api/refreshToken` 的**可达性有明确决策**（要么对客户端开放、要么继续只让 kean 内网调用）。

**F-2 ⭐「到此为止仍然留在 kean 的东西」清单（终态口径，逐条都要有人负责）**：

| # | 留在 kean 的东西 | 为什么留 | 终态下它的角色 |
|---|---|---|---|
| 1 | **`sys_user` 的全部业务字段** | 学校 / 校区 / 角色 / 昵称 / 头像 / `status` / `forbid_*` / `muted` / 各类计数，box 一个都没有 | **业务用户主档**（id 与 `im_user` 同值） |
| 2 | `sys_user.status` + `kean:user:banned:{userId}` | 封禁的**管理动作**来自 kean 的管理端与申诉流程 | **封禁权威**（box 侧是镜像） |
| 3 | **注册**（含邮箱验证码 / 图形验证 / 学校选择） | box **没有注册的配置项，我们也不用它的 `/register`**（会自增 id） | 注册入口 |
| 4 | **注销账号** | box **没有这个接口**（3.E.1 已核对） | 注销入口 |
| 5 | **找回密码 / 改邮箱** | box **完全没有邮箱与验证码概念** | 账号恢复 |
| 6 | **学校 / 校区 / 任务 / 履约 / 申请 / 评价 / 举报 / 申诉** | box 里**没有任何可对等的东西** | 业务主体 |
| 7 | **通知 / 公告**（含 `RealtimePublisher`、BANNED 推送、站内通知） | box 只有 IM 消息，**没有通知模型**；`/ws/chat` 的**非聊天推送必须保留** | 通知系统 |
| 8 | **管理端 `web-kean`**（含 IM 看板 `/api/admin/im/*`） | box 的 `box-im-admin` 是**独立仓库**，接不上我们的权限体系与业务视角 | 管理后台 |
| 9 | **禁言 / 拉黑 / 互动限制** | box **没有这两个模型**（H3 已述） | 聊天前置校验（**必须在 kean 侧**） |
| 10 | **对象存储的签名 URL 签发**（`FileUrls` / `FileUrlSigner` / `FileAccessGuard`） | RustFS 私有桶，box 只会拼永久公开 URL（§5.2/F15/F16） | 媒体访问控制 |
| 11 | **`Kean.chat_message` 的历史行** | §4 的明确决策：不搬 | 只读历史归档 |
| 12 | **`kean` 的 `/ws/chat` 通道** | 承载第 7 条的非聊天推送；也是**IM 的兜底通道** | 兜底 + 非聊天实时推送 |

**F-3 F 阶段可以删掉的东西（删干净才算收尾，否则技术债永久留存）**：

| 可删项 | 判据 |
|---|---|
| `KEAN_IM_PLATFORM_ENABLED` 双写开关与双写代码 | 阶段 C 之后它就是死代码；**保留它 = 保留「某天有人打开它」的风险** |
| `ChatSeqService.allocateNext` 的**调用点**（阶段 C 已删调用） | 若类本身只剩历史用途，**加注释说明为何保留**（历史行重排序可能要它） |
| kean 的 `/ws/chat` **实时聊天**分支 | ⚠️ **只删聊天分支**，第 12 条的「兜底 + 非聊天推送」**不许删** |
| kean 的 `single_device`（单设备）策略对 IM 的影响 | 阶段 C 起踢线归 box（§6.4.1）；**策略本身若还用于自研通道则保留** |
| `audit`/临时灰度表 `auth_graylist` | 100% 之后它只是「谁能回退」的记录 —— **建议保留但只读**，作为应急资产而非删除 |
| ⚠️ **不要删**：`im_platform` 库、`im_user` 影子行、任何历史消息、任何 `im:*` Redis 键规范 | 见 §8.3 最后一条 |

- **预估人天**：**3 ~ 5 人天**（清理 + 清单固化 + 一次「E 已 100%」的完整回归）。

---

## 4. ⭐ 数据策略（历史消息搬不搬 —— 不可逆点）

### 4.1 ✅ 建议：**不搬。原表只读保留 + 客户端两段式读取。**

| 选项 | 结论 |
|---|---|
| 把 kean 的 `chat_message` 搬进 `im_private_message` | ❌ **不做** |
| kean 的 `chat_message` 保留、置为**只读历史归档** | ✅ 做 |
| 客户端按时间点**两段式读取**（老消息读 kean，新消息读 box） | ✅ 做 |
| 迁移期删除 kean 原表 | ❌ **绝对不做** |

### 4.2 为什么不搬（理由按严重程度排序）

1. **`seq_no` 必须按会话重排 —— 这一步本身就是有损的。**
   box 的 `seq_no` 语义是「**单个会话消息的序号连续递增**」（建表注释原文），
   而 kean 的 `seq_no` 是**从 V34 才开始分配**的，历史行是 `NULL`（`ChatMessage` 的字段注释写明「历史行可能为 NULL」）。
   要把历史行搬进去，必须按 `(created_at, id)` 升序**为每个会话重排**一遍，
   而重排出来的序号与客户端**已经缓存在本地的游标**（`chatStore` 里的 `serverMaxSeq`）**对不上** ——
   客户端会以为自己已经读到 500，而重排后同一条消息可能变成 800，导致**漏消息或重复消息**。
2. **逐条已读无法还原。** kean 只有**位点**（`a_read_seq` / `b_read_seq` / `read_at`），
   而 box 的已读是**逐条 `status=3`**（F11）。从位点反推「哪几条被读过」在语义上是可行的，
   但 kean 的位点是**后加**的（历史行为 `NULL`），对历史消息**根本无法还原**，
   只能像下面 §4.3 那样「全置为已读」—— 等于**告诉用户「你早就读过这些消息了」**。
3. **媒体路径不同构。** kean 存的是**对象键**（`chat/{userId}/{uuid}.ext`），
   box 存的是**完整公开 URL**（F15：`{domain}/{bucket}/{image|file|video}/{name}`）。
   搬过去必须为每条图片消息**构造**一个 box 形状的 URL，而 kean 的键在 RustFS 私有桶里、
   **没有** `image/` 这一层（见 §5.2）—— 等于要伪造一个 URL 结构。
4. **搬了也没人读。** 阶段 C 之后，客户端的历史消息**仍然可以**从 kean 读（那本来就是课安自己的数据），
   box 的 `loadOfflineMessage` 只负责**「我离线期间的增量」**（F5：`id > minId` 且 60 天窗口）。
   也就是说：**新消息进 box，历史留在 kean**，本来就是一个能自洽工作的结构。
5. **可回滚性。** 不搬 = kean 的历史数据完整无损，任何阶段回滚都不会丢历史。
   搬了 = 一旦上游表结构变化 / 迁移脚本有 bug，**历史消息不可恢复**。

### 4.3 若确实要搬：具体搬法（附校验 SQL）

> ⚠️ **前置：必须在 kean 库做一次全量备份，并在 `im_platform` 侧先演练一遍。**
> ⚠️ **`conv_key` 的拼法以 `ConvUtil.buildConvKey` 为准**（本文按「小的在前、下划线连接」书写，**未逐字核对源码**，见 §12）。

```sql
-- ============================================================
-- 0) 前置：备份（两条都必须做）
-- ============================================================
-- kean 库：见 docs/ops/rustfs.md §10 的 mysqldump 写法
-- im_platform 库：搬之前先 dump 一份空库结构，便于"就地重来"

-- ============================================================
-- 1) 建立「会话 → 新 seq_no」的映射表（一次性，放在 im_platform 库）
-- ============================================================
CREATE TABLE IF NOT EXISTS im_platform._mig_seq_map (
    session_id BIGINT NOT NULL,
    old_id     BIGINT NOT NULL,
    new_seq    INT    NOT NULL,
    PRIMARY KEY (session_id, old_id),
    KEY idx_new_seq (session_id, new_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 按 (created_at, id) 升序，为每个会话重排序号（从 1 开始）
INSERT INTO im_platform._mig_seq_map (session_id, old_id, new_seq)
SELECT session_id,
       id,
       ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at ASC, id ASC)
FROM Kean.chat_message
WHERE deleted = 0;        -- 逻辑删除的不搬（搬了客户端会看到已删消息）

-- ============================================================
-- 2) 搬消息本体
--    ⚠️ 只搬一对一的两端：conv_key 由 user_a_id/user_b_id 推出
--    ⚠️ type 映射：TEXT→0，IMAGE→1（kean 只有这两种，见 ChatServiceImpl.send 的类型校验）
--    ⚠️ status **只能全置 1（已送达）**：历史逐条已读无法还原，见 §4.2 第 2 条
-- ============================================================
INSERT INTO im_platform.im_private_message
    (local_id, seq_no, send_id, recv_id, conv_key, content, type, status, send_time)
SELECT COALESCE(NULLIF(c.local_id, ''), CONCAT('mig', c.id)),   -- localId 必填且 <=32 字符
       m.new_seq,
       c.sender_id,
       CASE WHEN c.sender_id = s.user_a_id THEN s.user_b_id ELSE s.user_a_id END,
       CONCAT(LEAST(s.user_a_id, s.user_b_id), '_', GREATEST(s.user_a_id, s.user_b_id)),
       c.content,
       CASE WHEN c.msg_type = 'IMAGE' THEN 1 ELSE 0 END,
       1,                                                        -- 全置"已送达"
       c.created_at
FROM Kean.chat_message c
JOIN Kean.chat_session s ON s.id = c.session_id
JOIN im_platform._mig_seq_map m ON m.session_id = c.session_id AND m.old_id = c.id
WHERE c.deleted = 0;

-- ============================================================
-- 3) 清理映射表
-- ============================================================
DROP TABLE im_platform._mig_seq_map;
```

**搬完的校验 SQL（全部要过）**

```sql
-- 3.1 序号无重复、无 NULL，且与条数一致
SELECT session_id, COUNT(*) AS cnt, COUNT(DISTINCT seq_no) AS d, MIN(seq_no) AS mn, MAX(seq_no) AS mx
FROM im_platform.im_private_message
GROUP BY conv_key
HAVING d <> cnt OR mn <> 1 OR mx <> cnt;
-- 期望：0 行（注意 HAVING 里要用 SELECT 中的别名时需按你的 MySQL 版本调整）

-- 3.2 每一行的 conv_key 两端确实是 send_id / recv_id
SELECT COUNT(*) FROM im_platform.im_private_message
WHERE conv_key <> CONCAT(LEAST(send_id, recv_id), '_', GREATEST(send_id, recv_id));
-- 期望：0

-- 3.3 local_id 长度合规（建表是 varchar(32)，超长会被静默截断 → 破坏幂等）
SELECT MAX(CHAR_LENGTH(local_id)) FROM im_platform.im_private_message;
-- 期望：<= 32

-- 3.4 两端用户的影子行都在（否则 box 的会话列表会缺人）
SELECT DISTINCT send_id FROM im_platform.im_private_message
WHERE send_id NOT IN (SELECT id FROM im_platform.im_user);
SELECT DISTINCT recv_id FROM im_platform.im_private_message
WHERE recv_id NOT IN (SELECT id FROM im_platform.im_user);
-- 期望：两个都 0 行

-- 3.5 总数对得上（排除逻辑删除后）
SELECT COUNT(*) AS moved FROM im_platform.im_private_message;
SELECT COUNT(*) AS should_move FROM Kean.chat_message WHERE deleted = 0;
-- 期望：两者相等

-- 3.6 位点交接（§3.C.1 第 2 条的同一个查询）
--    搬完之后 kean 的 last_seq_no 与新表的 max(seq_no) **不再相等**（因为重排了），
--    所以这一步的正确做法是：**搬完后停止使用 kean 的位点**，把客户端游标**整体重置**到
--    "已读位置 = 该会话的 max(seq_no)"，见 §6.3 的收敛方案。
```

### 4.4 迁移期的铁律

| 铁律 | 原因 |
|---|---|
| ❌ **绝不删 kean 的 `chat_message`** | 它是历史消息的唯一完整副本，也是回滚的最后依靠 |
| ✅ 把 kean 的读路径降级为**只读** | 避免双写期间两边都有人写，导致分叉 |
| ✅ 所有迁移脚本**先 dry-run**（把 `INSERT` 换成 `SELECT COUNT(*)`） | 4.3 的语句一次性跑错就得从备份恢复 |
| ❌ 不要在业务高峰跑 | `INSERT ... SELECT` 跨库搬全表会长时间占资源 |

---

## 5. ⭐ 富媒体映射（对应目标②）

### 5.1 结论先行

> **把 box 的对象存储配置指向现有 RustFS（`minio.endpoint` → RustFS 的 S3 API），
> 但把 `minio.domain` 指向 kean 的 `/api/files/**` 鉴权代理，并且——由于 RustFS 不支持 ACL、
> 我们的桶必须保持私有——「公开 URL」这条路整体作废，统一走 kean 自己的临时签名 URL。**

### 5.2 冲突的本质：两条访问路径的权限模型完全不同

| | **kean 现在（私有桶 + 鉴权代理）** | **box 默认假设（公开桶 + 永久 URL）** |
|---|---|---|
| 桶权限 | **私有** | **公开可读**（F16：启动时 `setBucketPublic`） |
| 客户端拿到的 URL | `/api/files/{objectKey}?exp=&sig=`（`FileUrls.of` + `FileUrlSigner.sign`） | `{domain}/{bucket}/{path}/{name}`（F15，**永久有效、无签名**） |
| 鉴权 | `FileUrlSigner.verify`（HMAC + 2 小时 TTL）**或** `FileAccessGuard.canRead`（按目录逐类判归属） | **无**。任何拿到 URL 的人都能读 |
| 敏感目录 | `report` / `appeal` / `chat` / `fulfill` 需要签名 + 归属校验（`FileUrlSigner.SENSITIVE_FOLDERS`） | 无此概念 |
| nginx | **明确禁止**把对象存储反代到公网（`nginx.conf.example` 第 4 节） | 生产配置里 `domain=https://www.boxim.online/file` —— 就是一个**公网静态路径** |
| RustFS 能力 | —— | ⚠️ RustFS 的 S3 兼容矩阵写明**完全不支持 ACL 授权**（本仓 `docs/ops/rustfs.md` §2 已引用） |

**为什么不能妥协成「把桶改成公开」**：桶里除了聊天图片，还有 `report`（举报证据）、`appeal`（申诉）、
`fulfill`（履约照片）、用户头像。**把桶设为公开可读 = 任何知道对象键的人都能读到全部举报证据与履约照片。**
这是不可接受的安全降级，**没有任何「先上线再补」的空间**。

### 5.3 ✅ 推荐方案：统一走 RustFS + kean 后端签发临时 URL

| 项 | 配置 | 说明 |
|---|---|---|
| `minio.endpoint` | `http://127.0.0.1:19000` | RustFS 的 S3 API。宿主机端口取自 `.env.prod` 的 `STORAGE_PORT`（当前线上是 `19000`）；⚠️ **本仓 `.env.prod` 里写的是 `STORAGE_ENDPOINT=http://156.224.78.44:19000`（公网 IP）**，同机进程用 `127.0.0.1` 更稳 |
| `minio.accessKey` / `secretKey` | 与 `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` **完全一致** | RustFS 侧是 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` |
| `minio.bucketName` | `kean` | 与 `STORAGE_BUCKET` 一致。**复用同一个桶，不新建桶** |
| `minio.imagePath` | `image` | 默认值，保持 |
| `minio.filePath` | `file` | 默认值，保持 |
| `minio.videoPath` | `video` | 默认值，保持 |
| `minio.expireIn` | **建议设成一个极大值（如 36500）** | ⚠️ F18：`FileExpireTask` 会**真的删对象**。见下面「三个必须处理的坑」 |
| `minio.domain` | **`https://api.kean.college/api/files`** | 使 box 生成的 URL 变成 `https://api.kean.college/api/files/kean/image/{name}`，**恰好落在 kean 的鉴权代理上** |

**这么配之后，box 生成的 URL 会被 kean 的代理「读懂」吗？—— 会，但必须处理三个坑：**

#### 坑 1️⃣：URL 里多了一层 `kean/`（桶名）与 `image/`，而 kean 的对象键没有这两层

- box 生成的：`https://api.kean.college/api/files/kean/image/abc.jpg`
- kean 的 `FileUrls.objectKey()` 会剥掉 `/api/files/` 前缀，得到 `kean/image/abc.jpg`
  → 作为对象键去 RustFS 查 → **不存在**（kean 真实键是 `chat/123/uuid.jpg` 或 `image/123/uuid.jpg` 这类）。

**处理（三选一，推荐第 2 个）**：

1. 在 kean 的 `FileController` 里加一条「剥掉前两段」的兼容逻辑 —— 会污染一个已经很干净的安全边界，**不推荐**；
2. ✅**推荐**：让**客户端**永远只持有 kean 形状的 URL / 对象键，**不把 box 生成的 `file_path` 直接交给前端**。
   具体做法：box 的 `im_file_info.file_path` 照常写（它自己要用），但 kean 在返回消息给客户端时，
   把 `content` 里的 URL **重新规范成 `/api/files/{objectKey}` 并签名**（复用现有 `FileUrls.of`）。
   这样客户端只有一套 URL 语义，且**永远带签名**；
3. 把 `minio.bucketName` 也拼进 `domain`（即 `domain=https://api.kean.college/api/files/kean/image`），
   让 box 生成 `.../api/files/kean/image/kean/image/...` —— **更糟，不要**。

#### 坑 2️⃣：RustFS 私有桶 ⇒ box 生成的永久 URL 一定 403

即使形状对了，RustFS 私有桶也不会对无签名的 GET 返回 200。
所以**必须**让客户端拿到的每一个 URL 都带上 kean 的 `exp` + `sig`。
这正是推荐方案里「客户端只拿 kean 签名 URL」的原因 —— 它**同时解决坑 1 与坑 2**。

#### 坑 3️⃣：⚠️ `FileExpireTask` 会因 URL 形状不对而**抛异常 / 删错对象**（F19）

```java
// im-platform/.../task/schedule/FileExpireTask.java
String relativePath = url.substring(fileInfo.getFilePath().indexOf(minioProps.getBucketName()));
String[] arr = relativePath.split("/");
String path  = arr[1];
String fileNme = StrUtil.join("/", arr[2], arr[3]);
```

它**硬假设** `file_path` 形如 `{domain}/{bucket}/{path}/{name}`（正好 4 段以上）。
如果 `domain` 指向 `/api/files`，`indexOf(bucketName)` 之后的段数会变化，`arr[1..3]` 可能取到错误的段，
甚至 `ArrayIndexOutOfBoundsException`。**更危险的是**：它按取错的 `path`/`fileNme` 去删对象 —— **可能删掉别的文件**。

**三个可选处置（按推荐度）**：

| # | 做法 | 评价 |
|---|---|---|
| 1 | ✅ **把 `minio.expireIn` 设为 `36500`（100 年）** | 最小改动、零代码。`loadBatch` 的判据是 `upload_time <= now - expireIn 天`，100 年后才可能命中 ⇒ **任务永远查不到记录** ⇒ 永不删对象。**这是推荐做法** |
| 2 | 保留 `file_path` 为 box 形状（即 `domain` 指向一个**真实可访问但只对内**的地址），另外给客户端一律换签名 URL | 需要额外维护一个「对内可访问的 domain」，复杂度高 |
| 3 | fork 掉 `FileExpireTask` | 又一处分叉，不划算 |

> ⚠️ **无论选哪个，都要先确认**：RustFS 私有桶 + 无签名 URL ⇒ `minioService.isExist(...)` 在 `FileExpireTask` 里
> 是否会把「存在但无权读」误判成「不存在」（那样反而不会删）。**这一点未证实**（§12），
> 所以**不要**把「它不会删」当作默认预期 —— 请按做法 1 设置 `expireIn` 来保证安全。

### 5.4 box 的 `MessageType` 取值与 `content` 语义（F8 + F 见 `validMessage`）

| type | 名称 | `content` 的语义 | 校验 |
|---|---|---|---|
| `0` | TEXT | **纯文本**，最长 **1024** 字符（F6） | `validMessage` 校验长度 |
| `1` | IMAGE | **JSON 字符串**（`validMessage` 对非 TEXT 一律 `JSON.parse` 校验） | 必须是合法 JSON |
| `2` | FILE | **JSON 字符串** | 同上 |
| `3` | AUDIO | **JSON 字符串** | 同上 |
| `4` | VIDEO | **JSON 字符串** | 同上 |
| `10` | RECALL | JSON：`{"id":<被撤回的消息id>,"tip":"xxx 撤回了一条消息"}`（`recallMessage` 原文） | 服务端生成 |
| `11` | READED | 只在 `sendToSelf` 上出现，通知**自己的其它终端**「已读位置变了」 | 服务端生成 |
| `12` | RECEIPT | JSON：`{"id":<最大已读的消息id>}`，推给**对方** | 服务端生成 |
| `21` | TIP_TEXT | 纯文本提示（如「你们已成为好友…」） | —— |
| `20` | TIP_TIME | 时间提示 | —— |
| `80..84` | FRIEND_* | 好友变化 | —— |
| `90..93` | GROUP_* | 群变化 | —— |

> ⚠️ **`content` 的确切 JSON 结构未证实**：`PrivateMessageDTO` 只写了 `@NotEmpty`，
> `validMessage` 只保证「是合法 JSON」。图片/语音/文件/视频各自的确切字段名**必须**从
> `im-uniapp` / `im-web` 的消息组件里读出来（§12 已列为未证实项）。**不要凭猜测约定字段名。**

**「怎么让 box 的存储指向 RustFS」的验证要点**

```bash
# 1) 上传一张图片（需要 accessToken，见 §5.3）
curl -s -X POST 'https://api.kean.college/im-api/image/upload' \
  -H "accessToken: <BOX_ACCESS_TOKEN>" \
  -F 'file=@/tmp/t.png' -F 'isPermanent=true' | tee /tmp/up.json

# 2) 看返回的 originUrl 形状（期望 domain 里含 /api/files 与桶名）
python3 -c "import json;print(json.load(open('/tmp/up.json'))['data']['originUrl'])"

# 3) 确认对象真的进了 RustFS 的 kean 桶（用 kean 的凭据列举前缀）
docker exec -i $RUSTFS_CTR sh -c 'ls -la /data/kean/image | tail -5'   # 单节点本地卷；或走控制台 9001
#    也可用 aws-cli：
aws --endpoint-url http://127.0.0.1:19000 s3 ls s3://kean/image/ --recursive | tail -5

# 4) 关键：**不带签名**直接取对象，必须是 403/404（证明桶没被改成公开）
curl -s -o /dev/null -w '%{http_code}\n' \
  'https://api.kean.college/api/files/kean/image/<上一步的文件名>'
#    期望：403（kean 的 FileAccessGuard 拒绝）

# 5) 带 kean 签名取，必须 200 + 内联图片（不是 attachment）
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
  'https://api.kean.college/api/files/kean/image/<文件名>?exp=<未来时间戳>&sig=<正确签名>'
#    期望：200 image/png

# 6) 确认 FileExpireTask 不会删东西（跑一次或看日志）
journalctl -u im-platform | grep -i '过期文件'
#    期望：无删除日志；或执行后 im_file_info 行数不变
```

### 5.5 语音 / 文件 / 视频：客户端要改哪些页面

| 客户端 | 文件 | 要做什么 |
|---|---|---|
| `uni-kean` | [`uni-kean/src/pages/message/chat.vue`](../../uni-kean/src/pages/message/chat.vue) | 现在只渲染 `TEXT` 与 `IMAGE`（`item.msgType === 'IMAGE'` 分支 + `previewImage`）；需新增 `FILE` / `AUDIO` / `VIDEO` 三种气泡与交互（播放器、下载、视频预览）。**上传仍走 kean 的 `uploadFile(path, 'CHAT')`**（见 `uni-kean/src/utils/request.ts:258` + `scene` 参数），不改为调 im-platform 的上传接口 |
| `uni-kean` | [`uni-kean/src/api/chat.ts`](../../uni-kean/src/api/chat.ts) | `ChatMessageItem.msgType` 的取值域从 `"TEXT" \| "IMAGE"` 扩到含 `"FILE" \| "AUDIO" \| "VIDEO"`；`sendChatMessage` 的 `msgType` 放开对应值；`content` 变成 JSON 时要定义类型 |
| `uni-kean` | `uni-kean/src/utils/chatMerge.ts` / `chatStore.ts` / `chatSync.ts` | 消息去重与合并目前按 `seqNo` / `localId`，新增类型需要保证 `content` 的比较不被 JSON 字段顺序影响 |
| `uni-kean` | `uni-kean/src/utils/request.ts` | `resolveMediaUrl` 与 `uploadFile` 的 `UploadScene` 可能需要放开：box 的文件消息存的是完整 URL，需要归一化成 `/api/files/...`（§5.3 坑 1 的做法 2） |
| **可参考的上游实现** | `im-uniapp/components/**`（聊天消息组件）、`im-uniapp/pages/chat/**`（会话页）、`im-uniapp/store/**`（消息与本地库）、`im-uniapp/db/**`（本地消息存储，README 提到新版本用 IndexedDB / SQLite） | ⚠️ **只参考消息气泡与富媒体交互的形态**，**不要照搬**它的 URL 拼接（`{domain}/{bucket}/...`，§5.2）与好友/群模型 |
| `web-kean` | 管理端 | 若管理端要展示图片消息，注意 URL 语义已统一为 `/api/files/**`，无需改动 |

> ⚠️ **`content` 里放什么、由谁负责签名 URL** —— 这是富媒体阶段最容易做错的一处。
> 建议的契约（请在实现时与 §5.3 的坑 1 做法 2 保持一致）：
> **存储层（box 的 `content`）存完整 URL 还是对象键由 im-platform 决定，但 kean 返回给客户端的
> 一定是「`/api/files/{objectKey}?exp=&sig=`」这一个形状。** 客户端永远不需要知道对象存储的存在。

---

## 6. 离线与多端（对应目标①）

> 本节所有关于 box 行为的描述都来自 §0.1 的源码核对；**凡是没能从源码确证的，都明确标注**。

### 6.1 `loadOfflineMessage(minId)` 的确切行为（**已证实**，F5）

```java
// im-platform/.../service/impl/PrivateMessageServiceImpl.java（原文，节选）
public List<PrivateMessageVO> loadOfflineMessage(Long minId) {
    UserSession session = SessionContext.getSession();
    LambdaQueryWrapper<PrivateMessage> wrapper = Wrappers.lambdaQuery();
    Date minDate = DateUtils.addDays(new Date(), Math.toIntExact(-Constant.MAX_OFFLINE_MESSAGE_DAYS)); // -60 天
    wrapper.gt(PrivateMessage::getId, minId);                    // ① 只看 id > minId
    wrapper.ge(PrivateMessage::getSendTime, minDate);            // ② 只看最近 60 天
    wrapper.and(wp -> wp.eq(PrivateMessage::getSendId, session.getUserId()).or()
                        .eq(PrivateMessage::getRecvId, session.getUserId())); // ③ 我发的或我收的
    wrapper.orderByDesc(PrivateMessage::getId);
    wrapper.last("limit " + Constant.MAX_OFFLINE_MESSAGE_SIZE);  // ④ 最多 10000 条，且是**最新的**
    List<PrivateMessage> messages = this.list(wrapper);
    if (messages.size() >= Constant.MAX_OFFLINE_MESSAGE_SIZE) {
        messages = appendLastMessageInConversation(messages, minId); // ⑤ 补"每个会话至少一条"
    }
    ...
    // ⑥ 把"我收到的、status=PENDING(0)"的消息置为 DELIVERED(1)
    // ⑦ 过滤掉"整个会话被删"的消息
    // ⑧ 按 id 升序返回
}
```

**逐条结论（照抄源码，不要臆测）**：

| # | 行为 | 影响 |
|---|---|---|
| ① | 参数叫 `minId`，语义是**「我已经见过的最小/最大 id」**：查询是 `id > minId`，即**返回比它更新的消息**。（命名与实际语义相反，容易误用） | 客户端应传**它已知的最大 id**，而不是最小 id |
| ② | **60 天窗口**（`MAX_OFFLINE_MESSAGE_DAYS = 60`）。更早的消息**永远拉不到** | 用户超过 60 天不登录，中间的消息只能靠历史分页接口（`loadHistoryMessage`）补 |
| ③ | 只看「我发的」+「我收的」，**不区分会话** ⇒ 一次返回**所有会话**的增量 | 与 kean 现在「按会话拉」的模型不同，见 §6.3 |
| ④ | **取最新的 10000 条**（`orderByDesc(id) + limit`），然后升序返回 | ⚠️ **不是「最旧的 10000 条」**。若增量超过 10000 条，会**跳过中间的**（只拿到最新那批） |
| ⑤ | 条数触顶时，`appendLastMessageInConversation` 会**补上每个没有代表消息的会话的最后一条** | 保证「每个会话至少有一条」，避免某些会话完全不出现在列表里。⚠️ 它依赖 `im_friend`（`friendService.findFriendIds()`）来枚举会话 —— **`im_friend` 不全 ⇒ 补不齐** |
| ⑥ | **会把收到的 `status=0` 改成 `status=1`**（副作用！） | ⚠️ **这个接口有写操作**：调一次就把「等待推送」变成「已送达」。**不要为了刷新而反复调它**，否则未读状态会被破坏 |
| ⑦ | 过滤「整个会话被删除」的消息（`im_message_deletion` + `DeleteType.BY_CHAT`） | 用户删过会话，就不会再被推回来 |
| ⑧ | 返回前按 `id` 升序排序 | 客户端可直接顺序渲染 |

### 6.2 `sendToSelf` 的确切行为（**已证实**，F7）

⚠️ **`sendToSelf` 不是一个 service 方法**，而是 `IMPrivateMessage` / `IMBatchPrivateMessage` 上的一个 `Boolean` 字段。
它的语义**在 `im-client` 的 `IMSender.sendBatchPrivateMessage` 里实现**：

```java
// im-client/.../sender/IMSender.java（原文，节选）
if (message.getSendToSelf()) {
    Long senderId = sender.getId();
    List<Integer> terminals = IMTerminalType.codes();          // WEB=0, APP=1, PC=2
    for (Integer terminal : terminals) {
        if (terminal.equals(sender.getTerminal())) continue;   // ← 跳过"当前终端"
        selfKeys.add(IMRedisKey.userServerIdKey(senderId, terminal));
        selfOtherTerminals.add(terminal);
    }
}
...
for (int i = 0; i < selfKeys.size(); i++) {
    Integer serverId = (Integer) serverIds.get(recvKeyCount + i);
    if (serverId != null) {                                     // 只有"在线"才投
        List<IMUserInfo> receivers = List.of(new IMUserInfo(senderId, selfOtherTerminals.get(i)));
        pushPrivateMessage(serverId, sender, receivers, false, message.getData()); // sendResult 固定 false
    }
}
```

| 行为 | 结论 |
|---|---|
| 投给谁 | **只投给发送者自己的「其它终端」**，**明确跳过** `sender.getTerminal()` |
| 何时投 | 仅当该终端**在线**（`im:user:server_id:{senderId}:{terminal}` 有值）才入队；离线终端**不投**（靠 `loadOfflineMessage` 补） |
| `sendResult` | 「同步给自己」的这些投递**强制 `false`**，**不会产生 `im:result:*` 回执队列** |
| 依赖 | 依赖 **发送方** 的 `sender.getTerminal()` 正确 —— 它来自 JWT 的 `info.terminal`（`UserSession.terminal`），**由登录/取票时写入** |

> ⚠️ **对课安的直接后果**：kean 现在的 `ImTokenService.issue(userId, terminal)` 里，
> `terminal` 若为 `null` 会**回落成 APP(1)**（`DEFAULT_TERMINAL = 1`）。
> 如果客户端总是拿 APP 这一个 terminal，那么 **H5(应=WEB 0) 与 App(应=APP 1) 会被当成同一个终端**，
> `sendToSelf` 就会认为「发送者的当前终端」是 APP，从而**跳过 APP、只同步给 WEB/PC** ——
> 表现为「多端同步时有时无、且方向诡异」。**阶段 C 必须让客户端按真实终端取票**（`GET /api/im/token?terminal=0|1|2`）。

### 6.3 kean `afterSeq` 增量拉取 vs box `minId` —— 游标怎么对应、未读以谁为准

#### 6.3.1 两个游标的本质差异

| | kean（现状） | box |
|---|---|---|
| 游标键 | **会话内** `seq_no`（`chat_message.seq_no`，由 `ChatSeqService` 分配） | **全局** `id`（`im_private_message.id`，数据库自增） |
| 拉取形状 | `GET /api/chats/{id}/messages?afterSeq=N` —— **按会话**、返回 `seq_no > N`、升序、上限 200（`CHAT_CATCH_UP_LIMIT`） | `loadOfflineMessage(minId)` —— **跨会话**、返回 `id > minId`、上限 10000、60 天窗口 |
| 粒度 | 会话 | 用户 |
| 语义 | 「这个会话里我没见过的」 | 「我离线期间所有会话的增量」 |

**结论：这两个游标不能直接互相赋值。** `seq_no` 是**会话内的相对序号**，`id` 是**全局的绝对主键**。
把 kean 的 `last_seq_no` 塞给 box 的 `minId`（或反过来）会得到**灾难性的结果**：
- 把 `seq_no` 当 `id` 用：`seq_no` 通常远小于全局 `id` ⇒ **每次都会重新拉回巨量历史**（甚至触发 10000 条上限的截断）；
- 把 `id` 当 `seq_no` 用：`afterSeq` 巨大 ⇒ kean 侧**永远返回空**，客户端以为「没有新消息」。

#### 6.3.2 ✅ 收敛方案（三步，必须按顺序）

**第 1 步（阶段 B，双写期）：让 kean 成为 `seq_no` 的唯一分配者，把同一个值写进 box。**

这是整个方案里**最重要的一条设计决定**：`im_private_message.seq_no` 的值**不由 box 的 `getNextSeqNo` 决定**，
而是**直接用 kean 已经分配好的 `seq_no`**（kean 侧 `ChatSeqService.allocateNext` 的返回值，
已经通过 `ImSenderService.sendPrivate(..., seqNo, ...)` 传进镜像投递里了）。
双写时把这个值一并写进 `im_private_message.seq_no`，并**跳过 box 的 `saveMessage`/`getNextSeqNo` 路径**
（即：双写走一个「显式指定 seq_no」的写入，而不是调 `POST /private/message`）。
⇒ **两个系统的 `seq_no` 逐条相等**，游标天然对齐，不存在分叉。验收见 §3.B.1 第 2 条。

> ⚠️ 这需要 im-platform 侧提供一条**能显式指定 `seq_no` 的写入路径**，而 `POST /private/message`（`sendMessage`）
> **不支持**（它内部 `saveMessage` 自己分配）。所以阶段 B 的双写**要么**：
> ① 由 kean **直接写 `im_private_message` 表**（同库不同 schema，用 kean 自己的数据源）；
> **或** ② 给 im-platform 加一个只给内部用的写接口（= 一处 fork）。
> **推荐 ①**：不 fork、不动 im-platform，代价是 kean 多一个数据源配置（指向 `im_platform` 库），
> 但这条路径**只用于双写**，读路径仍走 im-platform 的 REST。

**第 2 步（阶段 C 切换时）：把客户端游标从「kean 的会话 `seq_no`」**一次性重基（rebase）**到「box 的全局 `id`」。**

⚠️ 这一步**不是**把 `seq_no` 赋给 `minId`（那会按 §6.3.1 的两种错法之一炸掉），而是**翻译**：
客户端为它的每一个会话找出「它已经读到的那条 kean 消息」，再查出那条消息在 box 侧对应的 `id`，
然后把这个 `id` 作为新的全局游标。

```sql
-- 生成「重基表」：把一个 (kean 用户, kean 会话) 的已读序号，翻译成 box 的全局 id
-- ⚠️ 依赖 conv_key 的拼法（§12 U3）；且要求阶段 B 的双写让两边的 seq_no 逐条相等（第 1 步）
SELECT m.conv_key,
       m.seq_no                      AS kean_read_seq,   -- 客户端当前的会话内游标
       m.id                          AS box_min_id,      -- 翻译后的全局游标
       MAX(m2.id)                    AS box_max_id,      -- 该会话在 box 里的最大 id（用于核对）
       m2.seq_no                     AS box_max_seq
FROM im_platform.im_private_message m
JOIN im_platform.im_private_message m2
     ON m2.conv_key = m.conv_key
    AND m2.seq_no  = (SELECT MAX(seq_no) FROM im_platform.im_private_message WHERE conv_key = m.conv_key)
WHERE m.seq_no = :客户端上报的该会话已读序号
GROUP BY m.conv_key;
```

- 客户端把全局游标写成 `MIN(box_min_id) - 1`（即**取所有会话里最靠前的那个 id 减一**），
  保证「不会把已读历史重新拉回来」，同时也不会漏掉任何一个会话里未读的部分；
- ⚠️ `loadOfflineMessage` 的 **60 天窗口**（F5 第 ② 条）意味着：只要这个 `id` 对应的时间在 60 天内，
  重基就是安全的；若最旧的会话已经超过 60 天，那部分消息本来就**永远拉不到**（与重基无关，是 box 的固有限制）；
- 同时把手机会话列表上**每会话的未读置 0**（作为「一切以 box 为准」的起点）。

> ❌ **两个绝对不要做的动作**：
> ① **不要**把 kean 的 `seq_no` 直接当 `minId` 传给 `loadOfflineMessage`（§6.3.1 错法之一，会拉回巨量历史）；
> ② **不要**把 `minId` 重置成 `0`：那会让 `loadOfflineMessage(0)` 一次性拉回 60 天 / 10000 条，
> 而且第 ⑥ 条副作用会把所有消息标成「已送达」，用户会看到「一夜之间全部已读」。

**第 3 步（阶段 C 之后，运行期）：只用 box 的 `id` 做增量。**

```ts
// 伪代码：客户端保存的游标从 seqNo 换成 boxMessageId
const cursor = await store.getBoxMaxId();          // 上次拿到的最大的 im_private_message.id
const list   = await loadOfflineMessage(cursor);   // 一次拿到所有会话的增量
await store.setBoxMaxId(Math.max(cursor, ...list.map(m => m.id)));
```
⚠️ 注意 `loadOfflineMessage` **有写副作用**（第 ⑥ 条），**只在「客户端真的准备消费这批消息时」调用一次**，
不要放在轮询 / 下拉刷新里。

#### 6.3.3 未读以谁为准 —— 双写期间与切换后的明确口径

| 阶段 | 未读的**写入方** | 未读的**读取方（界面角标）** | 说明 |
|---|---|---|---|
| 现在（A 之前） | kean（`chat_session.a_unread` / `b_unread`，`ChatServiceImpl.send` 里 `+1`，`markRead` 里清零） | kean | —— |
| **阶段 B（双写）** | **kean 唯一写**（`a_unread`/`b_unread` 照常 +1/清零）。box 侧只写消息 `status`，**kean 的计数器不读 box 的任何东西** | **kean** | ⚠️ **绝对不要**在阶段 B 让 box 的已读回写 kean 的计数器 —— 两套未读同时生效就会分裂成「两套变三套」 |
| **阶段 C（切换）** | **box 唯一写**（消息 `status` + Redis `im:readed:private:position:*`）。**kean 的 `a_unread`/`b_unread` 必须停止 +1 并在切换时清零** | **box**（客户端按「未读消息条数」算） | 切换时把 `chat_session.a_unread`/`b_unread` 置 0，并**从代码里删掉 `send` 里的 +1**（不是注释掉） |
| 阶段 C 之后 | box | box | kean 的 `chat_session` 只保留会话关系与历史位点，不再参与未读 |

> ⚠️⚠️ **这是整份方案里最容易出错的地方**（原文的要求也点明了）。原因：
> box 的「未读」**不是一张表上的计数器**，而是**消息 `status` 的函数**（`status=0` 即未读，F9）；
> 而 kean 的未读是**会话表上的计数器**。两者是**完全不同形状的数据**，一旦同时生效：
> - 用户看到角标 A（kean 算的）、点进去看到未读 B（box 算的）⇒ 对不上；
> - `markRead` 只清了一边 ⇒ 角标**永远清不掉**。
>
> **唯一的正确处理是「同一时刻只有一个写入方」**，并用下面这条 SQL 做切换时的对齐检查：
> ```sql
> -- 切换后：kean 侧计数器应全部归零，且不再变化
> SELECT COUNT(*) FROM Kean.chat_session WHERE a_unread <> 0 OR b_unread <> 0;
> -- 期望：0
> ```

### 6.4 多端同步与 kean 现有 `single_device` 策略的冲突

| | kean 现状 | box |
|---|---|---|
| 策略 | **V30 单设备**（同一账号只允许一个设备在线） | 按 `(userId, terminal)` 分槽位，**每个终端一条连接**；同终端再来一条按 `devId` 判断：**同 devId = 挤掉旧连接**，**不同 devId = 给旧连接的 server 投一条 `im:user:force_logout:{serverId}`** |
| 踢线实现 | kean 自己的 `ChatSessionHub` + `/ws/chat` | `LoginProcessor`（im-server，**已实测生效**）+ `PullForceLogoutTask`（im-server） |
| 与目标①的关系 | **单设备 = 多端同步永远不可能实现** | 多端同步（`sendToSelf`）**必须先允许多终端** |

#### 6.4.1 ✅ 必须定「谁管踢线」，否则两套互相踢

**推荐：阶段 C 起，把「踢线」的唯一权力交给 box（`LoginProcessor` + `devId`），kean 的 `single_device` 策略对 IM 通道停用。**

| 项 | 具体做法 |
|---|---|
| kean 的 `/ws/chat` | **保留**（兜底通道，一行不删）。它的单设备策略继续管**自研通道**上的连接 |
| kean 的 `ImKickService.forceLogout` | **保留**，但它只在**封禁 / 注销**这类「管理动作」时使用，**不再用于「同账号新登录踢旧登录」** |
| box 的 `devId` | **客户端必须传一个稳定的 `devId`**（建议：设备唯一标识或「安装 id」持久化）。⚠️ 若不传 / 传空，`ForceLogoutProcessor` 的分支是 `StrUtil.isEmpty(devId) \|\| !devId.equals(devId)` ⇒ **空 devId 会走「强制下线」分支**（即：不传 devId 会**踢掉所有同终端连接**） |
| 冲突的表现 | 如果两边都在踢：用户会「刚登录就被另一个端踢下线」→ 无限互踢。**这类故障极难排查**，所以必须在上线前明确「踢线归 box」 |

#### 6.4.2 客户端要配合的两件事（阶段 C 的前置）

1. **终端号要真实**：`GET /api/im/token?terminal=0|1|2`（WEB=0 / APP=1 / PC=2）。
   ⚠️ 现状 `ImTokenService` 在 `terminal` 非法/为空时**回落成 APP(1)**，见 §6.2 的警告。
2. **`devId` 要稳定且非空**：写入 `{cmd:0, data:{accessToken, devId}}` 登录帧。
   ⚠️ 当前 kean 的客户端连接代码在 `uni-kean/src/utils/imSocket.ts`，需确认它是否传了 `devId`（§12 未证实）。

---

## 7. 部署与运维

### 7.1 im-platform 的部署形态与内存评估

| 项 | 建议 | 依据 |
|---|---|---|
| 进程托管 | **systemd**（与 `im-server.service` 同一风格），**不要**裸 `nohup` | 与既有 `docs/ops/im-server-patch.md` §3.2 保持一致 |
| 监听 | **`127.0.0.1:8888` 或 `172.17.0.1:8888`**，**只对内** | 它没有 TLS，且我们关闭了 `/login`、`/register`；对外一律走 nginx |
| 工作目录 | `/opt/im-platform`（与 `/opt/kean`、`/opt/im-server` 并列） | 约定 |
| jar 路径 | `/opt/im-platform/im-platform.jar` | 由 `mvn -pl im-platform -am -DskipTests package` 产出（`docs/ops/im-server-patch.md` §3 用的是同一种 `-pl ... -am` 写法） |
| **内存上限** | **`-Xmx384m` ~ `-Xmx512m`**，并在 systemd 里设 `MemoryMax=768M` | 见下面推算 |
| **JVM 参数** | `-Xms128m -Xmx512m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k` | 3.8G 小内存机器上，SerialGC 比 G1 省内存与线程栈 |
| Redis | **必须与 kean / im-server 共用同一个实例、同一个库（0 号库）** | `docs/ops/im-server-patch.md` §2.3；kean 的 `RedisConfig` **不读 `database`** |

**内存推算（3.8G 总量）**

| 组件 | 现状 | 加上 im-platform |
|---|---|---|
| kean（Spring Boot） | ≈ **276MB**（实测） | 276MB |
| im-server | 上限 **512m** | 512m |
| MySQL 8.4 容器 | 未提供实测值 | 建议 `mem_limit: 1g`（若尚未设，**先设上**） |
| Redis 7.2.5 容器 | 未提供实测值 | 建议 `maxmemory 384mb` + `maxmemory-policy noeviction`（IM 队列不能被驱逐） |
| RustFS | `mem_limit: 2g`（`docker-compose.prod.yml:86`） | ⚠️ **2g 在 3.8G 的机器上已经偏激进**，见 `docs/ops/rustfs.md` §6 |
| nginx 容器 | 很小（几十 MB） | ~30MB |
| **im-platform** | —— | **预留 512MB（`-Xmx384m` 时实测常驻约 350~450MB）** |
| 系统 + 其它 | —— | ~300MB |

> ⚠️ **结论：3.8G 的机器上「RustFS 2g + im-server 512m + MySQL 1g + im-platform 512m」是超配的。**
> 上 im-platform 之前**必须先做一次内存盘点**（`free -m`、`docker stats --no-stream`），
> 并按实际情况**下调 RustFS 的 `mem_limit`（例如 1g）或 MySQL 的 `innodb_buffer_pool_size`**。
> **宁可先给 im-platform 256m 观察，也不要让整机进 swap / OOMKill**（RustFS 被 OOMKill 的风险见 `docs/ops/rustfs.md` §6）。

**systemd 单元（建议形态）**

```ini
# /etc/systemd/system/im-platform.service
[Unit]
Description=box-im im-platform (kean)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/im-platform
EnvironmentFile=/opt/im-platform/im-platform.env
ExecStart=/usr/bin/java -Xms128m -Xmx384m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k \
          -jar /opt/im-platform/im-platform.jar --spring.profiles.active=prod
Restart=always
RestartSec=5
MemoryMax=768M

[Install]
WantedBy=multi-user.target
```

> ⚠️ **不要**照抄 im-server 那份示例里的 `EnvironmentFile=/opt/kean/.env.prod`：
> kean 的变量名（`REDIS_HOST` / `STORAGE_ENDPOINT` / `IM_JWT_SECRET`）与 im-platform 的 Spring 属性名
> （`spring.data.redis.host` / `minio.endpoint` / `jwt.accessToken.secret`）**完全不同，不会自动对上**。
> 用一个**独立**的 `/opt/im-platform/im-platform.env`，并在里面直接用 Spring 能识别的名字：
>
> ```bash
> # /opt/im-platform/im-platform.env  （chmod 600，属主 root）
> SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:13306/im_platform?useSSL=false&useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true
> SPRING_DATASOURCE_USERNAME=<kean 库的账号>
> SPRING_DATASOURCE_PASSWORD=<kean 库的密码>
> SPRING_DATA_REDIS_HOST=127.0.0.1
> SPRING_DATA_REDIS_PORT=26739
> SPRING_DATA_REDIS_PASSWORD=<与 kean 相同>
> # ⚠️ 不要设 SPRING_DATA_REDIS_DATABASE（保持 0 号库）
> JWT_ACCESSTOKEN_SECRET=<与 kean 的 IM_JWT_SECRET / im-server 的 jwt.accessToken.secret 逐字节一致，且 >=32 字节>
> JWT_REFRESHTOKEN_SECRET=<可选；建议另设一个 >=32 字节的值>
> MINIO_ENDPOINT=http://127.0.0.1:19000
> MINIO_DOMAIN=https://api.kean.college/api/files
> MINIO_ACCESSKEY=<STORAGE_ACCESS_KEY>
> MINIO_SECRETKEY=<STORAGE_SECRET_KEY>
> MINIO_BUCKETNAME=kean
> MINIO_EXPIREIN=36500
> ```
>
> ⚠️ **`jwt.accessToken.secret` 必须与「kean 的 `IM_JWT_SECRET`」和「im-server 的 `jwt.accessToken.secret`」三者完全一致，
> 且 `>= 32` 字节。** 校验方式（不泄露密钥）：
> ```bash
> printf '%s' "$SECRET" | sha256sum | cut -c1-16
> # 三处（kean / im-server / im-platform）跑一遍，前 16 位必须一致
> ```
> ⚠️ **Spring 环境变量名的大小写映射**：`JWT_ACCESSTOKEN_SECRET` ↔ `jwt.accessToken.secret`
> 这种「驼峰转全大写」的 relaxed binding **对含大写字母的属性名容易出错**。
> **更稳的做法**：在 im-platform 自己的 `application-prod.yml`（部署时生成，不进版本库）里直接写
> `jwt.accessToken.secret`，**只把密钥本体从环境变量传入**：
> ```yaml
> jwt:
>   accessToken:
>     secret: ${JWT_ACCESS_TOKEN_SECRET}
>   refreshToken:
>     secret: ${JWT_REFRESH_TOKEN_SECRET}
> ```

> 📌 **数据库名必须显式指定**：F23 显示上游 `application-dev.yml` 用的是 `im_platform_open`。
> 启动时用 `--spring.profiles.active=prod`（走 `im_platform`），**或**在 `SPRING_DATASOURCE_URL` 里明确写死 `im_platform`。
> ⚠️ **不要**依赖默认 profile（`application.yml` 里 `spring.profiles.active: dev`），否则会连到一个**不存在的库**。

### 7.2 nginx 与前缀（给出建议）

**建议：用路径前缀 `/im-api/`，不新开子域。**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **`/im-api/` 前缀**（在 `api.kean.college` 下） | ① **复用现有 Cloudflare Origin 证书**（SAN 已覆盖 `*.kean.college`）；② 复用现有 ufw 规则（只需加 8888）；③ 前端只需一个 `VITE_IM_API_BASE=/im-api`；④ 不需要新的 DNS / 证书 / CF 配置 | 需要处理「前缀剥离」 | ✅ **推荐** |
| 独立子域（如 `im.kean.college`） | 路径干净，没有前缀剥离问题 | 要新增 DNS、可能新增证书、新增 CF 配置、新增一个 server 块；收益仅「好看」 | ❌ 不推荐 |

**nginx 片段（可直接粘贴；`/im-api/` 必须剥前缀）**

```nginx
    # ------------------------------------------------------------------
    # box-im im-platform（阶段 A 起）
    # ⚠️ im-platform 的服务端**没有** context-path（F22），
    #    所以 nginx 必须把 /im-api/ 前缀**剥掉**：proxy_pass 末尾带 `/` 即剥前缀。
    #    /im-api/user/self  →  http://172.17.0.1:8888/user/self
    # ------------------------------------------------------------------

    # 先挡住 box 自己的注册/登录取票（我们不用它，见 §2.5）
    location = /im-api/register     { return 404; }
    location = /im-api/login        { return 404; }
    location = /im-api/logout       { return 404; }
    location = /im-api/refreshToken { return 404; }
    location = /im-api/modifyPwd    { return 404; }
    # ⭐ 本轮新增：阻止「绕过 kean 的改名通路」（box 的 update 会级联改好友/群内昵称头像，
    #    见 §2.5.1 / §3.E.6 第 1 条）。**终态也保持拒绝。**
    location = /im-api/user/update  { return 404; }
    # ⚠️ E 阶段灰度认证时，上面 login / refreshToken / modifyPwd 三条会**按需临时放开**，
    #    但**必须**配合 §3.E.7 第 7 条的检查，且回滚时（§8.3 第 5 步）要重新 404 掉。

    location /im-api/ {
        # ⚠️ 末尾的 "/" 是关键：它让 nginx 做前缀替换，把 /im-api/ 剥掉
        proxy_pass http://172.17.0.1:8888/;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $http_cf_connecting_ip;
        proxy_set_header X-Forwarded-For   $http_cf_connecting_ip;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 上传：单文件最大 20MB（F6），留余量
        client_max_body_size 25m;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
        proxy_connect_timeout 10s;
        proxy_buffering off;
        proxy_request_buffering off;
    }
```

```bash
# nginx 是容器，改的是服务器上的 /root/nginx/conf/nginx.conf（不是本仓的示例文件）
docker exec <nginx容器名> nginx -t && docker exec <nginx容器名> nginx -s reload

# ⚠️ 必须放行 ufw（与 8080 / 8878 同一条思路；漏了会得到 502/504）
ufw allow from 172.17.0.0/16 to any port 8888 proto tcp comment 'nginx container -> im-platform'

# 前缀剥离是否生效：期望 401/403（说明打通到服务了），不是 404/502
curl -s -o /dev/null -w '%{http_code}\n' https://api.kean.college/im-api/user/self
```

> 📌 改完 nginx，记得把片段回抄进 [`docs/ops/nginx.conf.example`](./nginx.conf.example) 的
> `api.kean.college` server 块（与既有 `/im`、`/ws/`、`/api/` 并列），保持文档与线上一致。
> **本次任务只写本文件，未改动 `nginx.conf.example`。**

### 7.3 Redis 0 号库共用

| 项 | 结论 |
|---|---|
| 是否共用 | **必须共用**（与 kean、im-server 同一个实例、同一个 0 号库） |
| 为什么 | `im:user:denied:*`、`im:user:server_id:*`、`im:user:force_logout:*`、`im:message:*:*` 这组键的语义**只在同一个库内成立**（`docs/ops/im-server-patch.md` §2.3） |
| 键冲突 | ✅ **无冲突**：kean 自己的键是 `kean:*` 前缀（如 `kean:user:banned:*`），box 全是 `im:*`（F27）。**不要**为了"省地方"把 box 的键改前缀 |
| ⚠️ 新增依赖 | im-platform 引入 **Redisson**（F10：`RedissonClient` + `RLock`）⇒ 会**额外占用若干 Redis 连接**，且如果 Redisson 配置写错（比如配成 sentinel/cluster），启动会失败。**保持 standalone 单节点配置** |
| ⚠️ 内存 | 队列 + 锁 + 位点都在同一个库。`maxmemory-policy` **不要**用 `allkeys-lru`（会把队列/封禁键驱逐掉） |

### 7.4 `im_platform` 库的备份策略

```bash
# 每日一次，与 kean 库同节奏（docs/ops/rustfs.md §10 建议"对象与数据库每日一次、异地留存"）
docker exec $MYSQL_CTR sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines --triggers im_platform' | gzip > im_platform-$(date +%F).sql.gz

# 恢复演练（每季度至少一次，光有备份不算数）
gunzip -c im_platform-2026-02-18.sql.gz | \
  docker exec -i $MYSQL_CTR sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" im_platform'
```

| 要点 | 说明 |
|---|---|
| **必须与对象存储一起备** | `im_private_message.content` 里的图片 URL 指向 RustFS 的对象；**只备库不备对象 = 恢复出来一堆悬空 URL**（同 `docs/ops/rustfs.md` §10） |
| 备份要包含 `im_user` / `im_friend` | 它们是影子数据，**重建成本低但不为零**（昵称/头像需要重新物化） |
| ⚠️ 备份**不要**含 `im_sensitive_word` 的大 seed 也没关系 | 44990 字节，可忽略 |
| 建议节奏 | 每日一次 + 异地；`im_platform` 是本方案里**唯一没有 Flyway 保护**的库（一次性 SQL 建的），所以备份尤其重要 |

### 7.5 端口与资源清单表

| 组件 | 位置 | 端口 | 暴露范围 | 内存 | 备注 |
|---|---|---|---|---|---|
| kean 后端 | 宿主机 `172.17.0.1` | **8080** | 仅 docker 网桥（**不监听 0.0.0.0**） | ≈276MB（实测） | ufw 放行 `172.17.0.0/16 → 8080` |
| **im-platform**（新增） | 宿主机 | **8888** | **仅 127.0.0.1 / 172.17.0.1** | 建议 `-Xmx384m`，`MemoryMax=768M` | ufw 放行 `172.17.0.0/16 → 8888`；**不要**开公网 |
| im-server | 宿主机 | **8878**（WS，路径写死 `/im`） | 仅 docker 网桥 | 上限 512m | ufw 放行 `172.17.0.0/16 → 8878`；**8879 不要暴露** |
| MySQL | 容器 `$MYSQL_CTR` | 宿主 **13306** → 容器 3306 | 仅 `127.0.0.1` | 建议 `mem_limit: 1g` | 库：`Kean`（业务）+ `im_platform`（新增） |
| Redis | 容器 | 宿主 **26739** → 容器 6379 | 仅 `127.0.0.1` | 建议 `maxmemory 384mb` + `noeviction` | **0 号库共用**；kean / im-server / im-platform 三边一致 |
| RustFS | 容器 `kean-rustfs` | 宿主 **19000** → 9000（S3）、**19001** → 9001（控制台） | 仅 `127.0.0.1` | `mem_limit: 2g`（**建议下调**） | 桶 `kean`，**私有** |
| nginx | 容器 | 80 / 443 | 仅 Cloudflare 网段（ufw） | ~30MB | 反代 `/api/`、`/ws/`、`/im`、**`/im-api/`（新增）** |

> ⚠️ `.env.prod` 里 `MYSQL_PORT=13306`、`REDIS_PORT=26739`、`STORAGE_PORT=19000` 是**服务器实际端口**。
> 本仓 `docker-compose.prod.yml` 写的是 `${MYSQL_PORT:-3306}` 这类**带默认值**的写法，
> 说明服务器上的 `docker-compose.yml` 与 `.env.prod` 才是权威。

### 7.6 监控：复用已有的 + 新增要盯的

**复用（已完成，不要重做）**：`docs/ops/im-monitoring.md` 的四件事 ——
投递计数器、60 秒 `SCAN` 巡检、邮件告警（30 分钟冷却）、`/health/ready` 的 `im` 字段、
管理端 `/api/admin/im/stats` / `/alerts` / `/queues/clean`。

**上了 im-platform 之后新增要盯的**：

| # | 指标 | 怎么看 | 为什么必须盯 |
|---|---|---|---|
| 1 | **8888 存活** | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8888/user/self`（期望 401，不是连接失败）；或 `systemctl is-active im-platform` | im-platform 挂了 ⇒ 消息**落不进 box 的表** ⇒ 离线消息与多端同步静默失效（kean 业务不受影响，所以**没有告警就没人知道**） |
| 2 | **im-platform 自己的 Redis 键在不在** | `redis-cli -n 0 --scan --pattern 'im:message:private:max_seq:*' \| head` | 它没连上 Redis 时，`getNextSeqNo` 会失败 ⇒ 发消息 500 |
| 3 | ⚠️ **`im:result:*` 回执队列** | `redis-cli -n 0 --scan --pattern 'im:result:*'` 然后 `LLEN` 每一个 | **这是我们之前刻意不产生的队列**（kean 的 `ImSenderService` 一直用 `sendResult=false`，见 `docs/ops/im-server-patch.md` §2.8），**一旦上了 im-platform 就会有**：`sendMessage` 里 `sendMessage.setSendResult(true)`，而回执的消费者在 **im-platform 的 `task/consumer`** 里。**如果消费者没跑起来，这个队列会无界增长**（与 `im:message:*` 一样的病） |
| 4 | `im:queue:user:banned` | `LLEN im:queue:user:banned` | box 的封禁是通过队列异步生效的（`RedisKey.IM_QUEUE_USER_BANNED`）。⚠️ kean **不走** box 的封禁队列（我们直接写 `im:user:denied`），但**如果将来有人用 box 管理后台封禁**，这条队列就会用上 |
| 5 | `im:lock:*` 锁等待 | `redis-cli -n 0 --scan --pattern 'im:lock:*'` 的次数（不该长期存在） | `seq_no` 分配与消息保存都用 Redisson 锁（F10）。锁泄漏 ⇒ 发消息全部阻塞 |
| 6 | im-platform 的错误率 | `journalctl -u im-platform -p err --since '1 hour ago' \| tail` | 尤其盯 **`ClassCastException`**（= 封禁键值类型没改，见 §2.4） |
| 7 | 磁盘 / 对象增长 | `du -sh` RustFS 卷；`SELECT COUNT(*) FROM im_platform.im_file_info` | 富媒体上线后增长很快 |
| 8 | `/health/ready` 的 `im` 字段 | 已存在，继续用 | 它反映的是 **kean → im-server 队列**的健康度，**不反映 im-platform**。所以第 1 条必须单独加 |

> ⚠️ **`im:result:*` 是本阶段最容易被漏掉的新增长点**。建议**立刻**把
> `kean.im.queue-warn-threshold` 那套巡检扩展一份到 `im:result:*`（或在 kean 侧新增一条独立巡检），
> 阈值可先用 `LLEN > 1000` 告警。参见 `docs/ops/im-monitoring.md` §2 的 `SCAN` 写法（**禁止 `KEYS`**）。

---

## 8. 风险清单与停止条件

### 8.1 高危项

| # | 风险 | 为什么危险 | 缓解 |
|---|---|---|---|
| H1 | **双写不一致**（kean 写成功、box 写失败） | 用户以为消息发出去了，box 侧没有 ⇒ 他换设备登录后**看不到这条消息**，且 `seq_no` 可能出现空洞 | ① 双写**吞异常不改业务**（与 `ImSenderService` 同一约束）；② §3.B.1 第 2 条**逐条比对 `seq_no`**，不一致不放过；③ 计数器 `platformFailed` 纳入告警 |
| H2 | **未读口径分裂（两套变三套）** | 角标对不上、清不掉；一旦上线**用户立刻能感知** | 严格遵守 §6.3.3：**同一时刻只有一个写入方**；切换时用 SQL 校验 `chat_session.a_unread` / `b_unread` 全为 0。⚠️ 「两套变三套」的第三套是**客户端本地缓存的未读**（`uni-kean` 的 `chatStore`）—— 切换时必须让客户端**丢弃本地未读**，以服务端为唯一来源 |
| H3 | **封禁 / 禁言 / 拉黑在多处重复实现，漏一处即失效** | 现在至少有 **4 处**：① kean `sys_user.status` + `TokenRevokeService`/`kean:user:banned:{userId}`；② `im:user:denied:{userId}`（im-server 连接 + **im-platform REST**）；③ `im_platform.im_user.is_banned`（box 自己的登录链路）；④ kean 的**禁言** `UserRestrictions.muted(me)`（`ChatServiceImpl.send` 的前置校验）与**拉黑** `blacklistService.assertCanInteract`。**漏一处 = 被封用户仍能用那条通道** | ① 把 ①②③ 的写入口**收敛到 `ImKickService` 一处**（`deny` / `allow` / `markUnregistered`）；② 把「三个键/字段都被正确写入」做成一条集成测试；③ **禁言与拉黑（④）不进 box**（box 没有这两个模型），它们**只**在 kean 的发送前置校验里生效 —— 这意味着**走 im-platform 的写路径必须先经过 kean 的校验**，即客户端**不能**绕过 kean 直接调 im-platform 的 `POST /private/message`。⚠️ **这是阶段 C 的一个硬约束**：要么在 nginx 层禁止客户端直接调用 im-platform 的发消息接口（只允许 kean 后端调），要么接受「禁言/拉黑被绕过」 |
| H4 | **`seq_no` 并发分配** | box 侧用 `Redis INCR` + Redisson 锁（F10），kean 侧用 `ChatSeqService`。**两套分配器同时工作 ⇒ 必然重号或乱序** | §6.3.2 第 1 步：**kean 是唯一分配者**，box 的 `getNextSeqNo` 路径在双写期**不走**；阶段 C 之后反过来，**kean 的 `ChatSeqService` 停止对新消息分配**（不是注释掉，是删除调用）。验收见 §3.B.1 第 2 条 |
| H5 | **im-platform 与 kean 的 Redis 键冲突** | 潜在风险 | ✅ **当前无冲突**（F27：box 全 `im:*`，kean 全 `kean:*`）。**但必须保持**：新增键时先确认前缀归属；**不要**让任何一方去 `FLUSHDB` |
| H6 | **`FileExpireTask` 在 URL 形状非 box 原生时删错对象**（F19） | **不可逆的数据丢失**（对象被删，库里 URL 悬空） | §5.3 坑 3：把 `minio.expireIn` 设成 36500，使任务永远查不到记录 |
| H7 | **`loadOfflineMessage` 的写副作用被滥用**（F5 第 ⑥ 条） | 反复调用 ⇒ 未读被刷成已读 | 客户端只在「准备消费」时调一次；**禁止**放进轮询/定时刷新 |
| H8 | **`/*/upload` 过宽的上传放行**（F21） | 若能匿名上传，会污染对象存储（存储成本 + 可能被当作图床滥用） | 阶段 A 验收第 5 条**实测**；无论结果一律在 nginx 层收紧 |
| H9 | **box 的自助注册被开放**（§2.5） | 抢注用户名 / 占用 id，破坏同 id 复用 | nginx 精确匹配 `return 404`（阶段 A 验收第 4 条） |
| H10 | **`sendResult=true` ⇒ `im:result:*` 无界增长** | Redis 内存被吃满，影响**所有**依赖 Redis 的功能 | §7.6 第 3 条；确认 im-platform 的 `task/consumer` 消费者在跑 |
| H11 | **`serverId` 漂移 + `Restart=always`** | `im:max_server_id` 只增不减；重启后旧队列永不被消费，消息静默堆积 | 已在 `docs/ops/im-monitoring.md` §2.2 有残留队列检测；**新增 im-platform 不会改变这一点**，但**会新增 `im:result:*` 的同类问题** |
| H12 | **`devId` 没传 ⇒ 同终端被全踢**（§6.4） | 用户端表现为「多端互踢」 | 阶段 C 前确认客户端传 `devId` |
| H13 | **内存超配**（§7.1） | RustFS / MySQL 被 OOMKill ⇒ **存储或数据库整体不可用** | 上线前盘点 + 下调 `mem_limit` + 给 im-platform 设 `MemoryXxx` |

#### 8.1.1 ⭐ 终态新增的高危项（**由「全量换成 box」这个终态直接引入，A~D 阶段不存在**）

| # | 风险 | 为什么危险 | 缓解 |
|---|---|---|---|
| **H14** | 🔴🔴 **认证双轨期的安全漏洞**：**封禁 / 注销 / 改密在一侧生效、另一侧不生效**（漏一处即失效） | 这是**终态新增的、且最不容易被发现的一类漏洞**：<br>· **改密不同步** ⇒ 用户在 kean 改了密码，**旧密码仍能在 box 侧登录**；<br>· **封禁不同步** ⇒ box 侧 `is_banned=0` 时，被禁用户可借 `/refreshToken` **删掉 `im:user:denied` 键**，从而**绕过封禁**（源码：`refreshToken` 成功后 `redisTemplate.delete(IM_USER_DENIED...)`）；<br>· **注销不同步** ⇒ 已注销账号仍能连 WS / 调 REST；<br>这些东西**全都不会报错**，只能靠主动校验或用户投诉发现 | ① **严格按 §3.E.5 的 7 条原则**：双轨期**写权威只有一个（kean）**；<br>② **nginx 把 `/im-api/register`、`/logout`、`/modifyPwd`、`/user/update`、`/refreshToken` 全部 `return 404`**（**这条最省事也最可靠**：调不到就不存在「一侧生效」）；<br>③ 把 §3.E.7 的 **7 条验收做成自动化**（尤其第 3、4、5 条），纳入每次 E 阶段放量前的门禁；<br>④ `im_user.is_banned` 的同步**从「纵深防御」升级为「必需项」**（它现在是 box 登录与 refreshToken 的判定依据）；<br>⑤ 埋点 `boxAuthFallbackToKean`，它的突增是**唯一早期信号** |
| **H15** | 🔴 **同 id 复用被破坏**（有人加了映射列 / 映射表 / 让 `im_user` 自增） | **在 A~D 阶段完全没有症状**（那时不需要任何翻译），所以**不会被任何测试发现**；一旦走到 F 阶段，「全量换成 box」就**必须迁全站业务外键** ⇒ 退回 **E1**：**全站数据动一遍、不可逆**（§1.5.2）。**这是典型的「无症状架构债」** | ① 把 §1.5.4 的 **L1 守卫 A / B** 做成 **CI 检查或发版前必跑**（一条 `comm` + 两条 `information_schema` 查询，30 秒）；<br>② **写进代码评审清单**：出现 `im_user_id` / `box_user_id` / `user_map` 这类命名一律拒绝；<br>③ 物化 SQL 必须**显式写 `id` 列**（阶段 A 的硬约束），并保留 `AUTO_INCREMENT` 的实测记录；<br>④ 在 **§10 的 A-3b** 里落一条**可执行的校验** |
| **H16** | 🔴 **账号真相切换期间的「会话 / 数据归属混乱」** | E 阶段前后必然有一段「一部分账号在 box、一部分在 kean」的窗口。此时：<br>· **同一份聊天数据被两个身份系统解释**（一个账号可能同时持有两类 token）；<br>· **封禁/注销的作用域变成「按链路」而不是「按账号」**（用户在 A 链路被禁，却在 B 链路能登）；<br>· **审计与客服无法回答「这个账号现在到底归谁管」**（没有单一查询入口）；<br>· **回退时的状态残留**：把比例调回 0 之后，`im_user.password` 里留下的真哈希**仍然是「可被 box 登录」的**（只是被 nginx 挡住了） | ① **单一查询入口**：提供一个「账号归谁管」的排查工具/接口（输入 userId，输出：`grayState` / `force_kean` / `im_user.password` 是否真哈希 / `is_banned` / `im:user:denied`），**客服与运维共用同一份判据**；<br>② **灰度状态必须持久化且可审计**（`auth_graylist` 表要记录「切到 box 的时间 / 操作人 / 原因」）；<br>③ **回退时同步处理哈希可达性**（§3.E.8 第 3 条：用 nginx 关门，而**不是**批量改密码列）；<br>④ **任何时刻都要能回答「如果现在全量回退，会不会丢数据」** —— E 阶段没有数据搬迁，所以答案是「不会」，这正是取 E2 而不是 E1 的价值（§1.5.2） |

### 8.2 必须回滚的情况与动作

| # | 触发条件（**看到就回滚，不要"再观察一下"**） | 回滚动作 | 预计耗时 |
|---|---|---|---|
| R1 | **kean 的任何既有功能受影响**：登录失败率上升、`/health/ready` 的 `db`/`redis` 变 DOWN、通知/任务/文件读取报错 | **立即**把 `im-platform` 从 nginx 摘掉（注释 `location /im-api/` + `reload`），按 §8.3 第 1 步 | **< 5 分钟** |
| R2 | **服务器内存压力**：`free -m` 的 available < 300MB，或出现 OOMKill 日志（`dmesg \| grep -i oom`） | `systemctl stop im-platform`；若仍紧张，把 RustFS 的 `mem_limit` 下调并重启它 | **< 10 分钟** |
| R3 | **`im:result:*` / `im:message:*` / `im:queue:*` 任一队列快速增长且无消费者** | 停 im-platform（它停止产生回执）；按 `docs/ops/im-monitoring.md` §5.3 **人工确认后**再 `DEL` | **< 15 分钟** |
| R4 | **封禁失效**（被封用户仍能发消息 / 仍能连） | 关掉 im-platform（把 IM 通道退回到「只有 im-server」，那里封禁是**上游自带且已实测**的）；同时**检查 `im:user:denied` 的值类型是否被改回字符串** | **< 10 分钟** |
| R5 | **未读大面积对不上**（用户报「角标清不掉」且量大） | 阶段 C 回滚：前端回退上一版 + kean 读路径切回 + 执行 §3.C.1 的反向回填（**必须先跑完回填再切**，否则丢消息） | **1~2 小时**（这是最贵的回滚） |
| R6 | **发现消息在 box 与 kean 两侧内容不一致**（双写 bug） | `KEAN_IM_PLATFORM_ENABLED=false` 立刻停止双写；保留 `im_platform` 数据用于排查，**不要删** | **< 5 分钟** |
| R7 | **`FileExpireTask` 开始删文件**（日志出现「删除过期文件异常」或对象数下降） | 立刻 `systemctl stop im-platform`（任务随进程停止）；从**最近的 RustFS 卷备份**恢复（`docs/ops/rustfs.md` §10） | **30 分钟 ~ 数小时** |
| **R8** ⭐ | **E 阶段：`boxAuthFallbackToKean` 突增**，或登录失败率/客服报障量上升（**哪怕还没有确凿原因**） | ① 立刻把 `kean.auth.box-login-percent` **调回 0**；② 给报障账号打 `force_kean`；③ 回滚后**不要**动 `im_user.password`（见 §3.E.8） | **< 5 分钟** |
| **R9** ⭐ | **E 阶段：发现「一侧生效、另一侧不生效」**（例：kean 改了密码 box 还能用旧密码登；或被封用户仍能调 box 的 REST） | ① nginx 把 `/im-api/login`、`/im-api/refreshToken`、`/im-api/modifyPwd`、`/im-api/user/update` **全部 `return 404`**（**这一步就把「另一侧」整体关掉，比逐点修补可靠**）；② 再按 H14 的清单逐条补齐同步；③ 补一条 §3.E.7 的自动化验收 | **< 10 分钟**（关入口）+ 修复时间 |
| **R10** ⭐ | **E 阶段：确认是「密码哈希不兼容」**（3.E.2 的 P1~P4 命中） | 立刻停灰度（R8 的 ①）；⚠️ **不要**去批量重刷密码列；回到 §3.E.2 逐条核对前缀/形状，**先在测试库验证过再重开** | **< 5 分钟**（停）+ 排查时间 |

### 8.3 分阶段回滚动作（从快到慢）

```bash
# ============ 第 1 步（秒级~分钟级）============
# 阶段 A/B/C 通用：让 im-platform 从公网不可达
#   编辑服务器上的 /root/nginx/conf/nginx.conf，注释掉 location /im-api/ 块
docker exec <nginx容器名> nginx -t && docker exec <nginx容器名> nginx -s reload
# 效果：客户端无法再调 im-platform；kean 的 /api 完全不受影响

# ============ 第 2 步（分钟级）============
# 阶段 B/C：停止双写（kean 侧一个开关，不改代码、不回退版本）
#   在 /opt/kean/.env.prod 里设 KEAN_IM_PLATFORM_ENABLED=false，然后重启 kean
#   ⚠️ 注意区分两个开关（见 §3 阶段 B 的配置项说明）：
#     KEAN_IM_PLATFORM_ENABLED  → 管"kean → im-platform 双写"（本步骤要关的是它）
#     KEAN_IM_MIRROR_ENABLED    → 管"kean → im-server 队列镜像"（**不要**一起关，
#                                否则实时推送也会停，用户会立刻感觉"消息不实时"）
systemctl restart kean
curl -s http://127.0.0.1:8080/health/ready | grep -o '"im":"[A-Z]*"'   # 既有探针仍须正常

# ============ 第 3 步（分钟级）============
# 停掉 im-platform 进程（数据保留，便于事后排查）
systemctl stop im-platform
systemctl disable im-platform        # 防止服务器重启后又被拉起

# ============ 第 4 步（仅阶段 C，1~2 小时）============
# 读路径回切 + 反向回填（顺序不能颠倒：先回填 kean，再切前端）
#   1) 跑反向回填：把 im_private_message 里 kean 没有的消息写回 Kean.chat_message
#   2) 校验 §3.C.1 第 6 条的 box_newer == 0
#   3) 前端回退上一版；kean 的读路径切回 Kean.chat_message
#   4) 把 chat_session.a_unread/b_unread 恢复成由 kean 维护（回退 send 里的 +1）

# ============ 关于数据库：不要删 ============
# ❌ 不要为了"回滚干净"去 DROP DATABASE im_platform —— 阶段 C 之后那里有真实用户消息。
# ✅ 阶段 B 的数据（影子用户/好友）可以留着，没人读，也没有副作用。

# ============ 第 5 步（仅阶段 E，< 5 分钟）⭐ 认证回滚 ============
# ⚠️ E 阶段的回滚**不需要重新发布**，也**不需要动任何数据**。
#   1) 把认证灰度比例归零（首选；若配置不支持热更新则重启 kean，约 1 分钟）
#       在 /opt/kean/.env.prod 里设 KEAN_AUTH_BOX_LOGIN_PERCENT=0
#   2) 给报障账号打"强制回 kean"标记（秒级，单点恢复，不影响其他人）
#       例：在 auth_graylist 里把该账号的 force_kean 置 1（表名以落地实现为准）
#   3) 若仍不放心：在 nginx 把 box 的认证入口整体关掉（这一步与 A~D 阶段的效果等价）
#       location = /im-api/login        { return 404; }
#       location = /im-api/refreshToken { return 404; }
#       location = /im-api/modifyPwd    { return 404; }
#       docker exec <nginx容器名> nginx -t && docker exec <nginx容器名> nginx -s reload
#   4) ✅ 校验：名单内账号的登录已回到 kean 链路，且**登录本身仍然成功**
#   5) ❌ **不要**执行"把 im_user.password 批量改回 {noop}__disabled__" ——
#         第 3 步已经让那些哈希不可达；批量改密码列是**最容易改错**的操作
#         （UPDATE 漏 WHERE = 废掉全站密码）。
#   6) ⚠️ 回滚后请**保留** auth_graylist 的记录：它是"谁曾被切过"的审计依据，
#         也是下次重开灰度时的起点。不要为了"干净"清空它。
```

> ⚠️ **回滚的黄金法则**：**先保 kean，再管 IM**。kean 的可用性永远优先于 IM 的完整性。
> 第 1 步（摘 nginx）能在 5 分钟内让一切回到「IM 只是不工作」而不是「整站故障」。

---

## 9. 工作量汇总表 + 里程碑

### 9.1 工作量汇总（**按终态六阶段重估**）

> ⚠️ 上一轮的估算是按 **A/B/C/D 四阶段、只迁 IM 业务层** 给的（合计 16~27）。
> 终态改为「**全量换成 box**」之后，**A~D 的估算不变**（它们的范围没变），但要**新增 E（认证）与 F（收尾）**。

| 阶段 | 内容 | 人天（区间） | 终态位置 | 前置条件 | 可回滚性 |
|---|---|---|---|---|---|
| **A 地基** | 建库 / 建影子用户（**同 id**）/ 部署 im-platform / nginx / redis / 存储打通 / 封禁键值类型修正 | **3 ~ 5** | `im_platform` 库与 `im_user` 归 box | `im_platform` 库与 9 张表已建（已完成）；`IM_JWT_SECRET` 已在服务器配置 | ✅ **最容易**（停进程 + 摘 nginx） |
| **B 双写灰度** | `ImPlatformClient` + 双写 + 影子/好友投影 + 灰度开关 + 观测 | **5 ~ 8** | 消息的写通路开始归 box | A 全绿；§3.B.1 第 2 条的比对 SQL 能跑 | ✅ **容易**（一个环境变量） |
| **C 全量切换** | 读路径代理 + 游标交接 + 未读统一 + **反向回填** + 前端 | **8 ~ 14** | ✅ 会话/消息/未读/读路径**归 box** | B 的 `seq_no` 逐条一致率 100%；反向回填已就绪 | ⚠️ **最难**（1~2 小时，且**必须先回填**） |
| **D1 富媒体** | `FILE`/`AUDIO`/`VIDEO` 全链路 + 客户端气泡 ｜ **目标②在这里兑现** | **3 ~ 6** | 富媒体归 box（**URL 签发仍归 kean**） | C 完成（或与 C 并行，只要写路径已走 box） | ✅ 容易（关前端入口） |
| **D2 群聊** | `im_group` 全家桶 + 产品定义 + 管理 | **10+**（且需产品定义） | 群聊归 box（纯新增） | C 完成；**业务方有明确需求** | ✅ 容易（关入口） |
| **E ⭐ 账号灰度切换（新增）** | 见 §9.1.1 的分解 | **10 ~ 18** | ✅ **认证归 box（终态账号真相在 box）**；业务字段与 `status` 仍归 kean | **A~D 全部完成**；§3.E.2 的密码哈希形状普查通过；§3.E.7 的自动化验收就绪；**已做过一次单账号回退演练** | ⚠️⚠️ **最危险**（见 §9.1.1） |
| **F 收尾（新增）** | 清理双轨期代码/开关、固化「留在 kean 的清单」、一次完整回归 | **3 ~ 5** | 终态冻结 | E 已 100% 且观察 14 天无回退 | ✅ 容易（F 是「冻结」不是「切换」） |
| —— | **合计（A+B+C）** | **16 ~ 27 人天** | —— | —— | —— |
| —— | **含 D1（目标①+② 都兑现）** | **19 ~ 33 人天** | —— | —— | —— |
| —— | **⭐ 全量终态（A+B+C+D1+E+F）** | **32 ~ 56 人天** | —— | —— | —— |
| —— | （可选）D2 群聊 | **+10 起** | —— | 需产品定义 | —— |
| —— | 运营 / 联调 / 回归（建议单列预留） | **+5 ~ 8** | —— | —— | —— |

#### 9.1.1 ⭐ E 阶段（认证）的分解 —— **这是最大的风险项，必须单独排期与回滚演练**

| 子项 | 内容 | 人天 |
|---|---|---|
| E-1 | **密码哈希兼容性核实与普查**（§3.E.2 的 P1~P5 + 形状普查 SQL；**含在测试库的一次真实验证**） | **1 ~ 2** |
| E-2 | **认证灰度框架**：白名单 + 比例 + 单账号 `force_kean`；`auth_graylist` 持久化与审计 | **2 ~ 3** |
| E-3 | **kean 侧认证代理/兑换层**（§3.E.4 方案①：客户端仍然只调 kean 的登录接口，kean 转发到 box 并把结果换回 kean 的会话凭证） | **3 ~ 5** |
| E-4 | **双轨一致性联动**：改密双写、封禁四处齐写、注销三件事（§3.E.5 原则 4/5/6） | **2 ~ 3** |
| E-5 | **观测与告警**：`boxAuth*` 四计数器 +「账号归谁管」排查工具（H16） | **1 ~ 2** |
| E-6 | **回滚演练 + 验收自动化**（§3.E.7 / §3.E.8；**至少真跑两次：一次在测试环境、一次在灰度 1% 时**） | **1 ~ 3** |
| **E 合计** | —— | **10 ~ 18** |

> ⚠️ **E 阶段的三条排期铁律**（缺一条就不许开始）：
> 1. **必须单独排期**：不许把它塞进 A~D 的尾巴，也不许与 C/D 并行 —— 它是**全站入口**的变更；
> 2. **必须有回滚演练记录**：§3.E.8 的四步 + 单账号 `force_kean` **要在灰度 1% 时真跑一遍**，
>    并把「实际耗时」写进变更单（预期 < 5 分钟；**如果演练超过 15 分钟，说明回滚路径不可用，不许放量**）；
> 3. **必须有自动化门禁**：§3.E.7 的 7 条（特别是**封禁 / 改密 / 注销一致性**三条）不通过，**不许进入下一档比例**。

> ⚠️ **人天是工程估算，不含产品定义、不含前端 UI 设计、不含灰度观察期**。
> ⚠️ **E 阶段的观察期要单独算**：建议 **每个比例档位 ≥ 3 天**（比 A~D 的 2 天更长），
> 且 **100% 之后还要观察 14 天**才能进 F（见 §3 阶段 F-1 的判据）。

### 9.2 推荐顺序与里程碑（**六阶段 —— 注意认证在最后**）

```
里程碑 0  决策（本文档评审通过）
   │  ✔ 确认 §1.5 终态 = E2（同 id 复用，不建映射表/映射列）；§2 采用方案 B
   │  ✔ 确认 §4 不搬历史；§5.3 统一走 kean 签名 URL
   │  ✔ 确认顺序铁律：IM 先行（A~D）、认证殿后（E）
   ▼
里程碑 A  im-platform 上线、影子用户（id 同值）与好友投影就绪、封禁键值类型已改
   │  ✔ 通过 §3.A.1 的 8 条验收（第 8 条必须看到"两侧 id 是同一个数"）
   │  ✔ 跑一次 §1.5.4 的 L1 守卫 A / B
   │  ✔ 观察 3 天：8888 存活、无 CCE、无 OOM
   ▼
里程碑 B  双写灰度（1% → 10% → 50% → 100%）
   │  ✔ 每一档放量前都跑一次 §3.B.1 第 2 条的 seq_no 比对，必须 100%
   │  ✔ 观察 7 天：platformFailed 无异常、kean 既有功能零回归
   ▼
里程碑 C  ★ IM 全量切换（IM 侧的不可逆点）
   │  ✔ 反向回填已上线并验证（§3.C.1 第 6 条 box_newer 不增长）
   │  ✔ 未读口径已唯一化（§6.3.3），SQL 校验 a_unread/b_unread 全 0
   │  ✔ 灰度切前端：1% → 10% → 50% → 100%，每档观察 2 天
   │  ✔ ⚠️ 收尾：把 KEAN_IM_PLATFORM_ENABLED 关掉（双写不该带进终态）
   ▼
里程碑 D1 富媒体（语音/文件/视频）—— 直接对应目标②
   │  ✔ 到此为止：目标①（C 的离线/多端）与目标②（D1）**都已兑现**
   ▼
里程碑 D2 群聊（按业务需要，可无限期推迟；**不阻塞 E/F**）
   ▼
里程碑 E  ⭐⭐ 账号灰度切换（全站最危险的一段，必须单独排期与回滚演练）
   │  ✔ §3.E.2 的密码哈希形状普查通过（期望 100% 是 $2a$10$）
   │  ✔ §3.E.7 的 7 条验收全部自动化并通过
   │  ✔ 单账号 force_kean 回退**已真跑过一次**，且耗时 < 5 分钟
   │  ✔ 比例推进：内部账号 → 1% → 10% → 50% → 100%，**每档 ≥ 3 天**（见 §9.3.1）
   │  ✔ 每档都盯 boxAuthFallbackToKean（它的突增 = 立即回退，见 R8）
   │  ✔ 100% 后再观察 14 天，无回退记录
   ▼
里程碑 F  收尾（终态冻结）
   │  ✔ 账号真相在 box；业务仍在 kean（同 id，**无需改任何外键**）
   │  ✔ 固化 §3 阶段 F-2 的「仍然留在 kean 的 12 项」清单，每项都有负责人
   │  ✔ 清理 F-3 的双轨期残留（**只删聊天分支，保留 /ws/chat 的非聊天推送**）
   ▼
终态      IM 与认证全量归 box；业务数据永远归 kean；两者靠同一个 id 缝合
```

### 9.3 建议的放量顺序（每档都要有明确的可观测指标）

> ⚠️ 下表是 **A~D（IM）** 的放量顺序。**E 阶段（认证）的放量顺序不同**，见 §9.3.1 ——
> 认证的档位更长、指标不同，**不要套用这张表**。

| 档位 | 用户比例 | 观察窗口 | 主要看什么 |
|---|---|---|---|
| 内部账号 | 1~5 个 | 1 天 | `seq_no` 比对、离线消息、多端同步 |
| 1% | 随机 | 2 天 | 错误率、`im:result:*` 长度、用户反馈 |
| 10% | 随机 | 2 天 | 同上 + 内存水位 |
| 50% | 随机 | 3 天 | 同上 + 未读角标投诉 |
| 100% | 全量 | 持续 | 上面全部 + 队列巡检告警 |

#### 9.3.1 ⭐ E 阶段（认证）的放量顺序（**与上表不同，不要混用**）

| 档位 | 账号范围 | 观察窗口 | 主要看什么（**与 IM 完全不同**） |
|---|---|---|---|
| 内部账号 | 1~5 个（含**至少 1 个已注销账号、1 个被封禁账号**做负例） | 1 天 | §3.E.7 的 7 条一致性；**负例必须仍然登不进去** |
| 1% | 白名单 + 1% | **≥ 3 天** | **`boxAuthFallbackToKean`**（首要指标）、登录成功率、改密后两侧哈希是否相等、客服报障 |
| 10% | 白名单 + 10% | **≥ 3 天** | 同上 + **封禁/解封的四处一致性**（跑 §3.E.7 第 3 条） |
| 50% | 白名单 + 50% | **≥ 3 天** | 同上 + 注销流程（§3.E.7 第 5 条）+「账号归谁管」排查工具的使用率 |
| 100% | 全量 | **≥ 14 天** | 上面全部 + 「还有没有账号在走 kean 认证」（期望 0） |

> ⚠️ **E 阶段每档放量的门禁（硬性，四条全满足才允许提比例）**：
> ① `boxAuthFallbackToKean` 在上一个档位期间**没有突增**；
> ② §3.E.7 的 7 条**全绿**；
> ③ 本档期内**已重做一次单账号回退演练**；
> ④ 上表该档的观察窗口**已满**。

---

## 10. 阶段 A 的最小可执行清单（**下一步就能做的事**）

> 这一节是给「现在就要动手」的人准备的。**按顺序执行，每步都有检查点。**
> ⚠️ 本文档未执行任何一步；以下命令**均需在服务器上手动执行**。

```bash
# =====================================================================
# A-1  内存与端口盘点（**先做这个，不要跳过**）
# =====================================================================
free -m
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'
ss -lntp | grep -E ':(8080|8888|8878|13306|19000|26739|80|443)\b'
# 检查点：available 内存 >= 900MB；8888 未被占用
# 若 available < 900MB → **先**下调 RustFS mem_limit 或 MySQL buffer pool，再进行 A-2

# =====================================================================
# A-2  确认 im_platform 库与 9 张表（既有成果，只做确认）
# =====================================================================
export MYSQL_CTR=$(docker ps --format '{{.Names}}' | grep -Ei 'mysql' | head -1)
echo "MYSQL_CTR=$MYSQL_CTR"
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW TABLES FROM im_platform"'
# 检查点：**恰好 9 行**：im_user、im_friend、im_private_message、im_group、
#          im_group_member、im_group_message、im_sensitive_word、im_file_info、im_message_deletion
# 若库不存在（新机器）：先执行 docs/ops/im-migration.md §2.2 的建库命令

# =====================================================================
# A-3  影子用户的自增与占位（§2.3）
# =====================================================================
# 先算出该设多少
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
  SELECT MAX(id) FROM Kean.sys_user"'
# 假设输出 12345 → N = 22345（原文的 <N> 用这个数字替换后执行 §2.3 的两条 SQL）
# 检查点：
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT id, user_name, type FROM im_platform.im_user WHERE id = 0;
  SHOW TABLE STATUS FROM im_platform LIKE \"im_user\"\G" | grep -E 'Auto_increment|user_name'
# 期望：id=0 的行存在；Auto_increment > sys_user 的 max(id)

# =====================================================================
# A-3b  ⭐ 同 id 复用的硬校验（§1.5 铁律 L1 —— **新增，不可跳过**）
# =====================================================================
# 背景：终态取 E2（同 id 复用），所以「影子行的 id 必须就是 sys_user 的 id」。
#       下面是**三条可执行的校验**，全部要留证据（截图或输出贴进变更单）。
#
# --- 校验 1：物化 SQL 必须显式写 id 列（代码级检查，不用跑数据库）---
#   在 kean 侧搜物化实现，期望能看到 "INSERT INTO im_platform.im_user" 且**列清单里有 id**：
grep -rn "im_platform.im_user" --include=*.java --include=*.xml --include=*.sql . | head -20
#   ❌ 若某处写成 "INSERT INTO im_platform.im_user (user_name, nick_name, ...)"（**没有 id**）
#      ⇒ 那一行会被 AUTO_INCREMENT 分配一个新 id ⇒ **铁律 L1 被破坏** ⇒ 立刻标为缺陷。
#
# --- 校验 2：影子表里不许有「课安不认识的 id」（运行期）---
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT id FROM im_platform.im_user WHERE id <> 0"' | sort > /tmp/box_ids.txt
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
  "SELECT id FROM Kean.sys_user"' | sort > /tmp/kean_ids.txt
comm -23 /tmp/box_ids.txt /tmp/kean_ids.txt
# 期望：**无输出**。有输出 = 影子表里出现了课安不存在的 id
#   ⇒ ① box 的 /register 没被关掉（见 §2.5 / H9）；② 或某条物化路径没用显式 id。**停下排查，不要继续。**
wc -l /tmp/box_ids.txt /tmp/kean_ids.txt
# 期望：box 的行数 <= kean 的行数（**「按需物化」所以允许更少**，但不允许更多）
#
# --- 校验 3：结构性守卫 —— 不许出现映射类列/表（见 §1.5.4 守卫 B）---
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT table_name, column_name FROM information_schema.columns
   WHERE table_schema = \"Kean\"
     AND (column_name LIKE \"%im_user_id%\" OR column_name LIKE \"%box_user_id%\");
  SELECT table_name FROM information_schema.tables
   WHERE table_schema = \"Kean\"
     AND (table_name LIKE \"%user_map%\" OR table_name LIKE \"%user_mapping%\");"'
# 期望：两条**都无输出**。有输出 ⇒ 已经有人开始建映射结构 ⇒
#   ⚠️ **现在删掉成本是 0；等走到 F 阶段再删 = 全站业务外键迁一遍**（H15）。**当场处理。**
#
# --- 校验 4：自增计数器只增不减（与校验 1/2 互为佐证）---
docker exec -i "$MYSQL_CTR" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "SHOW TABLE STATUS FROM im_platform LIKE \"im_user\"\G"' | grep -E 'Auto_increment'
# 期望：Auto_increment 恒 > Kean.sys_user 的 max(id)。
#   ⚠️ 它**不是**同 id 复用的证据（显式插入 id 不需要自增帮忙），而是「万一有人漏写 id」时的**兜底网**。

# =====================================================================
# A-4  构建 im-platform（上游源码在 /opt/boxim-src/box-im）
# =====================================================================
cd /opt/boxim-src/box-im
git rev-parse HEAD                      # 记录 commit，写进变更单
mvn -pl im-platform -am -DskipTests package
ls -lh im-platform/target/*.jar
# 检查点：BUILD SUCCESS + 产出 im-platform jar
# ⚠️ im-platform 依赖 im-common 与 im-client，所以必须带 -am（或直接 mvn clean package）

# =====================================================================
# A-5  写 im-platform 的配置与 systemd 单元
# =====================================================================
sudo mkdir -p /opt/im-platform
sudo cp /opt/boxim-src/box-im/im-platform/target/im-platform.jar /opt/im-platform/
# 生成自己的 prod 配置（不进版本库），只写密钥占位
sudo tee /opt/im-platform/application-prod.yml >/dev/null <<'YAML'
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:13306/im_platform?useSSL=false&useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true
    username: ${JWT_DB_USER}
    password: ${JWT_DB_PASSWORD}
  data:
    redis:
      host: 127.0.0.1
      port: 26739
      password: ${JWT_REDIS_PASSWORD}
      # ⚠️ 不要设 database（保持 0 号库）
jwt:
  accessToken:
    expireIn: 1800
    secret: ${JWT_ACCESS_TOKEN_SECRET}      # 必须与 kean 的 IM_JWT_SECRET 逐字节一致
  refreshToken:
    expireIn: 604800
    secret: ${JWT_REFRESH_TOKEN_SECRET}
minio:
  endpoint: http://127.0.0.1:19000
  domain: https://api.kean.college/api/files
  accessKey: ${STORAGE_ACCESS_KEY}
  secretKey: ${STORAGE_SECRET_KEY}
  bucketName: kean
  imagePath: image
  filePath: file
  videoPath: video
  expireIn: 36500          # ⚠️ 见 §5.3 坑 3：防止 FileExpireTask 删错对象
YAML
sudo chmod 600 /opt/im-platform/application-prod.yml
# 检查点：文件里**没有任何明文密钥**（全是 ${...}）

# =====================================================================
# A-6  校验三边密钥一致（不泄露密钥本体）
# =====================================================================
printf '%s' "$IM_JWT_SECRET"          | sha256sum | cut -c1-16   # kean 侧
printf '%s' "$JWT_ACCESS_TOKEN_SECRET" | sha256sum | cut -c1-16  # im-platform 侧
grep -A2 'accessToken' /opt/im-server/application.yml | grep secret   # im-server 侧（人工比对）
# 检查点：三个前 16 位**完全一致**（既有实测值是 1aa7a016cacd65ad，改过密钥就不是它了）

# =====================================================================
# A-7  起服务
# =====================================================================
sudo systemctl daemon-reload && sudo systemctl enable --now im-platform
journalctl -u im-platform -n 80 --no-pager
# 检查点（三件事都要看到）：
#   ① Started ... / Tomcat started on port 8888
#   ② 没有 ClassCastException、没有 Redis 连接失败、没有 MySQL Access denied
#   ③ 没有 "Table 'im_platform.xxx' doesn't exist"（说明库名/profile 对了）

# =====================================================================
# A-8  nginx 前缀 + ufw
# =====================================================================
# 把 §7.2 的片段加进服务器上的 /root/nginx/conf/nginx.conf（**不是**本仓的示例文件）
export NGINX_CTR=$(docker ps --format '{{.Names}}' | grep -Ei 'nginx' | head -1)
docker exec "$NGINX_CTR" nginx -t
docker exec "$NGINX_CTR" nginx -s reload
ufw allow from 172.17.0.0/16 to any port 8888 proto tcp comment 'nginx container -> im-platform'
ufw status | grep 8888
# 检查点：nginx -t 通过；ufw 里有 8888 那条

# =====================================================================
# A-9  跑完 §3.A.1 的 8 条验收
# =====================================================================
# （见 §3.A.1，逐条粘贴执行。8 条**全绿**才算阶段 A 完成）
```

**阶段 A 的代码改动清单（只有这 4 处，全部在 kean 侧）**

| # | 文件 | 类:方法 | 改什么 | 为什么 |
|---|---|---|---|---|
| 1 | `kean/src/main/java/com/kean/im/ImKickService.java` | `ImKickService.markBanned` | 注入改为 `RedisTemplate<String,Object>`；写入 **`Integer 1`** 而不是字符串 `"1"` | F3：`AuthInterceptor` 会 `(Integer)` 强转，字符串会让 im-platform 每个请求 500 |
| 2 | `kean/src/main/java/com/kean/im/ImKickService.java` | 新增 `markUnregistered(Long)` | 写 **`Integer 2`** | F4：注销与封禁的提示语不同 |
| 3 | `kean/src/main/java/com/kean/im/ImShadowUserService.java` | **新增** | 影子用户按需物化（§2.3 的 SQL）+ 改名/头像同步 | F13：`bindFriend` 等路径会查 `im_user` |
| 4 | `kean/src/main/java/com/kean/im/ImFriendProjectionService.java` | **新增** | kean 的会话关系双向投影进 `im_friend` | F12：box 的 `sendMessage` 会校验好友关系 |

> ⚠️ 第 1、2 处**必须在阶段 A 完成**，否则 im-platform 一上线就每个请求抛 `ClassCastException`。
> 第 3、4 处在阶段 A 可以只交付「可被单独调用 + 有验证」，真正的批量物化发生在阶段 B/C。

---

### 10.5 ⭐⭐ 终态对照表（**每个能力最终归谁 —— 一张表回答「全量换成 box im」之后还剩什么**）

> **读法**：
> - **归属** 一列只有三种取值：**box** / **kean** / **两者**。
> - **「两者」= 单一能力被拆成两半**（例如「聊天消息由 box 存、但消息里的文件 URL 由 kean 签发」）。
>   这类拆分**必须在设计期就说清楚**，否则会被误当成「迁移没做完」。
> - **⚠️ 高危** 一列标出 **双轨期会「重复实现」的能力** —— 也就是**同一个语义在两处各有一份实现**。
>   **重复实现 = 高危**，因为两处必然会在某个时刻不一致，而**不一致不会报错**。
> - **落到哪个阶段** 一列让这张表可以直接当排期表用（阶段定义见 §3.0）。

#### 10.5.1 能力归属总表

| # | 能力 | **归属** | 依据 / 对等物 | **⚠️ 双轨期是否重复实现（高危）** | 落到哪个阶段 |
|---|---|---|---|---|---|
| 1 | **账号（登录 / token 签发）** | **box**（终态） | `im_user` + `UserServiceImpl.login`（3.E.1）；kean **代理**以保持客户端无感（3.E.4） | 🔴 **是** —— 双轨期 kean 的 `AuthServiceImpl` 与 box 的 `UserServiceImpl.login` **两条登录链路同时在线**。缓解：按账号灰度 + 单账号回退（3.E.3） | **E** |
| 2 | **账号名 / 密码哈希** | **box**（终态） | `im_user.user_name`（唯一键）/ `im_user.password`；两侧都是 BCrypt（3.E.2）⇒ **可直接复制，无需重加密** | 🔴 **是** —— `sys_user.password_hash` 与 `im_user.password` 两份。⚠️ **改密必须双写**（3.E.5 原则 5），否则「旧密码仍可登录」 | **E** |
| 3 | **注册** | **kean**（**永远**） | box **不提供可用的注册**（`/register` 会自增 id，破坏 L1）；kean 的注册含邮箱验证码 / 图形验证 / 学校选择 | ✅ 否（**刻意只留一处**：`/im-api/register` 从 A 阶段就在 nginx 404） | —— 永久 |
| 4 | **注销账号** | **kean**（**永远**） | box **没有注销接口**（3.E.1 已核对：10 个 controller 均无删除账号入口） | ⚠️ **半重复** —— box 侧没有「注销」这个动作，但有「注销态」需要同步（`is_banned=1` + `im:user:denied=2`）。**同步漏一处 = 已注销账号还能连 IM** | **E**（同步） |
| 5 | **改密** | **box**（终态，灰度后） | `PUT /modifyPwd`（需要 accessToken） | 🔴 **是** —— 同 #2 的「改密双写」问题。**这是 E 阶段最容易漏的安全点** | **E** |
| 6 | **找回密码 / 改邮箱** | **kean**（**永远**） | box 完全没有邮箱与验证码概念 | ✅ 否 | —— 永久 |
| 7 | **封禁（管理动作）** | **kean**（**永远**，权威） | `sys_user.status` + `AccountBanServiceImpl` + 管理端 + 申诉流程 | 🔴 **是（4 处）** —— 见 H3：`status` / `kean:user:banned` / `im:user:denied` / `im_user.is_banned`。⚠️ **E 阶段起 `im_user.is_banned` 从「纵深防御」升级为「必需项」**（box 的 `/refreshToken` 会用它决定是否删除 `im:user:denied`，见 3.E.5 原则 3） | **E**（补第 4 处） |
| 8 | **禁言 / 互动限制** | **kean**（**永远**） | `sys_user.muted` / `forbid_publish` / `forbid_apply` + `ChatServiceImpl.send` 前置校验 | ⚠️ **潜在重复** —— box 侧**没有**这两个模型，所以它**必须**由 kean 在写路径前校验；⚠️ **硬约束：客户端不许绕过 kean 直接调 box 的发消息接口**（H3 已述） | C（加 nginx 限制） |
| 9 | **拉黑 / 黑名单** | **kean**（**永远**） | `blacklistService.assertCanInteract` | 同 #8：box 无黑名单模型，靠 kean 前置校验 | C |
| 10 | **会话（一对一会话关系）** | **box** | `im_friend`（双向）+ `im_private_message.conv_key`；⚠️ 注意 box 的「会话」不是一张表 | ⚠️ **是** —— `Kean.chat_session` 与 `im_friend` 并存。终态里 `Kean.chat_session` **只保留历史位点与业务关联**，不再承载未读（§6.3.3） | **C** |
| 11 | **消息收发与落库** | **box** | `im_private_message` | 🔴 **是（阶段 B）** —— 双写期两份消息表。终态**必须**在 C 结束时关掉双写（见 §3 阶段 B 的「终态位置」说明） | **B → C** |
| 12 | **历史消息（C 之前的老数据）** | **kean**（**永远**，只读） | §4 的决策：不搬，客户端两段式读取 | ⚠️ **是（但可控）** —— 客户端要读两处。⚠️ 这是**有意的、只读的、不会分叉的**重复，与 #11 的写双写**性质完全不同** | **C**（两段式读取） |
| 13 | **未读 / 已读** | **box** | 消息 `status(0/1/2/3)` + Redis `im:readed:private:position:*`（F9/F11） | 🔴🔴 **是（最危险）** —— `chat_session.a_unread/b_unread`（计数器）与 box 的 status 推导（行数）**形状完全不同**，同时生效 ⇒ 角标清不掉。**唯一正确的解法是「同一时刻只有一个写入方」**（§6.3.3 / H2） | **C** |
| 14 | **离线消息拉取** | **box** | `loadOfflineMessage(minId)`（F5）｜**目标①的主要收益** | ⚠️ 阶段 B 不重复（读仍在 kean）；C 之后 kean 不再有离线拉取 | **C** |
| 15 | **多端同步** | **box** | `sendToSelf`（F7）+ `IMTerminalType`（三终端）+ `devId` | ⚠️ **是** —— kean 的 `V30 single_device`（单设备）与 box 的多终端策略**互斥**。必须明确「踢线归 box」（§6.4.1），否则无限互踢 | **C** |
| 16 | **群聊** | **box** | `im_group` / `im_group_member` / `im_group_message` | ✅ 否（kean **完全没有群概念** ⇒ 纯新增，不存在重复实现） | **D2** |
| 17 | **富媒体（语音 / 文件 / 视频）消息** | **box** | `MessageType.AUDIO(3)/FILE(2)/VIDEO(4)` + `im_file_info` | ✅ 否（kean 现在没有这三种消息类型） | **D1** |
| 18 | **对象存储与媒体 URL 签发** | **两者 ⭐** | **文件存在 RustFS；消息里的 URL 由 kean 的 `/api/files/**` 签名签发**（§5.3）；box 只会拼**永久公开 URL**（F15/F16），而桶是**私有**的 | ⚠️ **是（必须接受）** —— box 生成一份 `file_path`（它自己用），kean 再规范成签名 URL 交给客户端。**这不是缺陷，是 §5.2 的直接推论** | **D1** |
| 19 | **通知 / 公告** | **kean**（**永远**） | `NotificationService` + `Announcement` + `RealtimePublisher`；⚠️ **`/ws/chat` 的非聊天推送必须保留** | ⚠️ **是（容易被误删）** —— 「IM 消息」归 box，但「通知」归 kean。二者都通过实时通道下发，**退役 `/ws/chat` 时只许删聊天分支** | **C**（清理时） |
| 20 | **任务 / 履约 / 申请** | **kean**（**永远**） | box 无对等物 | ✅ 否 | —— 永久 |
| 21 | **学校 / 校区** | **kean**（**永远**） | box 无对等物（§1.5.1 的核心矛盾） | ✅ 否 | —— 永久 |
| 22 | **评价 / 举报 / 申诉** | **kean**（**永远**） | box 无对等物；证据文件存 RustFS（见 #18） | ✅ 否 | —— 永久 |
| 23 | **管理端（`web-kean`）** | **kean**（**永远**） | box 的 `box-im-admin` 是**独立仓库**（F29），提供用户/群组/消息/敏感词管理，但**接不上我们的权限体系与业务视角** | ⚠️ **潜在重复** —— 若将来引入 `box-im-admin`，会出现「两套后台管同一批 IM 数据」。**当前建议：不引入**，kean 管理端继续读 `/api/admin/im/*` | —— 永久 |
| 24 | **敏感词** | **两者 ⚠️** | box 有 `im_sensitive_word` 表 + 注册/昵称过滤 + 群消息过滤；kean 有自己的内容安全与举报流程 | 🔴 **是** —— **两套词库、两套判定**。⚠️ 词库不同步会表现为「kean 拦得住、box 拦不住」。**必须明确谁是权威**（建议：kean 为业务权威，`im_sensitive_word` 作为 IM 侧的第二道，且**由 kean 单向同步**） | **D2**（群聊启用时） |
| 25 | **钱包 / 余额（如存在）** | **kean**（**永远**） | ⚠️ **本仓未核实是否存在钱包模块**（见 §12 U23）；**无论如何** box 里没有任何资金相关模型 | ✅ 否 | —— 永久 |
| 26 | **会话位点（`last_seq_no` / `a_read_seq` / `b_read_seq`）** | **kean**（只读历史） | 只在「两段式读取」与「回滚」时有用 | ⚠️ **是** —— C 阶段之前是权威，C 之后降级为只读遗留。**切换时必须清零/停用未读语义**（#13） | **C** |
| 27 | **`seq_no` 分配器** | **box**（终态） | box 的 Redis `INCR` + Redisson 锁（F10） | 🔴 **是（阶段 B）** —— kean 的 `ChatSeqService` 与 box 的 `getNextSeqNo` 并存。**必须保证同一时刻只有一个分配器**（H4） | **B → C** |
| 28 | **在线状态 / 终端槽位** | **box** | `im:user:server_id:{userId}:{terminal}`（已实测） | ⚠️ **是** —— kean 的 `PresenceService` 与 box 的槽位并存。终态建议：**IM 在线态以 box 为准**，kean 的 `PresenceService` 只服务非 IM 场景 | **C** |
| 29 | **踢线（单账号多端登录）** | **box**（终态） | `LoginProcessor` + `devId`；kean 的 `ImKickService.forceLogout` **只保留给封禁/注销**（§6.4.1） | 🔴 **是** —— 两套踢线机制。**不明确归属就会无限互踢**（H12） | **C** |
| 30 | **音视频通话（RTC）** | **box**（可选） | `MessageType.RTC_*`（F8）+ `/webrtc/private/**`；⚠️ **多人通话在 README 里标注为商业版能力** | ✅ 否（kean 现在没有） | 未排期（按需） |
| 31 | **消息撤回** | **box** | `MessageStatus.RECALL(2)` + `im_message_deletion` | ⚠️ **是** —— kean `chat_message.status=2` 与 box 的撤回并存 | **C** |
| 32 | **聊天记录的本地缓存** | **客户端** | box 新版用 IndexedDB / SQLite（README） | ⚠️ **是（第三套未读）** —— 客户端本地缓存的未读**也必须**在切换时丢弃（H2 已述） | **C** |

#### 10.5.2 从这张表读出的三条结论

1. **终态里「归 box」的其实只有两块**：**IM 能力（#10~#18、#27~#31）** 与 **认证能力（#1、#2、#5）**。
   其余 20 项**永久留在 kean** —— 这不是「迁移没做完」，而是**box 里根本没有可换的东西**（§1.5.1）。
2. **🔴 高危重复实现共 9 处**（#1、#2、#5、#7、#11、#13、#24、#27、#29），
   其中 **#13（未读）与 #1/#2/#5（认证与密码）是「漏一处就出事、而且不报错」的两组**。
   **降低风险的通用手段只有一个：让重复的一方变成「只读/派生」，并给它一个可观测的一致性校验。**
3. **⭐ 「全量换成 box」在数据层只发生了一次真正的切换 = #1/#2/#5（认证）**，
   而这恰好也是**唯一能让整个 App 不可用的一处** ⇒ 它就是 **E 阶段**，也就是**必须最后做、最慢做**的那一段（§1.5 铁律 L2）。

---

## 11. 与既有文档的关系（避免重复与冲突）

| 文档 | 关系 |
|---|---|
| [`docs/ops/im-migration.md`](./im-migration.md) | **上游事实文档**：两个库的边界、`im-platform.sql` 的执行与回滚、`V34/V35`、阶段 3（切 im-server）的环境变量、部署顺序、回滚、封禁键的序列化差异（§6.7）。**本文不重复它**，只引用。⚠️ 它 §6.7 已经预告了「将来若部署 im-platform，必须改用 `RedisTemplate<String,Object>` 写数字」——本文 §2.4 就是这个预告的落地清单 |
| [`docs/ops/im-server-patch.md`](./im-server-patch.md) | **im-server 的运维与对接契约**：不需要补丁、密钥、Redis、队列键必须带 serverId、`/im` 路径、ufw 8878、`IMRecvInfo` 契约。**本文不重复**。⚠️ 它第 33-38 行与 §6 提到的「`ImKickService` 类注释过期」是**既有遗留问题**，本文不改代码、也不改那份文档 |
| [`docs/ops/im-monitoring.md`](./im-monitoring.md) | **已完成的监控**（计数器 / 巡检 / 告警 / `/health/ready` / 管理端三接口）。本文 §7.6 的「新增要盯的」是它的**扩展**，不是重复 |
| [`docs/ops/rustfs.md`](./rustfs.md) | **对象存储手册**：RustFS 的加固、验证、备份。本文 §5 依赖它的结论（**不支持 ACL**、私有桶、备份必须与库一起做） |
| [`docs/ops/nginx.conf.example`](./nginx.conf.example) | **nginx 参考**：`api.kean.college` 的三个 location。本文 §7.2 新增 `/im-api/`，**建议执行后回抄进该文件**（本次未改动它） |
| `docs/sql/im-platform.sql` | box 的 9 张建表 SQL（独立库 `im_platform`）。本文 §2.3 的 `AUTO_INCREMENT` 与 §4.3 的搬法都建立在它的列定义上 |
| [`docs/ops/e2e-im-test.py`](./e2e-im-test.py) | 既有的端到端测试脚本（kean 注册/登录 + 自研 WS + im-server）。**本文的验收以 curl/SQL 为主**；建议在阶段 B/C 时把「im-platform 双写一致性」也加进这个脚本 |

---

## 12. ⚠️ 未证实清单（**不要当成事实使用**）

以下是本文**无法从源码或本仓确证**的点。**每一条都在上面被标注为「未证实」，动手前必须实测。**

| # | 未证实项 | 影响 | 怎么证实 |
|---|---|---|---|
| U1 | `auth-interceptor.exclude-paths` 里的 **`/*/upload`** 是否真的让 `POST /image/upload`、`POST /file/upload` **免 token**（Spring 的 `AntPathMatcher` 中 `*` 是否跨 `/`） | **安全**。若放行，任何人都能匿名往我们的 RustFS 桶里传文件 | 阶段 A 验收第 5 条：不带 token `curl -X POST .../image/upload`，看是 401 还是别的 |
| U2 | 图片 / 文件 / 语音 / 视频消息里 **`content` JSON 的确切字段名与结构** | 富媒体（D1）无法正确实现 | 读 `im-uniapp` 的消息组件与 `im-web` 的源码；或抓一次真实客户端的请求体 |
| U3 | `ConvUtil.buildConvKey` 的**确切拼法**（本文按「小的在前、`_` 连接」书写） | §3.C.1 第 2 条与 §4.3 的 SQL 会写错，导致比对恒为空/恒不等 | 读 `im-platform/.../util/ConvUtil.java` |
| U4 | `PrivateMessageVO` 的**完整字段列表**（本文只用到 `id/localId/seqNo/sendId/recvId/content/type/status/sendTime/deleted`） | 客户端映射可能漏字段 | 读 `im-platform/.../vo/PrivateMessageVO.java` |
| U5 | `uni-kean/src/utils/imSocket.ts` 的登录帧**是否传 `devId`**（box 的 `{cmd:0, data:{accessToken, devId}}`） | §6.4 的多端策略无法确定 | 读该文件（本仓可读，本轮未读到该处） |
| U6 | **RustFS 是否支持匿名读取 + 桶策略**（本文的结论「不依赖它」是**保守选择**，不是「它一定不支持」） | 不影响本方案（我们统一走 kean 签名 URL），但会影响「将来能否用直接 URL」的判断 | 读 `https://docs.rustfs.com/zh/administration/data/bucket/policy`，并注意 RustFS 仓库有关于匿名访问与 `Principal: "*"` 的未决 issue（见 §13） |
| U7 | `FileExpireTask` 在「RustFS 私有桶 + 无签名 URL」下 `minioService.isExist` 的返回（被拒 vs 判定不存在） | 决定它会不会删对象。**本文用 `expireIn=36500` 绕开这个不确定性** | 在测试环境把 `expireIn` 设成 0 跑一次，观察日志与对象数 |
| U8 | im-platform 是否真的**没有** `context-path`（F22 只看到 `server.port` 与 `spring.mvc.pathmatch`，没看到 `servlet.context-path`） | §7.2 的 nginx 前缀剥离写错会 404 | 起服务后 `curl http://127.0.0.1:8888/user/self` 看是不是 401（而不是 404） |
| U9 | `im_user.id` 显式插入更大值后 MySQL `AUTO_INCREMENT` 的**推进语义**（本文按 InnoDB 的常见行为假设会推进，但仍要求显式 `ALTER`） | 若假设错，影子行可能与自增分配撞车 | 阶段 A 验收 §3.A.1 第 8 条 + `SHOW TABLE STATUS` 的 `Auto_increment` |
| U10 | 本仓 `.env.prod` 的 `STORAGE_ENDPOINT=http://156.224.78.44:19000` 是否是**服务器上真实生效**的值 | 配置写错会导致对象存储连不上 | 以服务器 `/opt/kean/.env.prod` 为准（本仓文件可能滞后；`IM_JWT_SECRET` / `KEAN_IM_MIRROR_ENABLED` 在本仓 `.env.prod` 里**根本不存在**，已在既有文档中标注过同类问题） |
| U11 | 本仓 `docker-compose.prod.yml` 的容器名（`kean-mysql` 等）与服务器实际容器名是否一致 | §3.A.1 等命令的 `$MYSQL_CTR` 会取错 | `docker ps --format '{{.Names}}'`（既有文档用的是 `mysql_nzpx-mysql_nzPX-1`） |
| U12 | kean 侧 `chat_session` 的 `task_id` / 会话与**任务**的关系是否影响「好友投影」（kean 的会话是「任意两人」，不是好友关系） | §2.4 的 `ImFriendProjectionService` 口径可能过宽（把「聊过天的陌生人」写成好友） | 读 `ChatServiceImpl.open` 的注释（已读到：**「发起私信不再限制学校/校区：任何人都可以发起」**）—— 所以投影就是「任意两人」，这是**有意的**，但要确认它不违反 box 侧语义 |
| U13 | `im-platform` 在 3.8G 机器上的**实测常驻内存** | §7.1 的 384m/768m 是估算 | 上线后 `docker stats` / `systemctl status im-platform` + `ps -o rss` 观察 24 小时 |
| U14 | box 的 `sendResult=true` 产生的 `im:result:*` 队列**由哪个类消费、会不会漏消费** | H10 / §7.6 第 3 条 | 读 `im-platform/.../task/consumer/**`（本轮只确认了目录存在，未逐个读文件） |
| U15 | `docs/ops/im-server-patch.md` §7 记载的**行号**（如 `LoginProcessor.java:51`）未经本文重新核对 | 仅影响引用精度 | 需要时按该文档给出的 URL 重新打开 |

#### 12.1 ⭐ 本轮（终态全量版）新增的未证实项 U16 ~ U25

> 这些条目全部来自「终态 = 全量换成 box」这一定性 —— 它们**在上一轮的 A~D 范围里不存在**，
> 所以上一轮没有覆盖。**每条都必须在 E 阶段动手前证实。**

| # | 未证实项 | 影响 | 怎么证实 |
|---|---|---|---|
| **U16** | ⭐ **kean 的 BCrypt 哈希能否被 box 的 `PasswordEncoder` 直接验证通过**。本文的结论是「**理论上可以**」（两侧都是 `new BCryptPasswordEncoder()` 无参构造 ⇒ 强度 10，见 3.E.2 的源码核对），但这**是推断，不是实测** | 🔴 **E 阶段的核心风险**：若不可行，则所有**未改过密码**的老用户**无法在 box 侧登录** ⇒ 要么推迟 E、要么做一次「全量密码重设」（对用户是重大打扰） | **必做实测**（§3.E.7 第 2 条）：取一个账号，把 `sys_user.password_hash` 原值复制到 `im_user.password`，然后**内网直连 8888** 用 box 的 `/login` 试一次，期望 200。⚠️ 测完**立刻**把 `/im-api/login` 在 nginx 恢复为 404 |
| **U17** | ⭐ **`sys_user.password_hash` 的 BCrypt 前缀分布**（本文只知道「代码写的是 `BCryptPasswordEncoder`」，**没有普查过真实数据**） | 若历史数据里存在 `$2y$` / `$2b$` / 带 `{bcrypt}` 前缀 / 非 60 字符的行，`matches` 会**直接判失败**（P1/P4） | 跑 3.E.2 的形状普查 SQL：`SELECT LEFT(password_hash,7), CHAR_LENGTH(password_hash), COUNT(*) FROM Kean.sys_user GROUP BY 1,2` |
| **U18** | ⭐ **kean 与 box 的 JWT 是否「互相可解」**。已知：**签名密钥可以配成同一个**（本文 A-6 已经在校验三边一致），且 `ImTokenService` **必须显式 HS256**（§0.2 已完成项）。**未证实**的是：box 的 `im-platform` REST 与 im-server **会不会对 token 做额外校验**（例如比对 Redis 里的会话、校验 terminal 与连接是否一致、校验 token 是否由自己的 `/login` 签出） | 🔴 影响 E 阶段「双轨期两套 token 并存」的可行性：若 box 侧有**额外校验**，那么「kean 代理/兑换」的方案设计要改 | ① 读 `im-common` 的 `JwtUtil`（`checkSign` / `getInfo` / `getUserId`）；② 读 im-server 的登录处理器，确认除了验签与 Redis 在线槽位之外**没有别的检查**；③ 实测：用 kean 签的 token 调 box 的受保护接口（§3.A.1 第 6 条**已经就是这么做的**——把它的结论**显式记录**下来） |
| **U19** | ⭐ **box 侧是否存在任何「注销 / 停用账号」的隐藏入口**。本文已核对：`controller/` 下 10 个类**没有删除账号的接口**，`User` 实体**没有 `deleted` 字段**，`/logout` **没有实现**。**未证实**的是：是否有未列出的定时任务 / 管理端接口（`box-im-admin` 是**独立仓库**，本文**未读它的源码**）能停用账号 | 若 `box-im-admin` 能停用账号，则「注销只在 kean」这个结论**在启用 box 管理端时会失效** ⇒ H14 的「一侧生效」风险回归 | ① 确认**我们不会部署 `box-im-admin`**（当前建议，见 §10.5 #23）；② 若将来要部署，**先读它的用户管理接口**再评估 |
| **U20** | ⭐ **`box-im-admin` 的全部能力边界**（README 只说「用户、群组、消息、敏感词等后台能力」，**本文未读其源码**） | 决定 §10.5 #23（管理端归属）与 #24（敏感词）的最终方案 | 读 `https://github.com/bluexsx/box-im-admin` 的接口清单；**只有在决定引入它时才需要做** |
| **U21** | ⭐ **`im_sensitive_word` 的词库来源、匹配算法、与 kean 内容安全的差异**（已核对：`register` 会用 `sensitiveFilterUtil` 过滤用户名/昵称；**未读**群消息/单聊消息的过滤逻辑与词库） | 影响 §10.5 #24「敏感词」的归属：两套词库不同步 ⇒ **kean 拦得住、box 拦不住**（或反之） | 读 `im-platform/.../util/SensitiveFilterUtil.java` 与 `SensitiveWord*` 相关类；再决定「谁权威、谁单向同步」 |
| **U22** | ⭐ **E 阶段灰度的落地形态**：`kean.auth.box-login-percent` / `box-login-allowlist` / `auth_graylist` 表 **在本文里都是设计（示意），本仓当前并不存在** | 若按本文照抄配置项名会失败 | 落地时按本仓的配置风格命名；**本文的开关粒度（白名单 + 比例 + 单账号强制回退）是要求，命名不是** |
| **U23** | ⭐ **本仓是否存在「钱包 / 余额」模块**（§10.5 #25 标了「若有」） | 只影响对照表的完整性；**钱包无论如何都不会归 box**（box 无任何资金模型） | 在 `kean/` 下搜 `wallet` / `balance` / `coin` / `purse` 等命名；或问业务方 |
| **U24** | ⭐ **`im_user.password` 从 `{noop}__disabled__` 换成真哈希之后，有没有别的副作用**（例如 box 的某个任务/校验会因为「密码看起来像哈希」而行为改变） | 影响 3.E.2 的 P5 落地 | 读 `im-platform` 里所有读 `password` 字段的地方（本文已确认只有 `login` / `register` / `modifyPassword` 三处用到），**并实测一次** |
| **U25** | ⭐ **Spring Security 的 BCrypt 实现是否确实按哈希自带前缀选版本**（即 `$2b$` / `$2y$` 能否被 `matches` 直接校验通过）。本文在 3.E.2 的 **P1/P4** 里按「**能**」处理，并因此**不再建议改写前缀** —— 但这一点**本文未实测**（基于对该库行为的常识判断，未打开源码逐行核对） | 决定 P1/P4 的处理方式：若结论错，遇到 `$2b$`/`$2y$` 的历史行会**登录失败** | ① 在测试库造一行 `$2b$` / `$2y$` 的哈希，跑一次 `matches`；② 或读 `spring-security-crypto` 的 `BCrypt.checkpw` 实现。⚠️ **在 U16 的实测里顺手把这一条一起测掉**（成本几乎为零） |

> ⚠️ **U16 与 U18 是「必须实测」的两条**（不是「可以查文档」的两条）：
> 前者决定 E 阶段能不能做，后者决定 E 阶段怎么做。**两条都不许用推断代替实测。**

---

## 13. 来源清单（本文抓取过的 URL）

**抓取成功（本轮的 `[VERIFIED]` 依据）**

| 内容 | URL |
|---|---|
| `PrivateMessageServiceImpl`（`loadOfflineMessage` / `sendMessage` / `readedMessage` / `getNextSeqNo` / `saveMessage`） | https://raw.githubusercontent.com/bluexsx/box-im/master/im-platform/src/main/java/com/bx/implatform/service/impl/PrivateMessageServiceImpl.java |
| `IMSender`（`sendToSelf` 语义 / `pushPrivateMessage` / 队列键拼法） | https://raw.githubusercontent.com/bluexsx/box-im/master/im-client/src/main/java/com/bx/imclient/sender/IMSender.java |
| `AuthInterceptor`（**不查库** / 封禁键 `(Integer)` 强转） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/interceptor/AuthInterceptor.java |
| `UserSession`（身份来自 token） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/session/UserSession.java |
| `UserServiceImpl`（`login` / `refreshToken` 删封禁键 / `register`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/service/impl/UserServiceImpl.java |
| `FriendServiceImpl`（`isFriend` / `bindFriend` 查 `im_user`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/service/impl/FriendServiceImpl.java |
| `MessageType`（全量数值） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/enums/MessageType.java |
| `MessageStatus` | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/enums/MessageStatus.java |
| `FileType` | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/enums/FileType.java |
| `Constant`（60 天 / 10000 条 / 1024 字符 / 20MB） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/contant/Constant.java |
| `RedisKey`（box 的全部 Redis 键） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/contant/RedisKey.java |
| `FileServiceImpl`（URL 拼法 / `setBucketPublic`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/service/impl/FileServiceImpl.java |
| `FileController`（`/image/upload`、`/file/upload`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/controller/FileController.java |
| `LoginController`（`/login`、`/register`、`/refreshToken`、`/modifyPwd`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/controller/LoginController.java |
| `FileExpireTask`（每天 03:00 真删对象 + URL 形状硬假设） | https://raw.githubusercontent.com/bluexsx/box-im/master/im-platform/src/main/java/com/bx/implatform/task/schedule/FileExpireTask.java |
| `PrivateMessageDTO`（`localId`/`content`/`type` 必填） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/dto/PrivateMessageDTO.java |
| `application.yml`（8888 / jwt / exclude-paths） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/resources/application.yml |
| `application-dev.yml`（库名 `im_platform_open` / `minio.*`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/resources/application-dev.yml |
| `application-prod.yml`（库名 `im_platform` / `minio.domain`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/resources/application-prod.yml |
| `README.md`（模块结构 / 独立管理后台 / 建库说明 / PR 到 `v_4.0.0`） | https://raw.githubusercontent.com/bluexsx/box-im/master/README.md |
| `im-uniapp/.env.js`（`BASE_URL` / `WS_URL`） | https://raw.githubusercontent.com/bluexsx/box-im/master/im-uniapp/.env.js |
| `im-uniapp` 目录清单（`components/` `pages/` `store/` `db/`） | https://api.github.com/repos/bluexsx/box-im/contents/im-uniapp |
| `im-platform/.../task/schedule` 目录清单 | https://api.github.com/repos/bluexsx/box-im/contents/im-platform/src/main/java/com/bx/implatform/task/schedule |
| `im-platform/.../task` 目录清单（含 `consumer/`） | https://api.github.com/repos/bluexsx/box-im/contents/im-platform/src/main/java/com/bx/implatform/task |

**本轮（终态全量版）新抓取并逐字读到的（**新增 —— 支撑 §3 阶段 E 与 §10.5**）**

| 内容 | URL | 支撑了本文哪一条 |
|---|---|---|
| `MvcConfig`（`PasswordEncoder` = `new BCryptPasswordEncoder()`；拦截器挂在 `/**` 并排除 `exclude-paths`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/config/MvcConfig.java | ⭐ **3.E.2 的密码哈希结论**（与此前已知的 kean `SecurityConfig#passwordEncoder` = `new BCryptPasswordEncoder()` 比对） |
| `LoginController`（**只有 4 个接口**：`/login`、`/refreshToken`、`/register`、`/modifyPwd`；**`/logout` 没有实现**） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/controller/LoginController.java | **3.E.1** 的接口表 + 「box 没有 logout」 |
| `UserServiceImpl`（`login` / `refreshToken` / `register` / `modifyPassword` / `update` / `findUserById` / `search` 全量方法体） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/service/impl/UserServiceImpl.java | ⭐ **3.E.2**（`matches` / `encode` 的调用点）、**3.E.4**（token 签发与有效期）、**3.E.5 原则 3**（`refreshToken` 成功后删 `im:user:denied`）、**3.E.6**（`update` 级联改好友/群成员昵称） |
| `UserController`（`/user/self` / `/find/{id}` / `/update` / `/search` / `/terminal/online`） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/controller/UserController.java | **3.E.1**（无注销入口）、**3.E.6** 第 2 条（信息暴露面） |
| `User` 实体（**没有 `deleted` 字段**；`password` 注释写「明文」但实际是 BCrypt） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/entity/User.java | **3.E.1**（box 无注销模型） |
| `controller/` 目录清单（**10 个类，无账号删除接口**） | https://api.github.com/repos/bluexsx/box-im/contents/im-platform/src/main/java/com/bx/implatform/controller | ⭐ **3.E.1 的核心证据**：「box 不提供注销」 |
| `config/` 目录清单（无 `WebConfig`/`SecurityConfig`；`PasswordEncoder` 在 `MvcConfig` 里） | https://api.github.com/repos/bluexsx/box-im/contents/im-platform/src/main/java/com/bx/implatform/config | 排除了「box 有 Spring Security 全栈」的可能 |
| `im-platform/pom.xml`（确认**只有 `spring-security-crypto`**，即只有 `BCryptPasswordEncoder`，没有 security starter） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/pom.xml | 3.E.2（说明「BCrypt 兼容」这件事与 Spring Security 的鉴权无关） |
| `application.yml`（**`exclude-paths` 原文全量**：`/login`、`/logout`、`/register`、`/refreshToken`、`/*/upload`、swagger 系列） | https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/resources/application.yml | **3.E.1 / 3.E.5 原则 2** 的 nginx 拒绝清单（F21 的复现） |

**抓取失败 / 未逐条打开（仅作线索，未采信为事实）**

- `im-platform/.../config/WebConfig.java` → **404**（**本轮新发现：不存在这个类**；`PasswordEncoder` 定义在 `MvcConfig` 里）
- `im-platform/.../IMPlatformApplication.java` → **404**（**本轮**；启动类不在该路径下，未逐字读）
- `https://raw.githubusercontent.com/bluexsx/box-im/master/.../UserServiceImpl.java`（**本轮**首次 fetch 失败，改用 jsdelivr 成功）
- `https://raw.githubusercontent.com/bluexsx/box-im/master/im-platform/src/main/java/com/bx/implatform/enums/MessageType.java`（首次 fetch 失败，改用 jsdelivr 成功）
- `https://cdn.jsdelivr.net/gh/bluexsx/box-im@master/im-platform/src/main/java/com/bx/implatform/config/MinioConfig.java` → **404**（该类不在 `config/` 下，实际是 `config/props/MinioProperties` + `thirdparty/MinioService`，**未逐字读**）
- `im-uniapp/utils/request.js` → **404**（该目录结构与既有文档描述的 uni-app 布局不同，**未逐字读**）
- ⚠️ **`box-im-admin` 仓库源码（本轮未打开）**：README 只说它是「独立仓库，提供用户、群组、消息、敏感词等后台能力」。
  <https://github.com/bluexsx/box-im-admin> / <https://gitee.com/bluexsx/box-im-admin>
  → 这是 §12.1 **U19 / U20** 的来源：**在决定是否引入它之前必须读它的接口**。
  ⚠️ 特别注意：README 还说明「**演示环境部署的是商业版本，与开源版本功能存在一定差异**」——
  所以**不要拿在线体验站（boximchat.com）的行为当作开源版的事实**。
- RustFS 桶策略 / 匿名访问（**未采信为事实**，仅作为 §12 U6 的线索）：
  - <https://docs.rustfs.com/zh/administration/data/bucket/policy>
  - <https://github.com/rustfs/rustfs/issues/1874>（anonymous access via bucket policy）
  - <https://github.com/rustfs/rustfs/issues/1336>（`Principal: "*"` 不生效）
  - <https://github.com/rustfs/rustfs/pull/2045>（presigned URL 相关改动）

**本仓依据（代码与既有文档，已逐处引用）**

- `kean/src/main/java/com/kean/im/ImTokenService.java`（显式 HS256、`DEFAULT_TERMINAL=1`、`issue(userId, terminal)`）
- `kean/src/main/java/com/kean/im/ImKickService.java`（`BANNED_KEY_PREFIX`、`StringRedisTemplate` 的字符串值 `"1"`、`deny` / `allow` / `forceLogout`）
- ⭐ **`kean/src/main/java/com/kean/config/SecurityConfig.java`**（第 104-105 行：`passwordEncoder()` = **`new BCryptPasswordEncoder()`**）
  —— **本轮新增引用，是 §3.E.2「两侧都是 BCrypt」的另一半证据**
- ⭐ **`kean/src/main/java/com/kean/service/impl/AuthServiceImpl.java`**（`register` 第 178 行 `passwordEncoder.encode`、
  `changePassword` 第 260 行、`resetPassword` 第 279 行、注销账号第 456 行把 `password_hash` 写成
  **`passwordEncoder.encode(UUID.randomUUID().toString())`** = 一个**合法的随机 BCrypt 哈希**）
  —— **本轮新增引用，支撑 §3.E.2 的 P2 与 §3.E.5 原则 4**
- ⭐ **`kean/src/main/java/com/kean/service/impl/AccountBanServiceImpl.java`**（`onBanned` 的四步联动 + `denyIm` / `allowIm` 吞异常）
  —— **本轮新增引用，支撑 §3.E.5 原则 6 与 §10.5 #7**
- `kean/src/main/java/com/kean/service/impl/ChatServiceImpl.java`（`send` / `markRead` / `open` / `catchUp`）
- `kean/src/main/java/com/kean/service/ChatSeqService.java`、`kean/src/main/java/com/kean/utils/FileUrls.java`、
  `kean/src/main/java/com/kean/security/FileUrlSigner.java`、`kean/src/main/java/com/kean/controller/FileController.java`
- `kean/src/main/java/com/kean/entity/ChatMessage.java`、`ChatSession.java`
- `docs/ops/im-migration.md`、`docs/ops/im-server-patch.md`、`docs/ops/im-monitoring.md`、
  `docs/ops/rustfs.md`、`docs/ops/security-hardening.md`、`docs/ops/nginx.conf.example`
- `docs/sql/im-platform.sql`（`im_user` 的列定义：`user_name` / `nick_name` / `password` 三者 `not null`，`unique key idx_user_name`）、
  `docker-compose.prod.yml`、`.env.prod`、`.env.prod.example`
- `uni-kean/src/api/chat.ts`、`uni-kean/src/pages/message/chat.vue`、`uni-kean/src/utils/request.ts`
