package com.kean.vo;

import java.time.LocalDateTime;

/**
 * 通知返回体。
 *
 * <p>{@code receiverRole}（PUBLISHER / APPLICANT；见 V36）描述"这条通知是发给发布者还是代课者"，
 * 历史通知与单一收件角色的通知为 null。
 * ⚠️ 它刻意**追加在 record 末尾**：老客户端按字段名取值，多一个字段无感；
 * 而 application.yml 的 {@code default-property-inclusion=non_null} 会让 null 直接不出现在 JSON 里，
 * 所以老客户端看到的报文与改动前逐字相同。
 */
public record NotificationVO(
        Long id,
        String type,
        String title,
        String content,
        String bizType,
        Long bizId,
        Integer readFlag,
        LocalDateTime createdAt,
        String receiverRole
) {
}
