package com.kean.service;

import com.kean.vo.LoginDeviceVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface LoginDeviceService {

    /**
     * 记录本次登录的设备，并在用户开启「仅允许一台设备在线」时顶掉该用户的其他设备。
     * 开关为 0（默认）时只记录 + 发送新设备登录提醒，不踢任何设备。
     */
    void recordLogin(Long userId, String token, HttpServletRequest request);

    /**
     * 「仅允许一台设备在线」的顶号动作：把该用户除 {@code keepJti} 以外的登录态全部拉黑并软删。
     *
     * <p>两个调用点各自保证传对 {@code keepJti}：登录成功后传本次登录的 jti，
     * 打开开关时传当前请求所在设备的 jti。方法内部仍会先查一次
     * {@code sys_user.single_device}，只有为 1 才动手 —— 所以开关为 0（默认）时它是空操作。
     */
    void enforceSingleDevice(Long userId, String keepJti);

    void touchCurrent(HttpServletRequest request);

    void removeCurrent();

    void removeByJti(String jti);

    List<LoginDeviceVO> listMine();

    void kick(Long deviceId);
}
