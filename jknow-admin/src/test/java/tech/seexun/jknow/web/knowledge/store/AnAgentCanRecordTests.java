package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.entity.PendingFactView;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 经 MCP 提的记忆，审核卡上要认得出是<b>哪一个 agent</b>（#304 / 0026；
 * an_agent_can_record.rs 断言不改写）。
 *
 * <p>为什么非要连库：这条身份链整个由 SQL 承担——一列外键、一个 LEFT JOIN、一个 AS
 * 别名。写错列名、join 错表、别名和字段对不上，界面上只会安静地少显示半行字。
 *
 * <p>两条都要断言，因为它们坏的方式不同：
 * <ul>
 *   <li>带令牌的提议，视图要给出令牌的名字（join 漏了 → 永远是空）</li>
 *   <li>不带令牌的提议（网页端对话），那一位要是空（join 写成内连接 → 整条不见了）</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnAgentCanRecordTests extends StoreTestBase {

    @Autowired
    PendingService pending;

    @Test
    void aPendingFactNamesTheAgentThatProposedIt() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID etype = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        UUID acme = UUID.randomUUID();
        UUID zenith = UUID.randomUUID();
        UUID tag = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'agent-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'agent-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'agent-test')",
                kb, ws);
        // 邮箱唯一：带一次性后缀，别撞上库里留下的账号
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Zhang San')", user, org,
                "agent-test-" + tag + "@utopia.test");
        // token_hash 也唯一
        jdbc.update("INSERT INTO personal_tokens (id, user_id, name, token_hash, token_prefix, scope) "
                        + "VALUES (?, ?, 'Meeting notes agent', ?, 'utp_pat_test', 'write')", token,
                user, "hash-" + tag);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Organization')",
                etype, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'Acme')",
                acme, kb, etype);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'Zenith')",
                zenith, kb, etype);
        // blob 按 sha 跨库共用，sha 也带一次性后缀
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status) "
                        + "VALUES (?, ?, 'memory-log.md', ?, 'ready')", doc, kb, "sha-" + tag);
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) "
                        + "VALUES (?, ?, ?, 0, 'Acme partnered with Zenith on 2026-03-01.')", chunk,
                kb, doc);

        java.util.function.BiFunction<UUID, UUID, UUID> propose = (objectId, proposedToken) -> {
            PendingService.Outcome o = pending.propose(new PendingService.Proposal(kb, acme, null,
                    objectId, null, "partnered with", Validity.empty(), 0.6f, chunk, user,
                    proposedToken, null, null, null, null));
            if (!o.isProposed()) {
                throw new IllegalStateException("提议没有入队：" + o);
            }
            return o.id();
        };
        UUID byAgent = propose.apply(zenith, token);
        // 同一个 (主语, 谓词, 宾语) 会被判成 AlreadyPending，所以第二条换个宾语
        UUID byPerson = propose.apply(acme, null);

        List<PendingFactView> queue = pending.list(kb, 50, 0);
        PendingFactView agent = queue.stream().filter(v -> v.id().equals(byAgent)).findFirst()
                .orElseThrow(() -> new IllegalStateException("待确认项不在队列里"));
        PendingFactView person = queue.stream().filter(v -> v.id().equals(byPerson)).findFirst()
                .orElseThrow(() -> new IllegalStateException("待确认项不在队列里"));

        // 1. 令牌记的那条：人和 agent 都答得出。人是身份，agent 是「哪一个客户端」——
        //    同一个人挂三个 agent 时，卡片上只有后者分得开
        assertEquals("Zhang San", agent.proposedByName());
        assertEquals("Meeting notes agent", agent.proposedTokenName(),
                "令牌名没跟出来——多半是 VIEW_SELECT 少了那个 join");

        // 2. 网页端对话记的那条：没有 agent，但这一条本身不能消失
        //    （join 写成内连接就会整条不见，而那是最难发现的一种丢失）
        assertEquals("Zhang San", person.proposedByName());
        assertEquals(null, person.proposedTokenName());

        // 先删库再删 org：删 org 级联到 users，而 pending_facts.proposed_by 是不带
        // ON DELETE 的外键（台账该拦住这种删除）；库不跟着 org 级联
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        assertEquals(1, jdbc.update("DELETE FROM organizations WHERE id = ?", org),
                "一次性 org 没删掉");
    }
}
