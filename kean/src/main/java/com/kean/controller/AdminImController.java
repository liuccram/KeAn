package com.kean.controller;

import com.kean.common.Result;
import com.kean.dto.AdminImCleanRequest;
import com.kean.security.AdminGuard;
import com.kean.service.AdminImService;
import com.kean.vo.AdminImAlertsVO;
import com.kean.vo.AdminImCleanResultVO;
import com.kean.vo.AdminImMonitorVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端的 IM 监控接口（本轮监控告警功能的管理端入口）。
 *
 * <h2>权限</h2>
 * <p><b>不需要在这里写角色判断</b>：路径落在 {@code /api/admin/**} 下，而
 * {@code SecurityConfig} 已经有 {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")}
 * —— 与其余 {@code Admin*Controller} 完全一致（未登录 401 / 非管理员 403 由
 * {@code JwtAuthFilter} + {@code SecurityFilterChain} 统一处理，返回的也是统一 {@code Result} 信封）。
 * 唯一的例外是 {@link #cleanQueues}：它是<b>破坏性写操作</b>，额外调一次
 * {@link AdminGuard#require()} 做纵深防御（项目里 {@code AdminDashboardController#online} 也是这么用的）。
 * 两个过滤器（含「管理员必须改初始密码」）对 {@code /api/admin/**} 一视同仁，无需额外注册。</p>
 *
 * <h2>接口</h2>
 * <table border="1">
 *   <caption>三个接口</caption>
 *   <tr><th>方法 / 路径</th><th>读写</th><th>说明</th></tr>
 *   <tr><td>{@code GET /api/admin/im/stats}</td><td>只读</td>
 *       <td>看板：状态（与 {@code /health/ready} 的 {@code im} 同源）、计数器、各队列长度、残留队列、{@code im:max_server_id}、最近巡检时间</td></tr>
 *   <tr><td>{@code GET /api/admin/im/alerts?limit=50}</td><td>只读</td>
 *       <td>告警历史（<b>内存态，重启即丢</b>，最多 50 条）</td></tr>
 *   <tr><td>{@code POST /api/admin/im/queues/clean}</td><td><b>写</b></td>
 *       <td>清理残留队列：必须带确认串 {@value com.kean.dto.AdminImCleanRequest#CONFIRM_PHRASE}，
 *       且只删「巡检判定为残留 + 通过活跃 serverId 二次校验」的键</td></tr>
 * </table>
 *
 * <p>本轮<b>不动前端</b>（{@code web-kean} 尚未部署）：接口先就位，接入说明见
 * {@code docs/ops/im-monitoring.md} 的「管理端接入说明」一节。</p>
 */
@RestController
@RequestMapping("/api/admin/im")
public class AdminImController {

    private final AdminImService adminImService;

    public AdminImController(AdminImService adminImService) {
        this.adminImService = adminImService;
    }

    /** 看板：一次拿全（只读）。Redis 不可用时返回 {@code redisAvailable=false} + 说明，而不是 500。 */
    @GetMapping("/stats")
    public Result<AdminImMonitorVO> stats() {
        return Result.ok(adminImService.stats());
    }

    /**
     * 告警历史（只读，内存态）。
     *
     * @param limit 可选，默认 50，最大不超过历史缓冲容量（{@code kean.im.alert-history-size}）
     */
    @GetMapping("/alerts")
    public Result<AdminImAlertsVO> alerts(@RequestParam(required = false) Integer limit) {
        return Result.ok(adminImService.alerts(limit));
    }

    /**
     * 清理残留队列（<b>写操作，会永久丢弃队列里的消息</b>）。
     *
     * <p>必须带 {@code confirm: "CLEAN_RESIDUE"}；可选 {@code serverIds} 收窄范围。
     * 只删「最近一次巡检判定为残留」且「serverId 不在活跃集合里」的键，逐键返回结果。</p>
     */
    @PostMapping("/queues/clean")
    public Result<AdminImCleanResultVO> cleanQueues(@Valid @RequestBody AdminImCleanRequest request) {
        // 纵深防御：SecurityConfig 已限制 /api/admin/** 需要 ADMIN，这里再确认一次调用者身份
        // （破坏性操作值得多一道；顺带保证操作日志里的管理员一定是真实的）。
        AdminGuard.require();
        return Result.ok(adminImService.cleanResidue(request));
    }
}
