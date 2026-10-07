<div align="center">

# 课安 · KeAn

**校园临时代课互助平台**

让「需要代课」和「愿意代课」的同学，在同校范围内快速对接。

[功能特性](#-功能特性) · [技术栈](#-技术栈) · [项目结构](#-项目结构) · [快速开始](#-快速开始) · [常见问题](#-常见问题)

</div>

---

## 📖 项目简介

课安是一个面向高校的**临时代课互助平台**。发布者填写上课信息（日期、时间段、校区、地点、酬谢等），有时间的同学申请接单，双方在平台内确认后线下完成代课，结束时互相评价。

平台**不经手任何资金**：酬谢仅作展示，由双方自行结算。

仓库是**一个后端 + 两个前端**的单仓结构：

| 端 | 目录 | 说明 |
|---|---|---|
| 用户端 | `uni-kean/` | uni-app（Vue 3），一套代码产出 H5 与小程序 |
| 管理端 | `web-kean/` | Vue 3 + Element Plus 后台 |
| 服务端 | `kean/` | Spring Boot 3 单体，负责业务、鉴权、文件、消息与定时任务 |

## ✨ 功能特性

### 用户端

**账号与安全**

| 能力 | 说明 |
|---|---|
| 注册 / 登录 | QQ 邮箱 + 邮箱验证码；登录可启用人机校验（Turnstile） |
| 找回密码 | 通过邮箱验证码重置 |
| 修改密码 / 换绑邮箱 | 在「账号安全」内完成 |
| 登录设备 | 查看全部登录设备与最近使用时间，可远程下线非当前设备 |
| 封禁提示 | 账号被处置时给出统一提示 |

**资料与主页**

| 能力 | 说明 |
|---|---|
| 头像 / 封面 | 选择后进入裁剪页框选展示区域；上传带进度与失败重试 |
| 基本资料 | 昵称、性别、学校、校区 |
| 学校修改限制 | 学校设定后修改次数受限，避免随意换校 |
| 隐私账号 | 开启后他人仅可见头像、昵称与学校，且其他用户不能主动向你发起新的私信（已有会话不受影响） |
| 他人主页 | 查看对方可信度、完成次数、评分与收到的评价 |

**发布代课**

| 能力 | 说明 |
|---|---|
| 任务字段 | 日期、开始 / 结束时间、校区、教学楼与教室、是否上机、是否拍照、性别要求、酬谢、原因 / 要求 / 备注 |
| 课程 | 课程名**自行填写**（各校课程名不统一，不强制从课程库选择） |
| 草稿保留 | 在底部 Tab 之间切换不会丢失已填内容 |
| 一键清空 | 表单提供清空按钮，二次确认后重置 |
| 编辑规则 | 已有申请后只可修改地点、备注与酬谢；有人接单后开课前 1 小时内不可再改地点 |
| 我的发布 | 查看自己发布的任务及其状态 |

**申请与履约**

| 能力 | 说明 |
|---|---|
| 申请 / 撤回 | 可申请、可撤回；发布者从申请者中择优接受 |
| 一单一接 | 接受一人后其余申请自动关闭 |
| 私信沟通 | 匹配后可私信确认教室与见面地点 |
| 现场照片 | 任务可要求代课者上传到场照片，发布者能看到上传状态 |
| 完成确认 | 下课任一方确认即可完成；满 24 小时未确认则自动完成 |
| 可信度 | **发布可信度与代课可信度分开计算**；取消次数与被举报次数一并展示 |

**评价**

| 能力 | 说明 |
|---|---|
| 双向评价 | 星级 + 标签（最多 5 个）+ 文字（最多 500 字） |
| 星级说明 | 按星级给出对应文案；**不默认预选满分**，需主动选择 |
| 展示 | 汇总到对方主页与「收到的评价」，并计入对应可信度 |

**消息**

| 能力 | 说明 |
|---|---|
| 三个 Tab | 系统通知 / 申请与履约 / 私信会话 |
| 未读角标 | 底部 Tab 显示未读数（超过 99 显示 99+）；**请求失败时保留上次角标**，不会因一次网络抖动就清空 |
| 实时推送 | WebSocket 推送新消息与状态变化 |
| 会话列表 | 显示最后一条消息与时间，可发起新会话 |

**举报与治理**

| 能力 | 说明 |
|---|---|
| 举报 | 针对任务或用户，选择原因、补充说明、上传证据图 |
| 申诉 | 对处理结果可申诉，附理由与图片 |
| 记录 | 「举报记录 / 反馈记录」查看处理状态与处理说明 |
| 黑名单 | 拉黑后双方无法互相申请或私信，可随时移出 |

**体验设置**

| 能力 | 说明 |
|---|---|
| 深色模式 | 深色 / 浅色 / 跟随系统 |
| 字号 | 多档字号，作用于全局 |
| 语言 | 中文 / English |
| 壁纸与封面 | 内置壁纸 + 自定义封面，可恢复默认 |
| 显示选项 | 主题、字号、语言在同一页内**内联选择**，不再多级跳转 |

**可用性细节**（这些是刻意统一过的行为）

- 每个列表页都有 **加载中 / 加载失败（含原因与「重新加载」）/ 空态** 三种状态，失败**不会**被显示成「没有数据」
- 已有数据时后台刷新失败会**保留原列表**并只给轻提示，不用错误页顶掉用户正在看的内容
- 图片加载失败显示灰色占位块，不再是一块空白
- 上传图片显示阶段与百分比（处理中 / 上传中 / 重试中），上传期间禁用提交，失败自动重试

### 管理端

| 模块 | 能力 |
|---|---|
| 登录 | 管理员登录；若仍是初始口令会强制改密 |
| 用户 | 查询、封禁 / 解封、限制 |
| 代课 | 查询与处置 |
| 举报 | 查看举报与申诉，填写处理说明 |
| 学校目录 | 省份、学校、校区、课程的增改与启停 |
| 公告 | 发布公告，可按目标用户投放 |
| 统计 | 关键数据看板 |
| 系统配置 | 运行参数开关 |

### 服务端能力

| 能力 | 说明 |
|---|---|
| 鉴权 | JWT + Spring Security，统一 401 处理与封禁拦截 |
| 请求追踪 | 每个请求分配 `X-Request-Id`，写入日志并在响应头回显 |
| 定时任务 | 状态刷新、超时自动完成等；用 **ShedLock** 保证多实例下同一任务只执行一次 |
| 文件存储 | S3 兼容对象存储；按用途分目录，敏感目录记录审计日志；响应带缓存头 |
| 上传校验 | 限制可接受的图片格式与大小 |
| 实时消息 | WebSocket 推送 |
| 邮件 | SMTP 发送邮箱验证码 |
| 人机校验 | 支持 Cloudflare Turnstile |
| 限流 | 基于 Redis 的接口频次限制 |
| 数据库 | Flyway 管理建表与种子数据；统一逻辑删除 |
| 健康检查 | `/health`（不探测依赖）、`/health/ready`（探测 MySQL 与 Redis） |
| 异常处理 | 全局异常处理与统一响应体 |

## 🛠 技术栈

**服务端 `kean/`**

| 项 | 版本 / 说明 |
|---|---|
| Spring Boot | 3.3.13（Java 17） |
| MyBatis-Plus | 3.5.9 |
| 鉴权 | Spring Security + jjwt 0.12.6 |
| 数据库 | MySQL 8，迁移由 **Flyway** 管理 |
| 缓存 | Redis |
| 对象存储 | RustFS（S3 兼容，minio-java 8.5.17） |
| 定时任务 | Spring Scheduling + **ShedLock 6.10.0**（多实例下同一任务只跑一次） |
| 其它 | WebSocket、Spring Mail、Cloudflare Turnstile |
| 可观测 | 每个请求分配 `X-Request-Id`，写入日志并回显响应头；`/health`、`/health/ready` |

**用户端 `uni-kean/`**：uni-app 3.0（Vue 3.4）+ Vite 5 + wot-design-uni 1.14 + TypeScript

**管理端 `web-kean/`**：Vue 3.5 + Element Plus 2.11 + Vite 6 + Pinia + Vue Router + TypeScript

## 📁 项目结构

```
课安/
├─ kean/                        服务端（Spring Boot）
│  ├─ run.ps1                   启动脚本：先读 .env，再按 Profile 覆盖
│  └─ src/main/
│     ├─ java/com/kean/         controller / service / mapper / security / support / config
│     └─ resources/
│        ├─ application.yml     主配置
│        ├─ application-{dev,test,prod}.yml
│        └─ db/migration/       Flyway 迁移（建表 + 种子数据）
├─ uni-kean/                    用户端（uni-app）
│  └─ src/{pages,components,api,utils,composables,store,static}
├─ web-kean/                    管理端（Vue 3 + Element Plus）
├─ docs/                        文档
│  ├─ api/                      接口说明
│  ├─ admin/plan.md             管理端规划
│  ├─ ops/rustfs.md             对象存储运维
│  ├─ sql/                      早期手写建表脚本存档
│  └─ engineering-plan.md       工程化改进计划
├─ scripts/phase1-smoke.ps1     冒烟脚本
├─ docker-compose.dev.yml       依赖服务（MySQL / Redis / RustFS）
├─ docker-compose.prod.yml
└─ .env.example                 ← 从这里开始配置
```

## 🚀 快速开始

### 环境要求

- JDK 17+、Maven 3.9+
- Node.js 18+ 与 npm
- MySQL 8、Redis 7（可直接用仓库里的 compose 启动）
- 可选：RustFS 或任意 S3 兼容对象存储（图片与文件）

### 1. 配置环境变量

仓库提供四份模板：`.env.example`（公共）、`.env.dev.example`、`.env.test.example`、`.env.prod.example`。按要用的 Profile 复制成去掉 `.example` 的文件并填写：

```bash
cp .env.example .env
cp .env.dev.example .env.dev
```

`.env` 与 `.env.prod` 等真实配置文件已在 `.gitignore` 中，不会进版本库。

> ⚠️ **`.env` 不解析行内注释**：`STORAGE_ENDPOINT=http://host:19000   # 注意是 API 端口` 会把注释一起当成值，导致启动失败。注释请单独占一行。

### 2. 启动依赖服务

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 3. 启动后端

```powershell
cd kean
.\run.ps1                    # 默认 dev
.\run.ps1 -Profile test      # 测试
.\run.ps1 -Profile prod      # 生产
```

启动后监听 `http://127.0.0.1:8080`；健康检查 `GET /health`（不含依赖探测）与 `GET /health/ready`（探测 MySQL 与 Redis）。

### 4. 启动用户端

```bash
cd uni-kean
npm install
npm run dev:h5               # http://localhost:5173
```

测试包 `npm run build:h5:test`，生产包 `npm run build:h5`。H5 开发环境会把接口代理到本机 `127.0.0.1:8080`。

### 5. 启动管理端

```bash
cd web-kean
npm install
npm run dev                  # http://localhost:5174/login
```

测试包 `npm run build:test`，生产包 `npm run build`。请把 `ADMIN_PASSWORD` 设为**不少于 12 位**（禁止默认口令）；若库中管理员仍是旧口令，首次登录会强制改密。

## ⚙️ 配置说明

### 环境变量加载顺序

启动时先读 `.env`，再被 `.env.{profile}` 覆盖（`run.ps1` 以环境变量注入；同时 `DotEnvLoader` 以 System Property 注入，且**已存在于环境变量中的键会被跳过**）。因此命令行/IDE 里显式设置的环境变量优先级最高——本地验证时可用它临时指向别的库。

前端用 Vite 模式区分环境（`development` / `test` / `production`），各自读取对应的 `.env.*`，核心变量是 `VITE_API_BASE_URL`。

### 数据库归属

两套库用途不同，别混：

| 库 | 位置 | 用途 |
|---|---|---|
| `kean` | 生产服务器 | 生产数据 |
| `KeBang` | 内网开发机的 MySQL | 本地开发 |

生产服务器上只有 `kean` 一个业务库；`KeBang` 只存在于开发机（那台机器上另有几个与本项目无关的库）。**`KeBang` 里不是生产数据。**

一个容易忽略的点：服务器 MySQL 的 `lower_case_table_names=1`，库名实际以小写 `kean` 存储，因此 `.env.prod` 里写 `Kean` 也能连上。若将来迁到该配置为 `0` 的实例（Linux 默认值），`Kean` 与 `kean` 会被当成两个库 —— 建议把 `MYSQL_DATABASE` 统一写成小写 `kean`。

### 跨域

后端启动时会校验 `CORS_ALLOWED_ORIGINS`，必须包含所有前端来源（含本地调试用的 `http://localhost:5173`、`http://localhost:5174`）。漏配时浏览器侧表现为登录接口返回 `403 Invalid CORS request`。

## 🗄 数据库与迁移

建表与种子数据全部走 **Flyway**，位于 `kean/src/main/resources/db/migration/`，应用启动时自动执行（当前最新版本 **V29**，共 29 个迁移）。`docs/sql/` 下是早期的建表脚本存档，仅供追溯。

**不要手工改库结构或种子数据** —— 新增变更请加一个更大的版本号迁移文件。

学校与校区的种子数据来自**教育部《全国高等学校名单》**：学校名逐字采用官方全称（括号为全角），只收普通高等学校（本科 + 高职专科），不含成人高校。新增学校会同时补一个「主校区」，否则该校在「省 — 学校 — 校区」三级联动里无法被选中；**真实校区名需按学校官网逐个核对后，用管理端的学校 / 校区管理补充**。

## 📦 部署

- **依赖服务**：`docker compose -f docker-compose.prod.yml up -d`（MySQL / Redis / RustFS，均配置了 json-file 日志轮转）
- **后端**：跑在主机，`.\kean\run.ps1 -Profile prod`；启动时自动执行 Flyway 迁移。生产建议交给 systemd / 进程守护，日志目前只输出到控制台，请自行重定向或配置 `logging.file.name`
- **前端**：`npm run build`（用户端 `npm run build:h5`）产出静态文件，交给 Nginx 等托管

## 📚 文档

| 文档 | 内容 |
|---|---|
| [`docs/api/auth.md`](docs/api/auth.md) | 认证与账号 |
| [`docs/api/tasks.md`](docs/api/tasks.md) | 代课任务 |
| [`docs/api/applications.md`](docs/api/applications.md) | 申请与匹配 |
| [`docs/api/notifications.md`](docs/api/notifications.md) | 通知与消息 |
| [`docs/api/dict.md`](docs/api/dict.md) | 字典 / 学校 / 校区 / 课程 |
| [`docs/api/admin.md`](docs/api/admin.md) | 管理端接口（部分待实现） |
| [`docs/admin/plan.md`](docs/admin/plan.md) | 管理端规划 |
| [`docs/ops/rustfs.md`](docs/ops/rustfs.md) | 对象存储运维 |
| [`docs/engineering-plan.md`](docs/engineering-plan.md) | 工程化改进计划 |

## ❓ 常见问题

**启动报 `请配置 CORS_ALLOWED_ORIGINS`**
`.env.{profile}` 里的 `CORS_ALLOWED_ORIGINS` 为空。填入前端来源（多个用逗号分隔）。

**启动报 `invalid hostname http://xxx:19000     # 注意是 API 端口`**
值的末尾带了行内注释（见上文 ⚠️）。删掉 `#` 及其后内容，或把注释移到单独一行。

**浏览器登录返回 `403 Invalid CORS request`（纯文本，不是 JSON）**
请求的 `Origin` 不在 `CORS_ALLOWED_ORIGINS` 里。注意 Vite 代理只改写 Host、**不改写 Origin**，所以本地调试地址也必须列进去。

**启动报 `ADMIN_PASSWORD 至少 12 位`**
管理员初始密码太短。改长，或只在本次启动用环境变量临时覆盖。

**迁移报 `Found more than one migration with version N`**
`target/classes` 下残留了旧的迁移文件（改过文件名或删过迁移时会出现）。执行一次 `mvn clean` 即可。

**日志在哪看**
目前只输出到控制台：IDEA 的 Run 窗口、`run.ps1` 所在终端、或 systemd 的 `journalctl -u <服务名> -f`。每个请求都有 `X-Request-Id`（响应头同名回显），可直接在日志里搜索这个 ID 串起整条链路。

## 🤝 贡献

1. 从 `dev` 分支切出特性分支开发
2. 提交前请确认后端 `mvn -q test` 通过、前端 `npm run build:h5`（或 `npm run build`）能构建
3. 数据库变更一律新增 Flyway 迁移，不要修改已发布的迁移文件
4. 提交信息用中文说明「改了什么、为什么」，便于回溯

## 📄 许可证

本项目暂未指定开源许可证。如需对外开源，请补充 `LICENSE` 文件并在本节说明。
