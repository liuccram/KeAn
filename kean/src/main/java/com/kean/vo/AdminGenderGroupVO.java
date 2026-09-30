package com.kean.vo;

public record AdminGenderGroupVO(
        Long schoolId,
        String schoolName,
        Long campusId,
        String campusName,
        long male,
        long female,
        long unknown,
        long total
) {
}
