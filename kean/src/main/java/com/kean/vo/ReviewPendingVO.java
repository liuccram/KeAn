package com.kean.vo;

public record ReviewPendingVO(
        Long taskId,
        String courseName,
        String peerNickname,
        String role
) {
}
