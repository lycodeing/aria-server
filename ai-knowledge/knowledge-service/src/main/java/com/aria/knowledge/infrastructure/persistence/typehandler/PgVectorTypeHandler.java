package com.aria.knowledge.infrastructure.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * pgvector 字符串 ↔ PG vector 列 的 MyBatis TypeHandler。
 *
 * <p>背景（Bug-P0-016）：knowledge_chunk.content_vector 列是 PostgreSQL 扩展类型 vector(1024)，
 * 普通的 JDBC setString 会让驱动按 character varying 推断类型，导致：
 * {@code ERROR: column "content_vector" is of type vector but expression is of type character varying}。
 *
 * <p>解决：包装成 {@link PGobject}（type=vector，value=`[0.1,0.2,...]` 字符串）写入，
 * PostgreSQL 服务端会自动解析为 vector 类型。读取时直接拿 PGobject.value 即可。
 *
 * <p>使用方式（在实体字段上标注）：
 * <pre>
 * &#64;TableField(typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
 * private String contentVector;
 * </pre>
 * 并在 {@code @TableName} 上启用 {@code autoResultMap = true}，否则 mybatis-plus 不会应用 typeHandler。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class PgVectorTypeHandler extends BaseTypeHandler<String> {

    /** PG 扩展类型名 */
    private static final String PG_TYPE_VECTOR = "vector";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType(PG_TYPE_VECTOR);
        obj.setValue(parameter);
        ps.setObject(i, obj);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object value = cs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }
}
