package com.kean.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注销账号。必须提交当前密码，服务端用 {@code PasswordEncoder} 校验，
 * 校验不通过不做任何修改。
 *
 * <p>不可逆：成功后该账号被匿名化 + 逻辑删除，所有设备的登录态立刻失效。
 * 语义与执行顺序见 {@code AuthServiceImpl#deleteAccount} 与 docs/api/auth.md。
 */
public record DeleteAccountRequest(
        @NotBlank(message = "请填写当前密码")
        String password
) {
}
