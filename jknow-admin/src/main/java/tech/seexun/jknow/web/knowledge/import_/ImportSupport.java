package tech.seexun.jknow.web.knowledge.import_;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.import_.OntologyPacks.Pack;

/**
 * 建库时装选中的本体包（kbs.rs:400-434 install_packs 逐语义；M4 任务 6 的 422 降级
 * 自 M5 任务 5 起退役）。
 *
 * <p>从前这里要先跑一次 ensure_default_ontology：包里的类要认领同名的种子类
 * （schema:Organization 接管 organization）。现在没有种子可认领了——0009/0010/0011
 * 之后播种函数整个退场，包直接落进空库。
 *
 * <p><b>一个包失败不回滚已装的</b>：本体是加法，装了一半的库仍然可用，而回滚要撤
 * 已经建好的类——那正是 0008 决定不做导入撤销的理由。失败信息里带上是哪个包。
 *
 * <p>只被建库调用（0062 的前提）：新库没有事实就没有违规可对账，所以这里不像
 * {@link OntologyImportController} 那样做基线 + 判据对账。
 */
@Component
public class ImportSupport {

    private static final Logger log = LoggerFactory.getLogger(ImportSupport.class);

    private final ImportPlanner planner;

    public ImportSupport(ImportPlanner planner) {
        this.planner = planner;
    }

    /**
     * 逐包 {@code owl_import::apply}；packs&gt;1 时 relink 第二遍——包是挨个装的，
     * 先装的看不见后装的类：W3C Org 的 headOf 要等 FOAF 的 Agent（#222）。只装一个
     * 包时没有「别的包」。
     */
    public void installPacks(UUID kbId, UUID actor, List<String> packIds) {
        if (packIds == null || packIds.isEmpty()) {
            return;
        }
        List<Pack> packs = new ArrayList<>(packIds.size());
        for (String id : packIds) {
            Pack pack = OntologyPacks.get(id);
            if (pack == null) {
                throw AppException.invalid("unknown_pack", "未知的本体包：" + id);
            }
            packs.add(pack);
        }
        for (Pack pack : packs) {
            try {
                planner.apply(kbId, actor, pack.filename(), OntologyPacks.bytes(pack));
            } catch (Exception e) {
                throw AppException.other("装本体包 " + pack.id() + " 失败：" + e.getMessage(), e);
            }
        }
        // 第二遍：跨包的 domain/range
        if (packs.size() > 1) {
            for (Pack pack : packs) {
                int[] linked;
                try {
                    linked = planner.relinkDomainsRanges(kbId, pack.filename(),
                            OntologyPacks.bytes(pack));
                } catch (Exception e) {
                    throw AppException.other(
                            "补本体包 " + pack.id() + " 的签名失败：" + e.getMessage(), e);
                }
                log.debug("kb={} pack={} domains={} ranges={} 跨包签名补链", kbId, pack.id(),
                        linked[0], linked[1]);
            }
        }
    }
}
