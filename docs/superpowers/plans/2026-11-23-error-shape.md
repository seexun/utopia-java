# P2 错误形状对齐批 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 未知路由 404 映射（一行扩注解）+ 若依 BaseException 族分流（新 handler 回 {"msg","code"} 形状）+ README 测试纪律——修复两处对外语义缺陷，为 golden 复活铺路。

**架构：** AppExceptionHandler（@RestControllerAdvice，构造注入 ObjectMapper，包私有 handler 方法可同包直测）已具 noRoute 404 形状 handler（对齐 axum api.fallback）——Spring Boot 4 实抛 NoResourceFoundException 而非注解所接的 NoHandlerFoundException，扩注解数组即修；BaseException（若依用户异常族根：CaptchaExpire→UserException→BaseException）未被任何 handler 接、落 Exception 兜底变形 500——新增族 handler。

**技术栈：** Spring @ExceptionHandler、JUnit5 同包单测、curl 实测。

**规格：** `docs/superpowers/specs/2026-11-23-error-shape-design.md`（已获批准）

**环境注意：**
- 仓库根 `D:\workspace_com\jknow`；Git Bash；grep 一律 `LC_ALL=C grep -a`。
- dev 环境：后端 27401/前端 24110 在跑（读 /tmp/jknow-ports.txt）。后端改动需 `mvn install -q -DskipTests -pl jknow-admin -am` 重建 jar 后重启验证。
- **mvn test 纪律（本批 README 要写入的那条）**：跑全仓 mvn test 前停常驻后端进程（netstat 找 :27401/:27499 LISTEN 的 PID taskkill）——JOBS_WORKER 调度器扫共享库会干扰 ScriptedModelServer 计数（2026-11-22 实测计数翻倍 3-5 例假失败）。
- 永不提交：jknow-web/auto-imports.d.ts、jknow-web/vite.config.ts、tests/__snapshots__/*.snap、.playwright-cli/、jknow-web/src/docs/*.md。
- 分支：`feat/error-shape`（自 master 切出）。

---

### 任务 1：两 handler 扩充 + 形状测试 + README 纪律（§1+§2+§3+§4）

**文件：**
- 修改：`jknow-admin/src/main/java/tech/seexun/jknow/web/knowledge/err/AppExceptionHandler.java:82`（noRoute 注解数组）
- 修改：`jknow-admin/src/main/java/tech/seexun/jknow/web/knowledge/err/AppExceptionHandler.java:153` 附近（新增 BaseException handler，插在 AccessDenied handler 之前）
- 创建：`jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/err/AppExceptionHandlerShapeTests.java`
- 修改：`README.md`（验证节补一行测试纪律）

- [ ] **步骤 1：编写失败的形状测试（先写测试）**

创建 `AppExceptionHandlerShapeTests.java`（同包直测包私有 handler 方法；构造注入 `new AppExceptionHandler(new ObjectMapper())`）：

```java
package tech.seexun.jknow.web.knowledge.err;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tech.seexun.jknow.common.exception.base.BaseException;
import tech.seexun.jknow.common.exception.user.CaptchaExpireException;

/** 错误形状对齐（2026-11-23 批）：未知路由 404 与若依 BaseException 族分流的双 handler 钉形状。 */
class AppExceptionHandlerShapeTests {

    private final AppExceptionHandler handler = new AppExceptionHandler(new ObjectMapper());

    @Test
    void unknownRouteIs404NotJsonInternalError() {
        var res = handler.noRoute(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/v1/nonexistent"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("{\"error\":\"Not found\"}", res.getBody());
    }

    @Test
    void ruoyiUserExceptionFamilyGoesBackAsMsgCode() {
        var res = handler.service(new CaptchaExpireException());
        assertEquals(HttpStatus.OK, res.getStatusCode());
        // msg 字面量以 CaptchaExpireException 实测 message 为准（构造后 getMessage() 断言包含"验证码"）
        String body = res.getBody();
        assert body != null && body.contains("\"msg\"") && body.contains("\"code\":500")
                : "shape must be {\"msg\":...,\"code\":500}, got: " + body;
    }
}
```

（若 BaseException 包路径为 `tech.seexun.jknow.common.exception.base` 之外的实况，以 find 实测为准调整 import；`noResource`/`service` 方法名以步骤 3 实现为准对应。）

- [ ] **步骤 2：跑测试验证失败**

运行：`cd jknow-admin && mvn test -Dtest="AppExceptionHandlerShapeTests"`
预期：**编译失败**（`noRoute`（新签名）/`service` 方法不存在）——即 TDD 红灯。

- [ ] **步骤 3：实现两处 handler**

(a) :82 扩注解数组（方法体不动，已有 404 {"error":"Not found"} 形状）：

```java
    /** 未配路由（对齐 axum api.fallback：404 {"error":"Not found"}，api/mod.rs:622）。
     *  Spring Boot 4 实抛 NoResourceFoundException（Boot 3.2+ 弃用 NoHandlerFoundException 路径）——
     *  两个都接，未知路由不再落 Exception 兜底变形 500/200。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<String> noRoute(NoResourceFoundException e) {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", "Not found");
        return respond(HttpStatus.NOT_FOUND, body);
    }
```

（import 区补 `org.springframework.web.servlet.resource.NoResourceFoundException`；方法签名参数类型改为 NoResourceFoundException——它是 Boot 4 实抛类型，NoHandlerFoundException 场景 Boot 4 下已不触发，注解保留兼容。）

(b) :153 AccessDenied handler 之前插入：

```java
    /**
     * 若依用户异常族（BaseException：CaptchaExpire/UserPasswordNotMatch 等，登录链抛出）——
     * 回若依 AjaxResult 惯例形状 HTTP 200 + {"msg":...,"code":500}；此前落 Exception 兜底
     * 变形 {"error":"Internal server error"}，对外语义缺陷（2026-11-23 批，错误形状对齐）。
     */
    @ExceptionHandler(tech.seexun.jknow.common.exception.base.BaseException.class)
    ResponseEntity<String> service(tech.seexun.jknow.common.exception.base.BaseException e) {
        ObjectNode body = mapper.createObjectNode();
        body.put("msg", e.getMessage());
        body.put("code", 500);
        return respond(HttpStatus.OK, body);
    }
```

- [ ] **步骤 4：跑测试验证通过**

`cd jknow-admin && mvn test -Dtest="AppExceptionHandlerShapeTests"` 预期 2/2 PASS（若 CaptchaExpire message 与断言不符，按实测字面量修正断言并记录）。

- [ ] **步骤 5：jar 重建+实测三形状**

```bash
cd /d/workspace_com/jknow && mvn install -q -DskipTests -pl jknow-admin -am
netstat -ano | grep ":27401" | grep LISTEN | awk '{print $5}' | head -1 | xargs -r taskkill //F //PID
cd jknow-admin && (JKNOW_PG_PORT=15432 SERVER_PORT=27401 JKNOW_JOBS_WORKER=true JKNOW_DATA_DIR=/d/workspace_com/jknow/data nohup java -jar target/jknow-admin.jar > /tmp/jknow-backend.log 2>&1 &)
sleep 55
curl -s -o /dev/null -w "未知路由:%{http_code} " http://localhost:27401/api/v1/nonexistent; curl -s http://localhost:27401/api/v1/nonexistent
curl -s -X POST http://localhost:27401/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'
```
预期：未知路由 `404 {"error":"Not found"}`；无码登录 `{"msg":"验证码不存在或已过期","code":500}`（msg 以实测为准记录进报告）。**带验证码正常登录**：`GET /captchaImage` 取 code/uuid → 带 code 重放 /login → 正常 token 响应（或以 smoke-p0 登录面代证）。

- [ ] **步骤 6：README 测试纪律**

README.md 验证节（"测试前提已自足"段附近）补一行：

> 跑 `mvn test` 前先停常驻后端进程（taskkill :27401/:27499）——JOBS_WORKER 调度器会扫共享库并干扰测试的 scripted 模型计数（2026-11-22 实测：计数翻倍 3-5 例假失败）。

- [ ] **步骤 7：既有错误形状零回归抽测**

- 知识面 403：以 demo 用户（demo/admin123）token 调知识写接口（如 PUT /api/v1/kbs/{kb}/settings）→ 403 `{"error":"You don't have permission to do that"}`（既有 handler 不变）。
- 方法不符：`DELETE /api/v1/health` → 405 空 body（既有 handler 不变）。

- [ ] **步骤 8：Commit**

```bash
git add jknow-admin/src/main/java/tech/seexun/jknow/web/knowledge/err/AppExceptionHandler.java jknow-admin/src/test/java/tech/seexun/jknow/web/knowledge/err/AppExceptionHandlerShapeTests.java README.md
git commit -m "fix(admin): 错误形状对齐——未知路由 404（NoResourceFoundException 扩注解）+若依 BaseException 族分流 {msg,code}（golden 复活前置）"
```

---

### 任务 2：清卷全量回归与证据汇总（分支终审前置）

- [ ] **步骤 1：停常驻后端（本批新纪律）+ 清卷全量**

```bash
netstat -ano | grep -E ":27401|:27499" | grep LISTEN | awk '{print $5}' | sort -u | while read p; do taskkill //F //PID $p; done
cd /d/workspace_com/jknow && docker compose -f docker-compose.dev.yml down -v && docker compose -f docker-compose.dev.yml up -d && sleep 10
cd jknow-admin && (JKNOW_PG_PORT=15432 SERVER_PORT=27499 JKNOW_DATA_DIR=/d/workspace_com/jknow/data nohup java -jar target/jknow-admin.jar > /tmp/schema-build.log 2>&1 &) && sleep 75
docker exec jknow-pg-1 psql -U postgres -d jknow -tc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"   # 预期 112
netstat -ano | grep ":27499" | grep LISTEN | awk '{print $5}' | head -1 | xargs -r taskkill //F //PID
sleep 3 && mvn test   # admin 模块 969 基线+新增 2
cd .. && mvn test     # 全仓 1331 基线（1329+新 2）
```

- [ ] **步骤 2：重建演示环境+双 smoke+前端基线**

起正式后端（27401，env 同前）→ seed-demo → 重启后端（ES 对账）→ `JKNOW_APP_BASE=http://localhost:27401 ./scripts/smoke-p0.sh` + smoke-p1.sh → `cd jknow-web && pnpm test`（425）。

- [ ] **步骤 3：报告与证据归档**

`docs/superpowers/plans/error-shape/report.md`：三形状实测前后（curl 输出贴录）、形状测试 2/2、全仓计数、双 smoke、README 纪律行、CaptchaExpire message 实测字面量、deviation 清单。截图不需要（curl 文本即证据）。commit `docs(plan): error-shape 证据汇总报告`。
