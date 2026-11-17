package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/merge_direction_counts_live_facts_in_each_base.rs（156 行）——
 * 合并方向只看有效事实；知识库限定不能改变度数和 UUID 平局规则（度数不排除名字事实，
 * kb 由标量子查询取；平局 uuidv7 早者存活，字节序）。
 */
class MergeDirectionCountsLiveFactsInEachBaseTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    private UUID fact(UUID kb, UUID predicate, UUID subject, UUID object) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id) VALUES (?, ?, ?, ?, ?)",
                id, kb, subject, predicate, object);
        return id;
    }

    private void assertDirection(UUID survivor, UUID merged, String reason) {
        // 两个输入顺序都要得出同一方向
        assertEquals(new ResolutionService.MergeDirection(survivor, merged),
                resolution.mergeDirection(survivor, merged), reason);
        assertEquals(new ResolutionService.MergeDirection(survivor, merged),
                resolution.mergeDirection(merged, survivor), reason);
    }

    @Test
    void merge_direction_counts_live_facts_in_each_base() {
        UUID org = Uuids.nowV7();
        UUID workspace = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID otherKb = Uuids.nowV7();
        UUID predicate = Uuids.nowV7();
        UUID otherPredicate = Uuids.nowV7();
        UUID first = Uuids.nowV7();
        UUID second = Uuids.nowV7();
        UUID older = first.compareTo(second) < 0 ? first : second;
        UUID newer = first.compareTo(second) < 0 ? second : first;
        UUID peer = Uuids.nowV7();
        UUID other = Uuids.nowV7();
        UUID otherPeer = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'merge-degree-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'merge-degree-test')",
                workspace, org);
        for (UUID[] bk : new UUID[][]{{kb, predicate}, {otherKb, otherPredicate}}) {
            jdbc.update(
                    "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'merge-degree-test')",
                    bk[0], workspace);
            jdbc.update(
                    "INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'related_to', 'related to')",
                    bk[1], bk[0]);
        }
        for (Object[] e : new Object[][]{{older, kb, "Older"}, {newer, kb, "Newer"},
                {peer, kb, "Peer"}, {other, otherKb, "Other"}, {otherPeer, otherKb, "Other peer"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                    e[0], e[1], e[2]);
        }
        try {
            assertDirection(older, newer, "zero degrees use the smaller UUID");

            fact(kb, predicate, older, older);
            fact(kb, predicate, peer, newer);
            UUID outgoing = fact(kb, predicate, newer, peer);
            // older 的自关联只算一条；若拆成两次计数相加，2:2 会错误地留下 older。
            assertDirection(newer, older, "incoming and outgoing facts beat one self-reference");

            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", outgoing);
            assertDirection(older, newer, "invalidated facts do not break the one-to-one degree tie");

            fact(kb, predicate, older, peer);
            assertDirection(older, newer, "the larger live degree survives");

            fact(otherKb, otherPredicate, other, other);
            fact(otherKb, otherPredicate, other, otherPeer);
            fact(otherKb, otherPredicate, otherPeer, other);
            assertDirection(older, newer, "facts in another knowledge base leave the result unchanged");
            assertDirection(other, older, "each entity's degree uses its own knowledge base");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ANY(?::uuid[])",
                    "{" + kb + "," + otherKb + "}");
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
