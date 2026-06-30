# Auth Service 迁移计划：ai-dev-platform → ai-customerservice

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将用户/角色/菜单/权限认证模块从 `ai-dev-platform/ai-dev-auth` 迁移到 `ai-customerservice-backend/ai-auth`，让客服系统完全自治，不依赖平台服务。

**Architecture:** 新建 `ai-auth/auth-service` Maven 模块（端口 8083），只迁移客服系统所需的核心认证功能（用户/角色/菜单/部门/数据权限），去掉与客服无关的 Org/Team/Member/OAuth2/LDAP/MFA/ApiKey 等平台功能。包名从 `com.aidevplatform.auth` 改为 `com.aidevplatform.customerservice.auth`。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Sa-Token 1.39.0, MyBatis-Plus 3.5.7, PostgreSQL, Flyway, Lombok

---

## 迁移范围说明

### ✅ 迁移（客服系统必需）
- 用户表 `sys_user` + `UserDO/UserMapper/UserRepositoryImpl`
- 角色表 `sys_role/sys_user_role/sys_role_permission` + `RoleDO/RoleMapper`
- 菜单表 `sys_menu/sys_role_menu` + `MenuDO/MenuMapper/MenuMapper.xml`
- 部门表 `sys_dept/sys_user_dept/sys_role_data_scope` + `DeptDO/DeptMapper`
- 权限表 `sys_permission` + `PermissionDO/PermissionMapper`
- 所有 Application Service（Auth/User/Role/Menu/UserInfo）
- Controller（Auth/User/Role/Menu/UserInfo/Internal）
- Sa-Token 集成（StpInterfaceImpl）
- DataScopeAspect（数据权限切面）
- 所有 VO/Command/DO 类

### ❌ 不迁移（平台功能，留在 ai-dev-platform）
- Org/Team/Member 组织架构
- OAuth2/LDAP/SSO 第三方登录
- MFA 多因素认证
- ApiKey 管理
- AuditLog 审计日志（后续单独实现）
- SSH Key / GPG Key

---

## Task 1：创建 Maven 模块骨架

**Files:**
- Create: `ai-customerservice-backend/ai-auth/pom.xml`
- Create: `ai-customerservice-backend/ai-auth/auth-service/pom.xml`
- Modify: `ai-customerservice-backend/pom.xml`（添加 ai-auth 模块）

- [ ] **Step 1：在根 pom.xml 注册 ai-auth 模块**

```xml
<modules>
    <module>ai-knowledge</module>
    <module>ai-conversation</module>
    <module>ai-auth</module>   <!-- 新增 -->
</modules>
```

- [ ] **Step 2：创建 ai-auth 父 pom**

```xml
<!-- ai-auth/pom.xml -->
<project>
  <parent>
    <groupId>com.aidevplatform</groupId>
    <artifactId>ai-customerservice-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>ai-auth</artifactId>
  <packaging>pom</packaging>
  <name>AI Auth - 客服认证模块</name>
  <modules><module>auth-service</module></modules>
</project>
```

- [ ] **Step 3：创建 auth-service pom（端口 8083）**

依赖：common-core、common-web、spring-boot-starter-web、spring-boot-starter-data-redis、
sa-token-spring-boot3-starter、sa-token-jwt、mybatis-plus-spring-boot3-starter、
postgresql、flyway-core、spring-boot-starter-aop、lombok

- [ ] **Step 4：编译验证骨架**

```bash
cd ai-customerservice-backend
mvn compile -pl ai-auth/auth-service -am
# 期望：BUILD SUCCESS（空模块）
```

---

## Task 2：SQL 迁移文件（合并整合）

**Files:**
- Create: `ai-auth/auth-service/src/main/resources/db/migration/V1__init_cs_auth_schema.sql`
- Create: `ai-auth/auth-service/src/main/resources/db/migration/V2__seed_menu_data.sql`
- Create: `ai-auth/auth-service/src/main/resources/db/migration/V3__seed_test_users.sql`

- [ ] **Step 1：合并建表 SQL（V1）**

将原 auth-service 的 V1/V9 合并为一个文件，包含：
- `cs_auth` schema（独立 schema，不与平台 auth 冲突）
- `sys_user`、`sys_role`、`sys_user_role`
- `sys_permission`、`sys_role_permission`
- `sys_menu`、`sys_role_menu`
- `sys_dept`、`sys_user_dept`、`sys_role_data_scope`

Schema 名改为 `cs_auth`（避免与 ai-dev-platform 的 `auth` schema 冲突）。

- [ ] **Step 2：菜单种子数据（V2）**

从原 V10__seed_menu_data.sql 复制，schema 前缀改为 `cs_auth`。

- [ ] **Step 3：测试用户种子数据（V3）**

从原 V11__seed_test_users.sql 复制，schema 前缀改为 `cs_auth`。

---

## Task 3：迁移领域层

**Files:**（包名统一改为 `com.aidevplatform.customerservice.auth`）
- Create: `domain/datascope/DataScope.java`
- Create: `domain/datascope/DataScopeContext.java`
- Create: `domain/model/user/` 下 7 个文件
- Create: `domain/model/role/Role.java`、`Permission.java`
- Create: `domain/repository/IUserRepository.java`
- Create: `domain/service/LoginAttemptPolicy.java`、`PasswordPolicyChecker.java`

- [ ] **Step 1：复制所有文件，修改 package 声明**

```bash
# 批量替换包名
find src/main/java -name "*.java" \
  -exec sed -i 's/com\.aidevplatform\.auth/com.aidevplatform.customerservice.auth/g' {} \;
```

- [ ] **Step 2：验证编译**

```bash
mvn compile -pl ai-auth/auth-service -am
# 期望：领域层 BUILD SUCCESS
```

---

## Task 4：迁移基础设施层

**Files:**
- Create: `infrastructure/aspect/DataScopeAspect.java`（已重构到 infrastructure）
- Create: `infrastructure/auth/StpInterfaceImpl.java`
- Create: `infrastructure/persistence/user/{UserDO,UserMapper,UserRepositoryImpl}.java`
- Create: `infrastructure/persistence/role/{RoleDO,RoleMapper,PermissionDO,PermissionMapper}.java`
- Create: `infrastructure/persistence/menu/{MenuDO,MenuMapper}.java`
- Create: `infrastructure/persistence/dept/{DeptDO,DeptMapper}.java`
- Create: `infrastructure/security/password/BCryptPasswordHasher.java`
- Create: `resources/mapper/MenuMapper.xml`（所有 SQL 在 XML，无 @Select 注解）
- Create: `resources/mapper/RoleMapper.xml`

- [ ] **Step 1：复制文件，修改 package + schema 名**

所有 SQL 中 `auth.sys_` 改为 `cs_auth.sys_`。

- [ ] **Step 2：DeptMapper 补充 XML**

将 `DeptMapper` 中的 `@Select`/`@Insert`/`@Delete` 注解 SQL 全部迁移到 `DeptMapper.xml`：

```xml
<!-- 查询用户所属部门 ID 列表 -->
<select id="findDeptIdsByUserId" resultType="java.lang.Long">
    SELECT d.id FROM cs_auth.sys_dept d
    INNER JOIN cs_auth.sys_user_dept ud ON d.id = ud.dept_id
    WHERE ud.user_id = #{userId}
</select>

<!-- 查询部门子树所有 ID（包含自身） -->
<select id="findSubtreeDeptIds" resultType="java.lang.Long">
    WITH RECURSIVE subtree AS (
        SELECT id FROM cs_auth.sys_dept WHERE id = #{deptId}
        UNION ALL
        SELECT d.id FROM cs_auth.sys_dept d
        INNER JOIN subtree s ON d.parent_id = s.id
    )
    SELECT id FROM subtree
</select>

<!-- 查询角色自定义部门列表 -->
<select id="findCustomDeptIdsByRoleId" resultType="java.lang.Long">
    SELECT dept_id FROM cs_auth.sys_role_data_scope
    WHERE role_id = #{roleId} AND scope_type = 'CUSTOM_DEPT'
</select>
```

- [ ] **Step 3：验证编译**

```bash
mvn compile -pl ai-auth/auth-service -am
# 期望：BUILD SUCCESS
```

---

## Task 5：迁移应用服务层

**Files:**
- Create: `application/command/LoginCommand.java`
- Create: `application/service/AuthApplicationService.java`
- Create: `application/service/MenuApplicationService.java`
- Create: `application/service/RoleApplicationService.java`
- Create: `application/service/UserApplicationService.java`
- Create: `application/service/UserInfoApplicationService.java`

- [ ] **Step 1：复制 5 个 Service，修改 package**

重点确认：
- 无 `KnowledgeDocMapper` 等跨服务依赖
- 无 Org/Team/Member 相关方法调用
- `UserInfoApplicationService` 中 avatar 改为读配置（`${cs.auth.default-avatar:...}`）

- [ ] **Step 2：验证编译**

```bash
mvn compile -pl ai-auth/auth-service -am
# 期望：BUILD SUCCESS
```

---

## Task 6：迁移接口层（Controller + VO）

**Files:**
- Create: `interfaces/rest/{Auth,User,Role,Menu,UserInfo,InternalAuth}Controller.java`
- Create: `interfaces/rest/vo/` 下全部 VO 类（约 20 个）

- [ ] **Step 1：复制 6 个 Controller，修改 package**

API 路径保持不变（`/api/v1/auth`、`/api/v1/users` 等），无需修改前端。

- [ ] **Step 2：复制全部 VO 类**

包括：`LoginResultVO`、`PageVO`、`RoleVO`、`MenuVO`、`RouteMetaVO`、
`UserVO`、`PermissionTreeVO`、`PermissionItemVO`、`AssignPermissionsVO`、
`TokenRefreshVO`、`MeVO`、`SessionVO`

- [ ] **Step 3：创建启动类**

```java
package com.aidevplatform.customerservice.auth;

@SpringBootApplication
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
```

- [ ] **Step 4：验证编译**

```bash
mvn compile -pl ai-auth/auth-service -am
# 期望：BUILD SUCCESS
```

---

## Task 7：配置文件

**Files:**
- Create: `resources/application.yml`

- [ ] **Step 1：创建 application.yml**

```yaml
server:
  port: 8083          # 不与平台 auth-service(8090) 冲突

spring:
  application:
    name: cs-auth-service
  datasource:
    url: jdbc:postgresql://localhost:5432/ai_customerservice
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  flyway:
    schemas: cs_auth
    locations: classpath:db/migration
    default-schema: cs_auth
    create-schemas: true
    validate-on-migrate: false
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  mapper-locations: classpath:mapper/*.xml
  global-config:
    db-config:
      logic-delete-field: deletedAt
      logic-delete-value: "NOW()"
      logic-not-delete-value: "null"

sa-token:
  token-name: Authorization
  timeout: 28800
  token-prefix: "Bearer"
  is-read-header: true
  is-read-cookie: false
  max-login-count: 5
  jwt-secret-key: ${JWT_SECRET_KEY:cs-auth-dev-secret-key-change-in-prod}

cs:
  auth:
    default-avatar: "https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v2.webp"
```

---

## Task 8：更新前端代理

**Files:**
- Modify: `vue-vben-admin/apps/web-antd/vite.config.ts`
- Create: `vue-vben-admin/apps/backend-mock/api/menus/me.ts`（已创建）

- [ ] **Step 1：更新 vite.config.ts 代理 `/api` 指向新 auth-service**

```ts
'/api': {
  changeOrigin: true,
  rewrite: (path) => path.replace(/^\/api/, '/api/v1'),
  target: 'http://localhost:8083',   // 改为客服自己的 auth-service
  ws: true,
},
```

- [ ] **Step 2：验证前端登录流程**

启动 `cs-auth-service:8083`，打开 `http://localhost:5668`，
用 `superadmin / Test@123456` 登录，确认菜单正确加载。

---

## Task 9：整体验证

- [ ] **Step 1：全量编译**

```bash
cd ai-customerservice-backend
mvn clean compile
# 期望：ai-knowledge、ai-conversation、ai-auth 三个模块全部 BUILD SUCCESS
```

- [ ] **Step 2：启动 cs-auth-service**

```bash
cd ai-auth/auth-service
mvn spring-boot:run
# 期望：端口 8083 启动，Flyway 执行 V1/V2/V3，控制台无 ERROR
```

- [ ] **Step 3：接口冒烟测试**

```bash
# 登录
curl -s -X POST http://localhost:8083/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"superadmin","password":"Test@123456"}' | jq '.data.tokenValue'

# 用返回的 token 获取菜单
TOKEN=<上一步的token>
curl -s http://localhost:8083/api/v1/menus/me \
  -H "Authorization: Bearer $TOKEN" | jq '.data | length'
# 期望：返回菜单数组长度 > 0
```

- [ ] **Step 4：多角色 UI 测试**

依次登录 superadmin / kfmanager / kfstaff，确认菜单权限分隔与之前一致。

---

## 注意事项

1. **schema 隔离**：新服务使用 `cs_auth` schema，原 `ai-dev-platform` 的 `auth` schema 保持不变，两者完全独立。
2. **数据库**：建议新服务使用 `ai_customerservice` 数据库，与平台库分离。
3. **密码兼容**：BCrypt hash 算法不变，测试账号密码 `Test@123456` 直接复用。
4. **迁移完成后**：可选择性地将 `ai-dev-platform` 中与客服相关的 Flyway migration 标记为已过时，但无需删除（保持平台服务独立可运行）。


```
ai-customerservice-backend/
└── ai-auth/
    ├── pom.xml
    └── auth-service/
        ├── pom.xml
        └── src/main/
            ├── java/com/aidevplatform/customerservice/auth/
            │   ├── AuthApplication.java
            │   ├── application/
            │   │   ├── command/LoginCommand.java
            │   │   └── service/
            │   │       ├── AuthApplicationService.java
            │   │       ├── MenuApplicationService.java
            │   │       ├── RoleApplicationService.java
            │   │       ├── UserApplicationService.java
            │   │       └── UserInfoApplicationService.java
            │   ├── domain/
            │   │   ├── datascope/DataScope.java
            │   │   ├── datascope/DataScopeContext.java
            │   │   ├── model/user/{User,UserId,UserStatus,Password,PasswordHasher,PasswordPolicy,AuthProvider}.java
            │   │   ├── model/role/{Role,Permission}.java
            │   │   ├── repository/IUserRepository.java
            │   │   └── service/{LoginAttemptPolicy,PasswordPolicyChecker}.java
            │   ├── infrastructure/
            │   │   ├── aspect/DataScopeAspect.java
            │   │   ├── auth/{StpInterfaceImpl,SsoCookieWriter}.java
            │   │   ├── persistence/
            │   │   │   ├── user/{UserDO,UserMapper,UserRepositoryImpl}.java
            │   │   │   ├── role/{RoleDO,RoleMapper,PermissionDO,PermissionMapper}.java
            │   │   │   ├── menu/{MenuDO,MenuMapper}.java
            │   │   │   └── dept/{DeptDO,DeptMapper}.java
            │   │   ├── security/password/BCryptPasswordHasher.java
            │   │   └── seed/PermissionSeedRunner.java
            │   └── interfaces/
            │       ├── rest/
            │       │   ├── AuthController.java
            │       │   ├── UserController.java
            │       │   ├── RoleController.java
            │       │   ├── MenuController.java
            │       │   ├── UserInfoController.java
            │       │   └── InternalAuthController.java
            │       └── rest/vo/（所有 VO 类）
            └── resources/
                ├── application.yml           # 端口 8083
                ├── mapper/MenuMapper.xml
                ├── mapper/RoleMapper.xml
                └── db/migration/
                    ├── V1__init_auth_schema.sql
                    ├── V2__seed_menu_data.sql
                    └── V3__seed_test_users.sql
```
