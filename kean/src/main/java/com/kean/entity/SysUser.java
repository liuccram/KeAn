package com.kean.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String role;

    private String username;

    private String phone;

    private String email;

    private String passwordHash;

    private String nickname;

    private String gender;

    private String avatarUrl;

    private String coverUrl;

    private Long schoolId;

    private Long campusId;

    /** 用户手输校区（选填，最长 50 字）。展示优先用它，为空才回退到 campusId 关联出的旧校区名。 */
    private String campusText;

    private Integer schoolChangeCount;

    private Integer completedCount;

    private BigDecimal ratingAvg;

    private Integer ratingCount;

    private Integer publishCompletedCount;

    private BigDecimal publishRatingAvg;

    private Integer publishRatingCount;

    private BigDecimal applyRatingAvg;

    private Integer applyRatingCount;

    private Integer cancelledCount;

    private Integer reportedCount;

    private String status;

    private Integer forbidPublish;

    private LocalDateTime forbidPublishUntil;

    private Integer forbidApply;

    private LocalDateTime forbidApplyUntil;

    private Integer muted;

    private Integer mustChangePassword;

    private LocalDateTime mutedUntil;

    /**
     * 1 = 隐私账号。开启后隐藏完整主页的统计字段与可发现性
     * （其他用户不能主动发起新的私信，403 / 40300，仅在新建会话时校验；已有会话不受影响）；
     * <b>头像与昵称仍对外公开</b>。完整语义见 docs/api/auth.md 的「隐私账号语义」。
     */
    private Integer privateAccount;

    /**
     * 1 = 仅允许一台设备在线。默认 0 = 关闭。
     *
     * <p>⚠️ <b>本列现在只是「用户的意愿」，不再单独决定行为</b>：顶号还受一道全局配置约束
     * —— {@code kean.security.single-device.enabled}（环境变量
     * {@code KEAN_SECURITY_SINGLE_DEVICE_ENABLED}），<b>默认 {@code false}</b>。
     * 全局关闭时这一列<b>被完全忽略</b>（连读都不读），多端可同时在线。
     * 恢复方式见 {@code docs/ops/security-hardening.md}。</p>
     *
     * <p>全局开关打开后：1 = 每次登录成功都会把该用户<b>其他</b>登录态的 jti 拉黑
     * （当前设备除外），被踢的设备下一次请求就会收到 40102，客户端提示后回到登录页。</p>
     *
     * <p>列本身<b>不动</b>（仍是 TINYINT NOT NULL DEFAULT 0）：不做迁移、不删列，
     * 将来要恢复单设备限制只需改环境变量。</p>
     */
    private Integer singleDevice;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
