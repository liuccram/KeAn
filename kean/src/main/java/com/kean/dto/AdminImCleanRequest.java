package com.kean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理端清理 IM 残留队列的入参：{@code POST /api/admin/im/queues/clean}。
 *
 * <h2>为什么必须有确认串</h2>
 * <p>这是一个<b>会永久丢弃 Redis 消息</b>的写操作，绝不能让「一个空 POST」就把它删掉
 * （误点、脚本探测、CSRF 残留都会造成不可逆的数据丢失）。
 * 因此 {@code confirm} 是<b>必填</b>且必须逐字符等于 {@value #CONFIRM_PHRASE}。</p>
 *
 * @param confirm   显式确认串，必须为 {@value #CONFIRM_PHRASE}
 * @param serverIds 可选：只清理这些 serverId 的残留队列；
 *                  为空 / 不传 = 清理最近一次巡检判定出的<b>全部</b>残留队列。
 *                  无论怎么传，最终都还要过一遍「活跃 serverId」二次校验（见 {@code AdminImService}）
 */
public record AdminImCleanRequest(
        @NotBlank(message = "请提交确认串")
        // 这里的字面量与下面的 CONFIRM_PHRASE 必须保持一致（注解值刻意写成字面量，
        // 不引用常量，避免依赖「注解里前向引用自身常量」这种容易踩编译器的写法）。
        @Pattern(regexp = "^CLEAN_RESIDUE$", message = "确认串必须为 CLEAN_RESIDUE")
        String confirm,

        @Size(max = 64, message = "一次最多指定 64 个 serverId")
        List<Long> serverIds
) {

    /** 必须逐字符匹配的确认串。 */
    public static final String CONFIRM_PHRASE = "CLEAN_RESIDUE";
}
