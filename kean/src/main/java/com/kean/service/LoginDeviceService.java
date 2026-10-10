package com.kean.service;

import com.kean.vo.LoginDeviceVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface LoginDeviceService {

    /**
     * 记录本次登录的设备，并在<b>两道开关都满足</b>时顶掉该用户的其他设备：
     * ① 全局配置 {@code kean.security.single-device.enabled}（默认 {@code false}）；
     * ② 用户自己的 {@code sys_user.single_device = 1}。
     *
     * <p>全局开关默认关闭，因此默认行为只是「记录 + 发送新设备登录提醒」，不踢任何设备
     * —— 多端可同时在线。打开该配置项后，才按 {@code sys_user.single_device} 逐用户生效。
     */
    void recordLogin(Long userId, String token, HttpServletRequest request);

    /**
     * 「仅允许一台设备在线」的顶号动作：把该用户除 {@code keepJti} 以外的登录态全部拉黑并软删。
     *
     * <p>两个调用点各自保证传对 {@code keepJti}：登录成功后传本次登录的 jti，
     * 打开开关时传当前请求所在设备的 jti。</p>
     *
     * <p>方法内部先查全局开关 {@code kean.security.single-device.enabled}
     * （<b>默认 {@code false}，此时第一行就返回</b>），再查该用户的
     * {@code sys_user.single_device}，两道都为「开」才动手 —— 所以默认配置下它是空操作。</p>
     */
    void enforceSingleDevice(Long userId, String keepJti);

    /**
     * 注销账号专用：把该用户<b>全部</b>登录设备（<b>含当前这一台</b>）的 jti 拉黑，
     * 并把对应的 {@code login_device} 行软删（{@code deleted = 1}）。
     *
     * <p>与 {@link #enforceSingleDevice(Long, String)} 是同一套动作，唯一区别是不保留任何设备
     * —— 注销后整个账号都不该再登录，没有「当前设备」需要留下。TTL 同样复用
     * {@code LoginDeviceServiceImpl.blacklistJti}（按 device 行的 {@code expire_at} 计算，
     * 为空兜底 7 天、下限 60 秒），保证「同一件事只有一套算法」。
     */
    void revokeAll(Long userId);

    void touchCurrent(HttpServletRequest request);

    void removeCurrent();

    void removeByJti(String jti);

    List<LoginDeviceVO> listMine();

    void kick(Long deviceId);
}
