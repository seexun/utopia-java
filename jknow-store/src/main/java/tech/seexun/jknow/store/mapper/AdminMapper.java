package tech.seexun.jknow.store.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.ModelLimitRow;

/**
 * 管理面部署配置读 mapper（逐字搬迁自旧仓库 utopia-store/src/access.rs 部署配置节、
 * model_limits.rs；每条 SQL 的 file:line 见 AdminMapper.xml）。
 */
public interface AdminMapper {

    /** access.rs:203-210 default_ontology_lang（缺省 'en'）。 */
    String selectDefaultOntologyLang();

    /** access.rs:223-232 worker_concurrency（缺省 64，与列缺省一致）。 */
    Integer selectWorkerConcurrency();

    /** model_limits.rs list 第一段：按模型的并发限额行。 */
    List<ModelLimitRow> selectModelLimits();

    /** model_limits.rs list 第二段：未单独配置的模型走的并发缺省（缺省 10）。 */
    Integer selectDefaultModelConcurrency();

    /** model_limits.rs models_in_use：在用模型去重（chat 与 embed 两路 UNION）。 */
    List<InUseModel> selectModelsInUse();

    /** (base_url, model, kind) 三元组。 */
    record InUseModel(String baseUrl, String model, String kind) {
    }
}
