package com.kean.service;

import com.kean.dto.AdminResetPasswordRequest;
import com.kean.dto.AdminUserRestrictionsRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.vo.AdminUserDetailVO;
import com.kean.vo.AdminUserPageVO;
import com.kean.vo.UserVO;

import java.util.List;

public interface AdminUserService {

    AdminUserPageVO list(String keyword, Long provinceId, Long schoolId, Long campusId, String gender, String status, Long page, Long size);

    AdminUserDetailVO detail(Long id);

    UserVO updateStatus(Long id, AdminUserStatusRequest request);

    UserVO updateRestrictions(Long id, AdminUserRestrictionsRequest request);

    void resetPassword(Long id, AdminResetPasswordRequest request);

    List<UserVO> listOnline();
}
