package com.kean.service;

import com.kean.common.PageResult;
import com.kean.vo.NotificationVO;

public interface NotificationService {

    /**
     * 收件角色：发布者。写入 {@code notification.receiver_role}，取值与 {@code review.target_role}（V24）一致。
     */
    String RECEIVER_ROLE_PUBLISHER = "PUBLISHER";

    /**
     * 收件角色：代课者（申请人/已接受的代课者）。与 {@link #RECEIVER_ROLE_PUBLISHER} 同一取值口径。
     */
    String RECEIVER_ROLE_APPLICANT = "APPLICANT";

    /**
     * 写入一条站内通知，不标注收件角色（{@code receiver_role} 落 null）。
     *
     * <p>这是历史沿用下来的唯一写入通道，**签名保持不变**：系统通知、申请通知、举报通知等
     * 收件角色本来就唯一的那几类写入点继续用它，落库行为与 V36 之前逐字相同。
     *
     * <p>⚠️ 这里刻意**不加** try/catch —— 通知写入失败时的处理方式由调用方决定（有的调用点
     * 自己 catch 后记 warn 继续跑，有的在事务里让它一起回滚），不要在本方法里替调用方吞异常。
     */
    void notifyUser(Long userId, String type, String title, String content, String bizType, Long bizId);

    /**
     * 写入一条站内通知，并标注收件角色（{@code receiver_role}）。
     *
     * <p>只给"同一个事件同时发给发布者与代课者"的写入点用：两边的标题常常相同，客户端
     * 原先只能从正文里的互斥短语反推角色，本轮开始直接读这个字段（见 V36 的迁移说明）。
     * 取值为 {@link #RECEIVER_ROLE_PUBLISHER} / {@link #RECEIVER_ROLE_APPLICANT}，传 null
     * 等价于调用 6 参数的那个方法。
     *
     * <p>异常处理口径与 6 参数版本完全一致（不吞异常）。
     */
    void notifyUser(Long userId, String type, String title, String content, String bizType, Long bizId, String receiverRole);

    PageResult<NotificationVO> listMine(Long page, Long size, String scope);

    long unreadCount(String scope);

    void markRead(Long id);

    void markAllRead(String scope);
}
