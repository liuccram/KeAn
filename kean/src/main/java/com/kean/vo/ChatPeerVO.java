package com.kean.vo;

public record ChatPeerVO(
        Long id,
        String nickname,
        String avatarUrl,
        String campusName
) {
}
