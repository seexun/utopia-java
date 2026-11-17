package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.PendingReading;
import tech.seexun.jknow.store.mapper.ImplicationRulesMapper.Rule;
import tech.seexun.jknow.store.mapper.NamesMapper;

/**
 * 蕴含规则（implication_rules.rs 303 行对等，0044 决定 3 第五片）：一种形状的陈述、
 * 或带某个类别词的东西，蕴含另一条属性的事实。宾语要么就是陈述的宾语，要么由一个读数
 * 从宾语的字里读出来。与绑定同一套生命周期：对齐器提（{@link #propose}，代理），
 * 工作台批（{@link #decideWithDelivery}，人），人的判定不被代理盖。
 *
 * <p>执行在 {@code TypedMaterializeService} 里，<b>没有模型调用</b>：读数由
 * {@code read_phrases} 任务先算进 phrase_readings，物化只查缓存，缓存里没有的这一轮
 * 就不算，等缓存填上再来。
 */
@Service
public class ImplicationRulesService {

    /**
     * implication_rules.rs:17-30 READINGS：读数的种类。字符串进库、进提示词，所以是
     * 常量而不是枚举；执行它们的是模型，这里只定义「问什么」。
     */
    public static final String[][] READINGS = {
            {"country_of_nationality",
                    "the country a nationality or demonym names (British → United Kingdom, 法国 → France)"},
            {"country_of_place",
                    "the country a place belongs to (Piedmont region of Virginia → United States, Lyon → France)"},
            {"year_of_phrase",
                    "the year a phrase gives, as a four-digit number (\"the summer of 1952\" → 1952)"},
    };

    /** implication_rules.rs:209 填读数缓存的任务的种类（{@code read_phrases}）。 */
    public static final String READ_KIND = "read_phrases";

    private final ImplicationRulesMapper mapper;
    private final ResolutionService resolution;
    private final NamesService names;
    private final JobsService jobs;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public ImplicationRulesService(ImplicationRulesMapper mapper, ResolutionService resolution,
                                   NamesService names, JobsService jobs,
                                   PlatformTransactionManager txManager,
                                   org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.resolution = resolution;
        this.names = names;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
    }

    /** implication_rules.rs:32-34。 */
    public static boolean readingIsKnown(String reading) {
        for (String[] r : READINGS) {
            if (r[0].equals(reading)) {
                return true;
            }
        }
        return false;
    }

    /** implication_rules.rs:36-39 读数的输入归一：与短语、类别词同一条规矩。 */
    public static String normalize(String s) {
        return PhraseBindingsService.normalize(s);
    }

    /** implication_rules.rs:60-76 对齐器提的一条规则。 */
    public record Proposal(String trigger, String phrase, UUID subjectTypeId, UUID objectTypeId,
                           boolean objectIsValue, UUID concludePropertyId, String reading,
                           String status, JsonNode votes, String basis, long statementCount,
                           List<String> examples) {
    }

    /**
     * implication_rules.rs:80-135 {@code propose}：记下对齐器的提案；同一条规则已经有人
     * 判过的原样留着（返回 null）。代理自己的旧行被新提案覆盖——指纹变了对齐器才会
     * 再提，覆盖的是过期的看法。
     */
    public UUID propose(UUID kbId, Proposal p) {
        if (!"phrase".equals(p.trigger()) && !"kind_word".equals(p.trigger())) {
            throw AppException.validation("unknown trigger " + p.trigger());
        }
        if (!"proposed".equals(p.status()) && !"rejected".equals(p.status())) {
            throw AppException.validation("a proposal is proposed or rejected, not " + p.status());
        }
        if (p.reading() != null && !readingIsKnown(p.reading())) {
            throw AppException.validation("unknown reading " + p.reading());
        }
        String phrase = normalize(p.phrase());
        if (phrase.isEmpty()) {
            throw AppException.validation("an empty phrase implies nothing");
        }
        return mapper.upsertProposal(Uuids.nowV7(), kbId, p.trigger(), phrase,
                p.subjectTypeId(), p.objectIsValue() ? null : p.objectTypeId(), p.objectIsValue(),
                p.concludePropertyId(), p.reading(), p.status(), p.votes(), p.basis(),
                p.statementCount() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) p.statementCount(),
                p.examples());
    }

    /** implication_rules.rs:137-150 list（status 可空）。 */
    public List<Rule> list(UUID kbId, String status) {
        return mapper.selectList(kbId, status);
    }

    /** implication_rules.rs:152-163 get。 */
    public Rule get(UUID kbId, UUID id) {
        return mapper.selectGet(kbId, id);
    }

    /**
     * implication_rules.rs:168-206 {@code decide_with_delivery}：人批或驳一条规则，与它
     * 的后续工作<b>同一事务</b>提交（0051）。批准且要读数的：先排 {@code read_phrases}
     * 把缓存填上（它跑完自己会排物化）；不要读数的、或驳回的：直接排物化——驳回也要
     * 重算，隐含行得退掉。返回 job id；规则不在返回 null。
     */
    public Long decideWithDelivery(UUID kbId, UUID id, boolean approve, JsonNode votes) {
        return tx.execute(t -> {
            java.util.List<String> row = mapper.decideByPerson(kbId, id,
                    approve ? "approved" : "rejected", votes);
            if (row.isEmpty()) {
                return null;
            }
            String reading = row.get(0);
            String kind = approve && reading != null
                    ? ImplicationRulesService.READ_KIND
                    : PhraseBindingsService.MATERIALIZE_KIND;
            return jobs.enqueueWithMaxAttemptsTx(kind,
                    json.createObjectNode().put("kb_id", kbId.toString()), 3);
        });
    }

    /**
     * implication_rules.rs:220-241 {@code pending_readings}：已批准的规则里，还没有缓存
     * 的 (读数, 字)。答过「读不出来」的也算缓存过，不再列。
     */
    public List<PendingReading> pendingReadings(UUID kbId) {
        return mapper.selectPendingReadings(kbId);
    }

    /**
     * implication_rules.rs:256-278 {@code record_reading}：记一条读数的答案——库里的
     * 一样东西、一个字面值，或两者都空（读不出来，也记，别再问）。
     */
    public void recordReading(UUID kbId, String reading, String phrase, UUID entityId,
                              JsonNode value) {
        mapper.upsertReading(kbId, reading, normalize(phrase), entityId, value);
    }

    /**
     * implication_rules.rs:282-299 {@code resolve_or_create_named}：读数读出了一个名字：
     * 库里有这样东西就是它，没有就建一个有名字的。名字事实照 0041 记（known_as）——
     * 这是召回的桥，读数读出的国家下次就能被认出来。
     */
    public UUID resolveOrCreateNamed(UUID kbId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw AppException.validation("an empty name names nothing");
        }
        UUID existing = resolution.existingByName(kbId, trimmed);
        if (existing != null) {
            return existing;
        }
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                id, kbId, trimmed);
        names.record(kbId, id, trimmed, null, null);
        return id;
    }
}
