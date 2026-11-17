package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.ExplorationRun;
import tech.seexun.jknow.store.mapper.MappingsMapper;

/**
 * 一轮映射探索的账（exploration_runs.rs 137 行全量对等，#503）：扫了多大的东西、丢了
 * 什么、覆盖了多少。从前一轮探索只留下两样——concept_mappings 里若干行、以及「一条都没
 * 提出来」时的一条告警；覆盖率没有任何地方说，丢弃更是静默的（实测踩过一次：源叫
 * {@code tpch-2026-09-08-12-30}，模型照着 schema 回 {@code tpch}，十二条一条不剩地被
 * 吞掉，任务照样 done）。
 */
@Service
public class ExplorationRunsService {

    private final MappingsMapper mappings;

    public ExplorationRunsService(MappingsMapper mappings) {
        this.mappings = mappings;
    }

    /**
     * 丢弃原因码（drop_reason 模块）。前端按这个查文案，所以是稳定契约，不要改字面量
     * （与 extraction_drops::reason 同一约定）。
     */
    public static final class DropReason {
        /** 模型回的 source 对不上任何挂载源的名字——最常踩的一个。 */
        public static final String SOURCE = "source";
        /** kind 不是 metric / dimension。 */
        public static final String KIND = "kind";
        /** 概念类查不到（ensureConceptTypes 之后不该再发生）。 */
        public static final String TYPE = "type";
        /** definition 不是一个对象，或者拿不出可执行的定义。 */
        public static final String DEFINITION = "definition";
        /** 模型回的条数超过这一轮的上限，超出的没看。 */
        public static final String CAP = "cap";
        /** 这条概念在这个源上已经有人表过态（确认或拒绝）。 */
        public static final String DECIDED = "decided";

        private DropReason() {
        }
    }

    /**
     * 开一轮（start）。<b>先开行再干活</b>，跑挂了的那一轮也有账可查——失败与
     * 「跑了但什么都没提」在页面上从前都是「没有新提议」。
     */
    public UUID start(UUID kbId) {
        UUID id = Uuids.nowV7();
        mappings.insertExplorationRun(id, kbId);
        return id;
    }

    /**
     * 读完 schema、还没问模型时先记下规模（scanned）。<b>问模型可能要一分钟</b>，
     * 这期间页面上该看得见「正在扫的是多大的东西」。
     */
    public void scanned(UUID id, List<String> sources, int tables, int columns, boolean truncated,
                        int cap) {
        mappings.markExplorationScanned(id, sources, tables, columns, truncated, cap);
    }

    /**
     * 收一轮（finish）。{@code dropped} 的形状见 {@link DropReason}：每个原因一个
     * {@code {"n": 计数, "example": "一条例子"}}——<b>光有计数诊断不动</b>，「十二条
     * 源名对不上」要配上「模型说的是 tpch，挂的是 tpch-2026-09-08」才知道该改什么。
     */
    public void finish(UUID id, int returned, int accepted, JsonNode dropped,
                       List<String> tablesCovered) {
        mappings.finishExplorationRun(id, returned, accepted, dropped, tablesCovered);
    }

    /**
     * 这一轮挂了（fail）。记下来就收摊——<b>报错路径上的失败不该淹掉它要报的那件事</b>，
     * 调用方一律吞掉返回值（与 extraction_drops 同一约定）。
     */
    public void fail(UUID id, String error) {
        mappings.failExplorationRun(id, error);
    }

    /** 最近几轮，新的在前（recent）。数据映射页读它。 */
    public List<ExplorationRun> recent(UUID kbId, long limit) {
        return mappings.selectRecentRuns(kbId, limit);
    }
}
