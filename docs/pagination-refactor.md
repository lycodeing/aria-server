# 分页参数统一改造文档

## 一、背景与目标

### 1.1 背景

当前项目中各 Controller 接收分页参数的方式不统一，主要体现在以下三种写法混用：

```java
// 写法 A：0-based，字段名 page + pageSize（不一致）
@RequestParam(defaultValue = "0") int page,
@RequestParam(defaultValue = "20") int pageSize

// 写法 B：0-based，字段名 page + size（标准写法，但手动 set）
@RequestParam(name = "page", defaultValue = "0") int page,
@RequestParam(name = "size", defaultValue = "20") int size

// 写法 C：1-based，字段名 pageNum + pageSize（MP 原生，绕开了 PageUtil）
@RequestParam(defaultValue = "1") int pageNum,
@RequestParam(defaultValue = "20") int pageSize
```

这带来以下问题：
- 前端无法使用统一的分页请求参数名称
- `AdminAiModelController` 的 1-based `pageNum` 与其余接口的 0-based `page` 存在语义不兼容
- 各 Controller 都在手动将参数 set 进 Query 对象，存在重复样板代码
- `AdminAiModelController` 返回 MP 原生 `Page<VO>`，其余接口返回 `PageResult`，响应结构不统一

### 1.2 目标

1. **统一接口参数**：所有分页接口统一使用 `page`（0-based）+ `size`，默认值分别为 `0` 和 `20`
2. **消除手动 set**：利用 Spring MVC 参数绑定，直接将请求参数绑定到 `XxxPageQuery` 对象
3. **统一响应结构**：所有分页接口返回 `R<PageResult<VO>>`，不再直接暴露 MP 的 `Page` 对象
4. **复用 PageUtil**：所有 Repository/Service 层统一通过 `PageUtil.toMpPage()` 和 `PageUtil.toPageResult()` 完成转换

### 1.3 改造范围

| 模块 | 文件 | 改造类型 |
|---|---|---|
| ai-auth | `AdminAiModelController` | 参数名 + 返回类型 + pageNum→page |
| ai-auth | `AiModelConfigService` | 方法签名 → 接收 `PageQuery` |
| ai-auth | `UserController` | 参数绑定方式，去掉手动 set |
| ai-auth | `RoleController` | 参数绑定方式，去掉手动 set |
| ai-knowledge | `KnowledgeDocController` | 参数绑定方式，去掉手动 set |

## 二、现有分页基础设施（ai-common/common-core）

### 2.1 PageQuery — 分页查询基类

路径：`ai-common/common-core/src/main/java/com/aria/common/core/page/PageQuery.java`

```java
@Data
public class PageQuery implements Serializable {

    public static final int MAX_PAGE_SIZE = 200;

    /** 页码索引（0-based，默认 0） */
    private int page = 0;

    /** 每页大小（默认 20，上限 200） */
    private int size = 20;

    public int safePage() { return Math.max(page, 0); }
    public int safeSize() { return Math.min(Math.max(size, 1), MAX_PAGE_SIZE); }
}
```

**约定**：`page` 字段为 0-based，业务 Query 继承此类并扩展过滤条件字段。

---

### 2.2 PageUtil — 转换工具

路径：`ai-common/common-core/src/main/java/com/aria/common/core/page/PageUtil.java`

```java
public final class PageUtil {

    // PageQuery(0-based) → MP Page(1-based)
    public static <T> Page<T> toMpPage(PageQuery query) {
        return new Page<>(query.safePage() + 1L, query.safeSize());
    }

    // MP Page → PageResult（带类型转换）
    public static <DO, R> PageResult<R> toPageResult(
            Page<DO> mpPage, Function<DO, R> mapper, PageQuery query) { ... }

    // MP Page → PageResult（类型相同，防御性拷贝）
    public static <T> PageResult<T> toPageResult(Page<T> mpPage, PageQuery query) { ... }
}
```

---

### 2.3 PageResult — 统一响应结构

路径：`ai-common/common-core/src/main/java/com/aria/common/core/page/PageResult.java`

```java
public record PageResult<T>(
        long total,
        int page,
        int size,
        List<T> items
) {
    public static <T> PageResult<T> of(long total, int page, int size, List<T> items) { ... }
    public static <T> PageResult<T> empty() { ... }
    public int totalPages() { ... }
    public boolean hasNext() { ... }
}
```

---

### 2.4 业务 Query 子类（现状）

| 模块 | 类 | 继承 PageQuery | 扩展字段 |
|---|---|---|---|
| ai-auth | `UserPageQuery` | ✅ | `keyword` |
| ai-auth | `RolePageQuery` | ✅ | `keyword` |
| ai-knowledge | `DocPageQuery` | ✅ | `keyword`, `kbId`, `status` |

这些子类已经正确继承了 `PageQuery`，因此 `page` / `size` 字段天然具备。Spring MVC 的 `@ModelAttribute`（或无注解直接绑定）可以将请求参数直接填充进来，无需在 Controller 里手动 set。

## 三、问题诊断

### 3.1 参数命名不一致

| Controller | page 参数名 | size 参数名 | 起始值 |
|---|---|---|---|
| `UserController` | `page` | **`pageSize`** | 0 |
| `RoleController` | `page` | **`pageSize`** | 0 |
| `KnowledgeDocController` | `page` | `size` ✅ | 0 |
| `AdminAiModelController` | **`pageNum`** | `pageSize` | **1** |

`UserController` / `RoleController` 的 size 参数名是 `pageSize`，但 `PageQuery` 的字段名是 `size`，两者不匹配。导致即便使用 `@ModelAttribute` 绑定也无法自动填充 `size`，仍需手动 set。

`AdminAiModelController` 的 `pageNum` 从 1 开始，与其他接口的 `page` 从 0 开始语义不同，前端若统一封装分页请求工具会出现第一页偏差。

---

### 3.2 手动 set 样板代码冗余

以 `UserController` 为例：

```java
// 现有写法（冗余）
UserPageQuery query = new UserPageQuery();
query.setKeyword(keyword);
query.setPage(page);       // 手动 set
query.setSize(pageSize);   // 手动 set，且参数名还是 pageSize
PageResult<User> result = userAppService.search(query);
```

`RoleController` 和 `KnowledgeDocController` 同样存在此问题。利用 Spring MVC 参数绑定可以完全消除这段模板代码。

---

### 3.3 AdminAiModelController 响应结构不统一

```java
// 现有：直接返回 MP 的 Page 对象
public R<Page<AiModelVO>> list(...) {
    Page<AiModelConfigDO> doPage = service.page(pageNum, pageSize, modelType);
    Page<AiModelVO> voPage = new Page<>(...);
    voPage.setRecords(vos);
    return R.ok(voPage);
}
```

MP 的 `Page` 对象序列化后会包含 `records`、`current`、`pages`、`size`、`total`、`orders`、`optimizeCountSql` 等大量内部字段，结构臃肿且与前端约定的 `{ items, total, page, size }` 不符。其他接口均返回 `PageResult<VO>`，此处是孤例。

---

### 3.4 Service 层 page 方法签名过于宽泛

```java
// 现有：裸参数，调用方可随意传任意值
public Page<AiModelConfigDO> page(int pageNum, int pageSize, String modelType) {
    Page<AiModelConfigDO> page = new Page<>(pageNum, pageSize);
    ...
}
```

- 返回 MP 原生 `Page<DO>` 暴露了持久化层实现细节
- 没有使用 `PageUtil.toMpPage()` 统一转换，缺乏 `safeSize()` 的上限保护（目前可传任意大的 pageSize）
- 入参 `pageNum` 语义模糊（是 0-based 还是 1-based？）

---

### 3.5 问题汇总

| # | 问题 | 影响 |
|---|---|---|
| P1 | `pageSize` 字段名与 `PageQuery.size` 不匹配 | 无法自动绑定，需手动 set |
| P2 | `AdminAiModelController` 用 1-based `pageNum` | 前端无法统一分页参数 |
| P3 | `AdminAiModelController` 返回 `Page<VO>` | 响应结构与其他接口不统一 |
| P4 | `AiModelConfigService.page()` 缺乏 `safeSize` 保护 | 潜在大数据量查询风险 |
| P5 | 各 Controller 手动拼装 Query 对象 | 代码冗余，可读性差 |

## 四、改造方案

### 4.1 核心原则

1. **Spring MVC 直接绑定 Query 对象**：将 `XxxPageQuery` 作为方法参数，加 `@ModelAttribute`（GET 接口默认就是 ModelAttribute 绑定，可省略注解），Spring 自动将请求参数填充进对应字段。
2. **统一参数名**：前端请求参数统一为 `page`（0-based）+ `size`，`PageQuery` 字段名已经是这两个，只需确保 Controller 参数名与之对应。
3. **统一响应结构**：所有分页接口返回 `R<PageResult<VO>>`，通过 `PageUtil.toPageResult()` 转换。
4. **Service 层接受 `PageQuery`**：不再传裸 `int` 参数，统一通过 `PageUtil.toMpPage()` 转换，自动获得 `safeSize` 上限保护。

---

### 4.2 Spring MVC 参数绑定原理

对于 GET 请求，Spring MVC 默认用 `@ModelAttribute` 语义处理 POJO 参数：将 Query String 中的参数名按字段名匹配后反射赋值。因此：

```java
// 请求：GET /api/v1/users?keyword=张三&page=0&size=10
// Spring 自动将 keyword/page/size 填充进 UserPageQuery
@GetMapping
public R<PageResult<UserVO>> list(UserPageQuery query) {
    // query.getKeyword() == "张三"
    // query.getPage()    == 0
    // query.getSize()    == 10
}
```

无需手动 new 对象也无需手动 set，Spring 完成整个填充过程。`PageQuery` 中的默认值（`page=0`, `size=20`）也会在字段没有对应请求参数时生效。

---

### 4.3 各接口改造方案

#### UserController / RoleController

**改造点**：将 `keyword`、`page`、`pageSize` 三个分散的 `@RequestParam` 替换为一个 `XxxPageQuery query` 参数，删除手动 set 代码。

前端接口参数变化：`pageSize` → `size`（破坏性变更，需前端同步修改）

#### KnowledgeDocController

**改造点**：将 `keyword`、`kbId`、`status`、`page`、`size` 五个分散参数，其中分页两个替换为 `DocPageQuery` 绑定。由于 `status` 需要 String→枚举转换且有错误处理，保留 `status` 为独立 `@RequestParam`，仅让 `page`/`size` 通过 Query 对象绑定。

> 注：也可将整个 `DocPageQuery` 直接绑定，但 `status` 的枚举转换和错误反馈逻辑需要在 Controller 里显式处理，建议保留独立参数做校验，转换完再 set 进 query。实际上现有写法对 `DocPageQuery` 已经是完整绑定，只是 page/size 手动 set 是多余的——改造后让 Spring 自动绑定 page/size，status 继续手动转换并 set。

#### AdminAiModelController + AiModelConfigService

**改造点最多**：
1. 请求参数：`pageNum`（1-based）→ `page`（0-based），`pageSize` → `size`
2. Service 方法签名：`page(int pageNum, int pageSize, String modelType)` → `page(PageQuery pageQuery, String modelType)`
3. Service 内部：`new Page<>(pageNum, pageSize)` → `PageUtil.toMpPage(pageQuery)`
4. Controller 返回类型：`R<Page<AiModelVO>>` → `R<PageResult<AiModelVO>>`
5. Controller 转换逻辑：删除手动拼装 `Page<AiModelVO>` 的代码，改用 `PageUtil.toPageResult()`

---

### 4.4 前端影响说明

| 接口 | 参数变化 | 是否破坏性 |
|---|---|---|
| `GET /api/v1/users` | `pageSize` → `size` | ⚠️ 需前端同步修改 |
| `GET /api/v1/roles` | `pageSize` → `size` | ⚠️ 需前端同步修改 |
| `GET /api/knowledge/docs` | 无变化（已是 page+size） | ✅ 兼容 |
| `GET /api/v1/admin/ai-models` | `pageNum`(1-based) → `page`(0-based)，`pageSize` → `size` | ⚠️ 需前端同步修改 |

**响应结构变化**（`AdminAiModelController`）：

```json
// 改造前（MP Page 结构）
{
  "code": 0,
  "data": {
    "records": [...],
    "total": 100,
    "size": 20,
    "current": 1,
    "pages": 5,
    "orders": [],
    "optimizeCountSql": true,
    ...
  }
}

// 改造后（PageResult 结构，与其他接口一致）
{
  "code": 0,
  "data": {
    "items": [...],
    "total": 100,
    "page": 0,
    "size": 20
  }
}
```

## 五、具体代码变更

### 5.1 UserController

**文件**：`ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/UserController.java`

```java
// ---- 改造前 ----
@GetMapping
public R<PageVO<UserVO>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
    UserPageQuery query = new UserPageQuery();
    query.setKeyword(keyword);
    query.setPage(page);
    query.setSize(pageSize);
    PageResult<User> result = userAppService.search(query);
    List<UserVO> items = result.items().stream().map(UserAssembler::toVO).toList();
    return R.ok(new PageVO<>(items, result.total()));
}

// ---- 改造后 ----
@GetMapping
public R<PageResult<UserVO>> list(UserPageQuery query) {
    PageResult<User> result = userAppService.search(query);
    List<UserVO> items = result.items().stream().map(UserAssembler::toVO).toList();
    return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
}
```

**变更说明**：
- 3 个 `@RequestParam` 替换为 1 个 `UserPageQuery query`（Spring MVC 自动绑定）
- 返回类型由 `PageVO<UserVO>` 改为 `PageResult<UserVO>`（统一响应结构）
- 删除手动 set 代码（共 3 行）
- 前端参数：`pageSize` → `size`

> `PageVO` 是 auth 模块内的老包装类，改造后不再需要。确认无其他地方引用后可删除。

---

### 5.2 RoleController

**文件**：`ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/RoleController.java`

```java
// ---- 改造前 ----
@GetMapping
public R<PageVO<RoleVO>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
    RolePageQuery query = new RolePageQuery();
    query.setKeyword(keyword);
    query.setPage(page);
    query.setSize(pageSize);
    PageResult<Role> result = roleAppService.list(query);
    List<RoleVO> vos = result.items().stream().map(RoleAssembler::toVO).toList();
    return R.ok(new PageVO<>(vos, result.total()));
}

// ---- 改造后 ----
@GetMapping
public R<PageResult<RoleVO>> list(RolePageQuery query) {
    PageResult<Role> result = roleAppService.list(query);
    List<RoleVO> vos = result.items().stream().map(RoleAssembler::toVO).toList();
    return R.ok(PageResult.of(result.total(), result.page(), result.size(), vos));
}
```

**变更说明**：与 UserController 改造完全对称，前端参数 `pageSize` → `size`。

---

### 5.3 KnowledgeDocController

**文件**：`ai-knowledge/knowledge-service/src/main/java/com/aria/knowledge/interfaces/rest/KnowledgeDocController.java`

```java
// ---- 改造前 ----
@GetMapping
public R<PageResult<DocListVO>> list(
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "kbId",    required = false) String kbId,
        @RequestParam(name = "status",  required = false) String status,
        @RequestParam(name = "page",    defaultValue = "0")  int page,
        @RequestParam(name = "size",    defaultValue = "20") int size) {
    DocStatus docStatus = null;
    if (status != null && !status.isBlank()) {
        try {
            docStatus = DocStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return R.fail(400, "无效的文档状态值：" + status);
        }
    }
    DocPageQuery query = new DocPageQuery();
    query.setKeyword(keyword);
    query.setKbId(kbId);
    query.setStatus(docStatus);
    query.setPage(page);
    query.setSize(size);
    PageResult<KnowledgeDoc> result = ingestAppService.listDocs(query);
    List<DocListVO> items = result.items().stream().map(this::toListVO).toList();
    return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
}

// ---- 改造后 ----
@GetMapping
public R<PageResult<DocListVO>> list(
        @RequestParam(name = "keyword", required = false) String keyword,
        @RequestParam(name = "kbId",    required = false) String kbId,
        @RequestParam(name = "status",  required = false) String status,
        DocPageQuery query) {
    // status 需要 String→枚举转换，保留独立参数做显式校验
    if (status != null && !status.isBlank()) {
        try {
            query.setStatus(DocStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return R.fail(400, "无效的文档状态值：" + status);
        }
    }
    query.setKeyword(keyword);
    query.setKbId(kbId);
    // page/size 已由 Spring MVC 自动绑定进 DocPageQuery，无需手动 set
    PageResult<KnowledgeDoc> result = ingestAppService.listDocs(query);
    List<DocListVO> items = result.items().stream().map(this::toListVO).toList();
    return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
}
```

**变更说明**：
- `page`/`size` 两个独立 `@RequestParam` 删除，由 Spring 自动绑定进 `DocPageQuery`
- `keyword`/`kbId` 保留为独立参数（需要 set 进 query，明确语义）
- `status` 保留为独立参数（枚举转换需要错误处理）
- 前端参数名无变化，**完全兼容**

---

### 5.4 AiModelConfigService

**文件**：`ai-auth/auth-service/src/main/java/com/aria/auth/application/service/AiModelConfigService.java`

```java
// ---- 改造前 ----
public Page<AiModelConfigDO> page(int pageNum, int pageSize, String modelType) {
    Page<AiModelConfigDO> page = new Page<>(pageNum, pageSize);
    LambdaQueryWrapper<AiModelConfigDO> wrapper = new LambdaQueryWrapper<AiModelConfigDO>()
            .isNull(AiModelConfigDO::getDeletedAt)
            .orderByDesc(AiModelConfigDO::getCreatedAt);
    if (modelType != null && !modelType.isBlank()) {
        wrapper.eq(AiModelConfigDO::getModelType, modelType);
    }
    return mapper.selectPage(page, wrapper);
}

// ---- 改造后 ----
public PageResult<AiModelConfigDO> page(PageQuery pageQuery, String modelType) {
    LambdaQueryWrapper<AiModelConfigDO> wrapper = new LambdaQueryWrapper<AiModelConfigDO>()
            .isNull(AiModelConfigDO::getDeletedAt)
            .orderByDesc(AiModelConfigDO::getCreatedAt);
    if (modelType != null && !modelType.isBlank()) {
        wrapper.eq(AiModelConfigDO::getModelType, modelType);
    }
    Page<AiModelConfigDO> result = mapper.selectPage(PageUtil.toMpPage(pageQuery), wrapper);
    return PageUtil.toPageResult(result, pageQuery);
}
```

**变更说明**：
- 入参由裸 `int pageNum, int pageSize` 改为 `PageQuery pageQuery`，语义清晰，自动获得 `safeSize` 上限（200）保护
- 内部使用 `PageUtil.toMpPage()` 统一转换，不再手动 `new Page<>()`
- 返回类型由 MP 原生 `Page<AiModelConfigDO>` 改为 `PageResult<AiModelConfigDO>`，隐藏持久化细节
- 需要在文件头增加 `import com.aria.common.core.page.PageQuery;` 和 `import com.aria.common.core.page.PageUtil;`

---

### 5.5 AdminAiModelController

**文件**：`ai-auth/auth-service/src/main/java/com/aria/auth/interfaces/rest/AdminAiModelController.java`

```java
// ---- 改造前 ----
@GetMapping
public R<Page<AiModelVO>> list(
        @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String modelType) {
    Page<AiModelConfigDO> doPage = service.page(pageNum, pageSize, modelType);
    List<AiModelVO> vos = doPage.getRecords().stream()
            .map(this::toVO)
            .toList();
    Page<AiModelVO> voPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
    voPage.setRecords(vos);
    return R.ok(voPage);
}

// ---- 改造后 ----
@GetMapping
public R<PageResult<AiModelVO>> list(
        PageQuery pageQuery,
        @RequestParam(required = false) String modelType) {
    PageResult<AiModelConfigDO> doResult = service.page(pageQuery, modelType);
    List<AiModelVO> vos = doResult.items().stream()
            .map(this::toVO)
            .toList();
    return R.ok(PageResult.of(doResult.total(), doResult.page(), doResult.size(), vos));
}
```

**变更说明**：
- `pageNum`(1-based) → `page`(0-based，`PageQuery` 字段名），与其他接口一致
- `pageSize` → `size`（`PageQuery` 字段名）
- 返回类型 `R<Page<AiModelVO>>` → `R<PageResult<AiModelVO>>`
- 删除手动拼装 `Page<AiModelVO>` 的 3 行代码
- 删除 `import com.baomidou.mybatisplus.extension.plugins.pagination.Page;`（不再直接使用）
- 前端需同步修改：参数名 `pageNum`→`page`、`pageSize`→`size`，且起始页从 `1` 改为 `0`

## 六、改造总结

### 6.1 改造前后对比

#### 参数统一

| 接口 | 改造前 page 参数 | 改造前 size 参数 | 改造后 page 参数 | 改造后 size 参数 | 起始值 |
|---|---|---|---|---|---|
| `GET /api/v1/users` | `page` | `pageSize` | `page` | `size` | 0 |
| `GET /api/v1/roles` | `page` | `pageSize` | `page` | `size` | 0 |
| `GET /api/knowledge/docs` | `page` | `size` | `page` | `size` | 0 |
| `GET /api/v1/admin/ai-models` | `pageNum` | `pageSize` | `page` | `size` | 0 |

改造后四个接口全部统一为 `page`(0-based) + `size`，前端可以封装统一的分页请求工具。

#### 响应结构统一

| 接口 | 改造前响应结构 | 改造后响应结构 |
|---|---|---|
| `GET /api/v1/users` | `R<PageVO<UserVO>>` | `R<PageResult<UserVO>>` |
| `GET /api/v1/roles` | `R<PageVO<RoleVO>>` | `R<PageResult<RoleVO>>` |
| `GET /api/knowledge/docs` | `R<PageResult<DocListVO>>` ✅ | `R<PageResult<DocListVO>>` ✅ |
| `GET /api/v1/admin/ai-models` | `R<Page<AiModelVO>>` | `R<PageResult<AiModelVO>>` |

所有分页接口统一返回：
```json
{
  "code": 0,
  "data": {
    "items": [...],
    "total": 100,
    "page": 0,
    "size": 20
  }
}
```

#### 代码量变化

| 文件 | 删除行数 | 新增行数 | 净减少 |
|---|---|---|---|
| `UserController` | 7 | 4 | -3 |
| `RoleController` | 7 | 4 | -3 |
| `KnowledgeDocController` | 4 | 2 | -2 |
| `AdminAiModelController` | 10 | 6 | -4 |
| `AiModelConfigService` | 3 | 3 | 0 |

---

### 6.2 改造后数据流

```
前端请求
  ?page=0&size=20&keyword=xxx
        │
        ▼
  Controller 方法参数
  XxxPageQuery query       ← Spring MVC 自动绑定 page/size/keyword
        │
        ▼
  AppService.method(query)
        │
        ▼
  Repository / Service
  PageUtil.toMpPage(query)  → MP Page(current=1, size=20)  ← 0-based 转 1-based
  mapper.selectPage(...)
  PageUtil.toPageResult(...) → PageResult(total, page=0, size=20, items)
        │
        ▼
  Controller
  R.ok(PageResult.of(...))  ← 统一响应
        │
        ▼
  前端响应
  { items: [...], total: 100, page: 0, size: 20 }
```

---

### 6.3 注意事项

**前端需要同步修改的接口（破坏性变更）：**

1. `GET /api/v1/users`：请求参数 `pageSize` 改为 `size`
2. `GET /api/v1/roles`：请求参数 `pageSize` 改为 `size`
3. `GET /api/v1/admin/ai-models`：
   - 请求参数 `pageNum`(1-based) 改为 `page`(0-based)
   - 请求参数 `pageSize` 改为 `size`
   - 响应字段 `records` 改为 `items`，`current` 改为 `page`

**`PageVO` 清理**：
`UserController` 和 `RoleController` 改造完成后，`com.aria.auth.interfaces.rest.vo.PageVO` 可能不再被引用，确认后可以删除。

**`safeSize` 上限保护**：
改造后 `AiModelConfigService.page()` 通过 `PageUtil.toMpPage()` 调用了 `query.safeSize()`，自动限制每页最大 200 条，原有裸参数无此保护。

**`DashboardController` / `SessionQueueController` 不在本次改造范围**：
这两个接口使用的是 `limit`（无分页，只是数量限制），语义不同，不适合套用 `PageQuery`，保持现状即可。

---

### 6.4 执行顺序建议

建议按以下顺序执行，降低联调风险：

1. `AiModelConfigService`（无前端影响，纯后端重构）
2. `AdminAiModelController`（对齐 Service 改动，同步前端）
3. `UserController`（通知前端同步修改 `pageSize`→`size`）
4. `RoleController`（同上）
5. `KnowledgeDocController`（无破坏性变更，优先级最低）
