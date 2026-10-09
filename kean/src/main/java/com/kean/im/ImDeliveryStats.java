package com.kean.im;

/**
 * {@link ImSenderService} 的投递计数<b>只读快照</b>（阶段 3 监控，第 ① 项）。
 *
 * <h2>为什么要单独一个 record</h2>
 * <p>「消息已写入课安数据库」与「实时推送成功」是两件事：IM 是
 * <b>尽力而为的旁路</b> —— {@link ImSenderService} 把投递异常吞成 {@code log.warn}
 * （刻意如此，它在 {@code ChatServiceImpl.send()} 的 {@code @Transactional} 体内被调用，
 * 上抛会回滚已经成功的消息写入）。副作用是：<b>投递失败在业务层完全不可见</b>。
 * 这个快照就是给巡检任务（{@link ImQueueMonitorService}）与健康端点看的唯一事实来源。</p>
 *
 * <h2>四个计数的粒度（很重要，别混着看）</h2>
 * <table border="1">
 *   <caption>计数语义</caption>
 *   <tr><th>字段</th><th>含义</th><th>粒度</th></tr>
 *   <tr><td>{@code attempts}</td><td>尝试投递的消息条数</td>
 *       <td><b>消息</b>：一次 {@code sendPrivate} / {@code sendSystem} 调用 = 1</td></tr>
 *   <tr><td>{@code pushed}</td><td>成功写入队列的条数</td>
 *       <td><b>队列写入</b>：同一条消息可能写多个 {@code serverId} 队列（多端在线），每个 +1</td></tr>
 *   <tr><td>{@code failed}</td><td>投递异常次数</td>
 *       <td><b>队列写入</b>：单条消息的多个 serverId 可以部分成功、部分失败</td></tr>
 *   <tr><td>{@code skipped}</td><td>IM 未启用时的 no-op 次数</td>
 *       <td><b>消息</b>：{@code enabled() == false} 导致的直接返回</td></tr>
 * </table>
 *
 * <p>因此 {@code attempts} <b>不</b>等于 {@code pushed + failed}：接收方离线、参数非法
 * （{@code null} / 内容为空）的分支既不算成功也不算失败 —— 它们不是「投递故障」，
 * 不该把告警打起来。这也是 {@code skipped} <b>只统计「IM 未启用」</b>、不统计离线的刻意选择。</p>
 *
 * <p>计数器单调递增（进程内），重启即归零。跨轮比较请用 {@link #since(ImDeliveryStats)}，
 * 它已经处理了「上一轮观测值来自重启前进程」的情形。</p>
 *
 * @param attempts 尝试投递的消息条数
 * @param pushed   成功写入队列的条数
 * @param failed   投递异常次数
 * @param skipped  IM 未启用导致 no-op 的次数
 */
public record ImDeliveryStats(long attempts, long pushed, long failed, long skipped) {

    /** 全零快照：用作巡检任务的「上一轮观测值」初值。 */
    public static ImDeliveryStats zero() {
        return new ImDeliveryStats(0L, 0L, 0L, 0L);
    }

    /**
     * 相对上一轮快照的增量（逐字段）。
     *
     * <p>用途：告警阈值是「<b>最近一个巡检周期内</b>的失败次数 &gt; 20」，
     * 而计数器记的是累计值，必须做差。</p>
     *
     * @param previous 上一轮读到的快照；{@code null} 视为「首次观测」，直接返回 {@code this}
     */
    public ImDeliveryStats since(ImDeliveryStats previous) {
        if (previous == null) {
            return this;
        }
        return new ImDeliveryStats(
                positiveDelta(attempts, previous.attempts),
                positiveDelta(pushed, previous.pushed),
                positiveDelta(failed, previous.failed),
                positiveDelta(skipped, previous.skipped));
    }

    /**
     * 计数器是单调递增的，正常情况下 {@code current >= previous}。
     *
     * <p>只有「上一轮观测值来自重启前的进程」才会出现 {@code current < previous}：
     * 这时把当前值整体当作增量 —— 宁可多报一次，也不要漏掉重启后立刻累积的失败。</p>
     */
    private static long positiveDelta(long current, long previous) {
        return current >= previous ? current - previous : current;
    }
}
