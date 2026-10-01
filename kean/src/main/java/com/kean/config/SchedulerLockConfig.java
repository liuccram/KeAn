package com.kean.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 定时任务的分布式锁。
 *
 * <p>单实例时这层不起作用；但只要出现第二个实例（横向扩容，或滚动发布时新旧实例重叠），
 * 同一个 {@code @Scheduled} 就会在两个进程里各跑一遍 —— 重复发通知、completedCount 重复累加。
 * 加了 {@code @SchedulerLock} 之后，同一时刻只有一个实例真正执行，其余实例跳过本轮。
 *
 * <p>{@code usingDbTime()} 让锁的时间以数据库时钟为准。多实例之间系统时间难免有偏差，
 * 用各自的应用时钟会让 {@code lockAtMostFor} 的判定失真。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
