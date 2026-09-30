package com.kean.service;

public interface OperationLogService {

    void record(String operationType, String targetType, Object targetId, String description);
}
