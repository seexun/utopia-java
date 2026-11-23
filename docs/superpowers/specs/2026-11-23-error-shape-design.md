# P2 错误形状对齐批规格（未知路由 404 映射+ServiceException 族分流+README 测试纪律）

- 日期：2026-11-23
- 状态：已获用户批准（三节设计 OK）
- 背景：测试基建批终审建议的 P2 新账（golden 复活的前置受益项）；收尾终验实测确认两处对外语义缺陷。

## 侦察结论（规格依据）

1. **AppExceptionHandler（jknow-admin/.../knowledge/err/AppExceptionHandler.java，166 行）的 `@ExceptionHandler(Exception.class)` 兜底在本工程优先于若依 GlobalExceptionHandler**（类内 AccessDeniedException handler 注释明证，P2 任务 4 先例）——未显式接住的异常族全部落 500 `{"error":"Internal server error"}`。
2. **实测缺陷一（未知路由）**：`GET /api/v1/nonexistent` → HTTP 200 + `{"error":"Internal server error"}`（NoResourceFoundException 落 unexpected 兜底；状态码还经安全链/renderString 变形）——对外语义缺陷 + golden 复活 graph-404 步的适配障碍。
3. **实测缺陷二（无验证码登录）**：`POST /login`（无 code）→ `{"error":"Internal server error"}`（CaptchaExpireException 被兜底吞掉）——应为若依业务错误形状。
4. 若依 GlobalExceptionHandler（jknow-framework）已有 ServiceException 分流面，但被 AppExceptionHandler 兜底优先级压制——同 AccessDenied 先例，在本类补显式 handler 解决。
5. 上批收尾教训：常驻后端（JKNOW_JOBS_WORKER=true）的调度器扫共享库会干扰 mvn test 的 ScriptedModelServer 计数——环境纪律需入 README。

## 设计

### §1 未知路由 404 映射（任务 1）

AppExceptionHandler 补显式 handler（与类内 AccessDeniedException 接法同构）：

```java
/** 未知路由：Spring 6 NoResourceFoundException——对齐源仓契约形状 404 {"error":"Not found"}
 *  （golden graph-404 步零适配；原落 Exception 兜底变形为 500/200 内部错误形状）。 */
@ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
ResponseEntity<String> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
    ObjectNode body = mapper.createObjectNode();
    body.put("error", "Not found");
    return respond(HttpStatus.NOT_FOUND, body);
}
```

### §2 ServiceException 族分流（任务 1）

```java
/** 若依业务异常族（含 CaptchaExpireException 等子类）——回若依 AjaxResult 惯例形状
 *  HTTP 200 + {"msg":..., "code":500}；原落 Exception 兜底变形为内部错误形状。 */
@ExceptionHandler(tech.seexun.jknow.common.exception.ServiceException.class)
ResponseEntity<String> service(tech.seexun.jknow.common.exception.ServiceException e) {
    ObjectNode body = mapper.createObjectNode();
    body.put("msg", e.getMessage());
    body.put("code", 500);
    return respond(HttpStatus.OK, body);
}
```

- 实施时核实 CaptchaExpireException 继承链（预期 extends ServiceException 随族覆盖；若独立异常类则单独补同款 handler 并记录）。
- **范围控制**：只接终审记录的两族；若依 GlobalExceptionHandler 其余分流面（MissingPathVariable/MethodArgumentTypeMismatch/HttpRequestMethodNotSupported 等）不动（YAGNI）。

### §3 README 测试纪律（任务 1 内一行）

README 验证节补：「跑 `mvn test` 前停常驻后端进程——JOBS_WORKER 调度器会扫共享库并干扰测试的 scripted 模型计数（2026-11-22 收尾实测教训：计数翻倍 3-5 例假失败）」。

### §4 最小测试（任务 1 内）

新增形状断言测试（择既有测试基建形态——MockMvc standalone 或直接 handler 方法单测）：
1. `noResource` → 404 + `{"error":"Not found"}`。
2. `service` → 200 + `{"msg":"验证码不存在或已过期","code":500}`（以构造入参断言形状，不依赖真实登录链）。

## 验收

1. 实测三形状：`GET /api/v1/nonexistent` → 404 `{"error":"Not found"}`；`POST /login` 无 code → `{"msg":"...","code":500}` 形状；带验证码正常登录不受影响（走查或 curl 实证）。
2. 后端全仓 `mvn test`（清卷口径+停常驻后端纪律）0 fail；双 smoke 零退化；前端 `pnpm test` 425 零退化（错误面改动不触前端，防意外）。
3. 既有错误形状零回归：知识业务面的 422/403 形状（AppExceptionHandler 既有 handler）抽测不变。

## 不做的事

不动若依 GlobalExceptionHandler 本体；不做 auth 401 包装（HTTP 200+code:401）形状改造——属 golden 复活批引擎适配范畴；不扩其余异常分流面；不动前端。
