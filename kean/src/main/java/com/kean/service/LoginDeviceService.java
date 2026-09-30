package com.kean.service;

import com.kean.vo.LoginDeviceVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

public interface LoginDeviceService {

    void recordLogin(Long userId, String token, HttpServletRequest request);

    void touchCurrent(HttpServletRequest request);

    void removeCurrent();

    void removeByJti(String jti);

    List<LoginDeviceVO> listMine();

    void kick(Long deviceId);
}
