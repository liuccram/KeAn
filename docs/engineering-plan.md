# 课安 工程化改造方案

> 本文只做规划，不含代码改动。所有"现状"结论均为在 `D:\CursorWorkSpace\课安` 上实测所得，附证据。
> 目标：在不改动业务逻辑的前提下，先把资产锁住，再把质量门禁立起来，最后消除契约漂移。

---

## 0. 一句话结论

业务完成度已经很高（代课/申请/私聊/评价/收藏/黑名单/举报/公告/通知/管理端全套），
**真正的短板全部在工程化外围**：版本控制近乎为空、零测试、零 lint、零 CI，
"同一份接口契约在三处各写一遍且已经不一致"，外加**三个实打实的安全缺陷**。

改造顺序必须是：**先保资产 → 修安全 → 立门禁 → 最后谈统一**。
理由是：门禁要跑在稳定的历史之上，而契约统一会大面积改文件，若此时仓库只有 1 个 commit，回滚能力为零。

---

## 1. 现状体检

### 1.1 项目结构

| 模块 | 技术栈 | 规模 |
|---|---|---|
| `kean/` | Spring Boot 3.3.13 / Java 17、MyBatis-Plus、Spring Security + JWT、Redis、MySQL + Flyway、MinIO、WebSocket、Mail、Turnstile | 242 个 Java 文件、21 Controller（**106 个 endpoint**）、~30 Service、24 迁移 |
| `uni-kean/` | uni-app 3.0（Vue 3.4）、wot-design-uni、sass | 107 源文件、48 视图 |
| `web-kean/` | Vue 3.5 + Element Plus 2.11 + ECharts 6 + Pinia 3 + Vite 6 | ~33 源文件 |
| `docs/` | 手写 API 文档、管理端规划、SQL 副本、支付草案 | — |

### 1.2 做得对的地方（不要动）

- **`.env` 卫生良好**：`.env` 已 gitignore 且未入库；入库的只有 `.env.example` 与内容为空的 `.env.development`；`kean/src/main/resources` 无硬编码凭据；`.env.example` 连"禁止 `ChangeMe_Admin_123`"都写明。
  ⚠️ 但 **Java 源码里确实有硬编码凭据**（见 1.3⑧）—— 这推翻了我只看配置文件时的初判。
- **数据库演进规范**：Flyway 24 个版本化迁移，`V10__one_accepted_per_task.sql` 用 DB 唯一约束解决并发接单，方向正确。
- **审计与异常处理规范**：`AuditMetaObjectHandler` 自动填充，`operation_log` 表已建；`GlobalExceptionHandler.java:25-71` 把 `BizException`/`Bind`/`ConstraintViolation`/解析/上传/兜底全部映射到统一 `Result`，**没有裸 int 错误码**；连 Spring Security 的 401/403 也返回 `Result` 结构（`SecurityConfig.java:120-133`）。
- **DTO 校验覆盖率高**：39 处 `@Valid`、121 个 jakarta 约束分布在 35 个 DTO 上（仅参数级有缺口，见 1.4）。
- **Security 基本盘正确**：stateless（`SecurityConfig.java:57`）、csrf/httpBasic/formLogin 全关、BCrypt 默认强度、CORS 拒绝 `*`（`CorsProperties.java:26-29`）、`allowCredentials=false`。
- 代码风格相当一致（见 1.4 注）。
- `web-kean` 类型检查**干净通过**。

### 1.3 P0 级缺陷（阻断性，按严重度）

#### ① 版本控制实际上是空的 —— 全项目唯一的一级风险

```
total commits : 1
HEAD          : 4fa125a 2026-09-18 "primary"
dev == master == origin/dev，全部停在这 1 个 commit
工作区改动     : 384 项（87 modified、297 untracked、0 deleted）
未跟踪 .java   : 170 个
kean/src/main 未跟踪 : 196 个文件
```

聊天、评价、收藏、黑名单、公告、管理端、WebSocket、MinIO、Turnstile、设备登录、i18n、主题壁纸
—— **整个产品只存在于工作区**。一次 `git checkout .` 或误删即全部丢失；无法回滚、无法 review、无法协作。

#### ② `uni-kean` 的类型检查是坏的，而且没人知道

实测 `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`（在 `uni-kean/`）：

```
exit code 2 —— 33 个错误（9 个在 src/，24 个在 node_modules/wot-design-uni）
```

src 中的真实缺陷：

| 文件 | 行 | 错误 |
|---|---|---|
| `src/components/GesturePad.vue` | 135,136 | TS2322 触摸事件签名写成了普通数组，不是 `TouchList` |
| `src/pages/mine/crop.vue` | 324,325 | TS2322 同上 |
| `src/pages/auth/forgot.vue` | 145 | TS2339 `Property 'uni' does not exist` |
| `src/pages/auth/register.vue` | 212 | TS2339 同上 |
| `src/pages/mine/settings.vue` | 34,35 | TS2339 同上 |
| `src/pages/home/index.vue` | 120 | TS2322，由 `src/utils/i18n.ts:58` 的 `as const` 导致（`t()` 返回字面量联合，`{label:string}` 数组不兼容） |

24 个 node_modules 错误的根因：`uni-kean/tsconfig.json` **没有声明任何 strict 相关配置**，只继承 `@vue/tsconfig ^0.1.3`，而后者也未设 `skipLibCheck`。

**关键点：`type-check` 没有接进任何构建脚本**（`build:h5` 只是 `uni build`），所以它静默腐烂了很久。
对照组：`web-kean` 的 `build` 里带 `vue-tsc --noEmit`，实测 exit 0、零错误 —— 这条链路是健康的。

#### ③ 接口契约三处重复且已经不一致

- `uni-kean/src/api/task.ts` 自定义了 43 字段的 `TaskItem` + `PageResult`；
  而 `web-kean/src/api/task.ts` 却从 `./catalog`、`./user` 里 import 这两个类型 —— 类型定义散落在不相干的模块（`TaskItem` 竟定义在 `api/user.ts`，被 `api/task.ts:3` 与 `api/dashboard.ts:2` 引用）。
- **同一后端枚举，两端文案不同**：
  - uni：`WAITING:"待申请"`、`MATCHED:"待上课"`、`CONFIRMED:"待上课"`、`IN_PROGRESS:"上课中"`
  - web：`WAITING:"等待接单"`、`MATCHED:"已匹配"`、`CONFIRMED:"已确认"`、`IN_PROGRESS:"进行中"`
  - 移动端还把 `MATCHED` 与 `CONFIRMED` 合并成同一个文案（两个业务状态在 UI 上不可区分）。
- `web-kean/src/utils/dicts.ts` 为每个枚举维护 **4 张平行映射**（label/type/tone/color），仅任务状态就 32 条要同步。
- `web-kean` 内部还有重名类型：`AdminUser` 在 `src/utils/storage.ts:4`（5 字段）与 `src/api/user.ts:4`（34 字段）各定义一次。
- 两端 `request()` 签名不同（`(url, options)` vs `({url, method, data})`），无法直接共用。

#### ④ 接口文档覆盖率约 57%，且已实质漂移

`docs/api/*.md` 只覆盖约 **28 个**用户端 mapping（auth 6、dict 3、notifications 4、tasks 5、applications 10）加 `admin.md` 里约 33 条管理端路径，
而 21 个 Controller 共 **106 个 endpoint mapping** → **约 45 条完全没有文档**（Chat 8、Favorite 3、Blacklist 3、Report 5、Review 4、File 2、Me 的设备/头像/封面/邮箱/隐私/heartbeat 7）。

已抽样确认的具体错漏：

- `AuthController.java:52,57,63` 的 `POST /api/auth/sms`、`/api/auth/password`、`/api/auth/password/reset` 在 `auth.md` 中缺失。
- `CatalogController.java:26` 的 `GET /api/provinces` 在 `dict.md` 中缺失。
- `admin.md:411` 仍写"建议新增 `USER_NOT_FOUND`"，而 `common/ErrorCode.java:50` 早已定义 `40408`。
- `admin.md` 漏了 `AdminReportController.java:43,58` 与 `appealStatus` 参数（`AdminReportController.java:34`）。

**SQL 副本同样是陈旧复制品**：

- `docs/sql/` 23 份 vs 线上 Flyway 24 份 —— **`V8__user_email.sql` 缺失**。
- `docs/sql/V1__init_base_tables.sql` 与线上迁移**差 132 行**，且开头是：
  ```sql
  CREATE DATABASE IF NOT EXISTS KeBang ...;
  USE KeBang;
  ```
  这是 **Flyway 之前的旧手工建库脚本**，却与迁移同名同号 —— 照它执行会建出一个叫 `KeBang` 的库。
- `V1`、`V2` 逐字节不同，其余 21 份一致 —— 说明它曾经是"手工同步的副本"，然后停止了同步。

**没有任何机器可读契约**：全仓 `springdoc|swagger|openapi` 零命中。

#### ⑤ `pom.xml` 依赖重复声明

- `kean/pom.xml:37-41`  `mybatis-plus-spring-boot3-starter` 硬编码 **3.5.16**
- `kean/pom.xml:71-75`  同一 artifact 又用 `${mybatis-plus.version}` = **3.5.9**（属性在 `pom.xml:22`）

**生效版本 = 3.5.9（后者覆盖前者）**，已由 Maven 3.9.9 源码确认：`DefaultModelNormalizer.mergeDuplicates` 以 managementKey 为键存入 `LinkedHashMap` 后重新 put，**后声明的值覆盖先声明的值**，仅保留首次出现的位置（Maven 注释称此为 2.x 兼容行为）。
即 **`pom.xml:37-41` 是死代码**，且 `mybatis-plus-jsqlparser`（`pom.xml:76-80`）恰好与 3.5.9 一致 —— 所以当前"能用"，但**意图（3.5.16）与事实（3.5.9）不符**，Maven 也会打印 `must be unique ... -> version 3.5.16 vs ${mybatis-plus.version}` 警告。

→ 删除 `37-41`、统一走属性；`mvn dependency:tree -Dincludes=com.baomidou` 固化验证。

#### ⑥ 文件接口越权读取（安全缺陷）

`controller/FileController.java:57` 的判定是：

```java
verify(...) || currentUserOrNull() != null
```

—— **任何已登录用户都能读取任意 object key**，没有归属校验。
对一个存着用户头像、代课履约照片（`V7__task_fulfill_photo.sql`）、举报申诉图片（`V18__report_appeal_images.sql`）的对象存储来说，这是可越权拉取他人图片的路径。

#### ⑦ 两套并行的鉴权判定，且已经开始漂移（安全缺陷）

- `config/SecurityConfig.java` 维护一份 permitAll 规则；
- `security/JwtAuthFilter.java:45-57,128-149` **又自己实现了一遍**同一份名单，并在进入 filter chain 之前就返回 401。

两者已经不一致：`SecurityConfig.java:69` 放行 `GET /api/tasks/*`，而 filter 只放行 `/api/tasks/\d+`（`JwtAuthFilter.java:144`）。
→ 单一事实源应只在 `SecurityConfig`；filter 只做解析与注入，不做授权判定。

#### ⑧ 源码内硬编码凭据（安全缺陷）

`config/MinioProperties.java:12-16` 的默认值里内嵌了：

- 内网地址 `http://<内网主机>:9000`
- 账号 `admin` / 口令 `<默认口令>`

同时 `config/MinioConfig.java:21-30` **吞掉了连接失败异常**，导致配置错误时表现为"上传静默失败"而非启动报错。
→ 默认值必须为空并 fail-fast；凭据只能来自环境变量。

### 1.4 P1 级缺口（不阻断，但持续放血）

| 项 | 实测结论 |
|---|---|
| **零测试** | `kean/src/test` 目录不存在。pom 里声明了 `spring-boot-starter-test`+`spring-security-test` 却零使用。两端前端无任何 `*.spec.*`/`*.test.*`。唯一"测试"是 `scripts/phase1-smoke.ps1`——需后端先跑起来，且**硬编码管理员口令**。 |
| **零 lint/format** | 仓库根、web-kean、uni-kean 均无 `eslint.config.*`/`.eslintrc*`、`.prettierrc*`、`.editorconfig`、stylelint。Maven 侧无 Checkstyle/SpotBugs/JaCoCo。 |
| **零 CI** | 无 `.github/`，但 origin 已在 GitHub。 |
| **零 hook** | `.git/hooks` 只有 `.sample`。配合"无 CI"，等于提交前完全无门禁。 |
| **无 `.gitattributes`** | 而 `core.autocrlf = true`。满载中文注释的 Windows 仓库，384 个待入库文件极易产生整文件 diff。 |
| **无 actuator / 无可观测** | `actuator|micrometer|logback|tracing` 零命中；无 `/actuator/health`、无指标、无 `logback-spring.xml`、无 `logging.pattern` 覆盖。SLF4J 用得不错（10 个类 46 处）但无 MDC/trace/request id、无请求日志。 |
| **参数级校验缺失** | DTO 校验很完整，**但没有任何 Controller 标 `@Validated`** → `@RequestParam`/`@PathVariable` 不受校验：`TaskController.java:41-42` 的 `page`/`size` 无上界（而 `docs/tasks.md:27` 声称 max 50），`TaskQuery.java` 无任何约束。另 `MeController.java:86` 的 `updateCover` 漏了 `@Valid`（同级方法都有）。 |
| **无部署产物** | 无 Dockerfile、无 nginx 配置、无 systemd unit。`docker-compose.dev.yml` 只管 MySQL/Redis。**本机未安装 Docker。** |
| **无 Maven Wrapper** | 无 `kean/mvnw`，构建依赖机器上的 Maven。 |
| **Maven 质量插件全缺** | 无 Checkstyle/SpotBugs/PMD、无 JaCoCo、无 surefire/failsafe 配置、无 enforcer、无 dependency-check。 |
| **Spring Boot 3.3.13 已 EOL** | 它是 3.3.x 的最后一个补丁版，OSS 支持线已结束 → 不会再有安全补丁，需规划升级到受支持版本。 |
| **`forward-headers-strategy: none`** | `application.yml:3` 关闭，虽然已支持 `TRUSTED_PROXIES` 配置。上反代后需重开，否则拿不到真实 IP（直接影响限流与风控）。 |
| **工具链落后** | uni-kean 锁 `typescript ^4.9.4` + `vue-tsc ^1.0.24`（Volar 1）；web-kean 已是 TS 5.8 + vue-tsc 2.2.8（Volar 2）。uni-kean 无法解析 TS 5 语法，`satisfies` 等写法用不了。 |
| **无用依赖** | `vue-i18n ^9.1.9` 是 uni-kean 的声明依赖，**src 中零 import**（无 `vue-i18n`/`useI18n`/`$t`）。`@dcloudio/uni-automator` 已装但零测试。 |
| **i18n 实际覆盖 ~3%** | 真 i18n 是手写的 25 键字典（`src/utils/i18n.ts`），仅 4/48 视图使用。635/8508 行 `.vue` 含硬编码中文（含 `.ts` 共 762 行 CJK）。`pages.json` 里 ~40 个 `navigationBarTitleText` 硬编码，运行时 i18n 够不到。`pages/mine/display/lang.vue:14` 自己写着"语言只在本页生效"。web-kean 完全无 i18n，`router/index.ts` 的 `meta.en` 是死脚手架。 |

**注：经典"无 linter 症状"其实并不存在** —— `: any`/`as any` 在 web-kean 为 **0**、uni-kean 仅 1 处（`src/env.d.ts:23`）；
引号风格已统一双引号（web 121/121、uni 347/348 行 import）。
所以 lint 的真实收益是**语法规则 + 未使用导入/导出检测**，而不是收拾 `any` 泛滥。
这是好事：说明代码纪律本身不差，缺的是自动化。

### 1.5 P2 级清理项（实测确认）

| 项 | 结论 |
|---|---|
| 孤儿文件 | `uni-kean/src/static/logo.png`（0 引用；"logo" 只匹配到 "logout"，manifest.json 也未用）。**其余候选全部可达**：GesturePad/GestureLock/CodeBoxes/DisplayOptionPage/PersonAvatar/TurnstileChallenge、`crop.vue`、`imageCrop.ts`、`gesture.ts` 均在用（GestureLock → `App.vue:63`；TurnstileChallenge → login/register/forgot）；web-kean 0 个孤儿组件。 |
| 重复死文件 | 根级 `uni-kean/shims-uni.d.ts` 与 `src/shime-uni.d.ts`（**文件名拼错**）重复，且位于 `tsconfig.json:12` 的 include 之外 → 死文件。 |
| 游离文件 | 仓库根 `_prefs_apply.txt` 是一段属于 uni-kean 显示偏好逻辑的 TS 片段，未跟踪也未 ignore。 |
| 未使用导入 | uni-kean 恰 1 处：`src/components/TurnstileChallenge.vue:2`（import 了 `ref` 未用）。web-kean 0 处。 |
| 打包体积 | `web-kean/src/main.ts:12-14` 全局注册了**全部** Element Plus 图标。 |
| 手写 JSON | `FileController.java:76-81` 手搓 JSON 而非用 `ObjectMapper`（流式端点，不用 `Result` 可接受，但该复用 mapper）。 |

### 1.6 本机工具链（决定方案可行性）

```
java   23.0.2        （项目 target 17）
mvn    3.9.9         （无 mvnw wrapper）
node   v24.9.0 / npm 10.9.2 / pnpm 可用
git    可用
docker 未安装   ← 唯一环境阻塞
```

---

## 2. 改造原则

1. **不动业务逻辑。** 除"修复已存在的缺陷（含安全问题）"外不改变行为。
2. **先保资产再立门禁。** 没有稳定历史，任何自动化都是沙上建塔。
3. **门禁从"能挡住已经发生的错误"开始。** 最高优先级的门禁不是 lint，而是**把 uni-kean 的 type-check 接进构建**——它本可以挡住 1.3② 那 9 个错误。
4. **统一契约要生成的，不靠人写的。** 凡是要手工同步两遍的东西，最终一定不一致（1.3③④ 已经验证两次）。
5. **不引入与现状不匹配的重型设施**（见第 9 节）。

---

## 3. 阶段一：资产保全（P0，约 0.5 天）

> 唯一目标：让 384 个改动进入版本历史，且未来不产生行尾噪音。

| # | 任务 | 说明 |
|---|---|---|
| 1.1 | **先加 `.gitattributes`，再提交** | 顺序很重要：先加属性再入库，避免 384 个文件用 CRLF 进历史后再翻转。建议：`* text=auto eol=lf`、`*.ps1 text eol=crlf`、`*.bat text eol=crlf`、`*.png binary`、`*.jpg binary`。 |
| 1.2 | **清理明显的游离物** | 处理根级 `_prefs_apply.txt`（迁到 `uni-kean/src/utils/` 或删除）、`uni-kean/src/static/logo.png`、`uni-kean/shims-uni.d.ts`。这三项与提交切片无耦合，先清掉可减少噪音。 |
| 1.3 | **按模块分批提交** | 不要一次 `git add -A`。建议切片：① 后端新增能力（chat/review/favorite/blacklist/announcement/admin）② 迁移脚本 V12–V24 ③ uni-kean 页面与 utils ④ web-kean 管理端 ⑤ docs ⑥ 构建与配置。每片一条语义化 commit。 |
| 1.4 | **推送到 `origin/dev`** | 当前本地与远端同点，推完才算真正安全。 |
| 1.5 | **确认 `.env` 未被加入** | 已知 `.env` 在 gitignore 内，提交前 `git status` 再核一次。 |

### 验收标准

- `git rev-list --count HEAD` 从 1 增长到 ≥ 6。
- `git status --porcelain` 为空（或只剩刻意保留的未跟踪文件）。
- `git ls-files` 中无 `.env`。
- `origin/dev` 与本地一致（`git rev-list --left-right --count origin/dev...dev` 输出 `0  0`）。

### 风险

- **行尾翻转风险**：`autocrlf=true` + 无 `.gitattributes`，若先提交再加属性，会产生二次全文件 diff。→ 必须先做 1.1。

---

## 4. 阶段二 A：安全修复（P0，约 1 天，**建议独立成一个 PR 立即合并**）

> 这三项与工程化改造无关，是独立的真实缺陷，不该等。

| # | 任务 | 位置与做法 |
|---|---|---|
| 2.1 | **修硬编码凭据** | 清空 `config/MinioProperties.java:12-16` 的默认值（内网地址 + `admin/<默认口令>`），改为必填且 fail-fast；`config/MinioConfig.java:21-30` 不再吞异常，改为启动期报错。 |
| 2.2 | **修文件越权读取** | `controller/FileController.java:57` 增加归属校验：头像（公开）、履约照片（任务双方可见）、申诉图片（本人 + 管理员）各自不同的可读范围，不能只判"已登录"。 |
| 2.3 | **合并鉴权判定到单一事实源** | 移除 `security/JwtAuthFilter.java:45-57,128-149` 里重复的 permitAll 名单与提前 401，授权只在 `config/SecurityConfig.java`；顺带修 `SecurityConfig.java:69` 与 `JwtAuthFilter.java:144` 的 `/api/tasks/*` 不一致。 |
| 2.4 | 收紧 MinIO 失败语义与 `forward-headers-strategy` | 上反代后需重开（`application.yml:3`），否则限流与风控拿不到真实 IP。 |

---

## 5. 阶段二 B：质量门禁（P0，约 3–4 天）

> 目标：让"已经发生过的错误"不可能再静默发生。

| # | 任务 | 具体动作 |
|---|---|---|
| 2.5 | **修复 uni-kean 类型检查并接入构建** | ① `tsconfig.json` 显式声明 `strict: true`、`skipLibCheck: true`（消掉 24 个 node_modules 错误），补 `uni`/`getCurrentPages` 全局声明；② 修 9 个 src 错误（`GesturePad.vue:135-136`、`crop.vue:324-325` 的触摸签名，`forgot.vue:145`/`register.vue:212`/`settings.vue:34-35` 的 `uni` 未声明，`i18n.ts:58` 的 `as const` 改为显式返回类型）；③ 把 `type-check` 接进 `build:h5`，与 web-kean 对齐。 |
| 2.6 | 清理无用依赖与文件 | 移除 `vue-i18n`（零 import）、`@dcloudio/uni-automator`（零测试，若阶段六要用则保留）、`static/logo.png`、`shims-uni.d.ts`；修掉 `TurnstileChallenge.vue:2` 未使用导入；合并 web-kean 两个 `AdminUser` 类型。 |
| 2.7 | 补参数级校验 | Controller 加 `@Validated`；`TaskQuery` 与 `TaskController.java:41-42` 的 `page`/`size` 加上界（对齐 `docs/tasks.md:27` 声明的 max 50）；补 `MeController.java:86` 的 `@Valid`。 |
| 2.8 | 修 `pom.xml` 重复依赖 | 删除 `pom.xml:37-41`（死代码），统一走 `${mybatis-plus.version}`；`mvn dependency:tree -Dincludes=com.baomidou` 固化验证。 |
| 2.9 | 引入 ESLint 9 flat + eslint-plugin-vue + @typescript-eslint | 两端各一份。**uni-kean 必须声明 `uni`/`getCurrentPages` 等全局**，否则满屏 no-undef。起步用 `eslint:recommended` + `plugin:vue/vue3-recommended`，先跑通再逐步加严。 |
| 2.10 | 引入 Prettier + `.editorconfig` + `eslint-config-prettier` | 风格已一致，**优先级低于 2.9**，主要收益是 SFC template 格式化。`.editorconfig` 同时兜住编辑器侧行尾/缩进。 |
| 2.11 | 引入 husky + lint-staged + commitlint | 因为**当前没有 CI**，pre-commit 是唯一能立刻生效的门禁。 |
| 2.12 | 建立 GitHub Actions CI | 三个 job：① 后端 `mvn -B verify` ② `web-kean` `npm ci && npm run build` ③ `uni-kean` `npm ci && npm run type-check && npm run build:h5`。触发：PR + push 到 `dev`。 |
| 2.13 | 生成 Maven Wrapper | `mvn wrapper:wrapper`，CI 与新人不再依赖机器上的 Maven。 |
| 2.14 | 接入 JaCoCo | 先设很低门槛（如行覆盖 20%）只为**防止倒退**，不追求数字好看。 |

### 验收标准

- `uni-kean` 的 `npm run build:h5` 内部先跑 type-check，且 exit 0。
- 故意提交一个未使用变量 → CI 失败 / pre-commit 失败。
- `kean/pom.xml` 中 `mybatis-plus-spring-boot3-starter` 只出现一次。
- GitHub Actions 在 PR 上三绿。

### 风险

- **2.9 会一次性产生大量告警。** 对策：先宽档接入或用 `eslint --fix` 批量清理后逐步加严。**不要在同一 PR 里既加 lint 又改业务代码。**
- **2.5 的 `skipLibCheck: true` 是取舍**：它掩盖 wot-design-uni 的类型问题，但那是第三方库的问题，不该由本项目扛。可接受。

---

## 6. 阶段三：接口契约单一化（P1，约 3–5 天）

| # | 任务 | 说明 |
|---|---|---|
| 3.1 | 接入 `springdoc-openapi-starter-webmvc-ui` 2.6.x | 由 21 个 Controller 的 **106 个 endpoint 自动生成** OpenAPI，产出 `/v3/api-docs`（给机器）与 `/swagger-ui`（给人）。**这一步同时把 1.3④ 那 45 条缺失文档一次性补齐。** |
| 3.2 | 用 `openapi-typescript` 生成共享类型包 | 新建**纯类型**包（如 `packages/api-types`）。**不要做成 pnpm workspace 共享源码包** —— 见下方"为什么不"。 |
| 3.3 | 用生成类型替换手写类型 | 删除两份手写 `TaskItem`（43 字段）、`PageResult`，统一从生成包 import；修掉 `TaskItem` 定义在 `api/user.ts` 的错位；合并 web-kean 两个 `AdminUser`。 |
| 3.4 | 字典收敛到后端 | 项目已有 `docs/api/dict.md`。把状态码→文案/Tone 映射改为**后端下发**，前端不再硬编码；`web-kean/src/utils/dicts.ts` 的 4 张平行映射合并为 1 张 `{label, tone, color, elementType}`。此步直接修掉 1.3③ 的文案不一致。 |
| 3.5 | 治理文档漂移 | ① **删除 `docs/sql/`，以 `kean/src/main/resources/db/migration/` 为唯一事实源**，README 改指向后者（删前先 grep 全仓引用）。② `docs/api/*.md` 改为"3.1 生成 + 保留手写散文说明"，不再手工维护端点清单。 |
| 3.6 | CI 加契约漂移检查 | 生成 OpenAPI 后与提交版本比对，不一致则 CI 失败。 |
| 3.7 | 可选：MapStruct | 242 个 Java 文件里大量 VO/DTO 手工映射，可消除样板与漏字段。**可选**，收益不如 3.1–3.5 直接。 |

### 为什么不建共享源码 workspace（重要）

前端审计已论证：**共享 workspace 不成立**。理由：

- uni-app 无法可靠消费 workspace 软链的 TS 源码（easycom 与条件编译 `#ifdef` 只在 uni 构建内生效）。
- 两端 HTTP 客户端本质不同：`web-kean/src/utils/request.ts` 59 行（axios）vs `uni-kean/src/utils/request.ts` 229 行（`uni.request`，额外含 40301 封禁处理、`X-Kean-Device` 头、`uploadFile`、`resolveMediaUrl`、APP-PLUS 条件编译）。真正重叠只有 `ApiResult<T>` 5 行 + "code!==0 则抛错"的约定。
- `storage.ts` 连 key 都不同（`kean_admin_token` vs `kean_token`），用户模型 5 字段 vs 27 字段。
- 工具链分歧：Vue 3.4/3.5、TS 4.9/5.8、Vite 5.2/6。

→ 只共享**类型**（编译期产物，无运行时耦合），不共享实现。这是成本最低且不引发构建问题的路径。

### 验收标准

- 修改一个后端 DTO 字段后，对应前端 `npm run type-check` 报错（证明类型是生成且联动的）。
- 同一后端枚举在两端展示文案来自同一处定义。
- `docs/sql/` 不再是第二事实源。

---

## 7. 阶段四：测试（P1，约 3–5 天，可与阶段三并行）

> 先覆盖"最容易错且最难手测"的部分，不追求覆盖率数字。

### 7.1 前置：后端"可测试化"改造（**不是可选项**）

实测发现当前上下文在测试环境**根本起不来**，必须逐条解决：

| 障碍 | 位置 | 现象 |
|---|---|---|
| `JWT_SECRET` 为空即抛异常 | `security/JwtService.java:27-29`、`application-test.yml:33` 默认为空 | 上下文起不来 |
| CORS origins 为空即抛异常 | `config/CorsProperties.java:22-29`、`application-test.yml:36` 为空 | 上下文起不来 |
| `DotEnvLoader` 只在 `main()` 里加载 | `KeanApplication.java:18`、`config/DotEnvLoader.java:87` | `@SpringBootTest` 永远读不到仓库根 `.env`，且它会**全局改 System properties** |
| MinIO 主机硬编码 | `config/MinioProperties.java:12` | 测试连到内网 `<内网主机>` |
| 启动即做 DB 操作 | `KeanApplication.java:14` 的 `AdminInitializer` + `@EnableScheduling`/`TaskScheduleService` | 上下文启动就写库、起定时任务 |
| Turnstile 在测试 profile 仍启用 | `application-test.yml:54`（`enabled=true` 但 key 为空） | 一切依赖登录的测试都被拒 |

→ 做法：测试 profile 显式提供 JWT 密钥与 CORS 源；把 `DotEnvLoader` 移出测试路径（改为 `@Profile("!test")` 或注入化）；给 `AdminInitializer`/调度加 `@Profile("!test")`；测试里关掉 Turnstile。

### 7.2 分层计划

| 层 | 工具 | 目标 | 可行性 |
|---|---|---|---|
| 后端单元 | JUnit 5（**依赖已在 pom 里**） | `JwtService`、`FileUrls`/`FileUrlSigner`、`ErrorCode` | ✅ 立即可用 |
| 后端 Web 层 | `@WebMvcTest` + MockMvc + spring-security-test | 每个 Controller 的鉴权与校验边界（含 2.2 的越权用例） | ✅ |
| 后端集成 | **Testcontainers（mysql:8 + redis:7）** | **Flyway 24 个迁移从头跑通**（高价值）、并发接单唯一约束、状态机流转 | ⚠️ **需先装 Docker Desktop** |
| web-kean 单元 | Vitest + @vue/test-utils | `utils/request.ts` 拦截器、`stores/user.ts`、`utils/dicts.ts` | ✅ 纯 Vite 6，零阻力 |
| uni-kean 单元 | Vitest（stub 掉 `uni`） | 纯函数：`utils/format.ts`、`taskAction.ts`、`prefs.ts`、`imageCrop.ts` | ✅ |
| 管理端 E2E | Playwright | 登录 → 用户列表 → 封禁 → 代课强制取消 → 举报处理 | ✅ |
| 移动端 E2E | `@dcloudio/uni-automator`（**已装，零使用**） | H5 关键路径 | ✅ 白捡的 |

### 7.3 配套

- `scripts/phase1-smoke.ps1` 里的硬编码管理员口令必须清理（即便保留脚本，也应从环境变量读）。
- CI 上 `mvn verify` 必须跑出真实断言（非 0 测试）。

---

## 8. 阶段五：可观测与部署（P2，约 3–5 天）

| # | 任务 | 说明 |
|---|---|---|
| 5.1 | `spring-boot-starter-actuator` | 暴露 `/actuator/health`、`/actuator/info`。生产只暴露必要端点。 |
| 5.2 | `micrometer-registry-prometheus` | JVM、HTTP、连接池、Redis 指标。 |
| 5.3 | request-id MDC 过滤器 + `logback-spring.xml` | `logging.pattern.level` 用 `%X{requestId}`，把日志与 `operation_log` 串起来。 |
| 5.4 | Sentry | 后端 + 两端前端错误上报，替代"用户截图报错"。 |
| 5.5 | Dockerfile（多阶段，JRE 17） | 与 compose 对齐，替代"后端跑主机"。 |
| 5.6 | 生产 compose + nginx + TLS | `app + mysql + redis + nginx`。**微信支付前 HTTPS 是硬要求**（见 `docs/pay-wechat-apply.md`）。 |
| 5.7 | Renovate / Dependabot | 依赖更新；顺带把 Spring Boot 从 EOL 的 3.3.13 升到受支持版本。 |

### 验收标准

- 容器化后一条命令起全栈，`/actuator/health` 返回 UP。
- 生产经 nginx 走 HTTPS。

### 风险

- **必须先在开发机安装 Docker Desktop**，否则 5.5/5.6 与阶段四的 Testcontainers 都无法落地。这是本方案唯一的硬环境前置。
- 容器化会改变"MySQL/Redis 在 <内网主机>"的现有拓扑，需确认是迁移还是并存。

---

## 9. 阶段六：产品能力（P2/P3，按业务优先级排）

| 能力 | 推荐做法 | 为什么需要 |
|---|---|---|
| **移动端推送** | uni-push / 个推 | 目前只有 WebSocket，App 退后台即断。"有新课/被接单"的通知是代课平台刚需。**最实在的产品缺口。** |
| **内容安全** | 阿里云/腾讯云内容安全（文本 + 图片） | 有私聊与用户上传图片，面向校园场景，合规要求。现有举报只是事后补救。 |
| **微信支付** | 官方 `wechatpay-java` SDK + 幂等键 + 支付状态机 | `docs/pay-wechat-apply.md` 已有草案。**不要裸写签名。** |
| **限流** | Bucket4j 或 Redisson `RRateLimiter` | 现有 `AuthRateLimitService` 只在登录/SMS 上生效且为自研；Redis 已就位，可换成分布式正确实现并覆盖全部写接口。 |
| **短信** | 阿里云 SMS SDK | 现有 `SmsService` 可替换。 |
| **真实 i18n** | 若真做，需 vue-i18n 9 正式接入 + `pages.json` 标题方案 | 现状 ~3% 覆盖、`vue-i18n` 是死依赖。**建议先明确"是否真要英文版"**：若不真做，就删掉死依赖与 4 处半成品，避免误导。 |

---

## 10. 明确不做的事

| 不做 | 理由 |
|---|---|
| Elasticsearch | MySQL `LIKE` 当前够用；任务量级不支持这个复杂度。 |
| 消息队列（RabbitMQ/RocketMQ） | 通知量级不够；`@Async` + 重试足够。等真出现扇出瓶颈再说。 |
| 微服务拆分 | 单仓三端 + 单体后端完全匹配当前团队规模。 |
| uni-kean 上 `unplugin-auto-import`/`unplugin-vue-components` | easycom（`pages.json:2-6`）已在自动导入 `wd-*`；叠加插件会互相打架。**仅 web-kean 适用**（可去掉 `main.ts:12-14` 全量图标注册与多处手写 `ElMessage` import）。 |
| 共享源码 workspace | 见阶段三"为什么不"。 |
| 追求覆盖率数字 | JaCoCo 只用来防倒退，不用来考核。 |

---

## 11. 总览与里程碑

| 阶段 | 内容 | 优先级 | 工作量 | 前置依赖 |
|---|---|---|---|---|
| 一 | 资产保全（提交切片 + `.gitattributes`） | **P0** | 0.5 天 | 无 |
| 二 A | 安全修复（MinIO 凭据 / 文件越权 / 鉴权双源） | **P0** | 1 天 | 无，可立即独立合并 |
| 二 B | 质量门禁（修 type-check + lint + hook + CI） | **P0** | 3–4 天 | 阶段一 |
| 三 | 契约单一化（springdoc + 生成类型 + 字典） | P1 | 3–5 天 | 阶段二 B（类型检查要能拦住回归） |
| 四 | 测试（含后端可测试化改造） | P1 | 3–5 天 | 阶段二 B；Testcontainers 需 Docker |
| 五 | 可观测与部署（actuator + Docker + nginx TLS） | P2 | 3–5 天 | **需安装 Docker** |
| 六 | 产品能力（推送 / 内容安全 / 支付） | P2–P3 | 按需 | 阶段五（支付需 HTTPS） |

### 建议的 PR 顺序

1. **PR #1 —— 只做阶段一。** 零技术风险、零业务影响，且是后面一切的前提。一旦 384 个改动进入历史，"能不能回滚"这个悬在头顶的问题就消失了。
2. **PR #2 —— 只做阶段二 A（三个安全缺陷）。** 与工程化无关，是独立真实缺陷，应尽快上线。
3. **PR #3 —— 2.5（修 uni-kean type-check 并接入构建）+ 2.8（pom 重复依赖）+ 2.13（mvnw）。** 都是"修复已存在的缺陷"，不引入新规范、不产生大量告警，但立刻让两端类型检查都可信。
4. 之后才谈 lint 全量接入与 CI —— 那时会有大量告警需分批处理，不宜与前面混在同一 PR。

---

## 12. 已知风险汇总

| 风险 | 影响 | 对策 |
|---|---|---|
| 提交前未加 `.gitattributes` | 384 文件以 CRLF 入库，后续整文件 diff | 严格按 1.1 → 1.3 顺序 |
| 安全缺陷先于工程化被利用 | MinIO 凭据泄露、他人图片越权读取 | 阶段二 A 独立 PR 立即合并 |
| 无 Docker | Testcontainers 与容器化部署均不可行 | 安装 Docker Desktop；否则阶段四/五降级为"连共享虚拟机测试"（不推荐） |
| 后端测试环境起不来 | 阶段四无法启动 | 先做 7.1 的六项可测试化改造 |
| lint 一次性全量接入 | 大量告警淹没真实问题 | 分 PR、先宽后严、先 `--fix` |
| `skipLibCheck: true` | 掩盖 wot-design-uni 的类型问题 | 可接受；第三方库问题不由本项目承担 |
| Spring Boot 3.3.13 EOL | 不再有安全补丁 | 阶段五纳入升级计划 |
| 容器化改变现有部署拓扑 | 可能影响在跑的 `<内网主机>` 环境 | 先并存、再切换 |
| `docs/sql/` 直接删除 | 若有外部引用会失效 | 先 grep 全仓引用，README 同步改指向 |

---

## 附：本文结论的实测命令

```powershell
# 版本控制现状
git rev-list --count HEAD                 # → 1
git status --porcelain                    # → 384 项
git branch -a; git remote -v

# 迁移与文档漂移：对比文件名集合与 MD5
Get-FileHash kean\src\main\resources\db\migration\*.sql -Algorithm MD5
#   → docs/sql 缺 V8__user_email.sql；V1 差 132 行；V2 有差异

# 接口覆盖率（相对 docs/api/*.md）
#   → 21 Controller / 106 endpoint；文档约覆盖 28 + 33 条

# uni-kean 类型检查（关键发现）
cd uni-kean; node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
#   → exit 2，33 errors（9 in src/，24 in node_modules/wot-design-uni）

# web-kean 类型检查（健康对照）
cd web-kean; node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
#   → exit 0

# 工具链
java -version; mvn -v; node -v; npm -v; docker --version   # docker 未安装
```
