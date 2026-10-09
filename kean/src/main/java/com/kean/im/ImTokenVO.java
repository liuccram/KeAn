package com.kean.im;

/**
 * box-im 兼容 token 的接口返回体。
 *
 * <p>被包在课安统一的 {@code Result} 信封里返回，即
 * {@code {"code":0,"message":"成功","data":{"enabled":true,...}}}。</p>
 *
 * <p>{@code enabled=false} 时其余三个字段为 {@code null}（{@code Result} 上有
 * {@code @JsonInclude(NON_NULL)}，但那只作用于 {@code Result} 自身声明的字段，
 * 所以这里为 {@code null} 时仍会序列化成 {@code "accessToken":null}，属预期且无害）。</p>
 *
 * @param enabled      IM 通道是否已启用（等价于后端是否配好了 {@code IM_JWT_SECRET}）
 * @param accessToken  发给 im-server 的 {@code {cmd:0}} 登录帧里的 accessToken
 * @param refreshToken 双 token 里的 refreshToken；im-platform 未部署时无服务端消费方
 * @param expireAt     accessToken 的绝对过期时间（epoch 毫秒），由 {@code expireIn=1800s} 推出
 */
public record ImTokenVO(
        boolean enabled,
        String accessToken,
        String refreshToken,
        Long expireAt
) {

    /** IM 未启用时的空壳：只有 {@code enabled=false} 是有意义的信息。 */
    public static ImTokenVO disabled() {
        return new ImTokenVO(false, null, null, null);
    }
}
