# MyBatis-Plus Mapper 编码规范

## 核心规则：LambdaWrapper 替代魔法字符串 SQL

所有 MyBatis-Plus 单表 CRUD 操作，**必须使用 LambdaUpdateWrapper / LambdaQueryWrapper**，
禁止在 Mapper 接口上写 `@Update` / `@Select` 注解 SQL（魔法字符串）。

### ✅ 正确写法

```java
// 更新
update(Wrappers.lambdaUpdate(XxxEntity.class)
    .set(XxxEntity::getStatus,    SessionStatus.ACTIVE.getValue())
    .set(XxxEntity::getUpdatedAt, acceptedAt)
    .eq(XxxEntity::getSessionId,  sessionId)
    .eq(XxxEntity::getStatus,     SessionStatus.WAITING.getValue())   // 条件：乐观检查
);

// 查询列表
selectList(Wrappers.lambdaQuery(XxxEntity.class)
    .eq(XxxEntity::getStatus, SessionStatus.ACTIVE.getValue())
    .orderByAsc(XxxEntity::getCreatedAt)
);

// 计数
selectCount(Wrappers.lambdaQuery(XxxEntity.class)
    .eq(XxxEntity::getSessionId, sessionId)
    .eq(XxxEntity::getStatus,    SessionStatus.ACTIVE.getValue())
) > 0;
```

### ❌ 禁止写法

```java
// ❌ 禁止：注解魔法字符串
@Update("UPDATE cs_xxx SET status = 'ACTIVE' WHERE session_id = #{sessionId} AND status = 'WAITING'")
int activateBySessionId(@Param("sessionId") String sessionId);

// ❌ 禁止：@Select 字符串拼接
@Select("SELECT * FROM cs_xxx WHERE status = 'ACTIVE' ORDER BY started_at")
List<XxxEntity> selectActiveList();
```

### 规则

1. **Mapper 接口** 只继承 `BaseMapper<Entity>`，业务方法用 `default` 方法实现，内部调用 `update()` / `selectList()` 等 BaseMapper 方法
2. **枚举字段** 通过 `SessionStatus.ACTIVE.getValue()` 传值，不直接传 `"ACTIVE"` 字符串
3. **Repository 层** 封装 Mapper 调用，对上层屏蔽 MyBatis-Plus API 细节
4. **复杂多表 JOIN / 原生 SQL** 才使用 XML Mapper，单表操作一律 Wrapper API
5. **乐观检查**（防重复操作）通过 `.eq(Entity::getStatus, xxx)` 加条件，而非业务层 if 判断

### 最佳实践示例

```java
// 关闭：仅当非 CLOSED 时更新，幂等操作
default int closeBySessionId(String sessionId, OffsetDateTime endedAt) {
    return update(Wrappers.lambdaUpdate(XxxEntity.class)
            .set(XxxEntity::getStatus,    StatusEnum.CLOSED.getValue())
            .set(XxxEntity::getEndedAt,   endedAt)
            .set(XxxEntity::getUpdatedAt, endedAt)
            .eq(XxxEntity::getSessionId,  sessionId)
            .ne(XxxEntity::getStatus,     StatusEnum.CLOSED.getValue())  // 幂等：已关闭则 no-op
    );
}
```
