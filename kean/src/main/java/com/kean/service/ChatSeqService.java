package com.kean.service;

/**
 * 私聊会话内 seq_no 的分配器（对照 box-im "单个会话消息的序号连续递增"）。
 *
 * <p>约定：同一会话内并发分配不会拿到重复序号；由于发送失败/回滚会浪费已分配的号，
 * 序号是<b>严格递增</b>而不是<b>严格连续无空洞</b>。客户端只用它做"大于游标的增量拉取"，
 * 空洞不影响语义。
 */
public interface ChatSeqService {

    /**
     * 分配某会话的下一个 seq_no（从 1 开始）。
     *
     * @param sessionId  会话 id
     * @param lastSeqNo  会话行上的 {@code last_seq_no}，作为 Redis 冷启动时"至少从哪里开始"的下界
     * @return 下一个可用序号
     */
    long allocateNext(Long sessionId, Long lastSeqNo);

    /**
     * 读某会话当前的分配位点，用于 markRead 这种需要"最新序号"的场景。
     * 不分配新号。
     */
    long currentSeq(Long sessionId, Long lastSeqNo);
}
