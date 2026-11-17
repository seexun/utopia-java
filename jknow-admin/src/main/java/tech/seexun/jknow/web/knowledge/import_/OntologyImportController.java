package tech.seexun.jknow.web.knowledge.import_;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ReasoningService.ViolationKey;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.OntologyMapper;

/**
 * OWL 导入的 HTTP 面（ontology_routes.rs:584-681 + mod.rs:388-400 路由层逐语义）：
 * 预览与落库分开两个端点，<b>绝不让上传一个文件就不可逆地改掉本体</b>；两者跑
 * 同一个 plan——分开的代码路径会分叉，而分叉的后果是确认之后发生的事与刚看过的不
 * 一样。
 *
 * <p>两道限额的等价点：Rust 在路由层 {@code DefaultBodyLimit(16MB)} + read_upload 的
 * {@code MAX_ONTOLOGY_BYTES(8MB)}；Spring 的 servlet 限额是全局的（上传侧 100MiB，
 * 无法按路由分身），16MB 的那一道由 8MB 的应用层检查先到先拒——语义上更严一格，
 * 对 FOAF 44KB / FIBO 几百 KB 的量级无差别。
 */
@RestController
public class OntologyImportController {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * ontology_routes.rs:672 FOAF 44 KB、DCTerms 48 KB，FIBO 那种大部头分模块也在
     * 几百 KB 量级；再大多半是传错了东西。
     */
    private static final int MAX_ONTOLOGY_BYTES = 8 * 1024 * 1024;

    private final KbAccess rbac;
    private final OntologyMapper ontology;
    private final ImportPlanner planner;
    private final ReasoningService reasoning;
    private final AppEventBus events;
    /** 只读事务模板：检出基线多查询同一快照（baseline_tx 对等，审查 M-2）。 */
    private final org.springframework.transaction.support.TransactionTemplate readTx;

    public OntologyImportController(KbAccess rbac, OntologyMapper ontology,
                                    ImportPlanner planner, ReasoningService reasoning,
                                    AppEventBus events,
                                    org.springframework.transaction.PlatformTransactionManager txManager) {
        this.rbac = rbac;
        this.ontology = ontology;
        this.planner = planner;
        this.reasoning = reasoning;
        this.events = events;
        this.readTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /**
     * 先看会发生什么（preview_import）。Editor 即可——只算不写。
     */
    @PostMapping("/api/v1/kbs/{kbId}/ontology/imports/preview")
    public ObjectNode preview(@PathVariable UUID kbId, MultipartHttpServletRequest multipart,
                              HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        Upload upload = readUpload(multipart);
        ImportPlanner.Planned planned = planner.plan(kbId, upload.filename(), upload.bytes());
        ObjectNode out = JSON.createObjectNode();
        out.put("filename", upload.filename());
        out.set("plan", planned.plan().toJson(JSON));
        return out;
    }

    /**
     * 落库（apply_import）。导入会改判据（公理链、domain/range、父边）：先把旧本体下
     * 会检出的违规记下来（0062），apply 落库后按新本体对账——在旧判据下检出过的陈行
     * 以 criterion_changed 收场，不是整批抹掉。
     *
     * <p>apply 是多笔提交：中途失败时一部分判据已经落了，尽力对账（对账自身失败只记
     * 告警，原错误照样返回，不吞）。
     */
    @PostMapping("/api/v1/kbs/{kbId}/ontology/imports")
    public ObjectNode apply(@PathVariable UUID kbId, MultipartHttpServletRequest multipart,
                            HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        Upload upload = readUpload(multipart);
        // 检出必须在写入之前：此刻库里还是旧本体，记下的正是「旧判据下会算出来的违规」。
        // 包一个只读事务（ontology_routes.rs:630-635 baseline_tx 同款——detectionKeys
        // 内部多查询，autocommit 下会混时刻；审查 M-2）
        Set<ViolationKey> wasDetected = readTx.execute(status -> reasoning.detectionKeys(kbId));
        ImportPlanner.Applied applied;
        try {
            applied = planner.apply(kbId, user.id(), upload.filename(), upload.bytes());
        } catch (RuntimeException e) {
            try {
                reasoning.reconcileOntology(kbId, wasDetected);
            } catch (Exception re) {
                org.slf4j.LoggerFactory.getLogger(OntologyImportController.class)
                        .warn("导入失败后的违规对账也没跑成: {}", re.toString());
            }
            throw e;
        }
        // 这一笔只做对账：open 的意思是对账于当前本体
        int violations = reasoning.reconcileOntology(kbId, wasDetected);
        events.emitReview(kbId);
        ObjectNode out = JSON.createObjectNode();
        out.put("import_id", applied.importId().toString());
        out.set("plan", applied.plan().toJson(JSON));
        out.put("violations", violations);
        return out;
    }

    /** read_upload：取 multipart 里的第一个文件，带上限与空文件检查。 */
    private record Upload(String filename, byte[] bytes) {
    }

    private Upload readUpload(MultipartHttpServletRequest multipart) {
        for (Map.Entry<String, List<MultipartFile>> part : multipart.getMultiFileMap()
                .entrySet()) {
            for (MultipartFile file : part.getValue()) {
                String filename = file.getOriginalFilename();
                if (filename == null) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = file.getBytes();
                } catch (IOException e) {
                    throw AppException.invalidDetail("upload_read_failed",
                            "Could not read the uploaded file", e.toString());
                }
                if (bytes.length > MAX_ONTOLOGY_BYTES) {
                    throw AppException.invalid("file_too_large",
                            "Ontology file is too large (max 8 MB)");
                }
                if (bytes.length == 0) {
                    throw AppException.invalid("empty_file", "Ontology file is empty");
                }
                return new Upload(filename, bytes);
            }
        }
        throw AppException.invalid("no_files", "No file in the upload");
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
