package tech.seexun.jknow.store;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.ExtractionDrop;
import tech.seexun.jknow.store.mapper.DocumentsMapper;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;

/**
 * 抽取丢弃信号（extraction_drops.rs 头注）：哪些事实抽出来了却没能落地，以及为什么。
 *
 * <p><b>与 ontology_misses 分开是刻意的</b>——那张表说的是「你的本体缺这些」，读者是
 * 本体维护者，动作是加类型；这张说的是「这些事实没落地」，读者是上传文档的人，动作是
 * 改文档或改本体。混在一个面板里两边都讲不清。
 *
 * <p><b>记录失败不影响抽取</b>（调用方一律 {@code let _ =}，见 extraction.rs:120-135
 * {@code drop_signal}——{@link #dropSignal} 是它的 Java 等价点）：信号缺一条，远好过
 * 因为记信号失败而中断整篇文档的抽取。读面 for_kb 在 {@link DocumentsMapper}（M1）。
 */
@Service
public class ExtractionDropsService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionDropsService.class);

    private final DocumentsWriteMapper writes;
    private final DocumentsMapper reads;

    public ExtractionDropsService(DocumentsWriteMapper writes, DocumentsMapper reads) {
        this.writes = writes;
        this.reads = reads;
    }

    /**
     * 原因码（extraction_drops.rs:17-61 {@code mod reason}）。前端按这个查文案，所以是
     * <b>稳定契约，不要改字面量</b>。都由开放抽取那条路（0044）发出；类型化那条路的
     * 原因码随它一起退场了。
     */
    public static final class Reason {

        private Reason() {
        }

        /**
         * 这一块整个没抽成：调用失败（端点不可用、请求没送到、等太久）或回复解析不了。
         * <b>与别的原因码不同，它丢的是一整块原文，不是一条陈述</b>。任务的 last_error
         * 里也有这句话，可那是给排查的人看的；文档抽完之后还能看见「这一块没进去」的，
         * 只有这张表（0001：丢弃是一行，不是沉默）。
         */
        public static final String CHUNK_UNEXTRACTED = "chunk_unextracted";
        /** 陈述缺宾语也缺值。 */
        public static final String OBJECT_MISSING = "object_missing";
        /** 模型给的这一条不合结构（缺短语之类）→ 只跳这一条，不牵连整块。 */
        public static final String MALFORMED_ITEM = "malformed_item";
        /** 模型输出被截断（撞上 max_tokens）→ 已完整的那些留下，尾巴丢掉。 */
        public static final String TRUNCATED_REPLY = "truncated_reply";
        /**
         * 这条陈述的值不在它自己的引文里（#729）：证据没写着这个数。表格是这个错的
         * 产地——模型把一行五列压成五条只有值不同的陈述，引文却指向表上面那句导语。
         * <b>引文是这条陈述的全部依据</b>：值不在里面，它就不是文档说的。
         */
        public static final String VALUE_NOT_IN_QUOTE = "value_not_in_quote";
        /**
         * 模型报的别名（或它的引文）不在这一块原文里（0041 决定 2）：不记这个名字。
         * 名字是召回的桥，一座凭空的桥会把两个不相干的实体接到一起。
         */
        public static final String NAME_NOT_IN_TEXT = "name_not_in_text";
        /**
         * 模型报的别名已经是另一个实体的名字（0041 决定 2）：一个名字不会同时是两样
         * 东西的名字。
         */
        public static final String NAME_CLAIMED_BY_ANOTHER = "name_claimed_by_another";
        /**
         * 宾语既没在回复里列出、库里也没有叫这个名字的东西：陈述照落，宾语落成字面值
         * 而不是节点（#559）。记下来是为了量：这一类里有多少本该是实体（模型漏报），
         * 有多少本来就是描述。
         */
        public static final String OBJECT_UNDECLARED = "object_undeclared";
        /**
         * 模型的引文在这一块里找不到原样的一句：陈述照落、引文照记，只是没有偏移
         * （quote_start / quote_end 留空）。这个信号数的是有多少条没定位到。
         */
        public static final String QUOTE_NOT_IN_CHUNK = "quote_not_in_chunk";
        /**
         * 时间词不在它的引文里、也不在这一块里：不记这条时间提及。时间词是照抄的字，
         * 抄不出来的字就不是文档说的。
         */
        public static final String TIME_NOT_IN_QUOTE = "time_not_in_quote";
        /** 陈述、属性或名字指着一个回复里不存在的实体编号或已知句柄：跳过这一条。 */
        public static final String UNKNOWN_REF = "unknown_ref";
        /**
         * 短语就是那个值（「director nominees —ten (10)→ ten (10)」）：表格丢了列头时
         * 模型的写法之一。陈述照落（主语和值都在，信息没丢），记一笔让它可量。
         * 纯结构判断：两串字去掉首尾空白后相同。
         */
        public static final String PHRASE_IS_VALUE = "phrase_is_value";
        /** 短语就是主语的名字：同上，另一种写法，同样照落。 */
        public static final String PHRASE_IS_SUBJECT = "phrase_is_subject";
    }

    /** extraction_drops.rs:63-87 {@code record}：记一条丢弃信号。 */
    public void record(UUID kbId, UUID documentId, String reason, String detail, String example) {
        writes.insertExtractionDrop(kbId, documentId, reason, detail, example);
    }

    /** extraction_drops.rs:90-96 {@code clear_for_document}：重抽开始时清掉旧信号。 */
    public void clearForDocument(UUID documentId) {
        writes.clearExtractionDrops(documentId);
    }

    /**
     * extraction_drops.rs:100-108 {@code for_kb}（读面 M1 落在 DocumentsMapper——此处
     * 转发保持域内完整）：一个 KB 的全部丢弃信号，一次取回让 Library 既能算每篇的
     * 总数、又能直接展开详情。
     */
    public List<ExtractionDrop> forKb(UUID kbId) {
        return reads.selectExtractionDrops(kbId);
    }

    /**
     * extraction.rs:120-135 {@code drop_signal} 的 Java 等价点：记一条丢弃信号，
     * <b>写失败绝不带垮文档</b>——抽取器有七处 {@code continue}，每一处都是「事实抽出
     * 来了、被挡掉、什么都不说」，信号写失败绝不能带垮整篇文档的抽取，所以这里吞掉
     * 错误只记日志（任务 8 抽取执行器统一改走这里）。
     */
    public void dropSignal(UUID kbId, UUID documentId, String reason, String detail,
                           String example) {
        try {
            record(kbId, documentId, reason, detail, example);
        } catch (RuntimeException e) {
            log.warn("extraction drop signal lost: kb={} document={} reason={} detail={}",
                    kbId, documentId, reason, detail, e);
        }
    }
}
