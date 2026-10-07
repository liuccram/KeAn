package com.kean.utils;

/**
 * 校区展示名。
 *
 * 校区已改为用户手动输入的文本（campus_text），但历史数据里只有 campus_id。
 * 规则：有文本用文本；没有文本才回退到 campus_id 关联出来的旧校区名，旧数据因此不会突然变空。
 * 全站展示（用户资料、任务列表/详情、私信选人、黑名单、管理端）都走这里，不要各写一套。
 */
public final class CampusNames {

    private CampusNames() {
    }

    /**
     * @param campusText       用户手输的校区文本，可为 null / 空白
     * @param legacyCampusName campus_id 关联出来的旧校区名，可为 null
     * @return 优先返回去首尾空格后的文本；文本为空则返回旧校区名（可能为 null）
     */
    public static String display(String campusText, String legacyCampusName) {
        if (campusText != null && !campusText.isBlank()) {
            return campusText.trim();
        }
        return legacyCampusName;
    }

    /** 空值 = 没填：去首尾空格后为空的文本统一收敛成 null。 */
    public static String normalize(String campusText) {
        if (campusText == null || campusText.isBlank()) {
            return null;
        }
        return campusText.trim();
    }
}
