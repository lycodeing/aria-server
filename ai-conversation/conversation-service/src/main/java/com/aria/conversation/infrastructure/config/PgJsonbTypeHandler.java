package com.aria.conversation.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;

/**
 * PostgreSQL JSONB 列通用 TypeHandler。
 *
 * <p>MyBatis-Plus 内置的 {@code JacksonTypeHandler} 使用 {@code PreparedStatement#setString()}
 * 写入 JSON 字符串，但 PostgreSQL JDBC 驱动不会自动将 VARCHAR 转换为 JSONB（write 端），
 * 导致 {@code column is of type jsonb but expression is of type character varying} 错误。
 *
 * <p>本类改用 {@link PGobject}（type = "jsonb"）写入，解决该兼容性问题。
 * 子类通过传入 {@link TypeReference} 来支持泛型集合类型（如 {@code List<TimeRange>}）。
 *
 * @param <T> 映射的 Java 类型
 */
@Slf4j
public abstract class PgJsonbTypeHandler<T> extends BaseTypeHandler<T> {

    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final TypeReference<T> typeRef;

    protected PgJsonbTypeHandler(TypeReference<T> typeRef) {
        this.typeRef = typeRef;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pgo = new PGobject();
        pgo.setType("jsonb");
        try {
            pgo.setValue(MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("Failed to serialize object to JSONB", e);
        }
        ps.setObject(i, pgo);
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private T parse(String json) throws SQLException {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            throw new SQLException("Failed to deserialize JSONB to object", e);
        }
    }
}
