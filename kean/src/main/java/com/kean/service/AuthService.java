package com.kean.service;

import com.kean.dto.ChangeEmailRequest;
import com.kean.dto.ChangePasswordRequest;
import com.kean.dto.DeleteAccountRequest;
import com.kean.dto.LoginRequest;
import com.kean.dto.RegisterRequest;
import com.kean.dto.ResetPasswordRequest;
import com.kean.dto.SendSmsRequest;
import com.kean.dto.UpdateProfileRequest;
import com.kean.vo.LoginVO;
import com.kean.vo.SmsSendVO;
import com.kean.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    UserVO register(RegisterRequest request, HttpServletRequest httpRequest);

    SmsSendVO sendSms(SendSmsRequest request, HttpServletRequest httpRequest);

    void changePassword(ChangePasswordRequest request);

    LoginVO login(LoginRequest request, HttpServletRequest httpRequest);

    UserVO currentUser();

    UserVO updateProfile(UpdateProfileRequest request);

    UserVO updateAvatar(String objectKey);

    UserVO updateCover(String objectKey);

    UserVO changeEmail(ChangeEmailRequest request);

    UserVO updatePrivacy(Integer privateAccount);

    /**
     * 切换「仅允许一台设备在线」。0 = 关闭（默认，多端可同时在线），1 = 打开。
     *
     * <p>⚠️ 该功能现已被全局配置停用：{@code kean.security.single-device.enabled}
     * <b>默认 {@code false}</b>。此时本方法只写 {@code sys_user.single_device} 列、
     * <b>不顶号</b>（踢人判定集中在 {@code LoginDeviceServiceImpl.enforceSingleDevice}，
     * 那里第一行就按全局开关返回）。接口与列都保留，恢复只需改环境变量。</p>
     */
    UserVO updateSingleDevice(Integer singleDevice);

    /**
     * 注销账号（不可逆）。必须提交当前密码，服务端用 {@code PasswordEncoder} 校验。
     * 执行顺序固定：校验密码 → 拉黑全部设备 jti → 匿名化 {@code sys_user} →
     * 逻辑删除 {@code deleted = 1} → 清理只属于本人的数据。
     * 详见 {@code AuthServiceImpl#deleteAccount} 与 docs/api/auth.md。
     */
    void deleteAccount(DeleteAccountRequest request);

    void resetPassword(ResetPasswordRequest request, HttpServletRequest httpRequest);

    void logout(HttpServletRequest httpRequest);
}
