package tech.seexun.jknow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 移植自 crates/utopia-store/src/type_bindings.rs 内联 mod tests（:629-708，5 例）——
 * 类别词归一 + ClassSnapshot 指纹：候选的先后不算；编辑/祖父边/删候选都算变；菱形与环
 * 仍得出一个闭包。纯函数直译，无库（与 Rust 同模块内联测试对等：package-private 字段直取）。
 */
class TypeBindingsPureTests {

    @Test
    void aKindWordIsOneWordHoweverSpacedOrCased() {
        assertEquals("stockholder proposal",
                TypeBindingsService.normalize("  Stockholder   Proposal "));
        assertEquals("company", TypeBindingsService.normalize("Company"));
        assertEquals("指标", TypeBindingsService.normalize("指标"));
        assertEquals("", TypeBindingsService.normalize("   "));
    }

    private static OffsetDateTime at(String s) {
        return OffsetDateTime.parse(s);
    }

    /** organization ⊂ legal_entity；agent 暂时没有父类。固定 id（agent &lt; legal 的字节序）。 */
    private Object[] snapshot() {
        UUID org = UUID.fromString("018f6b2d-0000-7000-8000-000000000001");
        UUID legal = UUID.fromString("018f6b2d-0000-7000-8000-000000000003");
        UUID agent = UUID.fromString("018f6b2d-0000-7000-8000-000000000002");
        TypeBindingsService.ClassSnapshot s = new TypeBindingsService.ClassSnapshot();
        for (UUID id : new UUID[]{org, legal, agent}) {
            s.versions.put(id, at("2026-09-26T00:00:00Z"));
            s.parents.put(id, List.of());
        }
        s.parents.put(org, List.of(legal));
        return new Object[]{s, org, legal, agent};
    }

    @Test
    void aBasisNamesWhatWasShownNotTheOrderItWasShownIn() {
        Object[] setup = snapshot();
        TypeBindingsService.ClassSnapshot s = (TypeBindingsService.ClassSnapshot) setup[0];
        UUID org = (UUID) setup[1];
        UUID legal = (UUID) setup[2];
        assertEquals(s.basis(List.of(org, legal)), s.basis(List.of(legal, org)));
        assertEquals(s.basis(List.of(org, legal)), s.basis(List.of(org, legal, org)));
        assertNotEquals(s.basis(List.of(org, legal)), s.basis(List.of(org)));
    }

    @Test
    void anEditAGrandparentEdgeOrADeletedCandidateChangesTheBasis() {
        Object[] setup = snapshot();
        TypeBindingsService.ClassSnapshot s = (TypeBindingsService.ClassSnapshot) setup[0];
        UUID org = (UUID) setup[1];
        UUID legal = (UUID) setup[2];
        UUID agent = (UUID) setup[3];
        String before = s.basis(List.of(org));

        // 编辑：updated_at 挪了
        TypeBindingsService.ClassSnapshot edited = new TypeBindingsService.ClassSnapshot();
        edited.versions.putAll(s.versions);
        edited.parents.putAll(s.parents);
        edited.versions.put(org, at("2026-09-26T00:00:01Z"));
        assertNotEquals(before, edited.basis(List.of(org)), "an edit moves updated_at");

        // 父边的增删不碰 updated_at：org 的祖父变了，org 的指纹也得变
        TypeBindingsService.ClassSnapshot rooted = new TypeBindingsService.ClassSnapshot();
        rooted.versions.putAll(s.versions);
        rooted.parents.putAll(s.parents);
        rooted.parents.put(legal, List.of(agent));
        assertNotEquals(before, rooted.basis(List.of(org)), "a grandparent is in the closure");

        // 删掉的候选
        TypeBindingsService.ClassSnapshot gone = new TypeBindingsService.ClassSnapshot();
        gone.versions.put(legal, at("2026-09-26T00:00:00Z"));
        assertNotEquals(before, gone.basis(List.of(org)), "a deleted candidate is a change");
    }

    @Test
    void aDiamondOrACycleStillGivesOneClosure() {
        Object[] setup = snapshot();
        TypeBindingsService.ClassSnapshot s = (TypeBindingsService.ClassSnapshot) setup[0];
        UUID org = (UUID) setup[1];
        UUID legal = (UUID) setup[2];
        UUID agent = (UUID) setup[3];
        // 菱形：org → legal、org → agent，legal → agent
        s.parents.put(org, List.of(legal, agent));
        s.parents.put(legal, List.of(agent));
        List<UUID> ancestors = s.ancestors(org);
        assertEquals(List.of(agent, legal), ancestors.stream().sorted().toList());
        // 编辑器不许环；万一有，走得完且不把自己算进祖先
        s.parents.put(agent, List.of(org));
        assertFalse(s.ancestors(org).contains(org));
        assertEquals(16, s.basis(List.of(org, legal, agent)).length());
    }

    /** type_bindings.rs:213-220：与 phrase_bindings::basis_of 同一个 FNV-1a 哈希。 */
    @Test
    void theFingerprintIsFnv1a64() {
        // FNV-1a 64 of "" = cbf29ce484222325
        assertEquals("cbf29ce484222325", TypeBindingsService.fingerprint(""));
    }

    /**
     * M-4 前置（任务 7 审查记入任务 8）：basis 的时间戳是 chrono {@code to_rfc3339()} 的
     * AutoSi 形态——亚秒按值去尾零组（0/3/6/9 位），UTC 写 {@code +00:00}。PG 的时间戳
     * 只有微秒，同一时刻 Rust 侧写 6 位、本侧必须一字不差，否则 Java 每轮都把 Rust 存的
     * basis 判成 stale。
     */
    @Test
    void theBasisTimestampIsChronoAutoSiNotFixedNanos() {
        // 整秒：没有亚秒段
        assertEquals("2026-09-26T08:30:00+00:00",
                PhraseBindingsService.rfc3339(at("2026-09-26T08:30:00Z")));
        // PG 微秒 → 恰 6 位（.123456 秒）
        assertEquals("2026-09-26T08:30:00.123456+00:00",
                PhraseBindingsService.rfc3339(
                        at("2026-09-26T08:30:00.123456Z")));
        // 尾零组去掉：.123000 → 3 位；.000000 → 没有亚秒段
        assertEquals("2026-09-26T08:30:00.123+00:00",
                PhraseBindingsService.rfc3339(
                        at("2026-09-26T08:30:00.123000Z")));
        assertEquals("2026-09-26T08:30:00+00:00",
                PhraseBindingsService.rfc3339(
                        at("2026-09-26T08:30:00.000000Z")));
        // 纳秒精度（Java 侧自产的时刻）→ 9 位
        assertEquals("2026-09-26T08:30:00.123456789+00:00",
                PhraseBindingsService.rfc3339(
                        at("2026-09-26T08:30:00.123456789Z")));
        // 任意 offset 先归一 UTC（与 chrono DateTime<Utc> 同一时刻）
        assertEquals("2026-09-26T00:30:00+00:00",
                PhraseBindingsService.rfc3339(at("2026-09-26T08:30:00+08:00")));
    }
}
