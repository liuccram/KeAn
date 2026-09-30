package com.kean.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminStatsMapper {

    @Select("""
            SELECT DATE(created_at) AS statDate, COUNT(*) AS cnt
            FROM sys_user
            WHERE deleted = 0 AND role = 'USER'
              AND created_at >= #{fromTime} AND created_at < #{toTime}
            GROUP BY DATE(created_at)
            ORDER BY statDate
            """)
    List<DateCountRow> userGrowth(@Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

    @Select("""
            SELECT DATE(created_at) AS statDate, COUNT(*) AS cnt
            FROM substitute_task
            WHERE deleted = 0
              AND created_at >= #{fromTime} AND created_at < #{toTime}
            GROUP BY DATE(created_at)
            ORDER BY statDate
            """)
    List<DateCountRow> taskPublished(@Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

    @Select("""
            SELECT DATE(updated_at) AS statDate, COUNT(*) AS cnt
            FROM substitute_task
            WHERE deleted = 0 AND status = 'COMPLETED'
              AND updated_at >= #{fromTime} AND updated_at < #{toTime}
            GROUP BY DATE(updated_at)
            ORDER BY statDate
            """)
    List<DateCountRow> taskCompleted(@Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

    @Select("""
            SELECT school_id AS schoolId, COUNT(*) AS cnt
            FROM substitute_task
            WHERE deleted = 0
              AND created_at >= #{fromTime} AND created_at < #{toTime}
            GROUP BY school_id
            ORDER BY cnt DESC
            LIMIT 10
            """)
    List<SchoolCountRow> schoolRanking(@Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

    @Select("""
            SELECT status AS status, COUNT(*) AS cnt
            FROM substitute_task
            WHERE deleted = 0
            GROUP BY status
            """)
    List<StatusCountRow> taskStatusCounts();

    @Select("""
            SELECT type AS type, COUNT(*) AS cnt
            FROM report
            WHERE created_at >= #{fromTime} AND created_at < #{toTime}
            GROUP BY type
            """)
    List<TypeCountRow> reportTypeCounts(@Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);

    @Select("""
            SELECT school_id AS schoolId, campus_id AS campusId, gender AS gender, COUNT(*) AS cnt
            FROM sys_user
            WHERE deleted = 0 AND role = 'USER'
              AND (#{schoolId} IS NULL OR school_id = #{schoolId})
              AND (#{campusId} IS NULL OR campus_id = #{campusId})
              AND (#{status} IS NULL OR #{status} = '' OR status = #{status})
            GROUP BY school_id, campus_id, gender
            """)
    List<GenderCountRow> genderBySchoolCampus(
            @Param("schoolId") Long schoolId,
            @Param("campusId") Long campusId,
            @Param("status") String status
    );

    @Select("""
            SELECT status AS status,
                   forbid_publish AS forbidPublish,
                   forbid_apply AS forbidApply,
                   muted AS muted,
                   COUNT(*) AS cnt
            FROM sys_user
            WHERE deleted = 0 AND role = 'USER'
            GROUP BY status, forbid_publish, forbid_apply, muted
            """)
    List<UserFlagCountRow> userStatusCounts();

    @Data
    class DateCountRow {
        private LocalDate statDate;
        private Long cnt;
    }

    @Data
    class SchoolCountRow {
        private Long schoolId;
        private Long cnt;
    }

    @Data
    class StatusCountRow {
        private String status;
        private Long cnt;
    }

    @Data
    class TypeCountRow {
        private String type;
        private Long cnt;
    }

    @Data
    class GenderCountRow {
        private Long schoolId;
        private Long campusId;
        private String gender;
        private Long cnt;
    }

    @Data
    class UserFlagCountRow {
        private String status;
        private Integer forbidPublish;
        private Integer forbidApply;
        private Integer muted;
        private Long cnt;
    }
}
