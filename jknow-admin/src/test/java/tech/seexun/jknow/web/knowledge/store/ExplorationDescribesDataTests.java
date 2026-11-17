package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.WorkspaceKbWriteService;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 库的数据描述与约定是两列、两条写入路径（tests/store/exploration_describes_the_data.rs
 * 1 例逐字移植，断言不改写）：探索每跑一次重写描述；人写的约定探索不碰。混在一个字段
 * 里，下一次探索就把人的答案盖了——这条测试守的就是「各写各的、互不覆盖」。
 */
class ExplorationDescribesDataTests extends StoreTestBase {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    WorkspaceKbWriteService kbs;
    @Autowired
    tech.seexun.jknow.store.mapper.KbFamilyMapper kbFamily;
    @Autowired
    KbMapper kbRows;

    static UUID kb;

    @BeforeAll
    void seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'describe-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'describe-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'describe-test')",
                kb, ws);
    }

    @AfterAll
    void cleanup() {
        // 只删知识库，不删 org——用户是软删除的，测试也不该造一个产品里不存在的动作
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
    }

    /** kbs.rs update 的 data_conventions 档（其余字段全 null = 不动）。 */
    private void setConventions(String conventions) {
        kbs.updateKb(kb, null, null, null, null, null, null, null, null, null, conventions);
    }

    private void rename(String name) {
        kbs.updateKb(kb, name, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void theDescriptionAndTheConventionsDoNotOverwriteEachOther() {
        // 人先写约定
        setConventions("Amounts are in cents.\nis_test = 1 is excluded.");
        // 探索写描述与问题
        ArrayNode questions = JSON.createArrayNode();
        questions.add("Which ord_st values count?");
        kbFamily.setDataDescription(kb, "dw.dwd_ord_dtl: one row per order line.", questions);
        var got = kbRows.selectById(kb);
        assertEquals("Amounts are in cents.\nis_test = 1 is excluded.", got.dataConventions());
        assertEquals("dw.dwd_ord_dtl: one row per order line.", got.dataDescription());
        assertEquals(questions, got.dataQuestions());

        // 探索再跑一次：描述换了，约定原样
        kbFamily.setDataDescription(kb, "second run", JSON.createArrayNode());
        got = kbRows.selectById(kb);
        assertEquals("second run", got.dataDescription());
        assertEquals(0, got.dataQuestions().size());
        assertTrue(got.dataConventions() != null && got.dataConventions().contains("is_test"),
                "探索不碰人写的约定");

        // 人改约定（PATCH 只送这一项）：描述原样。送 null 等于不改——与 description
        // 同一约定
        setConventions("cents only");
        got = kbRows.selectById(kb);
        assertEquals("cents only", got.dataConventions());
        assertEquals("second run", got.dataDescription());
        rename("renamed");
        assertEquals("cents only", kbRows.selectById(kb).dataConventions());
    }
}
