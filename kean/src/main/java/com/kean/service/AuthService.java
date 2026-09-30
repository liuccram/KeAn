package com.kean.service;

import com.kean.dto.ChangeEmailRequest;
import com.kean.dto.ChangePasswordRequest;
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

    void resetPassword(ResetPasswordRequest request, HttpServletRequest httpRequest);

    void logout(HttpServletRequest httpRequest);
}
