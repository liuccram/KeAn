package com.kean.support;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FieldCipherTypeHandler} 的纯单元测试：用 Mockito 顶替 JDBC 接口，
 * 不连数据库、不启 Spring 上下文，也不依赖运行环境的 {@code DATA_ENC_KEY}。
 */
@DisplayName("字段加密 TypeHandler")
class FieldCipherTypeHandlerTest {

    private static final String KEY_BASE64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    /** 固定密钥的实例，保证断言不受环境变量影响。 */
    private final FieldCipherTypeHandler handler = new FieldCipherTypeHandler(new FieldCipher(KEY_BASE64));

    @Test
    @DisplayName("写入加密、读取解密：经 JDBC 的存储值是密文，读回来是原文")
    void writeEncryptsAndReadDecrypts() throws Exception {
        String plain = "同学你好，这是私信正文 🙂 中文 emoji 都要过";

        // 写：捕获真正传给 JDBC 的字符串
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, plain, JdbcType.VARCHAR);
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(1), stored.capture());

        assertThat(stored.getValue())
                .as("落库的必须是 v1: 密文，而不是明文")
                .startsWith(FieldCipher.VERSION_PREFIX)
                .isNotEqualTo(plain);

        // 读：把刚刚落库的字符串交回 ResultSet / CallableStatement
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("content")).thenReturn(stored.getValue());
        when(rs.getString(3)).thenReturn(stored.getValue());
        assertThat(handler.getNullableResult(rs, "content")).isEqualTo(plain);
        assertThat(handler.getNullableResult(rs, 3)).isEqualTo(plain);

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(3)).thenReturn(stored.getValue());
        assertThat(handler.getNullableResult(cs, 3)).isEqualTo(plain);
    }

    @Test
    @DisplayName("读到存量明文时原样返回（旧数据不需要迁移）")
    void readsLegacyPlainTextAsIs() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("handle_remark")).thenReturn("旧版本写入的明文处理备注");

        assertThat(handler.getNullableResult(rs, "handle_remark")).isEqualTo("旧版本写入的明文处理备注");
    }

    @Test
    @DisplayName("列为 NULL 时返回 null，不抛异常")
    void nullColumnYieldsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        CallableStatement cs = mock(CallableStatement.class);

        assertThat(handler.getNullableResult(rs, "remark")).isNull();
        assertThat(handler.getNullableResult(rs, 1)).isNull();
        assertThat(handler.getNullableResult(cs, 1)).isNull();
    }

    @Test
    @DisplayName("未配置密钥的实例明文落库，与 FieldCipher 的透传行为一致")
    void handlerWithoutKeyWritesPlainText() throws Exception {
        FieldCipherTypeHandler plaintextHandler = new FieldCipherTypeHandler(new FieldCipher(null));
        PreparedStatement ps = mock(PreparedStatement.class);

        plaintextHandler.setNonNullParameter(ps, 1, "明文备注", JdbcType.VARCHAR);

        verify(ps).setString(1, "明文备注");
    }

    @Test
    @DisplayName("保留 public 无参构造器：MyBatis 靠它实例化处理器")
    void hasPublicNoArgConstructor() {
        assertThat(new FieldCipherTypeHandler()).isNotNull();
    }
}
