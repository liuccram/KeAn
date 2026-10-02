package com.kean.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 把 {@link FieldCipher} 接到 MyBatis 上的 {@code String} 字段处理器：写入时加密，读取时解密。
 *
 * <h2>用法</h2>
 * <p>挂在实体字段上，<b>同时</b>必须在实体类上加 {@code @TableName(autoResultMap = true)}：</p>
 * <pre>
 * &#64;TableName(value = "review", autoResultMap = true)
 * public class Review {
 *     &#64;TableField(typeHandler = FieldCipherTypeHandler.class)
 *     private String content;
 * }
 * </pre>
 * <p>只加 {@code @TableField(typeHandler = ...)} 对<b>查询不生效</b>：MyBatis-Plus 只有在
 * {@code autoResultMap = true} 时才会为该实体生成带 typeHandler 的 resultMap，否则查询走
 * 自动映射、读出来的是密文。写入路径（insert / updateById）不依赖它，一直生效。</p>
 *
 * <h2>刻意不加 @MappedTypes</h2>
 * <p>本类只在实体上用 {@code typeHandler = ...} 显式引用。若标注 {@code @MappedTypes(String.class)}
 * 并且将来有人开启 {@code mybatis-plus.type-handlers-package} 扫描，它就会变成全局 String
 * 处理器，把<b>所有</b>字符串列都加密 —— 那是一次静默的、灾难性的扩大化。因此这里不标注。</p>
 *
 * <h2>绕不过去的写路径</h2>
 * <p>{@code LambdaUpdateWrapper.set(...)} / {@code UpdateWrapper.set(...)} 直接拼 SQL，不走
 * TypeHandler，会以明文写入；{@code eq/like/in} 等按字段检索同样不经过本类，
 * 加密后必然失配。给某个字段挂上本处理器前，请先确认没有任何按它检索的用法。</p>
 */
public class FieldCipherTypeHandler extends BaseTypeHandler<String> {

    private final FieldCipher cipher;

    /**
     * MyBatis 用无参构造器实例化（{@code TypeHandlerRegistry.getInstance} 先找
     * {@code (Class)} 构造器，找不到就回退到无参构造器），所以这个构造器必须保留为 public。
     */
    public FieldCipherTypeHandler() {
        this(FieldCipher.global());
    }

    /** 供单元测试注入固定密钥的实例，避免测试结果随运行环境的 DATA_ENC_KEY 变化。 */
    FieldCipherTypeHandler(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, cipher.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return cipher.decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return cipher.decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cipher.decrypt(cs.getString(columnIndex));
    }
}
