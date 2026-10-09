package com.kean.service;

public interface MailService {

    boolean ready();

    void sendVerifyCode(String to, String scene, String code);

    /**
     * 发送一封纯文本<b>运维告警</b>邮件（IM 监控告警专用，见 {@code ImAlertService}）。
     *
     * <p><b>刻意不新建邮件通道</b>：这里复用同一个 {@code JavaMailSender} Bean 与
     * 同一份 {@code kean.mail.*} 配置（from / from-name / ready 判定），
     * 与验证码邮件走完全同一条 SMTP 路径 —— 运维只需要维护一套发信配置。</p>
     *
     * <p>与 {@link #sendVerifyCode} 一样，<b>它会把失败抛出来</b>
     * （{@code BizException}）：是否吞掉由调用方决定。告警调用方
     * （{@code ImAlertService}）必须自己 try/catch —— 告警失败绝不能影响任何业务。</p>
     *
     * @param to      收件人地址（IM 告警的收件人由 {@code KEAN_IM_ALERT_TO} 决定）
     * @param subject 主题
     * @param text    纯文本正文
     * @throws com.kean.exception.BizException 邮件通道未配置（{@code MAIL_NOT_CONFIGURED}）
     *                                         或发送失败（{@code MAIL_SEND_FAILED}）
     */
    void sendAlert(String to, String subject, String text);
}
