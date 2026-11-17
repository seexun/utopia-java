package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.DataSourceView;

/**
 * 问数数据源读 mapper（逐字搬迁自旧仓库 utopia-store/src/datasources.rs；
 * 连接串→无凭据摘要的换算（row_to_view/conn_summary）在 DatasourcesService，SQL 只取行）。
 */
public interface DatasourcesMapper {

    /** 行（row_to_view 的输入；conn_string 库内为封印形态，service 侧按需开封出摘要）。 */
    record DataSourceRow(UUID id, String name, String engine, String conn,
                         java.time.OffsetDateTime createdAt,
                         java.time.OffsetDateTime lastTestAt, Boolean lastTestOk) {
    }

    /** datasources.rs mounted：本库挂载的（kb_data_sources 联表，name 序）。 */
    List<DataSourceRow> selectMounted(@Param("kbId") UUID kbId);

    /** datasources.rs granted_to_workspace：授给工作区的（admin available 面）。 */
    List<DataSourceRow> selectGrantedToWorkspace(@Param("workspaceId") UUID workspaceId);

    /** datasources.rs list：全部署每一个源，不过滤（admin 面）。 */
    List<DataSourceRow> selectAll();

    /** datasources.rs grants_for_source：一个源授给了哪些工作区。 */
    int insertGrant(@Param("dataSourceId") UUID dataSourceId,
                    @Param("workspaceId") UUID workspaceId, @Param("grantedBy") UUID grantedBy);

    int deleteMountsInWorkspace(@Param("dataSourceId") UUID dataSourceId,
                                @Param("workspaceId") UUID workspaceId);

    int deleteGrant(@Param("dataSourceId") UUID dataSourceId,
                    @Param("workspaceId") UUID workspaceId);

    Integer selectGranted(@Param("kbId") UUID kbId, @Param("dataSourceId") UUID dataSourceId);

    int insertMount(@Param("kbId") UUID kbId, @Param("dataSourceId") UUID dataSourceId);

    List<WorkspaceGrant> selectGrantsForSource(@Param("dataSourceId") UUID dataSourceId);

    // ---- 注册写面（datasources.rs:71-140/152-160/177-190；M5 任务 3） ----

    int insertDataSource(@Param("id") UUID id, @Param("name") String name,
                         @Param("engine") String engine, @Param("conn") String conn,
                         @Param("createdBy") UUID createdBy);

    int deleteDataSource(@Param("id") UUID id);

    int updateTestResult(@Param("id") UUID id, @Param("ok") boolean ok);

    EngineAndConn selectEngineAndConn(@Param("id") UUID id);

    /** datasources.rs:177-190 engine_and_conn 的返回（conn 为封印形态，service 开封）。 */
    record EngineAndConn(String engine, String conn) {
    }

    int deleteMount(@Param("kbId") UUID kbId, @Param("dataSourceId") UUID dataSourceId);

    /** (workspace_id, name) 对。 */
    record WorkspaceGrant(UUID id, String name) {
    }
}
