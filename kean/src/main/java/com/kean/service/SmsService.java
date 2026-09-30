package com.kean.service;

import com.kean.vo.SmsSendVO;

public interface SmsService {

    SmsSendVO send(String target, String scene);

    void verifyAndConsume(String target, String scene, String code);
}
