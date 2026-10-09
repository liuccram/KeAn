package com.kean.enums;

/**
 * 私聊消息状态，取值与 box-im {@code im_private_message.status} 完全对齐。
 *
 * <p>box 原始定义：{@code `status` tinyint not null comment '状态 0:未读 1:已发送 2:撤回 3:已读'}。
 * 课安此前用字符串枚举（SENT / READ / RECALLED），V35 已把列改成 TINYINT，这里同步成数值枚举，
 * 不再提供字符串写法，避免两套语义并存。
 *
 * <p>各状态在课安的落地时机：
 * <ul>
 *     <li>{@link #UNREAD}：box 语义保留位。当前实现里消息落库即"已发送"，
 *         未读是用会话位点（{@code chat_session.a_read_seq / b_read_seq}）和未读数表达的，
 *         所以服务端不会主动写入 0；保留它是为了不改动 box 取值表、也不排除后续
 *         "先落库失败重发"的场景。</li>
 *     <li>{@link #SENT}：新消息的初始状态，同时是数据库该列的 DEFAULT。</li>
 *     <li>{@link #RECALLED}：撤回。本轮不实现撤回接口，只在枚举与建表注释里留位。</li>
 *     <li>{@link #READ}：接收方调用 {@code POST /api/chats/{id}/read} 并传入 maxSeq 后，
 *         把该会话中"对方发给我的、seq_no <= maxSeq"的消息置为该状态。</li>
 * </ul>
 */
public enum ChatMessageStatus {

    /** 0：未读（box 保留位，课安当前不写入）。 */
    UNREAD(0),

    /** 1：已发送，新消息默认状态。 */
    SENT(1),

    /** 2：撤回，本轮不实现撤回功能，仅保留取值位。 */
    RECALLED(2),

    /** 3：已读，接收方拉取/标记已读后写入。 */
    READ(3);

    private final int code;

    ChatMessageStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean matches(Integer value) {
        return value != null && value == code;
    }

    /**
     * 把库里读出来的数值状态翻成枚举；未知值回退为 {@link #SENT}，
     * 避免历史脏数据把接口搞成 500。
     */
    public static ChatMessageStatus of(Integer code) {
        if (code != null) {
            for (ChatMessageStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        return SENT;
    }
}
