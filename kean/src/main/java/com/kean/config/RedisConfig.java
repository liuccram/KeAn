package com.kean.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password
    ) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        if (StringUtils.hasText(password)) {
            standalone.setPassword(password);
        }
        return new LettuceConnectionFactory(standalone);
    }

    /**
     * <b>只服务 IM 封禁键</b>的 {@code RedisTemplate<String,Object>}（阶段 A 新增，见
     * {@code docs/ops/im-platform-migration.md} §2.4 / §10 第 1 处改动）。
     *
     * <h2>为什么必须新增一个「专用」模板，而不是改现有配置</h2>
     * <p>kean 全站写 Redis 用的是 {@code StringRedisTemplate}（值一律是字符串），
     * 只有 box-im 的 {@code im:user:denied:{userId}} 这一枚键要求「值必须是数字」
     * —— 所以这里只补一个<b>只给那一枚键用</b>的模板：<b>不动</b>
     * {@code redisConnectionFactory}，<b>不动</b>任何既有 Bean，
     * <b>不设 {@code @Primary}</b>，因此<b>全站其余键的序列化与现在完全一致</b>。</p>
     *
     * <h2>为什么序列化配置逐项照抄 box-im（im-platform）</h2>
     * <p>im-platform 的 {@code AuthInterceptor} 读这枚键时写的是
     * {@code Integer type = (Integer) redisTemplate.opsForValue().get(...)} ——
     * 这是<b>强转</b>，不是转换：值反序列化出来必须<b>就是一个 {@code Integer} 实例</b>，
     * 否则每个 REST 调用都抛 {@code ClassCastException}（500）。</p>
     * <p>而 {@code Integer} 是 <b>final</b> 类，在 box-im 那份
     * {@code enableDefaultTyping(NON_FINAL, PROPERTY)} 的 ObjectMapper 下<b>不会带 {@code @class}</b>，
     * 序列化结果就是裸数字 {@code 1}；反序列化时按 {@code Object.class} 读，Jackson 得到
     * {@code Integer} ⇒ 强转成功。这正是上游 {@code UserBannedConsumerTask} 写出来的形状。</p>
     * <p>所以这里的 ObjectMapper 配置（可见性 / JavaTimeModule / NON_NULL /
     * {@code activateDefaultTyping(NON_FINAL, PROPERTY)} / 忽略未知字段）与
     * upstream {@code com.bx.implatform.config.RedisConfig#jackson2JsonRedisSerializer()}
     * <b>逐条对齐</b>，键序列化同样是 {@code StringRedisSerializer}。
     * 下游若按 {@code redis-cli OBJECT ENCODING im:user:denied:<id>} 看，期望是 {@code "int"}。</p>
     *
     * <h2>上游的序列化配置为什么用 {@code activateDefaultTyping}</h2>
     * <p>upstream（Spring Boot 2.x / Jackson 2.13）写的是已废弃的
     * {@code enableDefaultTyping(NON_FINAL, As.PROPERTY)}；本项目的 Spring Boot 3.3
     * （Jackson 2.17）改用它的<b>等价替代</b>
     * {@code activateDefaultTyping(LaissezFaireSubTypeValidator.instance, NON_FINAL, As.PROPERTY)}
     * —— 后者就是前者源码里的内部实现（{@code enableDefaultTyping} 直接委托给它），
     * 且 {@code LaissezFaireSubTypeValidator} 正是废弃方法内置的校验器，
     * 因此<b>输出字节与上游一致</b>，同时避开编译期弃用告警。</p>
     *
     * <h2>⚠️ 为什么序列化器类与构造器也与 upstream 完全一样</h2>
     * <p>这里用的是 {@code Jackson2JsonRedisSerializer<Object>}（upstream 用的就是它，
     * 不是 {@code GenericJackson2JsonRedisSerializer}）—— <b>必须同款</b>：
     * 换成别的实现会改变反序列化时「读成什么类型」，
     * 而这里的判定标准只有一个：<b>im-platform 读出来必须是 {@code Integer}</b>。</p>
     * <p>它的 {@code (ObjectMapper, Class)} 构造器在 Spring Data Redis 3.x 已标记弃用
     * （替代品是不带类型的 {@code (ObjectMapper)} 重载），但<b>弃用只是编译告警、不影响行为</b>，
     * 而「与上游同款」在这里比「消灭一条告警」重要得多 ——
     * 所以刻意保持同款，并在 {@code ObjectMapper} 上把类型信息补齐（见上）。</p>
     */
    @Bean("imDeniedRedisTemplate")
    public RedisTemplate<String, Object> imDeniedRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(imDeniedValueSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(imDeniedValueSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 与 {@link #imDeniedRedisTemplate} 配套的值序列化器；<b>刻意不暴露成 Bean</b>
     * （不注册进容器 ⇒ 不可能被别的组件按类型注入，也就不可能影响全站序列化）。
     *
     * @see #imDeniedRedisTemplate 上面关于「为什么逐条对齐 box-im」的说明
     */
    private Jackson2JsonRedisSerializer<Object> imDeniedValueSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 解决 jackson2 无法反序列化 LocalDateTime 的问题（与 upstream 一致）
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.registerModule(new JavaTimeModule());
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // ⚠️ 必须与 box-im 的 RedisConfig 等价：Integer 是 final 类 ⇒ 写成裸数字 1（不带 @class）
        om.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new Jackson2JsonRedisSerializer<>(om, Object.class);
    }
}
