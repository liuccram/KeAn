package com.kean.service;

import com.kean.common.PageResult;
import com.kean.dto.AdminChangePasswordRequest;
import com.kean.dto.AdminUserStatusRequest;
import com.kean.dto.CreateAdminRequest;
import com.kean.dto.UpdateConfigRequest;
import com.kean.vo.AdminAccountVO;
import com.kean.vo.OperationLogVO;
import com.kean.vo.SysConfigVO;

import java.util.List;

public interface AdminSystemService {

    List<AdminAccountVO> listAdmins();

    AdminAccountVO createAdmin(CreateAdminRequest request);

    AdminAccountVO updateAdminStatus(Long id, AdminUserStatusRequest request);

    void changeOwnPassword(AdminChangePasswordRequest request);

    PageResult<OperationLogVO> listLogs(Long adminId, String operationType, String from, String to, Long page, Long size);

    List<SysConfigVO> listConfig();

    List<SysConfigVO> updateConfig(UpdateConfigRequest request);
}
