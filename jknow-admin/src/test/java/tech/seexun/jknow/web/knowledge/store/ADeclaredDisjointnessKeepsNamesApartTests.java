package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_declared_disjointness_keeps_names_apart.rs（198 行，1 例）——
 * 0016 B3：owl:disjointWith 进消解——本体声明了互斥的两个类，同名也不进审阅队列。
 *
 * <p>消解判「两个类能不能指同一个东西」有三层：硬表 CONFUSABLE_TYPE_KEYS、类层级
 * （同一支系当易混，#226）、本体声明的互斥。这里守的是<b>声明优先于前两层</b>：
 * <ol>
 *   <li>没声明时行为不变：organization vs project 照硬表进队列，corporation vs
 *       federal_agency 照类层级（共有非根祖先 organization）进队列</li>
 *   <li>声明 organization ⟂ project 之后，同名的 organization / project 分开，不进队列</li>
 *   <li>声明 corporation ⟂ agency 之后，federal_agency（agency 的子类）跟 corporation
 *       也分开——<b>互斥是继承的</b>，声明在父类上就够</li>
 * </ol>
 */
class ADeclaredDisjointnessKeepsNamesApartTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OntologyService ontology;

    @Autowired
    ResolutionService resolution;

    record Fx(UUID org, UUID kb, UUID organization, UUID project, UUID corporation, UUID agency,
              UUID federalAgency) {
    }

    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        // 顶上要有一个根：类层级那条规则里「共有的祖先」不算根（schema.org 里万物皆
        // Thing，算上它 Person 与 Organization 也成了一家），所以 organization 得有父类
        UUID thing = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID project = Uuids.nowV7();
        UUID corporation = Uuids.nowV7();
        UUID agency = Uuids.nowV7();
        UUID federalAgency = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'disjoint-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'disjoint-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'disjoint-test')",
                kb, ws);
        for (Object[] row : new Object[][]{{thing, "thing"}, {organization, "organization"},
                {project, "project"}, {corporation, "corporation"}, {agency, "agency"},
                {federalAgency, "federal_agency"}}) {
            jdbc.update(
                    "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[1]);
        }
        for (Object[] row : new Object[][]{{organization, thing}, {project, thing},
                {corporation, organization}, {agency, organization}, {federalAgency, agency}}) {
            jdbc.update(
                    "INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)",
                    row[0], row[1]);
        }
        return new Fx(org, kb, organization, project, corporation, agency, federalAgency);
    }

    private UUID entity(Fx f, String name, UUID typeId) {
        UUID id = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, f.kb(), typeId, name);
        return id;
    }

    /** 消解一条 mention，回它挂上的「类型漂移」审核对指向谁。 */
    private List<UUID> driftReviews(Fx f, String name, UUID typeId) {
        var r = resolution.resolveMention(f.kb(), typeId, name, null, null, null, List.of());
        assertTrue(r.created(), "a cross-type same name is a new entity: keep apart, never merge");
        return r.reviews().stream()
                .filter(x -> x.reason().startsWith("type_drift|"))
                .map(ResolutionService.ReviewRequest::otherId)
                .toList();
    }

    @Test
    void aDeclaredDisjointnessKeepsNamesApart() {
        Fx f = seed();
        try {
            // 1. 没声明：硬表与类层级照旧
            UUID orion = entity(f, "Orion", f.organization());
            assertEquals(List.of(orion), driftReviews(f, "Orion", f.project()),
                    "organization vs project is confusable by the hard-coded list");
            UUID acme = entity(f, "Acme", f.corporation());
            assertEquals(List.of(acme), driftReviews(f, "Acme", f.federalAgency()),
                    "corporation vs federal_agency share the ancestor organization:"
                            + " kin, so Review");

            // 2. 声明 organization ⟂ project：硬表说易混，本体说互斥——本体赢
            ontology.setDisjointFor(f.kb(), f.organization(), List.of(f.project()));
            entity(f, "Vega", f.organization());
            assertTrue(driftReviews(f, "Vega", f.project()).isEmpty(),
                    "a declared disjointness wins over the hard-coded list");

            // 3. 声明 corporation ⟂ agency：federal_agency 是 agency 的子类，互斥继承下来，
            //    类层级说一家也不算
            ontology.setDisjointFor(f.kb(), f.corporation(), List.of(f.agency()));
            entity(f, "Beta", f.corporation());
            assertTrue(driftReviews(f, "Beta", f.federalAgency()).isEmpty(),
                    "a disjointness declared on the parent reaches the child and wins over"
                            + " kinship");
            // 反过来问也一样：表里两个方向各一行，继承沿另一头的祖先链走
            entity(f, "Gamma", f.federalAgency());
            assertTrue(driftReviews(f, "Gamma", f.corporation()).isEmpty(),
                    "the declaration holds from either side");

            // 4. 取消声明，回到没声明时的行为——编辑必须能撤
            ontology.setDisjointFor(f.kb(), f.corporation(), List.of());
            UUID delta = entity(f, "Delta", f.corporation());
            assertEquals(List.of(delta), driftReviews(f, "Delta", f.federalAgency()),
                    "with the declaration gone, kinship sends the pair to Review again");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
