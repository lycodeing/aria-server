# auth-service 接口测试用例

服务地址（本地网关）：`https://localhost/auth`
默认密码：`Test@123456`（种子账号：superadmin / kfmanager / kfstaff）

字段说明：`ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注`

## 1. 登录（POST /api/v1/auth/login）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-001 | 正确账密登录成功 | superadmin 处于 ACTIVE 状态 | POST /login `{username:superadmin, password:Test@123456}` | HTTP 200，`data.tokenValue` 非空，含 roles/permissions/userId/displayName | P0 | |
| AUTH-002 | 密码错误被拒 | 同上 | POST /login 密码传错 | 非 200 业务码，message="用户名或密码错误" | P0 | |
| AUTH-003 | 用户名不存在与密码错误返回一致错误 | - | 分别用不存在用户名、正确用户名+错误密码各调一次 | 两次响应的业务码与 message 完全一致（防用户名枚举） | P0 | 安全相关，建议断言 code+message 双一致 |
| AUTH-004 | 同 IP 每分钟超 10 次登录被限流 | - | 同一来源 IP 连续调用 login 11 次（可用错误密码） | 第 11 次返回 42900 "请求过于频繁，请稍后再试" | P1 | 依赖限流窗口重置，需等待或用独立测试IP header |
| AUTH-005 | 10 分钟内失败 ≥20 次封禁 IP 5 分钟 | - | 连续 20 次错误登录 | 之后请求直接被拒（含用正确密码也会被拒） | P2 | 耗时长，标记 slow，可选跳过 |
| AUTH-006 | 连续失败 5 次锁定账号 30 分钟 | 临时测试账号，初始 ACTIVE | 连续 5 次错误密码登录 | 第 5 次后账号状态变 LOCKED，`lockedUntil`≈now+30min；随后正确密码登录也被拒 AUTH_ACCOUNT_LOCKED | P0 | |
| AUTH-007 | 连续失败 4 次不触发锁定（边界） | 同上，独立测试账号 | 连续 4 次错误密码登录 | 第 4 次仍返回普通密码错误，账号状态仍 ACTIVE | P1 | 边界值验证，需与 AUTH-006 用不同账号避免互相干扰 |
| AUTH-008 | 已禁用账号登录被拒 | 账号 status=DISABLED | POST /login 正确密码 | 非 200，AUTH_ACCOUNT_DISABLED "账号已被禁用" | P0 | |
| AUTH-009 | 锁定到期后自动解锁并登录成功 | 账号 LOCKED 且 lockedUntil 已过期（可用短锁定时间的测试配置，或等待） | POST /login 正确密码 | 登录成功，返回 200；账号 status 恢复 ACTIVE，loginFailCount 清零 | P2 | 依赖时间流逝，建议用可配置的短锁定窗口环境跑，否则标记手动/跳过 |
| AUTH-010 | 密码过期登录成功但需强制改密 | 账号 passwordChangedAt 超过 90 天前（需种子数据或直接改库） | POST /login 正确密码 | HTTP 200 登录成功，`data.mustChangePassword=true`，密码哈希不变 | P2 | 需 DB 前置造数据，标记 db-assisted |
| AUTH-011 | rememberMe=false 默认 8 小时超时 | - | POST /login 不传 rememberMe | 登录成功；`sa-token timeout` 语义为 28800s（可通过后续 token 有效性间接验证，非直接断言项） | P2 | 超时时长无法通过单次请求直接断言，作为文档说明保留 |
| AUTH-012 | 登录成功清零失败计数与锁定 | 账号此前有 2-3 次失败记录（未达锁定） | 用正确密码登录 | 登录成功；之后故意错误登录 4 次仍不锁定（证明计数已清零） | P2 | |

## 2. 登出与 Token 刷新

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-013 | 已登录用户登出成功 | 已登录 token | POST /logout | HTTP 200 | P0 | |
| AUTH-014 | 登出后 token 立即失效 | 同上 | 登出后再 GET /me | HTTP 401 | P0 | |
| AUTH-015 | 未登录状态调用登出被拒（修正） | 无 token 或已失效 token | POST /logout | **实测 HTTP 401**（非此前假设的静默200）。原因：`/api/v1/auth/logout` 不在 `SaTokenWebConfig` 白名单内，未登录请求在全局 `SaInterceptor` 就被 `NotLoginException` 拦截，根本进不到 Controller；Controller 内 `if (StpUtil.isLogin())` 的幂等判断只在已登录会话才有意义，对未登录请求是死代码 | P1 | 已修正预期，勘误记录见第 11 节 |
| AUTH-016 | Token 刷新成功 | 已登录 token | POST /refresh | HTTP 200，返回新 tokenValue，且旧 token 失效 | P0 | |
| AUTH-017 | 未登录刷新被拒（修正） | 无 token | POST /refresh | **实测 HTTP 400，业务码 40100**（非此前假设的 HTTP 401）。原因：`/api/v1/auth/refresh` 在白名单内直接进 Service，`refreshToken()` 抛 `BusinessException(CommonErrorCode.UNAUTHORIZED)`，业务码 40100 是非标准三位数，`GlobalExceptionHandler` 统一兜底为 HTTP 400，业务码保留在响应体 `code` 字段 | P0 | 已修正预期，勘误记录见第 11 节 |
| AUTH-018 | 用户已被删除时刷新被拒 | 用户登录后账号被硬删除 | POST /refresh | HTTP 401 UNAUTHORIZED | P2 | 需配合 AUTH-041 硬删除场景 |
| AUTH-019 | 刷新不保留原 rememberMe，统一按 8 小时 | rememberMe=true 登录后刷新 | 刷新后检查新 session 行为 | 刷新固定使用 TIMEOUT_DEFAULT(8h)，不继承 rememberMe=true 的 30 天 | P2 | 超时时长非直接可断言项，作为已知行为记录 |

## 3. 基础信息接口

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-020 | GET /me 已登录返回用户信息 | 已登录 | GET /me | HTTP 200，含 userId/username/roles | P0 | |
| AUTH-021 | GET /me 未登录被拒 | 无 token | GET /me | HTTP 401 | P0 | |
| AUTH-022 | GET /codes 返回权限码列表 | 已登录 | GET /codes | HTTP 200，`data` 为字符串数组 | P1 | |
| AUTH-023 | GET /api/v1/user/info（Vben 兼容）| 已登录 | GET /user/info | HTTP 200 | P1 | |

## 4. 用户管理（/api/v1/users，需 super_admin 角色）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-024 | superadmin 查询用户列表 | superadmin token | GET /users?page=1&size=10 | HTTP 200，分页结构 | P0 | |
| AUTH-025 | 非管理员查询用户列表被拒 | kfstaff token | GET /users | HTTP 403 | P0 | |
| AUTH-026 | 无 token 查询用户列表被拒 | - | GET /users | HTTP 401 | P0 | |
| AUTH-027 | 创建用户成功 | superadmin token | POST /users `{username,displayName,email,phone,password}` | HTTP 200，返回新用户 id | P0 | |
| AUTH-028 | 创建用户名重复被拒 | 已存在用户名 | POST /users 复用已存在 username | 业务失败，code=AUTH_USERNAME_EXISTS，"用户名已存在" | P0 | |
| AUTH-029 | 创建邮箱重复被拒 | 已存在 email | POST /users 复用已存在 email | 业务失败，AUTH_EMAIL_EXISTS，"邮箱已存在" | P1 | |
| AUTH-030 | 创建用户密码不满足策略被拒 | - | POST /users password="123"（过短/无特殊字符） | HTTP 400 | P1 | 与 AUTH-057~060 密码策略联动 |
| AUTH-031 | 用户名含特殊字符/中文时校验不一致（缺陷验证） | - | POST /users username="用户 名!"（长度3-50满足DTO但违反Domain正则`^[A-Za-z0-9_.-]{3,50}$`） | 实际返回 HTTP 500（IllegalArgumentException 未包装为 BusinessException，被兜底），而非预期的 400 | P1 | **缺陷验证**：DTO 校验与领域层正则不一致 |
| AUTH-032 | 查询用户详情 | 已创建用户 | GET /users/{id} | HTTP 200 | P1 | |
| AUTH-033 | 本人查询自己信息 | kfstaff token | GET /users/me | HTTP 200 | P1 | |
| AUTH-034 | 更新用户资料 | 已创建用户 | PUT /users/{id} 更新 displayName/email/phone | HTTP 200，字段生效 | P1 | |
| AUTH-035 | 更新资料邮箱冲突 | 目标邮箱已被他人占用 | PUT /users/{id} email=已被占用邮箱 | 业务失败 AUTH_EMAIL_EXISTS | P2 | |
| AUTH-036 | 禁用用户 | 已创建用户，ACTIVE | POST /users/{id}/disable | HTTP 200，用户状态变 DISABLED | P0 | |
| AUTH-037 | 禁用后新登录被拒 | 同上 | 用该用户账密登录 | 登录失败（拿不到 token） | P0 | |
| AUTH-038 | 禁用不主动踢下线已登录会话（缺陷/已知行为） | 用户已登录持有有效 token，随后被管理员禁用 | 用旧 token 继续 GET /me | 实际仍可能返回 200（权限来自 token session 缓存，无实时 kickout） | P2 | **已知行为**：非实时生效，需记录实际观察结果 |
| AUTH-039 | 启用用户 | DISABLED 用户 | POST /users/{id}/enable | HTTP 200，状态回 ACTIVE，同时清零 loginFailCount/lockedUntil | P1 | |
| AUTH-040 | 删除用户为硬删除 | 已创建的临时用户 | DELETE /users/{id}，随后查 DB 或再次 GET | HTTP 200；再次 GET /users/{id} 应 404/不存在（物理删除，非软删除） | P1 | 与 AI 模型/系统配置的软删除行为对比记录 |
| AUTH-041 | 禁止自我删除 | superadmin 用自己 token 删自己 id | DELETE /users/{superadmin_id} | 业务失败 AUTH_SELF_DELETE "不能删除当前登录用户" | P0 | |
| AUTH-042 | 本人修改自己密码成功 | 临时用户登录 | POST /users/{id}/change-password `{oldPassword,newPassword}` | HTTP 200 | P0 | |
| AUTH-043 | 修改密码旧密码错误被拒 | 同上 | oldPassword 传错 | 业务失败 AUTH_PWD_OLD_MISMATCH "旧密码不正确" | P0 | |
| AUTH-044 | 新密码与历史密码重复应被拒（缺陷验证） | 用户刚把密码从 A 改成 B | 再次调用 change-password 把密码从 B 改回 A（A 在最近 5 次历史内） | **预期规范**应拒绝(AUTH_PWD_HISTORY_DUPLICATE)；**需验证实际行为**：`User.changePassword` 内 `hasher.matches(newPwd.hash(), oldHash)` 疑似传参错误（应传新密码明文而非已哈希值），可能导致历史校验实质失效，即改回旧密码也被放行 | P0 | **重点缺陷验证用例**，记录实际 HTTP 结果，不要假设一定被拒 |
| AUTH-045 | 修改他人密码需 super_admin 角色 | kfstaff 尝试改 superadmin 密码 | POST /users/{superadmin_id}/change-password 用 kfstaff token | HTTP 403（NotRoleException） | P0 | |
| AUTH-046 | 管理员重置密码不校验旧密码 | superadmin token | POST /users/{id}/reset-password `{newPassword}` | HTTP 200，且不要求提供 oldPassword | P1 | |
| AUTH-047 | 重置密码强制要求下次改密 | 同上 | 重置后查用户或用新密码登录 | 登录返回 `mustChangePassword=true` | P1 | |
| AUTH-048 | 重置密码不检查历史（可重置为最近用过的密码） | 用户密码历史含旧密码 X | reset-password 传入 X | HTTP 200 成功（reset 路径不像 change-password 那样查历史） | P1 | 与 AUTH-044 对比，验证 reset 和 change 两条路径历史校验差异 |
| AUTH-049 | 分配角色全量替换 | 用户已有角色 [A] | POST /users/{id}/roles `{roleIds:[B,C]}` | HTTP 200，之后查询用户角色为 [B,C]（A 被移除） | P0 | |
| AUTH-050 | 分配空角色列表清空所有角色 | 用户已有角色 | POST /users/{id}/roles `{roleIds:[]}` | HTTP 200，用户角色变为空 | P1 | |
| AUTH-051 | 分配不存在的 roleId 不校验直接成功（缺陷/已知行为） | - | POST /users/{id}/roles `{roleIds:[99999999]}` | HTTP 200（不校验角色是否存在），后续查询用户角色可能出现无效关联 | P2 | 记录实际返回，不假设报错 |
| AUTH-052 | `/me/login-records` 未实现返回业务码501（勘误：HTTP层仍是200） | 已登录 | GET /users/me/login-records | **实测**：HTTP 200，响应体 `{"code":501,"msg":"登录记录功能暂未实现"}`（Controller 直接 `R.fail(501,...)` 返回，未经异常抛出，故不经过 GlobalExceptionHandler 的 HTTP 状态码映射） | P2 | 断言应校验 `resp.code==501`，不要校验 `http_status==501` |
| AUTH-053 | `/me/notification-settings` 同上（勘误） | 已登录 | GET /users/me/notification-settings | HTTP 200，业务码 501 | P2 | 同上 |
| AUTH-054 | `/me/mfa` 同上（勘误） | 已登录 | GET /users/me/mfa | HTTP 200，业务码 501 | P2 | 同上 |
| AUTH-055 | `/me/ssh-keys/gpg` 同上（勘误） | 已登录 | GET /users/me/ssh-keys/gpg | HTTP 200，业务码 501 | P2 | 同上 |
| AUTH-056 | `/me/linked-accounts` 同上（勘误） | 已登录 | GET /users/me/linked-accounts | HTTP 200，业务码 501 | P2 | 同上 |
| AUTH-057 | 密码策略：长度不足 8 位被拒 | - | 创建/改密时用 7 位密码 | HTTP 400 "密码长度不足" | P1 | |
| AUTH-058 | 密码策略：缺大写字母被拒 | - | 密码全小写+数字+特殊字符 | HTTP 400 "密码需包含大写字母" | P1 | |
| AUTH-059 | 密码策略：缺数字被拒 | - | 密码无数字 | HTTP 400 "密码需包含数字" | P1 | |
| AUTH-060 | 密码策略：缺特殊字符被拒 | - | 密码无特殊字符 | HTTP 400 "密码需包含特殊字符" | P1 | |

## 5. 角色管理（/api/v1/roles，仅 @SaCheckLogin，无权限限制）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-061 | 查询角色列表 | 已登录 | GET /roles?page=1&size=10 | HTTP 200 | P0 | |
| AUTH-062 | 普通登录用户（非管理员）可任意创建角色（缺陷验证） | kfstaff token | POST /roles `{roleKey,roleName}` | 实际 HTTP 200 创建成功（Controller 无 `@SaCheckPermission`/`@SaCheckRole`） | P0 | **安全缺陷验证**：与 UserController 的 super_admin 限制形成明显对比，建议在报告中特别标注 |
| AUTH-063 | 创建角色 roleKey 重复被拒 | 已存在 roleKey | POST /roles 复用已存在 roleKey | 业务失败 ROLE_KEY_EXISTS "角色标识已存在" | P1 | |
| AUTH-064 | 查询角色详情 | 已创建角色 | GET /roles/{id} | HTTP 200 | P1 | |
| AUTH-065 | 更新角色为 inactive | 已创建普通角色 | PUT /roles/{id} `{status:"inactive"}` | HTTP 200，角色变为停用 | P1 | |
| AUTH-066 | 更新角色为 active | 停用角色 | PUT /roles/{id} `{status:"active"}` | HTTP 200，恢复启用 | P1 | |
| AUTH-067 | 更新 status 传非法值被静默忽略 | - | PUT /roles/{id} `{status:"whatever"}` | HTTP 200，但角色状态不发生变化（Service 层对非 active/inactive 值不处理也不报错） | P2 | |
| AUTH-068 | 系统角色改名被拒但状态码异常（缺陷验证） | 目标角色 isSystem=true（如 kf_staff/super_admin 等内置角色） | PUT /roles/{id} 修改 roleName | 领域层抛 `IllegalStateException`"系统内置角色不允许修改名称"，**未包装为 BusinessException**，预计实际 HTTP 500 而非语义化 400 | P0 | **缺陷验证**：记录实际返回码，不要假设是 400 |
| AUTH-069 | 系统角色停用被拒但状态码异常（缺陷验证） | 同上系统角色 | PUT /roles/{id} `{status:"inactive"}` | 同样抛 IllegalStateException，实际大概率 HTTP 500 | P0 | **缺陷验证** |
| AUTH-070 | 系统角色可以被重新激活（无限制） | 系统角色（假设已停用，若无法停用则跳过） | PUT /roles/{id} `{status:"active"}` | `activate()` 无系统角色限制，正常 200 | P2 | 依赖 AUTH-069 能否真的把系统角色停用，若停用失败此用例可能无法构造前置条件 |
| AUTH-071 | 删除非系统角色成功 | 已创建普通角色，无用户关联 | DELETE /roles/{id} | HTTP 200 | P0 | |
| AUTH-072 | 删除系统角色被拒 | isSystem=true 角色 | DELETE /roles/{id} | 业务失败 ROLE_IS_SYSTEM "系统内置角色不允许删除" | P0 | |
| AUTH-073 | 删除有用户关联的角色遗留脏数据（缺陷验证） | 角色 R 已分配给某用户 U（sys_user_role 有记录） | DELETE /roles/{R_id} | HTTP 200 删除成功（Service 不检查关联），之后查用户 U 的角色列表可能仍残留失效的 roleId 关联行 | P2 | **数据一致性缺陷验证**，需要能查询 sys_user_role 表或通过 GET /users/{U}/... 间接验证 |
| AUTH-074 | 权限树查询 | 已登录 | GET /roles/permissions/tree | HTTP 200，树结构按 module 分组，module=null 的权限被过滤 | P1 | |
| AUTH-075 | 分配权限全量替换 | 已创建角色 | PUT /roles/{id}/permissions `{permissionIds:[...]}` | HTTP 200 | P1 | |
| AUTH-076 | 分配不存在的 permissionId 不校验 | - | PUT /roles/{id}/permissions `{permissionIds:[99999999]}` | HTTP 200（不校验存在性） | P2 | |
| AUTH-077 | 分配菜单 | 已创建角色 | PUT /roles/{id}/menus `{menuIds:[...]}` | HTTP 200 | P1 | |
| AUTH-078 | 查询角色菜单 | 同上 | GET /roles/{id}/menus | HTTP 200 | P1 | |
| AUTH-079 | 设置数据域 | 已创建角色 | PUT /roles/{id}/data-scope `{scopeType:"DEPT_TREE"}` | HTTP 200 | P1 | |
| AUTH-080 | 设置数据域接受任意非法字符串（缺陷验证） | 同上 | PUT /roles/{id}/data-scope `{scopeType:"INVALID_TYPE"}` | 实际 HTTP 200 成功写入（Service 层不校验取值合法性），任意字符串被存入 | P2 | **缺陷验证** |
| AUTH-081 | 查询数据域，未配置时默认 SELF | 新建角色未设置过 data-scope | GET /roles/{id}/data-scope | HTTP 200，`data`（或对应字段）为 "SELF" | P1 | |
| AUTH-082 | 查询不存在角色的数据域不校验存在性（勘误） | - | GET /roles/999999999/data-scope | **实测**：HTTP 200，`data.scopeType="SELF"`（`getDataScope()` 直接调用 `roleRepo.findDataScope(roleId)`，未像 `setDataScope` 那样先 `getById()` 校验角色存在性，任意不存在的 roleId 都返回默认 SELF，不报 404） | P2 | **勘误**：原假设"应返回404"不成立，GET 端点无该校验；PUT 端点因内部调用 `getById()` 会先 404 |

## 6. 菜单管理（/api/v1/menus，仅 @SaCheckLogin，无权限限制）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-083 | 查询我的路由树 | 已登录 | GET /menus/me | HTTP 200，不含 BUTTON 类型节点，按 sortOrder 升序 | P0 | |
| AUTH-084 | 查询权限码列表 | 已登录 | 通过 /auth/codes 间接验证（仅取 BUTTON 且 permissionKey 非空） | HTTP 200，返回去重字符串列表 | P1 | 与 AUTH-022 联动 |
| AUTH-085 | 查询全量菜单树 | 已登录 | GET /menus | HTTP 200 | P0 | |
| AUTH-086 | 普通用户可任意创建/修改/删除菜单（缺陷验证） | kfstaff token | POST /menus `{menuName,menuType}` | 实际 HTTP 200（无权限校验） | P1 | **安全缺陷验证** |
| AUTH-087 | 创建菜单缺 menuName 报错但未语义化（缺陷验证） | - | POST /menus 不传 menuName | 领域层 `Menu.create()` 抛 IllegalArgumentException（未包装），实际可能 HTTP 500 | P2 | **缺陷验证** |
| AUTH-088 | 创建菜单 menuType 传任意字符串不校验合法性 | - | POST /menus `{menuName:"x", menuType:"WHATEVER", menuKey:"<唯一值>"}` | HTTP 200 成功创建（无枚举硬校验） | P2 | **勘误**：请求体必须携带 `menuKey`，否则会命中 AUTH-092（DB NOT NULL 约束）而非本用例要验证的行为 |
| AUTH-092 | 创建菜单缺 menuKey 触发未处理的数据库约束异常（新发现缺陷） | - | POST /menus `{menuName:"x", menuType:"MENU"}`（不传 menuKey） | 实际 HTTP 500："null value in column menu_key of relation sys_menu violates not-null constraint"（PSQLException 未被应用层捕获，直接穿透为 500） | P1 | **缺陷验证**：`Menu.create()`/`CreateMenuCommand` 均未将 `menuKey` 标记必填，但 DB 表 `sys_menu.menu_key` 有 NOT NULL 约束，应用层校验与数据库约束不一致；此缺陷也会污染 AUTH-086 的权限验证（导致该用例误判为"被拒绝"，实际是数据完整性错误） |
| AUTH-089 | 删除有子菜单的菜单被拒 | 菜单 A 下有子菜单 B | DELETE /menus/{A_id} | 业务失败 MENU_HAS_CHILDREN "请先删除子菜单" | P0 | |
| AUTH-090 | 删除无子菜单的菜单成功 | 叶子菜单 | DELETE /menus/{id} | HTTP 200 | P1 | |
| AUTH-091 | 菜单递归深度超 10 层静默截断（缺陷/已知限制） | 构造 11 层菜单树（若测试环境允许构造） | GET /menus 或 /menus/me | 深层节点被截断丢失，仅打 WARN 日志，无报错提示 | P2 | 构造成本高，标记为可选/低优先级，可用单元测试代替 |

## 7. AI 模型配置中心（/api/v1/admin/ai-models）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-092 | 查询模型列表需登录 | 已登录 | GET /admin/ai-models?page=1&size=10 | HTTP 200 | P0 | |
| AUTH-093 | 创建模型需 `system:ai-model:create` 权限 | 无该权限账号 | POST /admin/ai-models | HTTP 403 | P1 | |
| AUTH-094 | 创建模型成功且强制 isDefault=false | 有权限账号 | POST /admin/ai-models `{name,provider,apiProtocol,modelType:"CHAT",baseUrl,modelName,isDefault:true}` | HTTP 200；查询新记录 `isDefault` 实际为 false（Service 强制覆盖，忽略请求体传入的 true） | P0 | |
| AUTH-095 | apiKey 为空时按无鉴权模型处理 | - | POST /admin/ai-models 不传 apiKeyEnc | HTTP 200，存储为空串 | P2 | |
| AUTH-096 | 更新模型时 apiKeyEnc 传空不覆盖原值 | 已有模型且已配置 apiKey | PUT /admin/ai-models/{id} 不传或传空 apiKeyEnc | HTTP 200，原 apiKey 保持不变（可通过后续 GET 脱敏展示间接验证不为空） | P1 | |
| AUTH-097 | 更新不存在的模型返回 404 | - | PUT /admin/ai-models/999999999 | HTTP/业务码 404 "AI 模型配置不存在: id=999999999" | P1 | |
| AUTH-098 | 设为默认后同类型其他记录默认被清除 | 同类型（如 CHAT）已存在多条记录，其中一条 isDefault=true | POST /admin/ai-models/{new_id}/default | HTTP 200；查询列表，仅 new_id 的 isDefault=true，其余同类型全部 false | P0 | |
| AUTH-099 | 不同类型的默认互不影响 | CHAT 和 EMBEDDING 各有默认记录 | 设置 CHAT 类型新默认 | EMBEDDING 类型原默认记录不受影响 | P1 | |
| AUTH-100 | 启用/禁用模型 | 已创建模型 | POST /admin/ai-models/{id}/enabled `{enabled:false}` | HTTP 200，仅更新 isEnabled 字段 | P1 | |
| AUTH-101 | 默认配置禁止删除 | 模型 isDefault=true | DELETE /admin/ai-models/{id} | 业务失败 422 "默认配置不允许删除，请先切换默认配置" | P0 | |
| AUTH-102 | 非默认配置删除为软删除（**缺陷验证**） | 非默认模型 | DELETE /admin/ai-models/{id}，随后 GET 列表 | **预期**：HTTP 200，列表中不再出现该记录。**实测疑点**：`application.yml` 全局配置了 `logic-delete-field: deletedAt`，MyBatis-Plus 会认为 `deleted_at` 是框架专属托管字段，若 Service 层沿用"手动 `setDeletedAt(now)` + `updateById()`"模式，MP 生成的 UPDATE 语句会自动排除该列，导致软删除静默失效（DELETE 返回200但数据库列仍为 NULL，记录依然可查）。已在 system-config 模块（AUTH-122）实测复现此模式失效，AI 模型是否用同一套模式受影响需实测确认 | P0 | **重点缺陷验证**，怀疑是 `dc47565`（统一审计字段到 BaseDO）引入的回归，影响面可能覆盖所有"手动 set deletedAt + updateById"的软删除代码 |
| AUTH-103 | 测试连接 CHAT 类型（真实HTTP调用） | 已配置可用的 CHAT 模型 | POST /admin/ai-models/{id}/test | HTTP 200，`success` 字段视目标服务可用性而定 | P1 | 标记 `ai`，依赖真实模型服务或至少网络可达 |
| AUTH-104 | 测试连接 EMBEDDING 类型 | 已配置 EMBEDDING 模型 | POST /admin/ai-models/{id}/test | HTTP 200，调用 `{baseUrl}embeddings` | P1 | 标记 `ai` |
| AUTH-105 | 测试连接 RERANKER 类型为 mock（已知行为） | 已配置 RERANKER 模型 | POST /admin/ai-models/{id}/test | HTTP 200，`success:true` 固定返回，不做真实调用（TODO Phase-2） | P2 | **非缺陷，是当前已知实现**，测试断言不应误判为 bug；**前提缺陷**：见下方 AUTH-105b，当前环境可能根本无法创建出 RERANKER 记录来触发这条用例 |
| AUTH-105b | RERANKER 类型无法创建（数据库约束缺陷） | - | POST /admin/ai-models `{modelType:"RERANKER",...}` | **实测**：HTTP 500，数据库层 `CHECK` 约束 `ai_model_config_model_type_check` 只允许 `('CHAT','EMBEDDING','ROUTER')`，不含 `RERANKER`，INSERT 直接被 PG 拒绝并抛未捕获的 `PSQLException`，兜底为 500 而非语义化 400 | P0 | **重点缺陷验证**：应用层（Controller/Service/knowledge-service 的 `testConnection` mock 分支）明确支持 RERANKER 作为第四种模型类型，但数据库 schema 的 CHECK 约束没有同步更新，导致这个类型在当前环境完全不可用；需要开发团队确认是执行了不完整的迁移脚本，还是 RERANKER 支持本身还在开发中 |
| AUTH-106 | 测试连接 ROUTER 类型语义可能错误（缺陷验证） | 已配置 ROUTER 模型 | POST /admin/ai-models/{id}/test | 实际会走 CHAT 分支调用 `/chat/completions`（Service 未对 ROUTER 特殊处理），需记录实际响应，判断语义是否合理 | P2 | **缺陷验证** |
| AUTH-107 | 测试连接目标不可达返回失败但 HTTP 200 | 模型 baseUrl 指向不可达地址 | POST /admin/ai-models/{id}/test | HTTP 200，`success:false`，附带异常 message | P1 | |
| AUTH-108 | API Key 脱敏规则：短 key 全遮掩 | apiKey 长度≤8 | GET 模型详情/列表 | 返回的 apiKey 展示为 "****" | P2 | |
| AUTH-109 | API Key 脱敏规则：长 key 前4后4 | apiKey 长度>8 | GET 模型详情/列表 | 返回形如 "abcd****wxyz" | P2 | |
| AUTH-110 | DTO 允许创建时不传 isEnabled | - | POST /admin/ai-models 不传 isEnabled 字段 | HTTP 200，使用 DB 默认值 | P2 | |

## 8. 系统配置中心（/api/v1/admin/system-config）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-111 | 查询配置列表需 `system:config:*` 权限 | 无权限账号 | GET /admin/system-config | HTTP 403 | P0 | |
| AUTH-112 | superadmin 查询配置列表成功 | superadmin token | GET /admin/system-config?page=1&size=10 | HTTP 200 | P0 | |
| AUTH-113 | 创建 SYSTEM 类型配置需 super_admin 角色 | 非 super_admin（但有 config 权限）账号 | POST /admin/system-config `{configType:"SYSTEM",...}` | HTTP 403 "SYSTEM 类型配置仅超级管理员可操作" | P0 | |
| AUTH-114 | superadmin 创建 SYSTEM 类型配置成功 | superadmin token | POST /admin/system-config `{configKey,configValue,configType:"SYSTEM"}` | HTTP 200 | P0 | |
| AUTH-115 | 创建 CUSTOMER_SERVICE 类型配置无角色限制 | 有 config 权限的非 super_admin 账号 | POST /admin/system-config `{configType:"CUSTOMER_SERVICE",...}` | HTTP 200 | P1 | |
| AUTH-116 | configKey 重复被拒 | 已存在 configKey | POST 复用已存在 key | 业务失败 409(CONFLICT) "配置键已存在: X" | P1 | |
| AUTH-117 | configKey 格式校验：非字母开头被拒 | - | POST configKey="123abc" | HTTP 400（`@Pattern` 校验失败） | P1 | |
| AUTH-118 | configType 非枚举值被拒 | - | POST configType="OTHER" | HTTP 400（`@Pattern("^(SYSTEM|CUSTOMER_SERVICE)$")`） | P1 | |
| AUTH-119 | 更新配置不支持修改 configKey/configType | 已创建配置 | PUT /admin/system-config/{id} body 额外携带 configKey/configType 字段 | HTTP 200，但 configKey/configType 保持不变（DTO 无此字段，多余字段被忽略） | P2 | |
| AUTH-120 | 更新 SYSTEM 类型配置同样需 super_admin | 非 super_admin 账号 | PUT 已有 SYSTEM 类型记录 | HTTP 403 | P1 | |
| AUTH-121 | 更新不存在的配置返回 404 | - | PUT /admin/system-config/999999999 | HTTP/业务码 404 "系统配置不存在" | P1 | |
| AUTH-122 | 删除配置为软删除（**缺陷复现**） | 已创建配置 | DELETE /admin/system-config/{id}，随后 GET 列表 | **预期**：HTTP 200，列表中不再出现。**实测**：DELETE 返回 HTTP 200（无报错），但列表接口仍能查到该记录 —— 软删除未生效。直查数据库确认 `deleted_at` 列在 UPDATE 后仍为 NULL。根因：`application.yml` 全局配置 `mybatis-plus.global-config.db-config.logic-delete-field: deletedAt`，MyBatis-Plus 将其识别为框架托管的逻辑删除字段；`SystemConfigService.delete()` 采用"业务代码手动 `config.setDeletedAt(now)` 后调用 `updateById()`"的写法，与 MP 的逻辑删除机制冲突 —— MP 生成 UPDATE SQL 时会自动从 SET 子句剔除 `deleted_at` 列（认为只有框内建的 `deleteById()`/`delete()` 逻辑删除通道才能写这一列），导致设置动作被静默吞掉，事务本身正常提交不报错 | P0 | **重点缺陷，非阻断当前测试但需开发确认**：怀疑是 `dc47565`（统一审计字段到 BaseDO 并启用 MyBatis-Plus 自动填充，引入全局逻辑删除配置）引入的回归。影响面推测覆盖所有继承 `BaseDO`、且 Service 层沿用"手动 set + updateById"模式做软删除的实体（用户/AI模型/系统配置等），需要开发团队排查并统一改为框架内建的逻辑删除方法（如 `removeById()`）或改用显式 `UPDATE ... SET deleted_at=...`。测试仅记录现状，不假设已修复
| AUTH-123 | 删除 SYSTEM 类型配置需 super_admin | 非 super_admin | DELETE 一条 SYSTEM 记录 | HTTP 403 | P1 | |
| AUTH-124 | 启用/禁用切换 | 已创建配置 | POST /admin/system-config/{id}/enabled | HTTP 200 | P1 | |
| AUTH-125 | map 查询按类型批量取值 | 已有若干启用状态配置 | GET /admin/system-config/map?configType=CUSTOMER_SERVICE | HTTP 200，仅含 `isEnabled=true` 且未删除的 key→value | P1 | |
| AUTH-126 | 查询不存在/已禁用/已删除的 key 返回默认值而非 404 | - | 对应内部接口 `getValue` 语义（可通过 /internal/system-config/value 间接验证） | HTTP 200，data 为 defaultValue（可能是 null），不是错误响应 | P1 | 与 AUTH-132 联动 |

## 9. 内部接口（/internal/**, /api/v1/internal/**）

| ID | 标题 | 前置条件 | 步骤 | 预期结果 | 优先级 | 备注 |
|---|---|---|---|---|---|---|
| AUTH-127 | 正确密钥访问内部接口成功 | - | GET /internal/ai-models/active，header `X-Internal-Secret: aria-internal-lycodeing-2024` | HTTP 200 | P0 | |
| AUTH-128 | 缺少密钥访问内部接口被拒 | - | GET /internal/ai-models/active 不带该 header | HTTP 403，body `{"code":403,"message":"forbidden","data":null}`（注意字段名是 `message` 不是 `msg`） | P0 | |
| AUTH-129 | 错误密钥访问内部接口被拒 | - | 带错误的 X-Internal-Secret 值 | HTTP 403，同上错误体结构 | P0 | |
| AUTH-130 | 找不到激活的 CHAT 配置返回业务 404 | 无任何 CHAT 类型的 isDefault+isEnabled 记录（需清理环境或用隔离数据） | GET /internal/ai-models/active | 业务码 404 "未找到激活的...配置，请在后台设置默认配置" | P2 | 可能与真实环境已有默认配置冲突，需谨慎构造 |
| AUTH-131 | 内部 token 校验：有效 token | 已登录拿到的 tokenValue | POST /api/v1/internal/token/verify `{token}}` header 带正确密钥 | HTTP 200，`data.valid=true`，`data.userId` 为字符串形式 | P0 | |
| AUTH-132 | 内部 token 校验：无效 token | - | POST token/verify `{token:"invalid-xxx"}` | 业务失败，`R.fail("TOKEN_INVALID",...)`，code 为该字符串 hashCode 值（非标准40x数字），message="token 无效或已过期" | P1 | 断言时避免假设 code 是固定数字40x，应验证非成功状态或直接比对 hashCode 值 |
| AUTH-133 | 查询 EMBEDDING/ROUTER/RERANKER 激活配置 | 各类型已配置默认 | GET /internal/ai-models/active-embedding、active-router、active-reranker | HTTP 200，各自独立查询结果 | P1 | |
| AUTH-134 | 内部查询系统配置 value，key 不存在返回 200+null | - | GET /internal/system-config/value?key=not-exist-key | HTTP 200，`data:null`（非 404） | P1 | |
| AUTH-135 | 内部查询系统配置 value，key 存在返回实际值 | 已创建配置 | GET /internal/system-config/value?key={已存在key} | HTTP 200，`data`=配置值 | P1 | |

## 10. 数据域（DataScopeAspect，无独立接口，附录说明）

`@DataScope` 注解目前在 auth-service 代码库内**没有任何 Service 方法实际使用**，是预留给其他微服务（conversation-service）的机制。auth-service 层面没有可直接测试的接口用例，如需覆盖建议：
- 在 conversation-service 用例文档中查找是否有标注该注解的方法（本次调查未发现），若无则该机制暂时只能通过单元测试覆盖 `resolveDeptIds()` 的分支逻辑（ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF 五种取值），不纳入本次接口自动化范围。

## 11. auth-service 已知缺陷/风险清单

| 用例 ID | 问题描述 | 影响 | 建议 |
|---|---|---|---|
| AUTH-050 | `changePassword` 密码历史校验疑似永久失效：`hasher.matches(newPwd.hash(), oldHash)` 把已哈希的新密码当明文比对 BCrypt hash | 用户可能可以重复设置同一密码，历史校验形同虚设 | 测试用例验证：用当前密码"改成"同一个密码，是否真的被拒绝 |
| AUTH-055 | 用户名 DTO 校验（`@Size(3,50)`）与领域层正则（`^[A-Za-z0-9_.-]{3,50}$`）不一致，中文/特殊字符可通过 DTO 校验但在 `User.register()` 抛未包装的 `IllegalArgumentException` | 创建用户名含中文时可能返回 500 而非 400 | 测试验证实际返回码 |
| AUTH-057 | 两份 `CreateUserRequest` DTO 定义不一致（Controller 内部类要求 email 必填，独立 DTO 类不要求） | 需明确 Controller 实际引用哪份，行为以内部类为准 | 已在 AUTH-011 覆盖 |
| AUTH-045~046 | `/me/*` 大量子接口（登录记录/通知设置/MFA/GPG Key/关联账号）返回 501 Not Implemented | 前端若调用会得到未实现响应，非缺陷但需明确记录，避免误判为故障 | 已在用例中标注 |
| AUTH-062 | RoleController 全部接口仅 `@SaCheckLogin`，无权限限制，任何登录用户可创建/修改/删除角色 | **安全风险**：越权操作 | 建议开发团队补充 `@SaCheckPermission` |
| AUTH-086 | MenuController 同样无权限限制 | **安全风险** | 同上 |
| AUTH-068/069 | 系统角色改名/停用触发未包装的 `IllegalStateException`，预期返回 500 而非语义化 400/422 | 前端错误提示体验差 | 建议包装为 BusinessException |
| AUTH-073 | 删除有用户关联的角色不清理 `sys_user_role` 关联行，产生脏数据 | 数据一致性风险 | 建议增加级联清理或前置校验 |
| AUTH-080 | 角色数据域 `scopeType` 不校验取值合法性，任意字符串可写入 | 数据脏，可能导致下游 `DataScopeAspect` 分支判断失效（走 fail-safe 降级为 SELF） | 建议增加枚举校验 |
| AUTH-087 | 菜单缺 `menuName` 抛未包装的 `IllegalArgumentException`，预期 500 而非 400 | 同上错误码不友好问题 | 建议包装 |
| AUTH-106 | AI 模型测试连接对 ROUTER 类型走 CHAT 分支，语义可能不准确 | 测试连接结果可能误导用户判断 ROUTER 模型是否真的可用 | 建议为 ROUTER 增加专属测试逻辑 |
| AUTH-091 | 菜单递归深度超 10 层静默截断丢数据，无报错提示 | 深层菜单树数据不完整且无感知 | 低优先级，正常业务菜单层级很少超过 10 层 |
| AUTH-015 | `/api/v1/auth/logout` **未**在 `SaTokenWebConfig` 白名单中（仅 `/login`、`/refresh` 被排除鉴权），未登录请求会在全局拦截器被 `NotLoginException` 拦截，返回 **401**，Controller 内部"已登录才调用 `StpUtil.logout()`"的幂等判断实际是死代码，只有已登录时才会执行到 | 原用例文档预判"静默返回200"与实测不符，已修正为 401 | 已修正为实测行为，非阻断性问题 |
| AUTH-017 | `/api/v1/auth/refresh` 在白名单中，直接进 Service，未登录时抛 `BusinessException(CommonErrorCode.UNAUTHORIZED)`，业务码 `40100` 非标准三位 HTTP 码，按 `GlobalExceptionHandler` 规则统一映射为 **HTTP 400**（非字面上的 401），业务码 `40100` 在响应体中 | 原用例文档预判"HTTP 401"与实测不符，已修正为 HTTP 400 + 业务码 40100 | 已修正为实测行为；这是全局异常处理器的通用规则（非三位标准 HTTP 码统一走 400），不是本接口独有缺陷 |
| AUTH-052~056 | `/me/login-records` 等 5 个未实现子接口，实测均为 **HTTP 200 + 业务码 501**（Controller 直接 `R.fail(501,...)` 返回，不经过异常抛出/`GlobalExceptionHandler`），并非字面 HTTP 501 | 原用例文档预判"HTTP 501"与实测不符，已修正 | 断言应校验业务码而非 HTTP 状态码，与 AUTH-017 反向的同类模式（业务码/HTTP码分离） |
| **AUTH-092（新发现）** | 创建菜单时 `menu_key` 列在 DB 有 `NOT NULL` 约束（`sys_menu` 表），但 `CreateMenuCommand`/`Menu.create()`/Controller 均未要求该字段非空，缺省会直接触发未捕获的 `PSQLException`（`null value in column "menu_key" violates not-null constraint`），最终以 HTTP 500 兜底返回 | **应用层与数据库约束不一致**：任何不传 `menuKey` 的创建请求都会 500，而不是因为 `menuType`/`menuName` 校验问题；此前误将 kfstaff 越权创建菜单被拒判断为"权限缺陷可能已修复"，实际是同一个 500 的假阳性，与权限校验无关 | 建议在 `CreateMenuCommand`/`Menu.create()` 增加 `menuKey` 非空校验并包装为 `BusinessException`（400），避免真实的权限缺陷信号被这个数据完整性问题掩盖 |
| **AUTH-093（新发现，重点）** | `application.yml` 全局配置了 `mybatis-plus.global-config.db-config.logic-delete-field: deletedAt` + `logic-delete-value: "NOW()"`。这会让 MyBatis-Plus 把 `deleted_at` 视为框架专属托管的逻辑删除字段：若业务代码沿用"手动 `setDeletedAt(now)` + `mapper.updateById(entity)`"模式（而非调用 MP 生成的逻辑删除方法），MP 生成的 UPDATE 语句会**自动将 `deleted_at` 列从 SET 子句中排除**，导致该字段永远不会被写入。已实测复现：`SystemConfigService.delete()` 调用返回 HTTP 200，但直接查数据库 `deleted_at` 列仍为 NULL，记录在列表接口中依然可查——**软删除完全静默失效** | **数据一致性严重缺陷**：所有采用相同模式（继承 `BaseDO` 且手动 set `deletedAt` 后 `updateById`）的软删除功能都可能受影响，目前已确认受影响：`SystemConfigService.delete()`（AUTH-122）；待实测确认是否同样受影响：`AiModelConfigService.delete()`（AUTH-102）。怀疑是 `dc47565`（统一审计字段到 BaseDO 并启用 MyBatis-Plus 自动填充）引入的回归，该提交同批次很可能一并引入了 `logic-delete-field` 全局配置 | 建议开发团队全局搜索所有"手动 setDeletedAt+updateById"模式的软删除代码，改为统一调用 MP 的逻辑删除方法（如 `mapper.deleteById()`，由 MP 自动处理 `deleted_at` 赋值），或移除 `logic-delete-field` 全局配置改为业务代码显式管理 |
| **AUTH-094（新发现）** | 数据库 `ai_model_config` 表的 CHECK 约束 `ai_model_config_model_type_check` 定义为 `model_type IN ('CHAT','EMBEDDING','ROUTER')`，**完全不包含 `RERANKER`**。但应用层代码（`AiModelConfigService`、knowledge-service 的 `RerankService`）明确支持 RERANKER 类型（含专属的 mock 测试连接逻辑，见 AUTH-105），Controller/DTO 层也不校验 modelType 取值范围。任何尝试创建 `modelType="RERANKER"` 的记录都会触发 `PSQLException`（CHECK 约束违反），以 HTTP 500 兜底返回 | **应用层与数据库 schema 不一致**：RERANKER 类型的 AI 模型配置在当前数据库状态下**完全无法创建**，这与"知识库检索 reranker 可选降级"的设计意图矛盾——如果连配置都建不了，"降级"分支实质上是唯一可达路径，未观察到的并非真的降级，而是唯一路径 | 建议开发团队执行 DB 迁移，将 CHECK 约束改为 `IN ('CHAT','EMBEDDING','ROUTER','RERANKER')`，与应用层四类型设计对齐 |

## 12. 与 conversation-service 联动的账号/权限依赖说明

- `superadmin`：拥有 `super_admin` 角色，用于覆盖所有权限相关用例的正向路径。
- `kfmanager`：客服管理员，用于覆盖"有部分权限但非 super_admin"的边界（如系统配置 SYSTEM 类型限制）。
- `kfstaff`：普通客服，用于覆盖权限不足的 403 场景，以及 RoleController/MenuController **无权限限制缺陷**的验证（反直觉地应该能成功）。

以上三个账号与 `deploy/api-autotest.sh` 中使用的种子账号一致，密码统一为 `Test@123456`（来自 `docs/sql/auth-service-data.sql` 的 V3 seed 脚本）。
