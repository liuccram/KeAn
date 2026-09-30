package com.kean.service;

import com.kean.entity.SysUser;

public interface AccountBanService {

    void onBanned(SysUser user, String remark);

    void onUnbanned(Long userId);
}
