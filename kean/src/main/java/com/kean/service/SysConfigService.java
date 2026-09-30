package com.kean.service;

public interface SysConfigService {

    boolean registerEnabled();

    boolean imageUploadEnabled();

    String get(String key);

    void set(String key, String value);
}
