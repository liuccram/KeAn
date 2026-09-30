package com.kean.vo;

public record SmsSendVO(boolean sent, String debugCode, String channel) {
}
