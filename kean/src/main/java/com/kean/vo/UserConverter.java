package com.kean.vo;

import com.kean.entity.SysUser;
import com.kean.utils.FileUrls;
import com.kean.utils.UserRestrictions;

public final class UserConverter {

    private UserConverter() {
    }

    public static UserVO toVo(SysUser user) {
        return toVo(user, null, null);
    }

    public static UserVO toVo(SysUser user, String schoolName, String campusName) {
        return toVo(user, schoolName, campusName, false);
    }

    public static UserVO toVo(SysUser user, String schoolName, String campusName, boolean online) {
        return new UserVO(
                user.getId(),
                user.getRole(),
                user.getUsername(),
                user.getPhone(),
                user.getEmail(),
                user.getNickname(),
                user.getGender(),
                user.getAvatarUrl() == null ? null : FileUrls.of(user.getAvatarUrl()),
                user.getCoverUrl() == null ? null : FileUrls.of(user.getCoverUrl()),
                user.getSchoolId(),
                user.getCampusId(),
                schoolName,
                campusName,
                user.getSchoolChangeCount(),
                user.getCompletedCount(),
                user.getRatingAvg(),
                user.getRatingCount(),
                user.getPublishCompletedCount() == null ? 0 : user.getPublishCompletedCount(),
                user.getPublishRatingAvg(),
                user.getPublishRatingCount() == null ? 0 : user.getPublishRatingCount(),
                user.getApplyRatingAvg(),
                user.getApplyRatingCount() == null ? 0 : user.getApplyRatingCount(),
                user.getCancelledCount(),
                user.getReportedCount(),
                user.getStatus(),
                UserRestrictions.flag(user.getForbidPublish(), user.getForbidPublishUntil()),
                UserRestrictions.flag(user.getForbidApply(), user.getForbidApplyUntil()),
                UserRestrictions.flag(user.getMuted(), user.getMutedUntil()),
                UserRestrictions.forbidPublish(user) ? user.getForbidPublishUntil() : null,
                UserRestrictions.forbidApply(user) ? user.getForbidApplyUntil() : null,
                UserRestrictions.muted(user) ? user.getMutedUntil() : null,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getPrivateAccount() == null ? 0 : user.getPrivateAccount(),
                online,
                user.getMustChangePassword() != null && user.getMustChangePassword() == 1
        );
    }
}
