# 对象存储：MinIO → RustFS 迁移与运维手册

本文记录课安后端对象存储的换引擎过程、加固要求、验证方法、备份与回滚路径。
配套改动见 `docker-compose.dev.yml`、`docker-compose.prod.yml`、`.env*.example`。

---

## 1. 为什么要换掉 MinIO

MinIO 这几年的收缩是公开事实，逐条都有出处：

| 时间 | 事件 |
| --- | --- |
| 2025.06 | 社区版删除 Web 控制台（约 11 万行代码） |
| 2025.10 | 停止分发 Docker 镜像，改为仅源码分发（[minio/minio#21647](https://github.com/minio/minio/issues/21647)） |
| 2025.12 | 进入维护模式，不再接受新功能与 PR |
| 2026.02 | 官方仓库不再维护 |
| 2026.09 | Docker Hub 上的 `minio/minio`（累计 10 亿+ 拉取）返回 404 |

最后一个功能完整的社区版是 `RELEASE.2025-04-22T22-12-26Z`，目前只剩归档源码，**不再有安全更新**。
对一个存放用户头像、履约照片、举报申诉图片的公网应用来说，把无补丁的存储组件当长期方案不可接受。

参考报道：[小众软件](https://www.appinn.com/minio-remove-docker-images/)、[GIGAZINE](https://gigazine.net/gsc_news/en/20251023-minio-stops-distributing-free-docker-images)。

## 2. 换成了什么

[RustFS](https://github.com/rustfs/rustfs)：Rust 实现、Apache-2.0、S3 兼容对象存储，自带 Web 控制台。

- **1.0.0 GA 发布于 2026-09-16**，当前固定为 `rustfs/rustfs:1.0.0`
- 官方文档：<https://docs.rustfs.com/zh/>
- 官方提供 MinIO → RustFS 迁移路径（含复用 MinIO 数据目录的二进制替换）

**本次迁移不改业务代码**：客户端仍然是用 minio-java 8.5.17 —— 它是纯 S3 客户端（SigV4 + path-style），
与服务端实现无关。所以 `StorageService` / `StorageServiceImpl` / `FileUrls` / `FileController` 全部保持原样，
改动集中在编排、配置与文档。

课安实际用到的 S3 操作，逐条对照 RustFS 的
[S3 兼容性矩阵](https://docs.rustfs.com/zh/reference/s3-compatibility)（矩阵核验于 2026-08-09）：

| 代码位置 | S3 操作 | RustFS 状态 |
| --- | --- | --- |
| `MinioConfig#minioClient` | HeadBucket（`bucketExists`） | ✅ 已测试（创建/删除/列出/查看存储桶） |
| `StorageServiceImpl:92` | PutObject（带 Content-Type） | ✅ 已测试（上传/获取/复制/查看/删除对象，元数据往返） |
| `StorageServiceImpl:113,146` | HeadObject（`statObject`） | ✅ 已测试（同上） |
| `StorageServiceImpl:130` | GetObject | ✅ 已测试 |

RustFS 明确**未实现**的能力（POST Object 表单上传、存储桶访问日志、所有权控制，以及完全不支持的 ACL 授权）
本项目一个都没用到。

## 3. 本次改动清单

| 文件 | 改动 |
| --- | --- |
| `docker-compose.dev.yml` | 新增 `rustfs` + `rustfs-init`（自动建桶）两个服务；MySQL/Redis 不变 |
| `docker-compose.prod.yml` | **新增**。生产基线：MySQL + Redis + RustFS，全部只绑 `127.0.0.1` |
| `application-dev/test/prod.yml` | `kean.minio.*` 的取值来源由 `${MINIO_*}` 改为 `${STORAGE_*}` |
| `config/MinioConfig.java` | 仅日志与启动报错文案改为"对象存储"，并同步新变量名。**逻辑零改动** |
| `.env.example` / `.env.dev/prod/test.example` | `MINIO_*` → `STORAGE_*`，补注释 |
| `test/java/com/kean/storage/StorageEndpointCompatibilityTest.java` | **新增**兼容性冒烟测试，默认跳过 |
| `docs/ops/rustfs.md` | 本文档 |

**数据库不用动**：库里存的是对象键（或含键名的 `/api/files/...` URL），
`FileUrls.objectKey()` 会从旧 URL 里还原键名。只要新桶里的对象键与旧桶完全一致，迁移对业务透明。

## 4. 环境变量对照

### 4.1 应用侧（`.env*` 文件，Spring 读取）

| 旧名 | 新名 | 说明 |
| --- | --- | --- |
| `MINIO_ENDPOINT` | `STORAGE_ENDPOINT` | S3 API 地址，端口 **9000**（不是控制台 9001） |
| `MINIO_ACCESS_KEY` | `STORAGE_ACCESS_KEY` | 必须与容器侧 `RUSTFS_ACCESS_KEY` 一致 |
| `MINIO_SECRET_KEY` | `STORAGE_SECRET_KEY` | 必须与容器侧 `RUSTFS_SECRET_KEY` 一致 |
| `MINIO_BUCKET` | `STORAGE_BUCKET` | 开发/生产 `kean`，测试 `kean-test` |

> `MINIO_*` 旧名**不再被应用读取**。`MinioConfig` 是 fail-fast 的：三项缺一，启动直接抛
> `IllegalStateException`，报错文案已改为提示 `STORAGE_*`。这是刻意的——宁可启动失败，也不要静默降级。

配置前缀 `kean.minio.*` 保留不变：它对应的是 minio-java 客户端这一层，改名会牵动类名与全部 profile，
收益只是好看，不值得。

### 4.2 容器侧（compose 里给 RustFS 的变量）

| 变量 | 取值 | 说明 |
| --- | --- | --- |
| `RUSTFS_VOLUMES` | `/data` | 数据目录 |
| `RUSTFS_ADDRESS` | `:9000` | S3 API 监听 |
| `RUSTFS_CONSOLE_ENABLE` / `RUSTFS_CONSOLE_ADDRESS` | `true` / `:9001` | Web 控制台 |
| `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` | 来自 `.env` | 禁止使用默认值 `rustfsadmin` |
| `RUSTFS_DURABILITY_MODE` | `strict` | **单节点必须**，理由见 §6 |
| `RUSTFS_NEW_BUCKET_DURABILITY_MODE` | `strict` | 同上，双保险（此名出自 #8236，官方环境变量文档尚未收录，写错也只是被忽略） |
| `RUSTFS_OBS_LOGGER_LEVEL` | `error` | 降噪 |
| `RUSTFS_SCANNER_SPEED` | `slow`（仅生产） | 降低单节点扫描期内存峰值，见 §6 |

> RustFS 服务端自己还能识别 `MINIO_*` 旧变量名（会打弃用警告），这是它的迁移兼容特性，
> 与"应用侧不再读 `MINIO_*`"是两件事，别混。

## 5. 怎么起

### 本地开发

```powershell
# 1) 准备 .env（键名见 .env.dev.example），至少填 STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY
docker compose -f docker-compose.dev.yml up -d
# 2) 确认 rustfs-init 建桶成功
docker compose -f docker-compose.dev.yml logs rustfs-init
# 3) 起后端（run.ps1 会加载根目录 .env / .env.dev）
.\kean\run.ps1 dev
```

- S3 API：`http://127.0.0.1:9000`
- Web 控制台：`http://127.0.0.1:9001`（RustFS 的控制台是保留的，不像新版 MinIO 被删）

### 生产

```bash
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs rustfs-init
```

生产把 S3 API 与控制台都只绑到 `127.0.0.1`。看控制台请用 SSH 隧道，不要把 9001 暴露到公网：

```bash
ssh -L 9001:127.0.0.1:9001 user@server    # 然后本地访问 http://127.0.0.1:9001
```

### 手工建桶（自动建桶失败时的兜底）

```bash
# 控制台 9001 里直接建，或：
aws --endpoint-url http://127.0.0.1:9000 s3api create-bucket --bucket kean
```

## 6. 加固清单（每条都有具体理由，别删）

| 措施 | 为什么 |
| --- | --- |
| 单节点必须 `RUSTFS_DURABILITY_MODE=strict` | 1.0.0/1.0.1-preview 曾把**新建桶**默认置为 `relaxed`，而官方文档明确写 `relaxed` 不得用于单节点。实测（[rustfs/rustfs#8236](https://github.com/rustfs/rustfs/issues/8236)）断电后 `xl.meta` 变 0 字节，被覆盖对象的**全部历史版本一起丢**，客户端只看到 `NoSuchKey`。维护者 2026-09-30 确认属默认值缺陷、改动尚未合并 |
| 绝不用默认凭据 `rustfsadmin` | 官方明确"不允许"，且默认凭据 = 任何人可读写所有对象。compose 用 `${STORAGE_ACCESS_KEY:?}` 让缺失直接报错 |
| 固定镜像 tag，不用 `latest` | 1.0.0 GA 才两周，仍处快速修复期。升级必须人工确认 |
| 给 RustFS 设内存上限（生产 `mem_limit: 2g`） | 单节点单盘在扫描器深度扫描与前台负载重叠时内存持续上涨，约 7 天被 OOMKill（[#8121](https://github.com/rustfs/rustfs/issues/8121)，仍未关闭）。设上限把失败模式从"拖垮宿主机"收敛成"容器重启一次" |
| 生产开 `RUSTFS_SCANNER_SPEED=slow` | 同上，摊薄扫描强度，降低峰值。数据量小的时候没有副作用 |
| 控制台不暴露公网 | 控制台拥有完整管理面 |
| 独立备份（见 §10） | 单节点单盘**没有任何冗余**，官方 SNSD 模式也明说"容错完全依赖备份" |
| 不设 `RUSTFS_REGION` | 留空时按 `us-east-1` 应答，与 minio-java 客户端默认区域一致，可避开 `SignatureDoesNotMatch`。要改就客户端同步改 |

## 7. 验证清单

### 7.1 自动化冒烟（推荐先跑这个）

`StorageEndpointCompatibilityTest` 用**与生产完全相同的客户端和调用形状**打真实 endpoint，
默认不执行（`@EnabledIfSystemProperty`），不影响日常 `mvn test`。

```powershell
cd kean
mvn -q -Dtest=StorageEndpointCompatibilityTest test `
  "-Dstorage.it.endpoint=http://127.0.0.1:9000" `
  "-Dstorage.it.access-key=<你的 key>" `
  "-Dstorage.it.secret-key=<你的 secret>"
```

覆盖的语义（都是真实代码路径依赖的）：

1. `putObject` 用 `stream(in, length, -1)` —— 与 `StorageServiceImpl:95` 一字不差
2. `putObject` 用显式 partSize —— 对照组。**若 1 失败而 2 成功，把 `-1` 换成显式 partSize 即可规避**
3. `statObject` 读回 Content-Type / size —— `contentType()` 依赖；读不回正确 Content-Type，浏览器会把图片当附件下载
4. `getObject` 字节级比对 —— `open()` 依赖
5. `statObject` 缺失对象必须抛 `ErrorResponseException` 且 code 不是 `InternalError`
   —— `exists()` 与 `FileController` 转 404 依赖；返回 500 会让前端拿到错误码而不是 404
6. 对象键形状 `folder/userId/uuid.ext`（与真实键一致）

### 7.2 端到端手工验证

1. 控制台 9001 能看到 `kean` 桶
2. 前端走一次真实上传（换头像 / 提交履约照片 / 举报图）
3. 打开该图片：应 **200 + 内联显示**（不是下载）
4. 数据库里记录的键能在桶里找到；`GET /api/files/<key>` 正常
5. 把某个对象删掉后再请求：应该 404，而不是 500
6. 后端启动日志出现 `对象存储已连接 http://... / kean`；若出现"对象存储桶 ... 不存在"，说明建桶没成功

### 7.3 断电演练（强烈建议在正式启用前做一次）

`strict` 模式的意义就在断电后的完整性。可以在开发环境验证：写入若干对象 → 强制 `docker kill` →
重启 → 逐个 `statObject` + 字节比对。这一步能把你对单节点持久化的信心建立在实测上，而不是文档上。

## 8. 已知风险（诚实清单）

| 风险 | 现状与处置 |
| --- | --- |
| GA 只有两周（2026-09-16） | GA 前一个月还有 [#5716](https://github.com/rustfs/rustfs/issues/5716)：beta.11 进 OOM→full-heal 循环，beta.12 打破配额写入、分片上传、私有 OIDC。按"新 GA、需观察"对待：固定版本 + 备份 + 监控 |
| 新建桶持久化默认值缺陷 | [#8236](https://github.com/rustfs/rustfs/issues/8236)。已用显式 `strict` 规避；升级后请复查该 issue 的合并状态，并确认没有历史 relaxed 桶 |
| 单节点扫描器内存上涨 | [#8121](https://github.com/rustfs/rustfs/issues/8121)，未关闭。已用内存上限 + `SCANNER_SPEED=slow` 收敛 |
| 分布式模式更不成熟 | [#2794](https://github.com/rustfs/rustfs/issues/2794)（分布式起不来）、[#2663](https://github.com/rustfs/rustfs/issues/2663)（卷配置写错导致集群"重置"）、[#7964](https://github.com/rustfs/rustfs/issues/7964)（5 亿对象下反复重扫）。**当前只用单节点**；多实例部署指的是"多个后端共用同一个 endpoint"，不需要上分布式 |
| 单盘零冗余 | 官方 SNSD 定义即"容错完全依赖备份"。备份是硬要求，不是可选项 |
| `minio-java` 客户端随 MinIO 一起停更 | 现在能用（纯 S3 客户端）。长期建议换 `software.amazon.awssdk:s3`，改动集中在 `StorageServiceImpl` 的 5 个调用点 + `pom.xml`，属于独立的一次小改造 |
| 商业面 | 核心是 Apache-2.0（比 MinIO 的 AGPL 宽松，且不可追溯收回），定价页目前只卖支持与迁移规划。日志里已出现 `Connect service license` 一类商业化痕迹，属"留意"级别，不构成阻止因素 |

## 9. 从旧 MinIO 迁数据

对象键保持不变即可，业务与数据库都不用改。**推荐重传**，不要赌两边磁盘格式兼容。

```powershell
# rclone（独立项目，不受 MinIO 镜像下架影响）
rclone config create oldminio s3 provider=Minio endpoint=http://<旧地址>:9000 `
  access_key_id=<旧 key> secret_access_key=<旧 secret>
rclone config create newrustfs s3 provider=Other endpoint=http://127.0.0.1:9000 `
  access_key_id=<新 key> secret_access_key=<新 secret> region=us-east-1 force_path_style=true

rclone copy oldminio:kean newrustfs:kean --progress --checksum   # --checksum 逐对象校验

# 校验对象数一致
rclone size oldminio:kean
rclone size newrustfs:kean
```

备选工具：MinIO 的 `mc mirror`（如果你手上已有可用的 `mc` 二进制）、RustFS 自带的 `rc` CLI。

官方还宣称 RustFS 可直接接管 MinIO 的数据目录（[二进制替换](https://rustfs.com/blog/binary-replacement-a-simple-way-to-migrate-from-minio-to-rustfs/)），
支持的项：桶元数据、对象（含标签/对象锁/版本）、桶复制配置、IAM、生命周期、分层；
不支持：站点复制、事件通知、MinIO 在线配置、LDAP/OIDC。
课安只用到"对象"这一项，理论上够，但那是厂商营销页的说法，且它自己也写明"两边内部数据格式不同、
并非全部可自动迁移"——**不要把这条当生产迁移主路径**。

## 10. 备份与恢复

RustFS 用的是 compose 命名卷 `kean-rustfs-data`。备份要"对象 + 数据库"一起做，否则恢复出来是悬空的键。

```bash
# 备份对象卷（在服务器上执行，输出到当前目录）
docker run --rm -v kean-rustfs-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/rustfs-$(date +%F).tar.gz -C /data .

# 备份数据库
docker exec kean-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" kean' > kean-$(date +%F).sql
```

**恢复演练**（每季度至少一次，光有备份不算数）：

```bash
docker compose -f docker-compose.prod.yml stop rustfs
docker run --rm -v kean-rustfs-data:/data -v "$PWD":/backup alpine \
  sh -c "rm -rf /data/* && tar xzf /backup/rustfs-<日期>.tar.gz -C /data"
docker compose -f docker-compose.prod.yml start rustfs
# 恢复后用 §7.1 的冒烟测试 + 控制台抽查确认
```

建议节奏：对象与数据库每日一次、异地留存；对象是低频写入（≤5MB 图片），增量成本可忽略。

## 11. 升级与回滚

**升级前必须备份对象卷**：RustFS 的数据格式在不同版本间不保证向后兼容，回滚旧版本可能读不了新写入的数据。

1. 读 release notes，确认没有影响 S3 数据面的已知问题（GA 前后这类问题不少）
2. 备份对象卷与数据库
3. 改 `docker-compose.*.yml` 里的镜像 tag（建议同时锁定 digest）
4. `docker compose up -d rustfs` 后立刻跑 §7.1 冒烟测试，再抽查控制台
5. 异常则回滚 tag，并用第 2 步的备份恢复卷

**彻底放弃 RustFS 的退路**：应用侧只需改 `.env` 里的 `STORAGE_ENDPOINT` / 凭据这 3 个值，
就能指向任何 S3 兼容服务（自建旧版 MinIO、Garage、SeaweedFS、Ceph RGW……），
或者换成落本地磁盘的实现——`StorageService` 接口已经把存储层隔开了，切换成本很低。

## 12. 参考

- RustFS 仓库：<https://github.com/rustfs/rustfs> ｜ 发布：<https://github.com/rustfs/rustfs/releases/tag/1.0.0>
- 中文文档：<https://docs.rustfs.com/zh/>（[Docker 安装](https://docs.rustfs.com/zh/installation/container/docker) ｜
  [环境变量](https://docs.rustfs.com/zh/reference/environment-variables) ｜
  [S3 兼容性矩阵](https://docs.rustfs.com/zh/reference/s3-compatibility)）
- 键值持久化缺陷：<https://github.com/rustfs/rustfs/issues/8236> ｜ 单节点内存：<https://github.com/rustfs/rustfs/issues/8121>
- MinIO 下架镜像报道：[小众软件](https://www.appinn.com/minio-remove-docker-images/) ｜
  [GIGAZINE](https://gigazine.net/gsc_news/en/20251023-minio-stops-distributing-free-docker-images)
