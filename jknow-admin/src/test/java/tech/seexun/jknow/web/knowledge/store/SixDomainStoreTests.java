package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.DocumentsService.DocumentPage;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.entity.AlertGroup;
import tech.seexun.jknow.store.entity.ChunkFactView;
import tech.seexun.jknow.store.entity.ConversationMessage;
import tech.seexun.jknow.store.entity.ConversationView;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.DocumentVersion;
import tech.seexun.jknow.store.entity.ExtractionDrop;
import tech.seexun.jknow.store.entity.JobStatus;
import tech.seexun.jknow.store.entity.KbMemberView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.MappingRevision;
import tech.seexun.jknow.store.entity.MemberView;
import tech.seexun.jknow.store.entity.MyKbInfo;
import tech.seexun.jknow.store.entity.OrgUser;
import tech.seexun.jknow.store.entity.Readiness;
import tech.seexun.jknow.store.entity.TokenView;
import tech.seexun.jknow.store.mapper.AuditMapper;
import tech.seexun.jknow.store.mapper.ConversationsMapper;
import tech.seexun.jknow.store.mapper.DatasourcesMapper;
import tech.seexun.jknow.store.mapper.DocumentsMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;

/**
 * 六域读 SQL 对真实库的断言（任务 5；StoreTestBase 口径：直连共享库 + INSERT fixture +
 * @AfterAll 级联清理）。专建 fixture（org 外的一切自建自拆）：告警分组（相邻同类折叠 +
 * min_role 可见性 + 未读角标）/工作区-库族（可见列表/成员/我的库/审计筛选）/PAT 列表
 * （打戳不删行）/文档页四查（墓碑/作用域/统计）/口径三态与修订/会话检索/数据源摘要
 * （明文解析与封印拒开两路）/任务归属三路判定（jobs payload 的 KB_SCOPE）。
 * 走查1 增两处：admin 告警路径（is_admin → selectAllIds 全库 Owner——修复前该查询
 * 复用全列 resultMap 必抛 ExecutorException，admin 进 /alerts 即 500，此处即回归位）与
 * 值宾语 extractions 两形态（value 外壳取内层 / summary 外壳无 value 键回 NULL）。
 */
class SixDomainStoreTests extends StoreTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "task5-r-" + RUN + "@test.local";
    private static final String EMAIL2 = "task5-r2-" + RUN + "@test.local";

    private static UUID orgId;
    private static UUID ownerId; // is_admin=false（告警 min_role 判定走矩阵，不走 admin 全通）
    private static UUID peerId; // 矩阵 viewer（编辑级告警不可见）
    private static UUID adminId; // is_admin=true（告警 is_admin 支：selectAllIds 全库 Owner）
    private static UUID wsId;
    private static UUID kbOpen; // open 库（部署成员可见）
    private static UUID kbRestricted; // restricted 库（仅矩阵）
    private static UUID sourceId; // 文档来源（作用域筛选用）
    private static UUID docA; // 活文档（带来源）
    private static UUID docB; // 活文档（无来源）
    private static UUID docDead; // 墓碑
    private static UUID docPurged; // 真删
    private static UUID conceptId; // 口径概念实体
    private static UUID mappingId;
    private static UUID conversationId;
    private static UUID liveChunkId; // docA 的活分块（值宾语事实挂这里）

    @Autowired
    WorkspaceMapper workspaces;
    @Autowired
    KbFamilyMapper kbFamily;
    @Autowired
    AuditMapper audit;
    @Autowired
    DocumentsMapper documents;
    @Autowired
    DocumentsService documentsService;
    @Autowired
    ConversationsMapper conversations;
    @Autowired
    DatasourcesService datasources;
    @Autowired
    DatasourcesMapper datasourcesMapper;
    @Autowired
    MappingsService mappings;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject("SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        ownerId = insertUser(EMAIL, "Task5 Reader", false);
        peerId = insertUser(EMAIL2, "Task5 Peer", false);
        adminId = insertUser("task5-adm-" + RUN + "@test.local", "Task5 Admin", true);
        wsId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId, "ws-task5-r-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')", ownerId, wsId);

        kbOpen = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'kb-task5-open-" + RUN + "')", kbOpen, wsId);
        kbRestricted = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) VALUES (?, ?, 'kb-task5-rst-" + RUN + "', 'restricted')", kbRestricted, wsId);
        // 矩阵：owner=admin（restricted 可见）、peer=viewer（编辑级告警不可见）
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'admin')", kbRestricted, ownerId);
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role, added_by) VALUES (?, ?, 'viewer', ?)", kbRestricted, peerId, ownerId);

        // ---- 告警 fixture：同 kind 三连成一组 + 另一 kind 插进来断开成两组；系统级一条 ----
        insertAlert(kbRestricted, "error", "source.sync_failed", "editor", "{\"source\":\"s1\"}", "10 minutes");
        insertAlert(kbRestricted, "warning", "source.sync_failed", "editor", "{\"source\":\"s2\"}", "9 minutes");
        insertAlert(kbRestricted, "warning", "llm.rate_limited", "admin", "{\"model\":\"m\"}", "8 minutes");
        insertAlert(kbRestricted, "warning", "source.sync_failed", "editor", "{\"source\":\"s3\"}", "7 minutes");
        insertAlert(null, "error", "llm.unreachable", "admin", "{\"endpoint\":\"e\"}", "6 minutes");
        // owner（矩阵 admin）读过最早那条 → sync_failed 组 unread=2；rate_limited 组 admin 级仍可见
        jdbc.update("INSERT INTO alert_reads (alert_id, user_id) "
                + "SELECT id, ? FROM alerts WHERE kb_id = ? AND kind = 'source.sync_failed' "
                + "ORDER BY created_at DESC LIMIT 1", ownerId, kbRestricted);
        // admin 支专测数据：kbOpen 上 admin 级告警——admin 无矩阵仍是全库 Owner 可见，
        // owner 只是 open+membership 的 Viewer（rank 0）不可见（对照断言用）
        insertAlert(kbOpen, "warning", "kb.maintenance", "admin", "{\"task\":5}", "5 minutes");

        // ---- 文档 fixture：A（有来源，ready/done）、B（无来源，failed graph）、墓碑、真删 ----
        sourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO sources (id, kb_id, kind, name, config) VALUES (?, ?, 'custom', 'task5-src-" + RUN + "', '{}')",
                sourceId, kbOpen);
        docA = insertDoc(kbOpen, sourceId, "report-a.pdf", "ready", "done");
        docB = insertDoc(kbOpen, null, "note-b.txt", "ready", "failed");
        docDead = insertDoc(kbOpen, null, "dead-c.txt", "ready", "done");
        jdbc.update("UPDATE documents SET deleted_at = now() WHERE id = ?", docDead);
        docPurged = insertDoc(kbOpen, null, "purged-d.txt", "ready", "done");
        jdbc.update("UPDATE documents SET deleted_at = now(), purged_at = now() WHERE id = ?", docPurged);
        // 台账与分块：A 有版本 1 与活分块两块（第二块被取代）
        jdbc.update("INSERT INTO document_versions (id, document_id, version, sha256, size_bytes) "
                + "VALUES (?, ?, 1, 'task5sha" + RUN + "', 10)", UUID.randomUUID(), docA);
        liveChunkId = insertChunk(docA, 0, "alpha live", false);
        insertChunk(docA, 1, "beta superseded", true);
        // 抽取丢弃：count 逆序 + reason 次序
        jdbc.update("INSERT INTO extraction_drops (kb_id, document_id, reason, detail, count, example) "
                + "VALUES (?, ?, 'value_not_in_quote', 'd2', 1, NULL)", kbOpen, docB);
        jdbc.update("INSERT INTO extraction_drops (kb_id, document_id, reason, detail, count, example) "
                + "VALUES (?, ?, 'chunk_unextracted', 'd1', 5, 'the chunk')", kbOpen, docB);

        // ---- 任务 fixture：KB_SCOPE 三路判定（payload 的 kb_id / document_id / source_id） ----
        insertJob("failed", "{\"kb_id\": \"" + kbOpen + "\"}");
        insertJob("failed", "{\"document_id\": \"" + docB + "\"}");
        insertJob("failed", "{\"source_id\": \"" + sourceId + "\"}");
        insertJob("failed", "{\"kb_id\": \"" + UUID.randomUUID() + "\"}"); // 别库：不数
        jdbc.update("INSERT INTO jobs (kind, status, payload, last_error) "
                + "VALUES ('noop', 'done', '{\"document_id\": \"" + docA + "\"}', NULL)");

        // ---- 口径 fixture：确认一条 + 拒绝一条 + 修订史 + 探索轮 ----
        conceptId = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'GMV')", conceptId, kbOpen);
        // 值宾语事实两形态，主语复用 GMV 概念实体（不加新实体，不动 readiness 的 entities 计数）：
        // {"value": …} 取内层标量；{"summary": …} 无 value 键 → #>> '{value}' 回 NULL（graph.rs:2082）
        insertValueFact(conceptId, liveChunkId, "{\"value\": \"Hugging Face, Inc.\"}", "2 minutes");
        insertValueFact(conceptId, liveChunkId, "{\"summary\": \"three billion dollars\"}", "1 minute");
        mappingId = UUID.randomUUID();
        jdbc.update("INSERT INTO concept_mappings (id, kb_id, concept_id, source, table_name, expr, summary, derived, status) "
                + "VALUES (?, ?, ?, 'dw', 'dw.orders', 'sum(amt)', '总销售额', false, 'confirmed')",
                mappingId, kbOpen, conceptId);
        UUID rejected = UUID.randomUUID();
        jdbc.update("INSERT INTO concept_mappings (id, kb_id, concept_id, source, table_name, expr, derived, status, decided_by) "
                + "VALUES (?, ?, ?, 'lake', 'dw.refunds', 'sum(r)', true, 'rejected', ?)",
                rejected, kbOpen, conceptId, ownerId);
        jdbc.update("INSERT INTO concept_mapping_revisions (id, mapping_id, before, changed_by) "
                + "VALUES (?, ?, '{\"expr\": \"sum(old)\"}', ?)", UUID.randomUUID(), mappingId, ownerId);
        jdbc.update("INSERT INTO mapping_exploration_runs (id, kb_id, sources, tables_scanned, columns_scanned, "
                        + "schema_truncated, cap, returned, accepted, dropped, tables_covered) "
                        + "VALUES (?, ?, '{\"dw\"}', 3, 20, false, 10, 4, 2, '{}', '{\"dw.orders\"}')",
                UUID.randomUUID(), kbOpen);

        // ---- 会话 fixture：标题命中 + 正文命中各一 ----
        conversationId = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, kb_id, user_id, title) VALUES (?, ?, ?, 'Q3 revenue')",
                conversationId, kbOpen, ownerId);
        jdbc.update("INSERT INTO conversation_messages (id, conversation_id, role, content, steps, sources, stopped) "
                + "VALUES (?, ?, 'user', 'what about the annual report', '[]', '[]', false)",
                UUID.randomUUID(), conversationId);
        UUID convo2 = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, kb_id, user_id, title) VALUES (?, ?, ?, 'misc')",
                convo2, kbOpen, peerId); // 他人会话：list 不见、requireOwned 拒

        // ---- PAT fixture：两枚（一撤一活），created_at 倒序 ----
        jdbc.update("INSERT INTO personal_tokens (id, user_id, name, token_hash, token_prefix, scope, kb_ids, expires_at, revoked_at, created_at) "
                        + "VALUES (?, ?, 'old-revoked', 'h1', 'utp_pat_old00000', 'read', NULL, NULL, now(), now() - interval '2 days')",
                UUID.randomUUID(), ownerId);
        jdbc.update("INSERT INTO personal_tokens (id, user_id, name, token_hash, token_prefix, scope, kb_ids, expires_at, created_at) "
                        + "VALUES (?, ?, 'fresh', 'h2', 'utp_pat_new000000', 'write', ?::uuid[], NULL, now() - interval '1 day')",
                UUID.randomUUID(), ownerId, "{" + kbOpen + "}");

        // ---- 审计 fixture：动作前缀/时间筛选用 ----
        insertAudit(kbOpen, ownerId, "entity.retyped", "5 minutes");
        insertAudit(kbOpen, ownerId, "entity.renamed", "4 minutes");
        insertAudit(kbOpen, peerId, "kb.updated", "3 minutes");

        // ---- 设置 fixture：四把钥匙封印形态原样入库 ----
        jdbc.update("INSERT INTO llm_settings (workspace_id, chat_base_url, chat_api_key, chat_model, embed_dim) "
                + "VALUES (?, 'http://m.test/v1', 'enc:v1:c2VhbGVkLXZhbHVl', 'gpt-test', 1536)", wsId);

        // ---- 数据源 fixture：明文（legacy 行）与封印各一，挂载到 kbOpen、授予 wsId ----
        UUID dsPlain = UUID.randomUUID();
        jdbc.update("INSERT INTO data_sources (id, name, engine, conn_string) "
                + "VALUES (?, 'task5-plain-" + RUN + "', 'postgres', 'postgres://u:pw@10.0.0.1:5432/dw?ssl=true')", dsPlain);
        UUID dsSealed = UUID.randomUUID();
        jdbc.update("INSERT INTO data_sources (id, name, engine, conn_string) "
                + "VALUES (?, 'task5-sealed-" + RUN + "', 'mysql', 'enc:v1:bm90LWEta2V5')", dsSealed);
        jdbc.update("INSERT INTO data_source_grants (data_source_id, workspace_id) VALUES (?, ?)", dsPlain, wsId);
        jdbc.update("INSERT INTO kb_data_sources (kb_id, data_source_id) VALUES (?, ?)", kbOpen, dsPlain);
        jdbc.update("INSERT INTO kb_data_sources (kb_id, data_source_id) VALUES (?, ?)", kbOpen, dsSealed);
    }

    // ---- 告警（alerts.rs 分组/可见性/未读） ----

    @Test
    void alertGroupsFoldContiguousKindsAndCountUnreadPerPerson() {
        List<AlertGroup> groups = alertsList(ownerId, null);
        // owner（矩阵 admin）：时间倒序 -7 sync / -8 rate / -9 sync / -10 sync；rate 插进来把 sync 断成两组；
        // 系统级（kb_id NULL）仅部署 admin 可见——owner 不是，故共 3 组
        assertEquals(3, groups.size(), "相邻同类折叠：rate_limited 把 sync_failed 断成两组；系统级对非 admin 不可见");
        AlertGroup first = groups.get(0); // 最新的一组（7 分钟前那条被断开的 sync——正是读过的那条）
        assertEquals("source.sync_failed", first.kind());
        assertEquals(kbRestricted, first.kbId());
        assertEquals("warning", first.severity());
        assertEquals(1, first.count());
        assertEquals(0, first.unread(), "fixture 读的是最新一条 sync（DESC LIMIT 1）");
        assertEquals(1, first.lines().size());
        AlertGroup rate = groups.get(1);
        assertEquals("llm.rate_limited", rate.kind());
        AlertGroup syncOld = groups.get(2); // 最早的连续对（-9 warning + -10 error）
        assertEquals(2, syncOld.count());
        assertEquals("error", syncOld.severity(), "组内 severity 按轻重取最大，不按字典序");
        // 读的是最新的那条（-7，单飞组）；这组（-9/-10）没读过 → unread=2
        assertEquals(2, syncOld.unread());
        assertTrue(groups.stream().noneMatch(g -> g.kbId() == null), "系统级告警仅部署 admin 可见");
    }

    @Test
    void alertVisibilityFollowsMinRoleNotJustMembership() {
        // peer：restricted 矩阵 viewer → editor 级的 sync_failed 不可见，admin 级的 rate_limited 不可见，
        // 系统级（kb_id NULL，仅 is_admin）不可见 → 空列表
        assertTrue(alertsList(peerId, null).isEmpty(), "viewer 不见 editor/admin 级告警，非 admin 不见系统级");
    }

    @Test
    void unreadBadgeCountsVisibleUnreadRows() {
        long n = alertsService.unread(ownerUser());
        assertEquals(3, n, "4 条可见 - 1 条已读");
    }

    @Test
    void alertSearchMatchesKindKbNameAndDetail() {
        assertEquals(1, alertsList(ownerId, "rate_limited").stream()
                .filter(g -> g.kind().equals("llm.rate_limited")).count());
        assertTrue(alertsList(ownerId, "nothing-matches").isEmpty());
    }

    @Test
    void adminAlertsCoverEveryKbAndSystemLevel() {
        // is_admin 支走 selectAllIds（全库 Owner）——修复前该查询复用全列 kbRow 的构造器映射，
        // 只 SELECT id 缺列必抛 ExecutorException，admin 一进 /alerts 即 500；本测试即该路径的回归位
        var admin = userRow(adminId, "Task5 Admin");
        List<AlertGroup> groups = alertsService.list(admin, null, 50, 0).items();
        assertTrue(groups.stream().anyMatch(g -> g.kbId() == null), "系统级（kb_id NULL）仅 is_admin 可见");
        AlertGroup onOpen = groups.stream().filter(g -> kbOpen.equals(g.kbId())).findFirst().orElseThrow();
        assertEquals("kb.maintenance", onOpen.kind());
        assertEquals(1, onOpen.unread(), "admin 对 kbOpen 无矩阵记录仍是全库 Owner（rank 3 ≥ admin 级 min_role）");
        assertTrue(alertsList(ownerId, null).stream().noneMatch(g -> kbOpen.equals(g.kbId())),
                "对照：owner 对 kbOpen 只是 open+membership 的 Viewer（rank 0），admin 级告警不可见");
        // ≥ 而不是 ==：共享库可能另有他库/系统级告警，对 admin 全可见（fixture 自带 4+1+1=6 条）
        assertTrue(alertsService.unread(admin) >= 6, "admin 未读至少含本 fixture 的 6 条（restricted 4 + kbOpen 1 + 系统级 1）");
    }

    // ---- workspace-kb 族 ----

    @Test
    void listVisibleHidesRestrictedWithoutMatrix() {
        List<KbRow> mine = kbFamily.listVisible(wsId, ownerId, false);
        assertTrue(mine.stream().anyMatch(k -> k.id().equals(kbOpen)));
        assertTrue(mine.stream().anyMatch(k -> k.id().equals(kbRestricted)), "矩阵 admin 可见 restricted");
        List<KbRow> stranger = kbFamily.listVisible(wsId, peerId, false);
        // peer 无 ws 成员身份也无 kbOpen 矩阵，但 open 库不查矩阵（$3 OR visibility='open' 支）——仍可见；
        // restricted 走矩阵 viewer。真正不可见的是「无矩阵的 restricted」，由下一条 outsider 专测
        assertTrue(stranger.stream().anyMatch(k -> k.id().equals(kbOpen)), "open 库不需要矩阵，非成员也可见");
        assertTrue(stranger.stream().anyMatch(k -> k.id().equals(kbRestricted)), "矩阵 viewer 对 restricted 仍可见");
        UUID outsider = insertUser("task5-out-" + RUN + "@test.local", "Outsider", false);
        try {
            List<KbRow> out = kbFamily.listVisible(wsId, outsider, false);
            assertTrue(out.stream().noneMatch(k -> k.id().equals(kbRestricted)), "无矩阵者不见 restricted");
        } finally {
            jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", outsider);
        }
    }

    @Test
    void kbMembersExcludeDeactivated() {
        jdbc.update("UPDATE sys_user SET status = '1' WHERE utopia_uuid = ?", peerId);
        List<KbMemberView> members;
        try {
            members = kbFamily.selectKbMembers(kbRestricted);
        } finally {
            jdbc.update("UPDATE sys_user SET status = '0' WHERE utopia_uuid = ?", peerId);
        }
        assertTrue(members.stream().noneMatch(m -> m.userId().equals(peerId)), "停用的人不出现在成员列表");
        assertEquals(1, members.size());
        assertEquals("Task5 Reader", members.get(0).displayName());
    }

    @Test
    void myKbInfosCarryJoinAndCounts() {
        List<MyKbInfo> infos = kbFamily.selectMyKbInfos(List.of(kbOpen, kbRestricted), peerId);
        MyKbInfo restricted = infos.stream().filter(i -> i.kbId().equals(kbRestricted)).findFirst().orElseThrow();
        assertEquals("viewer", restricted.memberRole());
        assertEquals("Task5 Reader", restricted.addedByName(), "inviter 显示名（谁引进来的）");
        assertEquals(0, restricted.docCount());
        assertEquals(2, restricted.memberCount());
        MyKbInfo open = infos.stream().filter(i -> i.kbId().equals(kbOpen)).findFirst().orElseThrow();
        assertNull(open.memberRole(), "open 库凭部署身份进入，无矩阵记录");
        assertEquals(2, open.docCount(), "活文档 A/B（墓碑与真删不计入）");
    }

    @Test
    void orgUsersExcludeDeactivatedAndOrderCreated() {
        jdbc.update("UPDATE sys_user SET status = '1' WHERE utopia_uuid = ?", peerId);
        List<OrgUser> active;
        List<OrgUser> deactivated;
        try {
            active = workspaces.selectOrgUsers(orgId);
            deactivated = workspaces.selectDeactivatedUsers(orgId);
        } finally {
            jdbc.update("UPDATE sys_user SET status = '0' WHERE utopia_uuid = ?", peerId);
        }
        assertTrue(active.stream().noneMatch(u -> u.id().equals(peerId)));
        assertEquals(1, deactivated.stream().filter(u -> u.id().equals(peerId)).count());
    }

    @Test
    void readinessCountsLiveDocumentsAndEntities() {
        Readiness r = kbFamily.selectReadiness(kbOpen);
        assertEquals(2, r.documents(), "墓碑与真删不算活文档");
        assertEquals(1, r.failed(), "graph_status=failed 的 B");
        assertEquals(0, r.processing());
        assertEquals(1, r.entities(), "GMV 概念实体");
        assertEquals(true, r.hasChatModel(), "工作区配了非空 chat_model（llm_settings 联 workspace）");
    }

    @Test
    void auditListFiltersByPrefixActorAndHalfOpenWindow() {
        var all = audit.selectKbAuditEvents(kbOpen, null, null, null, null, 50, 0);
        assertEquals(3, all.size());
        assertEquals(3, audit.countKbAuditEvents(kbOpen, null, null, null, null));
        assertEquals(2, audit.countKbAuditEvents(kbOpen, "entity.", null, null, null), "动作前缀匹配");
        assertEquals(1, audit.countKbAuditEvents(kbOpen, null, peerId, null, null), "按人筛");
        assertEquals(1, audit.countKbAuditEvents(kbOpen, null, null,
                java.time.OffsetDateTime.now().minusMinutes(4).plusSeconds(30), null), "since 含起点");
        assertEquals(2, audit.countKbAuditEvents(kbOpen, null, null, null,
                java.time.OffsetDateTime.now().minusMinutes(4).plusSeconds(30)), "until 不含终点（半开）");
        List<String> actions = audit.selectKbActions(kbOpen);
        assertEquals(List.of("entity.renamed", "entity.retyped", "kb.updated"), actions, "DISTINCT 动作按字典序");
    }

    @Test
    void settingsRowCarriesSealedKeysAsIs() {
        LlmSettingsRow s = workspaces.selectSettings(wsId);
        assertNotNull(s);
        assertTrue(s.chatApiKey().startsWith("enc:v1:"), "封印形态原样带出（读面只折布尔，绝不解密）");
        assertNull(s.embedApiKey());
        assertTrue(s.chatReady());
    }

    // ---- PAT ----

    @Test
    void tokensListIncludesRevokedInReverseAgeOrder() {
        List<TokenView> tokens = tokensOf(ownerId);
        assertEquals(2, tokens.size());
        assertEquals("fresh", tokens.get(0).name(), "created_at 倒序（新在前）");
        assertEquals("write", tokens.get(0).scope());
        assertEquals(List.of(kbOpen), tokens.get(0).kbIds());
        assertNotNull(tokens.get(1).revokedAt(), "撤过的也列——撤过这件事本身要看得见");
        assertNull(tokens.get(0).revokedAt());
    }

    // ---- documents ----

    @Test
    void documentPageCountsScopesAndTombstones() {
        DocumentPage page = documentsService.page(kbOpen, null, null, null, false, 15, 0);
        assertEquals(2, page.total(), "活文档 A/B");
        assertEquals(1, page.deleted(), "整库墓碑数（真删不算）");
        assertEquals(2, page.ready());
        assertEquals(1, page.done());
        assertEquals(0, page.extracting());
        assertEquals(1, page.failed());
        DocumentPage bySource = documentsService.page(kbOpen, sourceId.toString(), null, null, false, 15, 0);
        assertEquals(1, bySource.total());
        assertEquals("report-a.pdf", bySource.docs().get(0).filename());
        assertEquals(List.of(), bySource.docs().get(0).tags(), "tags text[] 透传");
        DocumentPage noSource = documentsService.page(kbOpen, "none", null, null, false, 15, 0);
        assertEquals(1, noSource.total(), "只看没有来源的");
        DocumentPage tombstones = documentsService.page(kbOpen, null, null, null, true, 15, 0);
        assertEquals(1, tombstones.total(), "墓碑视图：删了没清的");
        assertEquals("dead-c.txt", tombstones.docs().get(0).filename());
        DocumentPage byName = documentsService.page(kbOpen, null, "note", null, false, 15, 0);
        assertEquals(1, byName.total(), "文件名包含筛选");
        DocumentPage byGraph = documentsService.page(kbOpen, null, null, "failed", false, 15, 0);
        assertEquals(1, byGraph.total(), "graph_status 筛选");
        assertEquals(0, documentsService.page(kbOpen, UUID.randomUUID().toString(), null, null, false, 15, 0).total(),
                "认不出的 source 参数当「全部」（documents_routes.rs:379-385）");
    }

    @Test
    void versionsAndLiveChunksOnly() {
        List<DocumentVersion> versions = documentsService.versions(docA);
        assertEquals(1, versions.size());
        assertEquals("task5sha" + RUN, versions.get(0).sha256());
        assertEquals(1, documentsService.chunksFull(docA).size(), "现行版本分块（superseded 不算）按 seq");
        assertEquals("alpha live", documentsService.chunksFull(docA).get(0).text());
        assertEquals("stated", documentsService.chunksFull(docA).get(0).origin());
    }

    @Test
    void extractionDropsOrderByCountDescThenReason() {
        List<ExtractionDrop> drops = documentsService.extractionDrops(kbOpen);
        assertEquals(2, drops.size());
        assertEquals("chunk_unextracted", drops.get(0).reason(), "count DESC");
        assertEquals("value_not_in_quote", drops.get(1).reason());
        assertEquals(5, drops.get(0).count());
        assertEquals("the chunk", drops.get(0).example());
    }

    @Test
    void extractionsReadInnerValueNotTheWholeJsonShell() {
        // graph.rs:2082：值宾语行取 object_value 的内层 value（#>> '{value}'），不是整段 JSON 外壳；
        // summary 形态没有 value 键 → 该表达式回 NULL（对齐 Rust；修复前 '{}' 笔误让两行都回整段 JSON）
        List<ChunkFactView> views = documentsService.extractions(docA);
        assertEquals(2, views.size(), "两条值宾语事实都挂在活分块上（被取代的分块不出场）");
        assertEquals("Hugging Face, Inc.", views.get(0).object(), "value 外壳取内层标量");
        assertNull(views.get(1).object(), "summary 外壳不含 value 键，此表达式就是 NULL");
        assertEquals("GMV", views.get(0).subject());
        assertNull(views.get(0).objectId(), "值宾语没有实体宾语");
        assertEquals(liveChunkId, views.get(0).chunkId());
    }

    @Test
    void failedJobsCountByThreeWayPayloadScope() {
        assertEquals(3, documentsService.failedJobs(kbOpen),
                "payload 的 kb_id / document_id（经 documents 表）/ source_id（经 sources 表）三路归属；别库不数");
        assertEquals(0, documentsService.failedJobs(kbRestricted));
    }

    @Test
    void jobInKbAnswersOnlyForOwnedJobs() {
        Long jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE payload->>'document_id' = ? LIMIT 1",
                Long.class, docA.toString());
        JobStatus job = documentsService.jobInKb(kbOpen, jobId);
        assertNotNull(job);
        assertEquals("noop", job.kind());
        assertEquals("done", job.status());
        assertNull(documentsService.jobInKb(kbRestricted, jobId), "别库的任务不答（null→404）");
    }

    // ---- mappings ----

    @Test
    void mappingPageCountsAndSearches() {
        var page = mappings.page(kbOpen, null, null, 25, 0);
        assertEquals(2, page.total());
        assertEquals(1, page.counts().confirmed());
        assertEquals(1, page.counts().rejected());
        assertEquals(0, page.counts().proposed());
        assertEquals("GMV", page.items().get(0).conceptName(), "canonical_name 序");
        assertEquals(1, mappings.page(kbOpen, "confirmed", null, 25, 0).total());
        assertEquals(1, mappings.page(kbOpen, null, "orders", 25, 0).total(), "表名可搜");
        assertEquals(2, mappings.page(kbOpen, null, "gmv", 25, 0).total(), "概念名可搜（同一概念两条口径都命中——行不是概念）");
        assertNotNull(page.lastRun(), "上一轮探索的账（#503 覆盖率）");
        assertEquals(3, page.lastRun().tablesScanned());
        assertEquals(List.of("dw.orders"), page.lastRun().tablesCovered());
    }

    @Test
    void mappingRevisionsReturnWholeSnapshot() {
        List<MappingRevision> revisions = mappings.revisions(kbOpen, mappingId);
        assertEquals(1, revisions.size());
        assertEquals("sum(old)", revisions.get(0).before().get("expr").asText(), "整版快照不是差异");
        assertEquals("Task5 Reader", revisions.get(0).changedByName());
        assertTrue(mappings.revisions(kbOpen, UUID.randomUUID()).isEmpty(), "kb 归属走 JOIN 校验");
    }

    @Test
    void relevantRanksLexicalHitsAndFallsBackToConfirmed() {
        List<tech.seexun.jknow.store.entity.ConceptMapping> hit = mappings.relevant(kbOpen, "total gmv amount", 8);
        assertEquals(1, hit.size());
        assertEquals("GMV", hit.get(0).conceptName());
        assertEquals(1, mappings.relevant(kbOpen, "  ", 8).size(), "空查询退 confirmed(k)");
        assertEquals(1, mappings.relevant(kbOpen, "zzz-no-match-xyz", 8).size(),
                "一个词都对不上也退字典序兜底（relevant:113-116）");
    }

    @Test
    void lexicalTokensSplitEnglishAndCjkPairs() {
        assertEquals(List.of("is", "our", "total", "what"), sort(MappingsService.tokens("What is our TOTAL?")));
        assertEquals(List.of("多少", "精选", "至少"), sort(MappingsService.tokens("精选 至少 是 多少")), "孤立的单字不产词（跨空格不连对）");
        assertEquals(List.of("gmv"), MappingsService.tokens("a x GMV"), "单个 ASCII 字符不算词");
    }

    // ---- conversations ----

    @Test
    void conversationsSearchTitleAndBodyButOnlyMine() {
        tech.seexun.jknow.store.ConversationsService.ConversationPage mine = conversationsOf(ownerId, null);
        assertEquals(1, mine.conversations().size(), "peer 的会话不在我的列表");
        assertEquals("Q3 revenue", mine.conversations().get(0).title());
        assertEquals(1, mine.conversations().get(0).messageCount());
        assertEquals(1, conversations.countConversations(kbOpen, ownerId, "annual"),
                "消息正文可搜（人记得住的是那句话，标题可能被截成别的样子）");
        assertEquals(1, conversations.countConversations(kbOpen, ownerId, "revenue"));
        assertEquals(0, conversations.countConversations(kbOpen, peerId, "annual"), "搜也不越权");
        assertNull(conversations.selectOwned(kbOpen, peerId, conversationId), "归属校验：别人的会话拿不到");
        List<ConversationMessage> messages = conversations.selectMessages(conversationId);
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).role());
    }

    // ---- datasources ----

    @Test
    void datasourceSummaryParsesPlaintextAndRefusesSealed() {
        List<DataSourceView> mounted = datasources.mounted(kbOpen);
        assertEquals(2, mounted.size());
        DataSourceView plain = mounted.stream().filter(v -> v.name().startsWith("task5-plain")).findFirst().orElseThrow();
        assertEquals("10.0.0.1:5432/dw", plain.summary(), "host:port/db，凭据不外流");
        DataSourceView sealed = mounted.stream().filter(v -> v.name().startsWith("task5-sealed")).findFirst().orElseThrow();
        assertEquals("(sealed with another key)", sealed.summary(), "开不了就说开不了，不把密文当 URL 解析");
        assertEquals(1, datasources.grantedToWorkspace(wsId).size(), "available 面按授予工作区过滤");
    }

    // ---- fixture helpers ----

    private UUID insertUser(String email, String name, boolean admin) {
        UUID id = UUID.randomUUID();
        jdbc.update("CALL jknow_test_user(?, ?, ?, ?, ?)",
                id, orgId, email, name, admin);
        return id;
    }

    private void insertAlert(UUID kbId, String severity, String kind, String minRole, String detail, String ago) {
        jdbc.update("INSERT INTO alerts (id, kb_id, severity, kind, min_role, detail, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, now() - interval '" + ago + "')",
                UUID.randomUUID(), kbId, severity, kind, minRole, detail);
    }

    private UUID insertDoc(UUID kb, UUID source, String filename, String status, String graphStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, kb_id, source_id, filename, mime, size_bytes, sha256, status, graph_status) "
                + "VALUES (?, ?, ?, ?, 'text/plain', 10, ?, ?, ?)", id, kb, source, filename,
                "sha" + UUID.randomUUID().toString().substring(0, 16), status, graphStatus);
        return id;
    }

    private UUID insertChunk(UUID doc, int seq, String text, boolean superseded) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text, origin, superseded_at) "
                + "VALUES (?, ?, ?, ?, ?, 'stated', " + (superseded ? "now()" : "NULL") + ")",
                id, kbOpen, doc, seq, text);
        return id;
    }

    /** 值宾语事实（object_id NULL、object_value JSONB）+ 一条证据挂到指定分块；recorded_at 定序用。 */
    private void insertValueFact(UUID subjectId, UUID chunkId, String objectValue, String recordedAgo) {
        UUID factId = UUID.randomUUID();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, object_value, recorded_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, now() - interval '" + recordedAgo + "')",
                factId, kbOpen, subjectId, objectValue);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id) VALUES (?, ?)", factId, chunkId);
    }

    private void insertJob(String status, String payload) {
        jdbc.update("INSERT INTO jobs (kind, status, payload) VALUES ('process_document', ?, ?::jsonb)",
                status, payload);
    }

    private void insertAudit(UUID kb, UUID actor, String action, String ago) {
        jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, detail, created_at) "
                + "VALUES (?, ?, ?, ?, 'entity', '{}', now() - interval '" + ago + "')",
                UUID.randomUUID(), kb, actor, action);
    }

    private static List<String> sort(List<String> in) {
        return in.stream().sorted().toList();
    }

    // 服务桥（测试类内的便捷封装——生产代码在 server 侧 AlertsService）
    @Autowired
    private tech.seexun.jknow.web.knowledge.api.AlertsService alertsService;

    private tech.seexun.jknow.store.entity.UserRow ownerUser() {
        return userRow(ownerId, "Task5 Reader");
    }

    private tech.seexun.jknow.store.entity.UserRow userRow(UUID id, String name) {
        return jdbc.queryForObject(
                "SELECT utopia_uuid AS id, NULL::uuid AS org_id, user_name AS email, NULL::text AS password_hash, nick_name AS display_name, (user_id = 1 OR EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = sys_user.user_id AND ur.role_id = 1)) AS is_admin, create_time AT TIME ZONE 'UTC' AS created_at, NULL::timestamptz AS deactivated_at FROM sys_user WHERE utopia_uuid = ?",
                (rs, i) -> new tech.seexun.jknow.store.entity.UserRow(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), name, rs.getBoolean(6),
                        rs.getObject(7, java.time.OffsetDateTime.class),
                        rs.getObject(8, java.time.OffsetDateTime.class)), id);
    }

    private List<AlertGroup> alertsList(UUID userId, String q) {
        var user = userId.equals(ownerId) ? ownerUser() : peerUser();
        return alertsService.list(user, q, 50, 0).items();
    }

    private tech.seexun.jknow.store.entity.UserRow peerUser() {
        return userRow(peerId, "Task5 Peer");
    }

    @Autowired
    private tech.seexun.jknow.store.mapper.TokensMapper tokensMapper;

    private List<TokenView> tokensOf(UUID userId) {
        return tokensMapper.selectTokens(userId);
    }

    @Autowired
    private tech.seexun.jknow.store.ConversationsService conversationsService;

    private tech.seexun.jknow.store.ConversationsService.ConversationPage conversationsOf(UUID userId, String q) {
        return conversationsService.list(kbOpen, userId, q, 30, 0);
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        // audit_events append-only（触发器拒 DELETE）：本轮台账行留账（kb 已删，对按 kb 过滤的查询不可见）
        // jobs 无 FK 级联：按 payload 先清本轮插入的行（document_id 两路 + kb_id + source_id）
        // jsonb ? 运算符写作 ??（pgjdbc 把裸 ? 当占位符，同 mapper 的处理）
        jdbc.update("DELETE FROM jobs WHERE (payload ?? 'document_id' AND payload->>'document_id' IN (?, ?)) "
                + "OR (payload ?? 'kb_id' AND payload->>'kb_id' = ?::text) "
                + "OR (payload ?? 'source_id' AND payload->>'source_id' = ?::text)",
                docA.toString(), docB.toString(), kbOpen, sourceId);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId); // 级联 kb/documents/alerts/tokens 引用行
        jdbc.update("DELETE FROM data_sources WHERE name LIKE 'task5-%-" + RUN + "'");
        jdbc.update("CALL jknow_test_user_cleanup_like('task5-%-" + RUN + "%')");
    }
}
