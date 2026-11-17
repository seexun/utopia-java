package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/facts_corroborate_an_identity.rs（310 行，#331，续 #270/#329）——
 * 画像分不开同名的人时，事实能分开。一个库里有两个「张伟」：一个在平台工程部，一个在
 * 财务部。<b>证据只往一个方向走</b>：一致是「同一人」的弱证据，不一致什么都不是——
 * 在 Acme 上班又在 Zenith 兼职的人有两个 works_for，他仍是一个人。这里既钉「命中就
 * 定案」，也钉「命中不了就一切照旧」——后者才是不把弱证据当强证据的那道闸。
 */
class FactsCorroborateAnIdentityTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    record Fx(UUID kb, UUID person, UUID zhangA, UUID zhangB) {
    }

    /**
     * 两个同名的张伟。shared 为真时再给他们一个<b>共同</b>的雇主——那条事实对分辨
     * 他们毫无帮助，却最容易在文档里撞上（SQL 侧 NOT EXISTS 排除）。
     */
    private Fx seed(boolean shared) {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID platform = Uuids.nowV7();
        UUID finance = Uuids.nowV7();
        UUID both = Uuids.nowV7();
        UUID zhangA = Uuids.nowV7();
        UUID zhangB = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'corroboration-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'corroboration-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'corroboration-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'Organization')",
                organization, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal)
                 VALUES (?, ?, 'works_for', 'works for', 'state')
                """, worksFor, kb);
        for (Object[] e : new Object[][]{{platform, organization, "Platform Engineering"},
                {finance, organization, "Finance"}, {both, organization, "Nebula Holdings"},
                {zhangA, person, "Zhang Wei"}, {zhangB, person, "Zhang Wei"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        // 两人的画像完全相同：同一个 chunk 播的种（#221 之后是常态）。向量注定分不开他们
        for (UUID id : new UUID[]{zhangA, zhangB}) {
            jdbc.update(
                    "UPDATE entities SET profile_embedding = ?::vector, profile_n = 1 WHERE id = ?",
                    "[1,0,0]", id);
        }
        List<UUID[]> pairs = new ArrayList<>(List.of(new UUID[]{zhangA, platform},
                new UUID[]{zhangB, finance}));
        if (shared) {
            pairs.add(new UUID[]{zhangA, both});
            pairs.add(new UUID[]{zhangB, both});
        }
        for (UUID[] p : pairs) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, Uuids.nowV7(), kb, p[0], worksFor, p[1]);
        }
        return new Fx(kb, person, zhangA, zhangB);
    }

    private void dropKb(Fx f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
    }

    /** 并列 + 原文点了其中一个人的部门 → 定案，不再掷硬币也不再劳烦人。 */
    @Test
    void a_fact_in_the_text_settles_a_namesake_tie() {
        Fx f = seed(false);
        try {
            float[] ctx = {1.0f, 0.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null,
                    "Zhang Wei of Finance signed off on the quarterly report.", List.of());
            assertFalse(r.created(), "原文点名了财务，不该再新建第三个张伟");
            assertEquals(f.zhangB(), r.entityId(), "该归到财务部那个张伟");
            assertTrue(r.reviews().isEmpty(), "事实已经把人分开了，不该再入人工审核对");
        } finally {
            dropKb(f);
        }
    }

    /** 原文把两个部门都提了 → 这条线索在他们之间也分不开，维持 #329 的行为。 */
    @Test
    void a_clue_that_points_at_both_settles_nothing() {
        Fx f = seed(false);
        try {
            float[] ctx = {1.0f, 0.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null,
                    "Platform Engineering and Finance both sent a Zhang Wei to the review.",
                    List.of());
            assertTrue(r.created(), "两边都命中就等于没命中，仍该新建实体");
            assertEquals(2, r.reviews().size(), "仍该对两个候选各入一条人工审核对");
        } finally {
            dropKb(f);
        }
    }

    /**
     * <b>共同的雇主不是线索。</b>两个张伟都在星云控股，文档提到星云控股什么也没说明
     * ——这条事实在 SQL 里就该被排除，否则它是最容易撞上的那种误判。
     */
    @Test
    void an_employer_they_share_is_not_a_clue() {
        Fx f = seed(true);
        try {
            float[] ctx = {1.0f, 0.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null,
                    "Zhang Wei has worked at Nebula Holdings for six years.", List.of());
            assertTrue(r.created(), "两人共有的雇主没有分辨力，不该拿它把 mention 判给其中一个");
            assertEquals(2, r.reviews().size(), "仍是分不开，照旧入两条人工审核对");
        } finally {
            dropKb(f);
        }
    }

    /**
     * 谁都没提到 → 一切照旧。<b>这一条是那道闸</b>：不一致（或没提）绝不能被当成
     * 「不是同一人」的证据，否则兼职的人会被拆成两个。
     */
    @Test
    void text_that_names_neither_changes_nothing() {
        Fx f = seed(false);
        try {
            float[] ctx = {1.0f, 0.0f, 0.0f};
            var withText = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null,
                    // 提到的是第三家公司：一致的证据没有，"不一致"也不算证据
                    "Zhang Wei moonlights at Zenith Robotics on weekends.", List.of());
            assertTrue(withText.created(), "没有命中就该保持 #329 的行为");
            assertEquals(2, withText.reviews().size(), "两条人工审核对照旧");
            assertNotEquals(f.zhangA(), withText.entityId());
            assertNotEquals(f.zhangB(), withText.entityId());
        } finally {
            dropKb(f);
        }
    }

    /**
     * 灰区（分数在 SIM_NEW..SIM_ATTACH）同样受旁证左右：向量说「不够像」，而原文点了名。
     * 阈值一个都没动，动的是在阈值之外多问了一句。
     */
    @Test
    void the_grey_zone_listens_to_the_facts_too() {
        Fx f = seed(false);
        try {
            // 让两个候选分开且都落在灰区：与 A 约 0.45，与 B 约 0.40，
            // 差 0.05 > SIM_TIE_MARGIN，所以走的是灰区那条路而不是并列那条
            jdbc.update("UPDATE entities SET profile_embedding = '[0,1,0]'::vector WHERE id = ?",
                    f.zhangB());
            float[] ctx = {0.45f, 0.40f, 0.80f};
            var r = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null,
                    "The Finance lead, Zhang Wei, approved it.", List.of());
            assertFalse(r.created(), "灰区里原文点了名，就不该再新建一个同名实体");
            assertEquals(f.zhangB(), r.entityId(), "该归到财务部那个");
        } finally {
            dropKb(f);
        }
    }
}
