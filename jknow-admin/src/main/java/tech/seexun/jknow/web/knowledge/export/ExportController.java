package tech.seexun.jknow.web.knowledge.export;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.ExportStore;
import tech.seexun.jknow.store.ExportStore.ExportClass;
import tech.seexun.jknow.store.ExportStore.ExportRelation;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 整库导出为 RDF（0020）——对齐旧仓 export_routes.rs:1-220。
 *
 * <p><b>边查边发</b>：一页事实序列化完就把攒下的字节送出去，不在内存里拼出整份文件。
 * 一个十万条事实的库正是最需要导出的那种库，也正是「先拼成一个 String」会把服务打死的
 * 那种库。
 *
 * <p><b>同一份快照</b>：体检、词汇表与每一页查询跑在同一条只读 REPEATABLE READ 事务里
 * （一条连接活到流结束）。若每页各自拿一条连接，词汇表发完之后才提交的规则会在派生页
 * 里留下一条指向它的 wasGeneratedBy——图里就出现没有本体的引用。
 *
 * <p><b>审计先行</b>：kb.exported 必须在快照事务之前写完并放掉连接——事务占满整条流的
 * 时长，占着它再回头要连接，会把最小池（两条）上的并发导出互相饿死。
 *
 * <p>中途出错只能截断——HTTP 头早就发出去了。所以错误进日志，而客户端拿到的是一份短了
 * 一截的文件；这比先攒后发要好，那种做法在同样的库上根本发不出来。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:settings:list')")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final KbAccess kbAccess;
    private final AuditService audit;
    private final DataSource dataSource;

    public ExportController(KbAccess kbAccess, AuditService audit, DataSource dataSource) {
        this.kbAccess = kbAccess;
        this.audit = audit;
        this.dataSource = dataSource;
    }

    // ---- GET /api/v1/kbs/{id}/export?format=&base=（export_routes.rs:39-190） ----

    @GetMapping("/api/v1/kbs/{id}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id,
                                                        @RequestParam(required = false) String format,
                                                        @RequestParam(required = false) String base,
                                                        HttpServletRequest request) throws SQLException {
        UserRow user = user(request);
        // 能看见的东西本来就能抄走——Viewer 起步
        KbRow kb = kbAccess.requireKb(user, id, Role.VIEWER);
        ExportFormat fmt = ExportFormat.parse(format);
        if (fmt == null) {
            throw AppException.validation("Unknown `format` (expected turtle or jsonld)");
        }
        Names names = Names.of(id, base);

        // 导出是一次「整个库离开这台机器」的动作，台账要记下（0014 的同一条理由）。
        // 必须在快照事务之前写完并放掉连接：下面那条事务一占就是整个流的时长，
        // 占着它再回头要连接，会把最小池（两条）上的并发导出互相饿死
        audit.record(client(request), id, user.id(), "kb.exported", "knowledge_base", id,
                "{\"format\":\"" + fmt.extension() + "\"}");

        // 整份导出占一条连接上的只读 REPEATABLE READ 事务：预检与流里每一页查询
        // 读同一个快照，事务活到流结束
        Connection conn = dataSource.getConnection();
        boolean handed = false;
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
            }
            // 出处链越库一律拒导（0070 挡新行，这里挡存量坏行）：宁可不发一个字节，
            // 也不能把别库的对象安上本库的 IRI——那份文件看着完整、实则悬空。
            // 在流开始之前拦下：客户端拿到的是确定性的错误，不是一截断掉的文件
            ExportStore.provenanceIntegrity(conn, id);
            // 认知状态同一条纪律（0062）：越库引用、词表以外的存储值、以及 open
            // 违规上解不开的判据，都在发第一个字节之前拦下
            ExportStore.contestIntegrity(conn, id);
            handed = true;
        } finally {
            if (!handed) {
                // 预检失败：连接即刻归还（回滚只读事务），错误走正常的 422 通道
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    // 只读事务回滚失败不影响归还
                }
                conn.close();
            }
        }

        String filename = slug(kb.name()) + "." + fmt.extension();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, fmt.contentType());
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        StreamingResponseBody body = out -> {
            // 连接由流负责收尾：正常走完回滚（只读）+ 关闭；中途出错只能截断
            //（头已发出，改不了状态码），错误进日志
            try (Connection c = conn) {
                emitAll(c, id, names, fmt, out);
                c.rollback();
            } catch (Exception e) {
                // 库里出的错在流中间只能变成 io 错误——响应头已经发出去了
                log.error("导出中断 kb={}: {}", id, e.getMessage(), e);
                throw new IOException(e);
            }
        };
        // Content-Type 原样串（"text/turtle; charset=utf-8"）——MediaType.parseMediaType 会
        // 重排分号空白（charset=utf-8），与 Rust 侧逐字节不同；只设 header，不再走 contentType()
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** 流式 emit 顺序固定（export_routes.rs:80-167）：词汇表 → documents → entities →
     *  facts（整份文件同一个 now）→ derived → fact_conflicts → axiom_violations → finish。 */
    private void emitAll(Connection conn, UUID kbId, Names names, ExportFormat fmt, OutputStream out)
            throws Exception {
        RdfEmitter sink = RdfEmitter.start(fmt, out);
        List<ExportClass> classes = ExportStore.classes(conn, kbId);
        List<ExportRelation> relations = ExportStore.relations(conn, kbId);
        RdfEmitter.Vocabulary vocab = RdfEmitter.vocabulary(names, classes, relations);
        for (ExportClass c : classes) {
            sink.emitClass(vocab, c);
        }
        for (ExportRelation r : relations) {
            sink.emitRelation(vocab, r);
        }
        out.flush();

        UUID after = null;
        List<ExportStore.ExportDocument> page;
        while (!(page = ExportStore.documentsPage(conn, kbId, after)).isEmpty()) {
            after = page.get(page.size() - 1).id();
            for (ExportStore.ExportDocument d : page) {
                sink.emitDocument(names, d);
            }
            out.flush();
        }

        after = null;
        List<ExportStore.ExportEntity> entities;
        while (!(entities = ExportStore.entitiesPage(conn, kbId, after)).isEmpty()) {
            after = entities.get(entities.size() - 1).id();
            for (ExportStore.ExportEntity e : entities) {
                sink.emitEntity(names, vocab, e);
            }
            out.flush();
        }

        // 现行三元组按「导出这一刻」判定，整份文件用同一个 now：边发边取当前时间的话，
        // 同一份文件的前后两半会按两个不同的现在写
        OffsetDateTime now = OffsetDateTime.now();
        after = null;
        List<ExportStore.ExportFact> facts;
        while (!(facts = ExportStore.factsPage(conn, kbId, after)).isEmpty()) {
            after = facts.get(facts.size() - 1).id();
            for (ExportStore.ExportFact f : facts) {
                sink.emitFact(names, vocab, f, now);
            }
            out.flush();
        }

        after = null;
        List<ExportStore.ExportDerived> derived;
        while (!(derived = ExportStore.derivedPage(conn, kbId, after)).isEmpty()) {
            after = derived.get(derived.size() - 1).id();
            for (ExportStore.ExportDerived d : derived) {
                sink.emitDerived(names, vocab, d);
            }
            out.flush();
        }

        // 认知状态（0062）：争议与违规都指着事实的 IRI，放在事实与派生之后
        after = null;
        List<ExportStore.ExportFactConflict> conflicts;
        while (!(conflicts = ExportStore.factConflictsPage(conn, kbId, after)).isEmpty()) {
            after = conflicts.get(conflicts.size() - 1).id();
            for (ExportStore.ExportFactConflict c : conflicts) {
                sink.emitFactConflict(names, c);
            }
            out.flush();
        }

        after = null;
        List<ExportStore.ExportAxiomViolation> violations;
        while (!(violations = ExportStore.axiomViolationsPage(conn, kbId, after)).isEmpty()) {
            after = violations.get(violations.size() - 1).id();
            for (ExportStore.ExportAxiomViolation v : violations) {
                sink.emitAxiomViolation(names, vocab, v);
            }
            out.flush();
        }

        sink.finish();
        out.flush();
    }

    /**
     * 文件名用的短名（export_routes.rs:200-220 slug）。ASCII 之外的字符不进
     * Content-Disposition 的 filename，中文库名会在那里变成一串问号。
     */
    static String slug(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('-');
            }
        }
        String out = sb.toString();
        while (out.contains("--")) {
            out = out.replace("--", "-");
        }
        String trimmed = out.replaceAll("^-+|-+$", "");
        return trimmed.isEmpty() ? "export" : trimmed;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
