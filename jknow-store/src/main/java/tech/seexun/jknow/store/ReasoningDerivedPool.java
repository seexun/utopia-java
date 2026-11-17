package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.reason.Derive;
import tech.seexun.jknow.reason.Derive.Derived;
import tech.seexun.jknow.reason.Kind;
import tech.seexun.jknow.reason.Violation;
import tech.seexun.jknow.store.DerivedPoolProvider.Contradiction;
import tech.seexun.jknow.store.DerivedPoolProvider.Pool;
import tech.seexun.jknow.store.DerivedPoolProvider.RuleDisagreement;
import tech.seexun.jknow.store.mapper.ReasoningMapper;

/**
 * {@link DerivedPoolProvider} 的真身（M3 任务 6）——detect（审核队列）与 materialize（落库）
 * <b>共用同一次求解</b>（reasoning.rs:238-354 detect 的派生侧）：这里报的正是那边拦下的，
 * 两边各算一套的话，队列会跟图对不上（0017 / 0047 决定 3，单一求解源不变量）。
 *
 * <p>矛盾行的键与外键都必须回到 {@code facts} 的行——链上最后一条<b>断言</b>前提
 * （每轮都不同的临时 id 不属于任何表）；整条前提链都推出来时该撞法不落行。
 */
@Component
public class ReasoningDerivedPool implements DerivedPoolProvider {

    private final ReasoningSolver solver;
    private final ReasoningMapper mapper;

    public ReasoningDerivedPool(ReasoningSolver solver, ReasoningMapper mapper) {
        this.solver = solver;
        this.mapper = mapper;
    }

    /** 一次求解要用到的名字（reasoning.rs:745-790 names_for）：实体名 + 谓词标签。 */
    private record Names(Map<UUID, String> entities, Map<UUID, String> predicates) {

        String entity(UUID id) {
            return entities.getOrDefault(id, "?");
        }

        String predicate(UUID id) {
            return predicates.getOrDefault(id, "?");
        }
    }

    @Override
    public Pool solve(UUID kbId) {
        ReasoningSolver.Resolved resolved = solver.resolve(kbId);
        Derive.Derivation derivation = resolved.checked.candidates();
        Derive.Contradictions clashes = resolved.checked.clashes();

        Names names = namesFor(derivation, clashes);
        List<Contradiction> contradictions = new ArrayList<>();
        Map<UUID, Integer> perPred = new HashMap<>();
        int capped = 0;
        for (Derive.Clash c : clashes.withAssertions) {
            Derived d = derivation.facts.get(c.derived());
            // 键与外键都要 facts 里的行。链经过规则推出的关系边时，最后一条前提是它的
            // 临时 id——每轮都不同——退到链上最后一条断言
            UUID last = null;
            for (int i = d.premises().size() - 1; i >= 0; i--) {
                if (resolved.assertedIds.contains(d.premises().get(i))) {
                    last = d.premises().get(i);
                    break;
                }
            }
            if (last == null) {
                // 整条前提链都是规则推出的临时 id：没有可落库的键与外键，这个撞法不落行
                continue;
            }
            UUID against = resolved.assertedIds.contains(c.against()) ? c.against() : last;
            final UUID keyLeft = against;
            final UUID keyRight = last;
            if (contradictions.stream().anyMatch(x ->
                    x.violation().left().equals(keyLeft)
                            && x.violation().right().equals(keyRight))) {
                continue;
            }
            int n = perPred.getOrDefault(d.predicate(), 0);
            if (n >= ReasoningSolver.MAX_CLASHES_PER_PREDICATE) {
                capped++;
                continue;
            }
            perPred.put(d.predicate(), n + 1);
            Derive.Span span = Derive.validity(d.premises(), resolved.spans);
            ObjectNode detail = JsonNodeFactory.instance.objectNode();
            detail.put("axiom", c.axiom().asString());
            detail.put("rule", d.rule().asString());
            UUID attributeRule = resolved.checked.candidateRule().get(c.derived());
            if (attributeRule != null) {
                detail.put("attribute_rule_id", attributeRule.toString());
            } else {
                detail.putNull("attribute_rule_id");
            }
            detail.put("via", d.via().toString());
            detail.put("via_label", names.predicate(d.via()));
            detail.put("subject_id", d.subject().toString());
            detail.put("subject", names.entity(d.subject()));
            detail.put("predicate_id", d.predicate().toString());
            detail.put("predicate", names.predicate(d.predicate()));
            detail.put("object_id", d.object().toString());
            detail.put("object", names.entity(d.object()));
            if (span != null) {
                if (span.from() != null) {
                    detail.put("valid_from", java.time.OffsetDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(span.from()),
                            java.time.ZoneOffset.UTC).toString());
                } else {
                    detail.putNull("valid_from");
                }
                if (span.to() != null) {
                    detail.put("valid_to", java.time.OffsetDateTime.ofInstant(
                            java.time.Instant.ofEpochSecond(span.to()),
                            java.time.ZoneOffset.UTC).toString());
                } else {
                    detail.putNull("valid_to");
                }
            } else {
                detail.putNull("valid_from");
                detail.putNull("valid_to");
            }
            ArrayNode premises = detail.putArray("premises");
            for (UUID p : d.premises()) {
                premises.add(p.toString());
            }
            // 证据链只留断言 id：链经规则推出的关系边时 premises 里混着那一轮的临时
            // id——它不属于任何表，出库后永远解析不出
            List<UUID> path = d.premises().stream()
                    .filter(resolved.assertedIds::contains).toList();
            contradictions.add(new Contradiction(
                    new Violation(Kind.DERIVED_CONTRADICTION, against, last, path), detail));
        }

        // 派生之间互撞按规则对聚合（RuleClash → RuleDisagreement 一比一；同对谓词几种
        // 撞法各一条，落库侧按谓词对合成一行 detail）
        List<RuleDisagreement> ruleClashes = new ArrayList<>();
        for (Derive.RuleClash rc : clashes.betweenDerivations) {
            List<String[]> examples = new ArrayList<>();
            for (int[] pair : rc.pairs()) {
                if (examples.size() >= 3) {
                    break;
                }
                examples.add(new String[]{triple(derivation, names, pair[0]),
                        triple(derivation, names, pair[1])});
            }
            ruleClashes.add(new RuleDisagreement(rc.a().predicate(), rc.a().rule().asString(),
                    rc.b().predicate(), rc.b().rule().asString(), rc.axiom().asString(),
                    rc.pairs().size(), examples));
        }
        return new Pool(contradictions, capped, ruleClashes);
    }

    private static String triple(Derive.Derivation derivation, Names names, int index) {
        Derived d = derivation.facts.get(index);
        return names.entity(d.subject()) + " · " + names.predicate(d.predicate()) + " · "
                + names.entity(d.object());
    }

    private Names namesFor(Derive.Derivation derivation, Derive.Contradictions clashes) {
        Set<UUID> ents = new HashSet<>();
        Set<UUID> preds = new HashSet<>();
        java.util.function.IntConsumer want = i -> {
            Derived d = derivation.facts.get(i);
            ents.add(d.subject());
            ents.add(d.object());
            preds.add(d.predicate());
            preds.add(d.via());
        };
        for (Derive.Clash c : clashes.withAssertions) {
            want.accept(c.derived());
        }
        for (Derive.RuleClash rc : clashes.betweenDerivations) {
            // 例句只取前三对（reasoning.rs:768-771），名字也只为它们查
            for (int k = 0; k < rc.pairs().size() && k < 3; k++) {
                want.accept(rc.pairs().get(k)[0]);
                want.accept(rc.pairs().get(k)[1]);
            }
        }
        for (Derive.RuleClash rc : clashes.betweenDerivations) {
            preds.add(rc.a().predicate());
            preds.add(rc.b().predicate());
        }
        Map<UUID, String> entities = new LinkedHashMap<>();
        if (!ents.isEmpty()) {
            for (ReasoningMapper.LabelRow r : mapper.selectEntityNames(List.copyOf(ents))) {
                entities.put(r.id(), r.label());
            }
        }
        Map<UUID, String> predicates = new LinkedHashMap<>();
        if (!preds.isEmpty()) {
            for (ReasoningMapper.LabelRow r : mapper.selectPredicateLabels(List.copyOf(preds))) {
                predicates.put(r.id(), r.label());
            }
        }
        return new Names(entities, predicates);
    }
}
