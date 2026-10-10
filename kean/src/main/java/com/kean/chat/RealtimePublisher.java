package com.kean.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kean.im.ImRealtimeRoleService;
import com.kean.im.ImSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final ChatSessionHub chatSessionHub;
    private final ObjectMapper objectMapper;
    /**
     * box-im im-server 的镜像投递（阶段 3 新增，<b>纯追加</b>）。
     *
     * <p>每个推送出口在「既有 {@link ChatSessionHub} 推完之后」再镜像一份到
     * {@code im:message:system:{serverId}}。{@code IM_JWT_SECRET} 未配置时
     * {@link ImSenderService#sendSystem} 内部直接 return，开关关闭时本类行为与新增前一致。</p>
     */
    private final ImSenderService imSenderService;

    /**
     * 阶段 C-4：自研 WS 实时推送的<b>总闸</b>（{@code kean.im.legacy-ws-enabled}，<b>默认 true</b>）；
     * 本轮起它<b>还</b>提供一把更窄的 {@code READ} 闸（{@code kean.im.read-receipt-enabled}，<b>默认 true</b>）。
     *
     * <p>⚠️ 它<b>只被 {@link #read} 读取</b>（{@code READ} 事件），<b>不</b>被 {@link #notice} /
     * {@link #send} / {@link #broadcast} 读取 —— 这是刻意的边界：
     * 通知 / 公告 / 封禁提示（{@code NOTICE} 类）没有 box 对等物，
     * 必须继续走自研 WS（见 {@code docs/ops/im-platform-migration.md} §1.4 与
     * {@link ImRealtimeRoleService} 类注释的边界表）。</p>
     */
    private final ImRealtimeRoleService imRealtimeRoleService;

    public RealtimePublisher(ChatSessionHub chatSessionHub,
                             ObjectMapper objectMapper,
                             ImSenderService imSenderService,
                             ImRealtimeRoleService imRealtimeRoleService) {
        this.chatSessionHub = chatSessionHub;
        this.objectMapper = objectMapper;
        this.imSenderService = imSenderService;
        this.imRealtimeRoleService = imRealtimeRoleService;
    }

    public void notice(Long userId, String noticeType, String bizType, Long bizId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NOTICE");
        payload.put("noticeType", noticeType == null ? "" : noticeType);
        payload.put("bizType", bizType == null ? "" : bizType);
        payload.put("bizId", bizId == null ? 0 : bizId);
        send(userId, payload);
    }

    /**
     * 已读回执。推给<b>发送方</b>：告诉它"你的消息已经被对方读到 maxSeq 了"。
     *
     * <p>payload 字段（与 NOTICE 一样扁平放在顶层，不套 data）：
     * <pre>
     * {
     *   "type": "READ",
     *   "sessionId": 12,
     *   "maxSeq": 35,
     *   "readerId": 7
     * }
     * </pre>
     * {@code maxSeq} 是对方读到的位点，{@code readerId} 是读消息的人。
     * 事件在 markRead 的数据库写入过程中直推（与既有 MESSAGE 推送一样不额外等待事务提交），
     * 对方不在线时由 {@link #send} 静默丢弃，库里已读位点仍然是准确的。
     *
     * <p><b>本方法不做 IM 镜像</b>（刻意的范围限定）：阶段 3 只要求镜像 {@code send()} / {@code notice()}，
     * 而 READ 是课安自己的已读位点事件，box 协议的对应物是 {@code MessageType.RECEIPT(12)}，
     * 语义与字段都要另外对齐 —— 留到下一阶段，避免现在就把没把握的映射写进协议层。</p>
     *
     * <h2>⭐ 本轮起的两道闸（优先级：{@code read-receipt-enabled} &gt; {@code legacy-ws-enabled}）</h2>
     * <ol>
     *   <li><b>{@code kean.im.read-receipt-enabled=false}</b>（本轮新增，<b>默认 true</b>）：
     *       本方法整体 no-op，<b>只</b>影响这一个推送点。
     *       ⚠️ 调用方 {@code ChatServiceImpl.markRead} 的 box 已读回写、位点、
     *       {@code chat_message.status=3}、{@code a_unread/b_unread} 清零<b>一行都不受影响</b>，
     *       也不影响客户端每一次已读<b>上报</b>（那条链路服务的是「自己的未读角标」）；</li>
     *   <li><b>{@code kean.im.legacy-ws-enabled=false}</b>（C-4 既有开关，默认 true）：
     *       照旧 no-op，语义与新增第 1 条之前完全一致。</li>
     * </ol>
     * <p>两道闸的「提前返回」都是<b>行为等价</b>的：READ 事件的唯一出口就是这个
     * {@code send(...)}（自研 WS 的 chatSessionHub），而 {@code send(...)} 里的 im-server 镜像
     * 对这个事件本来就是跳过的（{@link #systemMessageData} 对 {@code "READ"} 返回 {@code null}），
     * 所以提前返回<b>不会</b>少推任何一条 box 消息。</p>
     * <p><b>为什么本轮要新增第 1 条</b>：客户端已不再展示「对方已读」（双勾移除），
     * READ 事件于是<b>没有任何消费方</b>；而 {@code legacy-ws-enabled} 是一把同时管
     * {@code MESSAGE} 的总闸，用它来关 READ 会连带关掉消息实时推送。两件事的退役节奏不同，
     * 所以要一把<b>只管 READ</b> 的更窄的闸。</p>
     */
    public void read(Long userId, Long sessionId, Long maxSeq, Long readerId) {
        // 本轮新增的窄闸：只关 READ 这一个事件。
        // 默认 true（保持现状，仍然推），切换由部署后改 KEAN_IM_READ_RECEIPT_ENABLED=false 完成。
        if (!imRealtimeRoleService.readReceiptEnabled()) {
            imRealtimeRoleService.recordSuppressedReadReceipt();
            log.debug("skip legacy ws READ push ({} disabled), userId={}, sessionId={}, maxSeq={}",
                    ImRealtimeRoleService.PROPERTY_READ_RECEIPT_ENABLED, userId, sessionId, maxSeq);
            return;
        }
        // 阶段 C-4：自研 WS 的实时推送总闸（kean.im.legacy-ws-enabled，默认 true）。
        // false 时本方法整体 no-op —— 这是【行为等价】的：
        //   · READ 事件的唯一出口就是这个 send(...)（legacy WS 的 chatSessionHub）；
        //   · send(...) 里的 im-server 镜像对这个事件本来就是跳过的
        //     （systemMessageData 对 "READ" 返回 null），所以这里提前返回
        //     【不会】少推任何一条 box 消息。
        // 关掉之后「对方已读」的实时性由「客户端轮询（≤8s）+ 数据推导」承担：
        // 增量拉取的 ChatMessageVO 里【已经】带 status（3 = 已读），两条读路径都带
        // （kean 的 chat_message.status / box 的 im_private_message.status），见
        // ImRealtimeRoleService 类注释「关掉之后已读靠什么」。
        if (!imRealtimeRoleService.legacyWsPushEnabled()) {
            imRealtimeRoleService.recordSuppressedPush();
            log.debug("skip legacy ws READ push ({} disabled), userId={}, sessionId={}, maxSeq={}",
                    ImRealtimeRoleService.PROPERTY_LEGACY_WS_ENABLED, userId, sessionId, maxSeq);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "READ");
        payload.put("sessionId", sessionId == null ? 0 : sessionId);
        payload.put("maxSeq", maxSeq == null ? 0 : maxSeq);
        payload.put("readerId", readerId == null ? 0 : readerId);
        send(userId, payload);
    }

    public void send(Long userId, Map<String, Object> payload) {
        if (userId == null || payload == null || payload.isEmpty()) {
            return;
        }
        try {
            chatSessionHub.sendTo(userId, objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            // 用户不在线时只保留库内通知
        }
        // 阶段 3：既有推送之后追加一次 im-server 镜像投递（只对指定用户，不是广播 ——
        // 避免"通知一个人"被放大成全站推送）。未启用 IM 时 ImSenderService 内部直接 no-op。
        mirrorToIm(userId, payload);
    }

    /**
     * 把已有的事件 payload 镜像成 box-im 的 {@code SYSTEM_MESSAGE(5)}。
     *
     * <p>包一层是为了让 box 客户端收到的结构与课安自研 WS 的 {@code RealtimeEvent} <b>完全同形</b>：
     * im-server 只会把 {@code IMRecvInfo.data} 原样放进下行帧 {@code {cmd:5, data:...}}，
     * 所以这里投递的 {@code data} 就写成 {@code {type, noticeType, bizType, bizId}} 本身，
     * 客户端不需要为「消息来自 box 通道」写第二套解析。</p>
     *
     * <p><b>不抛异常</b>：{@link #send} 的调用方都在业务事务里（例如封禁流程），
     * 镜像失败绝不能影响它们。</p>
     */
    private void mirrorToIm(Long userId, Map<String, Object> payload) {
        try {
            Map<String, Object> data = systemMessageData(payload);
            if (data == null) {
                return;
            }
            imSenderService.sendSystem(userId, data);
        } catch (Exception ex) {
            log.debug("mirror realtime event to im-server failed, userId={}", userId, ex);
        }
    }

    /**
     * 事件 payload → box 系统消息体。
     *
     * <p>{@code READ} 事件被跳过（原因见 {@link #read}）：镜像成系统消息会变成一条没有意义的 NOTICE。</p>
     */
    private Map<String, Object> systemMessageData(Map<String, Object> payload) {
        Object type = payload.get("type");
        if ("READ".equals(type)) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type == null ? "" : type);
        if ("NOTICE".equals(type)) {
            data.put("noticeType", payload.get("noticeType"));
            data.put("bizType", payload.get("bizType"));
            data.put("bizId", payload.get("bizId"));
        }
        return data;
    }

    /**
     * 显式的「广播系统消息」入口（给将来的运维/公告场景预留，当前业务未使用）。
     *
     * <p>传 {@code null} 即 box 语义的广播（{@code IMSystemMessage.recvIds} 为空 = 所有在线用户）。</p>
     */
    public void broadcast(Map<String, Object> payload) {
        try {
            Map<String, Object> data = systemMessageData(payload);
            if (data == null) {
                return;
            }
            imSenderService.sendSystem((List<Long>) null, data);
        } catch (Exception ex) {
            log.debug("broadcast realtime event to im-server failed", ex);
        }
    }

    /** 便于测试/排查：当前 IM 镜像通道是否就绪。 */
    public boolean imMirrorEnabled() {
        return imSenderService.enabled();
    }
}
