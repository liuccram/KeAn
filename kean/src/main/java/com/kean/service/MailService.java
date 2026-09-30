package com.kean.service;

public interface MailService {

    boolean ready();

    void sendVerifyCode(String to, String scene, String code);
}
