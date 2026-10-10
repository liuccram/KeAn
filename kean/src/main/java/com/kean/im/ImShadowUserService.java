package com.kean.im;

import com.kean.entity.SysUser;
import com.kean.enums.UserStatus;
import com.kean.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * box-im <b>影子用户</b>物化服务（阶段 A 的地基之一，见
 * {@code docs/ops/im-platform-migration.md} §2.3 与 §10 的第 3 处改动）。
 *
 * <h2>它解决什么问题</h2>
 * <p>box 的 {@code im-platform} 有自己的用户表 {@code im_user}。它的<b>REST 鉴权并不查这张表</b>
 * （{@code AuthInterceptor} 只验签 + 读一次 Redis 封禁键），所以「没有影子行」不影响取票与普通接口；
 * 但有几条路径<b>真的会查库</b>：</p>
 * <ul>
 *   <li>{@code FriendServiceImpl.bindFriend} → {@code userMapper.selectById(friendId)}，
 *       拿不到行就是 <b>NPE</b>；</li>
 *   <li>{@code /user/info}、群成员、搜索用户等。</li>
 * </ul>
 * <p>本类的作用就是「在需要之前，把 kean 的用户按 box 的形状<b>物化</b>一份到 {@code im_user}」，
 * 让上面这些路径能查到人。</p>
 *
 * <h2>⭐⭐ 铁律：{@code im_user.id} 必须严格等于 {@code sys_user.id}</h2>
 * <p>本类的 UPSERT <b>显式写出 {@code id} 列</b>（{@code INSERT INTO ... (id, user_name, ...) VALUES (?, ?, ...)}），
 * <b>绝不</b>依赖 {@code im_user} 的 {@code AUTO_INCREMENT} 去「碰巧」得到一个相等的 id。
 * 原因不是洁癖，而是终态的成本：</p>
 * <ul>
 *   <li>一旦两侧 id 不再相等，kean 里<b>所有</b>带 {@code user_id} 的业务表（任务 / 申请 / 评价 / 举报 /
 *       通知 / 会话 …）就都需要一次「翻译」才能与 box 对齐 ⇒ 退化成「<b>全站业务外键迁一遍</b>」，
 *       而且不可逆（见 {@code im-platform-migration.md} §1.5.2 的 E1 / §1.5.3 铁律 L1 / §10.5 结论 3）；</li>
 *   <li>同 id 复用则让「谁当账号真相」从一个<b>不可逆的数据决策</b>降级成一个<b>可随时回退的入口决策</b>。</li>
 * </ul>
 * <p>⚠️⚠️ <b>因此：任何人给 {@code sys_user} 或 {@code im_user} 加「映射列 / 映射表」
 * （{@code im_user_id}、{@code box_user_id}、{@code user_id_mapping} …），就等于破坏这条铁律</b>，
 * 会把「全量迁移」退化成「要迁全站业务外键」。发版前请跑一次 {@code im-platform-migration.md}
 * §1.5.4 的 L1 守卫 A / B（一个 {@code comm} + 两条 {@code information_schema} 查询）。
 * 也<b>不允许</b>按 {@code user_name} 反查身份 —— {@code user_name} 只是展示名，不是身份桥。</p>
 *
 * <h2>字段映射（以 {@code docs/sql/im-platform.sql} 的 {@code im_user} 表结构为准）</h2>
 * <table border="1">
 *   <caption>im_user 每一列的来源</caption>
 *   <tr><th>im_user 列</th><th>来源</th></tr>
 *   <tr><td>{@code id}</td><td>{@code sys_user.id}（<b>显式插入</b>，铁律见上）</td></tr>
 *   <tr><td>{@code user_name}</td><td>{@code sys_user.username}（唯一键 {@code uk_sys_user_username}
 *       ⇒ 天然满足 {@code im_user} 的 {@code unique key idx_user_name}；可选前缀见
 *       {@code kean.im.shadow-user-name-prefix}）</td></tr>
 *   <tr><td>{@code nick_name}</td><td>{@code sys_user.nickname}（为空时回落到 {@code username}，
 *       因为 {@code im_user.nick_name} 是 NOT NULL）</td></tr>
 *   <tr><td>{@code head_image}</td><td>{@code sys_user.avatar_url}</td></tr>
 *   <tr><td>{@code head_image_thumb}</td><td><b>空串</b> —— kean 没有缩略图字段，
 *       不拿原图冒充缩略图（列允许空串，见建表 SQL）</td></tr>
 *   <tr><td>{@code password}</td><td><b>占位值</b> {@value #DISABLED_PASSWORD}（见下）</td></tr>
 *   <tr><td>{@code sex}</td><td>{@code sys_user.gender}：{@code MALE}→0、{@code FEMALE}→1、
 *       其它（含 {@code null}）→0（{@code im_user.sex} 默认值就是 0=男）</td></tr>
 *   <tr><td>{@code is_banned}</td><td>{@code sys_user.status == BANNED} → 1，否则 0
 *       （<b>复用现有口径</b> {@link UserStatus#BANNED}，不另造一套封禁判断）</td></tr>
 *   <tr><td>{@code reason}</td><td>空串 —— kean 的具体封禁文案在 Redis
 *       （{@code kean:user:banned:{userId}}），<b>不复制进 box</b>，避免两处文案分叉</td></tr>
 *   <tr><td>{@code type}</td><td>{@code 1}（普通用户；{@code 2} 是 box 的审核账号，本项目不用）</td></tr>
 *   <tr><td>{@code signature}</td><td>空串（kean 无此概念）</td></tr>
 *   <tr><td>{@code last_login_time}</td><td>{@code sys_user.last_login_at}（可空）</td></tr>
 *   <tr><td>{@code created_time}</td><td>{@code sys_user.created_at}</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么 {@code password} 是占位值，而不是把 kean 的 BCrypt 哈希抄过去</h2>
 * <p>本轮<b>不做账号迁移</b>（账号在 <b>阶段 E</b> 才动手，见 §3 阶段 E 与 §1.5 铁律 L2「IM 先行、认证殿后」）。
 * {@code im_user.password} 在阶段 A 填什么，<b>唯一的要求是「不能可用」</b>：</p>
 * <ul>
 *   <li>写 kean 的 BCrypt 哈希 ⇒ 等于<b>提前</b>把账号迁移做了一半，而且是在一条
 *       <b>没有经过灰度、没有双写一致性设计</b>的路径上（§3.E.2 / §3.E.5 原则 5 明确要求改密必须双写）；</li>
 *   <li>写 {@value #DISABLED_PASSWORD} ⇒ 这个影子的 box 账号<b>永远无法通过 box 的登录链路</b>
 *       （{@code PasswordEncoder.matches} 必然失败），与 §2.3 的设计一致；
 *       配合 nginx 把 {@code /im-api/login} 与 {@code /im-api/register} 拒绝掉（§2.5），双保险。</li>
 * </ul>
 * <p>并且 {@code ON DUPLICATE KEY UPDATE} 里<b>没有</b> {@code password} —— 即使阶段 E 之后
 * box 侧已经写入真实哈希，本类的重复物化也<b>不会把它覆盖掉</b>。</p>
 *
 * <h2>幂等：只补展示字段，绝不覆盖「有状态」的列</h2>
 * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE} 只更新
 * {@code user_name / nick_name / head_image / head_image_thumb / sex}（以 kean 为准的展示字段），
 * <b>绝不更新</b> {@code password / is_banned / reason / type / signature / last_login_time / created_time}。</p>
 * <p>为什么连 {@code is_banned} 也不覆盖：它的写入方应当是<b>封禁/解封那条链路</b>
 * （{@code ImKickService} 的投影，见 §2.4 联动矩阵），物化是「顺路补名片」的低频动作，
 * 不该承担状态同步的职责 —— 否则一次物化就可能把 box 侧的封禁状态抹掉。
 * 注意 {@code is_banned} <b>只在 INSERT 时</b>按 kean 的当前状态初始化，所以新行是对的。</p>
 *
 * <h2>默认关闭（不配就是 no-op）</h2>
 * <ul>
 *   <li>总开关：{@code kean.im.shadow-user-enabled}（环境变量 {@code KEAN_IM_SHADOW_USER_ENABLED}），
 *       <b>默认 {@code false}</b>；</li>
 *   <li>数据源：本轮<b>不需要配</b> —— 直接用项目的主 {@code DataSource}，
 *       SQL 里写的是全限定表名 {@code im_platform.im_user}，前提是
 *       <b>{@code im_platform} 与 kean 在同一个 MySQL 实例上、且当前数据库账号有该库权限</b>。
 *       {@code kean.im.shadow-user-datasource} 是<b>为阶段 B 预留</b>的开关（把 {@code im_platform}
 *       放到独立实例时用）：<b>本轮配了也只有一条 WARN 说明它尚未接线</b>，行为不变。</li>
 * </ul>
 * <p>默认关闭是有意的：<b>未配置时本类不碰任何数据库</b>（连 {@code JdbcTemplate} 都不创建），
 * 与 {@code ImSenderService} / {@code ImTokenService} 的开关风格一致 —— 与它们不同的是，
 * 本类<b>不</b>依赖 {@code IM_JWT_SECRET}：{@link #buildUpsertSql(SysUser)} 是纯函数，
 * 即使 IM 通道没开，也仍然可以产出 SQL 用于人工核对/一次性执行。</p>
 */
@Service
public class ImShadowUserService {

    private static final Logger log = LoggerFactory.getLogger(ImShadowUserService.class);

    /**
     * 影子行 {@code im_user.password} 的占位值。
     *
     * <p>形态刻意与 Spring Security 的 {@code DelegatingPasswordEncoder} 的 {@code {id}} 前缀一致
     * （{@code {noop}} = 明文编码器），但值本身写成 {@code __disabled__} ⇒ 任何密码都不可能匹配；
     * 即使将来 box 换用别的编码器，这也只是一个「不可用的哈希」。
     * <b>绝不要</b>把 kean 的 BCrypt 哈希写进这一列（理由见类注释）。</p>
     */
    public static final String DISABLED_PASSWORD = "{noop}__disabled__";

    /** 影子行的 {@code im_user.type}：1 = 普通用户（box 的 2 是审核账号，本项目不用）。 */
    private static final int USER_TYPE_NORMAL = 1;

    /** {@code im_platform.im_user} 的全限定表名（跨库写法，见类注释的「数据源」小节）。 */
    private static final String TABLE = "im_platform.im_user";

    /**
     * UPSERT 的列清单与 VALUES 占位符。
     *
     * <p>⚠️ <b>{@code id} 必须在列清单里且显式绑定</b> —— 这是铁律 L1 在代码层的落点
     * （§10 的 A-3b 校验 1 就是 {@code grep} 这一行）。</p>
     */
    private static final String INSERT_COLUMNS =
            "id, user_name, nick_name, head_image, head_image_thumb, password, "
                    + "sex, is_banned, reason, type, signature, last_login_time, created_time";

    private static final String INSERT_VALUES = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";

    /**
     * 冲突时<b>只更新展示字段</b>（见类注释）。刻意<b>没有</b>
     * {@code password}、{@code is_banned}、{@code reason}、{@code type}、{@code signature}。
     */
    private static final String UPDATE_CLAUSE =
            "user_name = VALUES(user_name), "
                    + "nick_name = VALUES(nick_name), "
                    + "head_image = VALUES(head_image), "
                    + "head_image_thumb = VALUES(head_image_thumb), "
                    + "sex = VALUES(sex)";

    /** 与建表 SQL 一致的上限：超出就是「插进去也报错」，所以在生成阶段就拦住。 */
    private static final int MAX_LEN_USER_NAME = 255;
    private static final int MAX_LEN_NICK_NAME = 255;
    private static final int MAX_LEN_HEAD_IMAGE = 255;

    private final SysUserMapper sysUserMapper;

    /** 影子用户物化总开关，默认 <b>false</b>。 */
    private final boolean enabled;

    /**
     * {@code user_name} 前缀（默认空串 = 严格映射 {@code sys_user.username}）。
     *
     * <p>⚠️ 若把它配成非空（例如 {@code kean_}），物化出来的 {@code user_name} 就是
     * {@code 前缀 + sys_user.username}（本类<b>不</b>拼 userId），于是<b>不再是</b>
     * {@code sys_user.username} 本身 —— 请以实际配置为准。</p>
     */
    private final String userNamePrefix;

    /**
     * {@code im_platform} 的访问通道；<b>开关关闭时为 {@code null}</b>
     * （构造期就不去解析数据源，避免无意义地触发 DataSource 初始化）。
     */
    private final JdbcTemplate imPlatformJdbc;

    public ImShadowUserService(
            SysUserMapper sysUserMapper,
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${kean.im.shadow-user-enabled:false}") boolean enabled,
            @Value("${kean.im.shadow-user-name-prefix:}") String userNamePrefix,
            @Value("${kean.im.shadow-user-datasource:}") String dataSourceBeanName
    ) {
        this.sysUserMapper = sysUserMapper;
        this.enabled = enabled;
        this.userNamePrefix = userNamePrefix == null ? "" : userNamePrefix.trim();
        this.imPlatformJdbc = enabled
                ? resolveJdbcTemplate(dataSourceProvider, dataSourceBeanName)
                : null;
        // 启动即可见：运维能一眼看出「开关到底有没有被 Spring 读到」（只看 /proc/<pid>/environ 只能证明
        // 环境变量被注入，不能证明 @Value 解析成功 —— 键名拼错时会静默沿用默认值 false）。
        log.info("[IM 影子用户] enabled={}（配置项 kean.im.shadow-user-enabled / 环境变量 KEAN_IM_SHADOW_USER_ENABLED），"
                        + "数据源={}，user_name 前缀='{}' → 实际生效={}",
                enabled,
                enabled ? (StringUtils.hasText(dataSourceBeanName) ? dataSourceBeanName : "主 DataSource")
                        : "未启用（不创建 JdbcTemplate）",
                this.userNamePrefix,
                enabled && this.imPlatformJdbc != null);
    }

    /**
     * 物化开关（<b>默认关闭</b>）。
     *
     * <p>只有「配置打开」<b>且</b>「数据源可用」才是 {@code true}；
     * 任一不满足时 {@link #ensureShadowUser(Long)} 直接 no-op（不查库、不写库）。</p>
     */
    public boolean enabled() {
        return enabled && imPlatformJdbc != null;
    }

    /**
     * 供调用方（{@code ImController} 取票成功后）使用的入口：<b>绝不外抛异常</b>。
     *
     * <p>整段 try/catch 是刻意的：物化是「顺路补名片」的旁路动作，
     * <b>任何失败都只能是一条 WARN 日志，绝不能影响取票</b>（阶段 A 的硬要求）。</p>
     *
     * @param userId 课安用户 id（= {@code sys_user.id} = {@code im_user.id}）
     */
    public void ensureShadowUser(Long userId) {
        try {
            if (!enabled() || userId == null) {
                return;
            }
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null) {
                // 逻辑删除的用户（@TableLogic deleted）或 id 不存在：不物化、也不报错。
                log.debug("[IM 影子用户] sys_user 中查不到 id={}（可能已逻辑删除），跳过物化", userId);
                return;
            }
            materialize(user);
        } catch (Exception ex) {
            log.warn("[IM 影子用户] 物化失败（不影响取票与 kean 其它流程），userId={}：{}", userId, ex.getMessage());
        }
    }

    /**
     * 幂等物化一个影子行：{@code INSERT ... ON DUPLICATE KEY UPDATE}（只补展示字段，见类注释）。
     *
     * <p><b>只写 {@code im_platform.im_user} 一张表，一行</b>；不读、不改 kean 的任何数据。</p>
     *
     * <p>⚠️ 本方法<b>不吞异常</b>（与 {@link #ensureShadowUser(Long)} 相反）：调用方需要知道
     * 「库写失败」与「本来就没开」的区别。直接调它的人请自己 try/catch。</p>
     *
     * @return {@code true} = 真的执行了一次 UPSERT；{@code false} = 开关关闭未执行
     * @throws IllegalArgumentException 用户的 id 为空、或展示字段超出 {@code im_user} 的列宽
     */
    public boolean materialize(SysUser user) {
        if (!enabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[IM 影子用户] 未启用（kean.im.shadow-user-enabled=false 或数据源不可用），"
                        + "跳过物化 userId={}", user == null ? null : user.getId());
            }
            return false;
        }
        // 参数与 SQL 由同一处生成（buildUpsertArgs 顺带做「id 非空 + 列宽」校验）
        Object[] args = buildUpsertArgs(user);
        // 用 args.clone() 明确走「varargs」那条重载（Object...），避免踩到 update(String, Object[]) 的歧义
        imPlatformJdbc.update(buildUpsertSql(), args.clone());
        // ⚠️ 日志脱敏：只打掩码后的 id，不打印用户名/昵称（与项目其它日志口径一致）
        log.info("[IM 影子用户] 已物化 im_user.id={}（同 id 复用，ON DUPLICATE KEY UPDATE 只补展示字段）",
                mask(user.getId()));
        return true;
    }

    /** 便捷重载：按 id 取 {@code sys_user} 再物化（与 {@link #ensureShadowUser(Long)} 同源，但会外抛异常）。 */
    public boolean materialize(Long userId) {
        if (!enabled() || userId == null) {
            return false;
        }
        return materialize(sysUserMapper.selectById(userId));
    }

    /**
     * 生成影子行的 UPSERT SQL，参数用 {@code ?} 占位（<b>纯函数，不访问数据库、不依赖开关</b>）。
     *
     * <p>两处用途：① {@link #materialize(SysUser)} 内部执行它；
     * ② 人工核对 / 需要把 {@code im_platform} 放在另一台机器上时，用它在目标库上一次性执行
     * （把 {@code ?} 换成 {@code im-platform-migration.md} §2.3 里的具体值即可）。</p>
     *
     * <p>⚠️ <b>列清单里必须有 {@code id}</b>，且它的值来自 {@code user.getId()} —— 这是铁律 L1
     * 的代码层证据（§10 的 A-3b 校验 1 就是 {@code grep} 这一行）。</p>
     *
     * @param user 影子行的来源用户；<b>只用于校验</b>（id 非空、展示字段不超列宽），
     *             真正的取值走 {@link #buildUpsertArgs(SysUser)} 的同序参数
     * @throws IllegalArgumentException 用户的 id 为空、或展示字段超出 {@code im_user} 的列宽
     */
    public String buildUpsertSql(SysUser user) {
        buildUpsertArgs(user);
        return buildUpsertSql();
    }

    /**
     * SQL 文本本体（列清单写死，与 {@link #INSERT_COLUMNS} / {@link #INSERT_VALUES} 同源）。
     *
     * <p>取值走 {@code ?} 占位而不是字符串拼接：<b>不把用户输入拼进 SQL</b>，
     * 因此没有注入面，也不需要为昵称/头像做转义。</p>
     */
    private static String buildUpsertSql() {
        return "INSERT INTO " + TABLE + " (" + INSERT_COLUMNS + ") VALUES (" + INSERT_VALUES + ") "
                + "ON DUPLICATE KEY UPDATE " + UPDATE_CLAUSE;
    }

    /**
     * 构造与 {@link #buildUpsertSql(SysUser)} 的 {@code ?} 一一对应的参数（顺序即列顺序，见字段映射表）。
     *
     * <p>抽成一个方法是为了让「SQL 的列顺序」与「参数顺序」在<b>同一处</b>维护，
     * 避免将来有人加了列却忘了加参数（那会得到一个错位的 UPDATE，且不会报错）。</p>
     */
    private Object[] buildUpsertArgs(SysUser user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("影子用户物化需要 sys_user.id，但拿到了 null");
        }
        Long userId = user.getId();
        String userName = truncate("user_name", userNamePrefix + safeText(user.getUsername()), MAX_LEN_USER_NAME);
        if (!StringUtils.hasText(userName)) {
            throw new IllegalArgumentException("sys_user.username 为空，无法生成 im_user.user_name（NOT NULL 唯一键），id=" + userId);
        }
        String nickName = safeText(user.getNickname());
        if (!StringUtils.hasText(nickName)) {
            // im_user.nick_name 是 NOT NULL：昵称缺失时回落到用户名，绝不写 null。
            nickName = userName;
        }
        nickName = truncate("nick_name", nickName, MAX_LEN_NICK_NAME);
        String headImage = truncate("head_image", safeText(user.getAvatarUrl()), MAX_LEN_HEAD_IMAGE);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", userId);                                                                  // ⭐ 显式 id
        args.put("user_name", userName);
        args.put("nick_name", nickName);
        args.put("head_image", headImage);
        args.put("head_image_thumb", "");                                                        // kean 无缩略图
        args.put("password", DISABLED_PASSWORD);                                                 // 占位，不可登录
        args.put("sex", mapSex(user.getGender()));
        args.put("is_banned", UserStatus.BANNED.name().equals(user.getStatus()) ? 1 : 0);         // 复用既有口径
        args.put("reason", "");                                                                  // 文案真源在 kean 的 Redis
        args.put("type", USER_TYPE_NORMAL);
        args.put("signature", "");                                                                // kean 无此概念
        args.put("last_login_time", toTimestamp(user.getLastLoginAt()));
        args.put("created_time", toTimestamp(user.getCreatedAt()));
        return args.values().toArray();
    }

    /**
     * {@code sys_user.gender} → {@code im_user.sex}（{@code 0:男 1:女}，建表默认 0）。
     *
     * <p>kean 存的是 {@code VARCHAR(16)} 的 {@code MALE}/{@code FEMALE}（可空，
     * 见 {@code V3__user_gender_and_task_fields.sql}）；box 那边是 {@code tinyint}。
     * <b>映射不了的一律落 0</b>（= box 的默认值），不做臆测。</p>
     */
    private static int mapSex(String gender) {
        if (gender == null) {
            return 0;
        }
        return switch (gender.trim().toUpperCase(Locale.ROOT)) {
            case "FEMALE" -> 1;
            default -> 0;
        };
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    /** 与建表 SQL 对齐的长度防御：宁可拒绝物化（有日志），也不要让 MySQL 抛数据截断/超长错误。 */
    private static String truncate(String column, String value, int maxLen) {
        String text = safeText(value);
        if (text.length() > maxLen) {
            throw new IllegalArgumentException(
                    "im_user." + column + " 超出列宽 " + maxLen + "（当前 " + text.length() + "），请先修正数据再物化");
        }
        return text;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /** 日志脱敏：只保留长度量级，不打印完整 id。 */
    private static String mask(Long userId) {
        String raw = userId == null ? "" : String.valueOf(userId);
        return raw.length() <= 2 ? "*" : raw.charAt(0) + "***" + raw.charAt(raw.length() - 1);
    }

    /**
     * 解析 {@code im_platform} 的访问通道。
     *
     * <p>本轮<b>刻意只用主 {@link DataSource}</b>（项目里 {@code SchedulerLockConfig} 也是这么拿 DataSource 的），
     * 表名写成全限定 {@code im_platform.im_user}：<b>不新增第二个 DataSource、不动既有数据源配置</b>
     * —— 这正是「本轮不真连它」的最小改动版本（SQL 里换一个库名就够了，
     * 前提是 {@code im_platform} 与 kean 在<b>同一个 MySQL 实例</b>且当前账号有该库权限）。</p>
     *
     * <p>解析失败<b>不抛异常</b>：只 WARN 并把本类降级成 no-op
     * —— 影子物化是旁路，<b>绝不能因为它把整个后端启动带崩</b>（与 {@code ImTokenService} 对密钥的处理同风格）。</p>
     *
     * @param dataSourceBeanName 仅用于日志与「阶段 B 换独立数据源」的预留位；
     *                           本轮不按名字解析 Bean（避免多 DataSource 场景下的注入歧义或启动告警）
     */
    private static JdbcTemplate resolveJdbcTemplate(ObjectProvider<DataSource> provider, String dataSourceBeanName) {
        try {
            DataSource dataSource = provider.getIfAvailable();
            if (dataSource == null) {
                log.warn("[IM 影子用户] 容器里没有可用的 DataSource，本次启动不物化影子用户（其它功能不受影响）");
                return null;
            }
            if (StringUtils.hasText(dataSourceBeanName)) {
                log.warn("[IM 影子用户] kean.im.shadow-user-datasource='{}' 本轮为预留项、尚未接线："
                        + "当前仍使用主 DataSource + 全限定表名 im_platform.im_user", dataSourceBeanName);
            }
            return new JdbcTemplate(dataSource);
        } catch (Exception ex) {
            log.warn("[IM 影子用户] 解析数据源失败（不影响启动），配置='{}'：{}", dataSourceBeanName, ex.getMessage());
            return null;
        }
    }
}
