package com.kean.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    SUCCESS(0, "ok", HttpStatus.OK),
    BAD_REQUEST(40000, "请求参数错误", HttpStatus.BAD_REQUEST),
    SCHOOL_INVALID(40001, "学校或校区无效", HttpStatus.BAD_REQUEST),
    COURSE_INVALID(40002, "课程无效", HttpStatus.BAD_REQUEST),
    TIME_INVALID(40003, "上课时间不合法", HttpStatus.BAD_REQUEST),
    TASK_NOT_EDITABLE(40004, "当前状态不允许修改或删除", HttpStatus.BAD_REQUEST),
    TASK_STATUS_INVALID(40005, "当前状态不允许该操作", HttpStatus.BAD_REQUEST),
    CANNOT_APPLY_OWN(40006, "不能申请自己发布的代课", HttpStatus.BAD_REQUEST),
    GENDER_INVALID(40007, "请选择性别", HttpStatus.BAD_REQUEST),
    GENDER_NOT_MATCH(40008, "不符合该任务的性别要求", HttpStatus.BAD_REQUEST),
    SCHOOL_CHANGE_LIMIT(40009, "学校最多只能修改 3 次", HttpStatus.BAD_REQUEST),
    SMS_TOO_FREQUENT(40010, "验证码发送过于频繁，请稍后再试", HttpStatus.BAD_REQUEST),
    SMS_CODE_INVALID(40011, "验证码不正确或已过期", HttpStatus.BAD_REQUEST),
    PHONE_REQUIRED(40012, "请先绑定手机号", HttpStatus.BAD_REQUEST),
    REVIEW_NOT_ALLOWED(40013, "当前不能评价", HttpStatus.BAD_REQUEST),
    FILE_TYPE_INVALID(40014, "仅支持 jpg/png/webp/gif 图片", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(40015, "图片不能超过 5MB", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED(40016, "图片上传失败", HttpStatus.BAD_REQUEST),
    USER_BLOCKED(40017, "双方存在拉黑关系，无法进行该操作", HttpStatus.BAD_REQUEST),
    EMAIL_REQUIRED(40018, "请先绑定 QQ 邮箱", HttpStatus.BAD_REQUEST),
    MAIL_NOT_CONFIGURED(40021, "邮件服务未配置", HttpStatus.BAD_REQUEST),
    MAIL_SEND_FAILED(40022, "邮件发送失败，请稍后重试", HttpStatus.BAD_REQUEST),
    CANNOT_FAVORITE_OWN(40019, "不能收藏自己发布的代课", HttpStatus.BAD_REQUEST),
    TIME_CONFLICT(40020, "该时段已有其他代课", HttpStatus.BAD_REQUEST),
    TURNSTILE_REQUIRED(40024, "请完成真人验证", HttpStatus.BAD_REQUEST),
    TURNSTILE_FAILED(40025, "真人验证失败，请重试", HttpStatus.BAD_REQUEST),
    IMAGE_DIMENSION_TOO_LARGE(40026, "图片像素过大，请压缩后重新上传", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    LOGIN_FAILED(40101, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    // 登录态被主动终止：被其他设备顶下线、凭证被批量作废（如改密）。
    // 与普通 40100 区分开，客户端才能明确告知用户原因，而不是静默清掉登录态。
    SESSION_ENDED(40102, "登录状态已失效，请重新登录", HttpStatus.UNAUTHORIZED),
    LOGIN_LOCKED(42901, "登录失败次数过多，请15分钟后再试", HttpStatus.TOO_MANY_REQUESTS),
    FILE_RATE_LIMITED(42902, "文件访问过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
    FORBIDDEN(40300, "没有权限", HttpStatus.FORBIDDEN),
    ACCOUNT_BANNED(40301, "账号已被封禁", HttpStatus.FORBIDDEN),
    FORBID_PUBLISH(40302, "账号已被禁止发布", HttpStatus.FORBIDDEN),
    FORBID_APPLY(40303, "账号已被禁止申请", HttpStatus.FORBIDDEN),
    ACCOUNT_MUTED(40304, "账号已被禁言", HttpStatus.FORBIDDEN),
    REGISTER_CLOSED(40305, "暂未开放注册", HttpStatus.FORBIDDEN),
    UPLOAD_CLOSED(40306, "暂未开放图片上传", HttpStatus.FORBIDDEN),
    MUST_CHANGE_PASSWORD(40307, "请先修改初始密码", HttpStatus.FORBIDDEN),
    TASK_NOT_FOUND(40401, "代课任务不存在", HttpStatus.NOT_FOUND),
    APPLICATION_NOT_FOUND(40402, "申请不存在", HttpStatus.NOT_FOUND),
    NOTIFICATION_NOT_FOUND(40403, "消息不存在", HttpStatus.NOT_FOUND),
    CHAT_NOT_FOUND(40404, "会话不存在", HttpStatus.NOT_FOUND),
    FILE_NOT_FOUND(40405, "文件不存在", HttpStatus.NOT_FOUND),
    REPORT_NOT_FOUND(40406, "举报不存在", HttpStatus.NOT_FOUND),
    BLACKLIST_NOT_FOUND(40407, "黑名单记录不存在", HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(40408, "用户不存在", HttpStatus.NOT_FOUND),
    ANNOUNCEMENT_NOT_FOUND(40409, "公告不存在", HttpStatus.NOT_FOUND),
    USERNAME_EXISTS(40901, "用户名已存在", HttpStatus.CONFLICT),
    PHONE_EXISTS(40902, "手机号已被注册", HttpStatus.CONFLICT),
    EMAIL_EXISTS(40907, "该 QQ 邮箱已被注册", HttpStatus.CONFLICT),
    ALREADY_APPLIED(40903, "已经申请过该任务", HttpStatus.CONFLICT),
    ALREADY_REVIEWED(40904, "已经评价过该任务", HttpStatus.CONFLICT),
    ALREADY_BLOCKED(40905, "已经拉黑该用户", HttpStatus.CONFLICT),
    ALREADY_REPORTED(40906, "你已举报过该内容", HttpStatus.CONFLICT),
    APPEAL_NOT_ALLOWED(40023, "当前不能申诉", HttpStatus.BAD_REQUEST),
    APPEAL_NOT_FOUND(40410, "申诉不存在", HttpStatus.NOT_FOUND),
    ALREADY_APPEALED(40911, "已提交过申诉", HttpStatus.CONFLICT),
    TASK_ALREADY_MATCHED(40908, "该任务已匹配代课者", HttpStatus.CONFLICT),
    CATALOG_DUPLICATE(40910, "名称或编码已存在", HttpStatus.CONFLICT),
    INTERNAL_ERROR(50000, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
