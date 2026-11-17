-- V0101__user_rebind：身份主档切 sys_user（方案 B：桥接列，业务 UUID 形状不变）
-- 引用面：对源迁移逐文件核对旧 users 表的外键命中行（V0001-V0099 共 28 处，含 kb_members/concept_mappings 各 2 列）；
-- 全部为内联列级 FK（源迁移无 CONSTRAINT <name> FOREIGN KEY 命名形），PG 缺省约束名 <表>_<列>_fkey。
-- 空库重放时序：sys_user 属 V0000（先建），业务表属 V0001+（后建），本迁移收尾；
-- V0001 users.deactivated_by 的自指 FK 随 DROP TABLE users 一并消失，无需改指。
-- gen_random_uuid 为 PG16 内置。旧行形状说明：本迁移只换 FK 指向，不回填业务数据。

ALTER TABLE sys_user ADD COLUMN utopia_uuid uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uk_sys_user_utopia_uuid ON sys_user(utopia_uuid);

-- 逐表改外键目标（NOT NULL 与 ON DELETE 动作逐条对齐源迁移 DDL；未写 ON DELETE 即缺省 NO ACTION）
-- V0001__core.sql
ALTER TABLE memberships DROP CONSTRAINT IF EXISTS memberships_user_id_fkey;
ALTER TABLE memberships ADD CONSTRAINT memberships_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
ALTER TABLE kb_members DROP CONSTRAINT IF EXISTS kb_members_user_id_fkey;
ALTER TABLE kb_members ADD CONSTRAINT kb_members_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
ALTER TABLE kb_members DROP CONSTRAINT IF EXISTS kb_members_added_by_fkey;
ALTER TABLE kb_members ADD CONSTRAINT kb_members_added_by_fkey
    FOREIGN KEY (added_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;
-- V0003__graph.sql
ALTER TABLE entity_retypes DROP CONSTRAINT IF EXISTS entity_retypes_actor_id_fkey;
ALTER TABLE entity_retypes ADD CONSTRAINT entity_retypes_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES sys_user(utopia_uuid);
-- V0004__ontology.sql
ALTER TABLE ontology_imports DROP CONSTRAINT IF EXISTS ontology_imports_imported_by_fkey;
ALTER TABLE ontology_imports ADD CONSTRAINT ontology_imports_imported_by_fkey
    FOREIGN KEY (imported_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;
ALTER TABLE ontology_proposals DROP CONSTRAINT IF EXISTS ontology_proposals_decided_by_fkey;
ALTER TABLE ontology_proposals ADD CONSTRAINT ontology_proposals_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
ALTER TABLE type_refinement_pairs DROP CONSTRAINT IF EXISTS type_refinement_pairs_approved_by_fkey;
ALTER TABLE type_refinement_pairs ADD CONSTRAINT type_refinement_pairs_approved_by_fkey
    FOREIGN KEY (approved_by) REFERENCES sys_user(utopia_uuid);
-- V0005__resolution.sql
ALTER TABLE resolution_reviews DROP CONSTRAINT IF EXISTS resolution_reviews_decided_by_fkey;
ALTER TABLE resolution_reviews ADD CONSTRAINT resolution_reviews_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
ALTER TABLE entity_merges DROP CONSTRAINT IF EXISTS entity_merges_merged_by_fkey;
ALTER TABLE entity_merges ADD CONSTRAINT entity_merges_merged_by_fkey
    FOREIGN KEY (merged_by) REFERENCES sys_user(utopia_uuid);
-- V0006__semantic_layer.sql
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_created_by_fkey;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;
ALTER TABLE concept_mappings DROP CONSTRAINT IF EXISTS concept_mappings_decided_by_fkey;
ALTER TABLE concept_mappings ADD CONSTRAINT concept_mappings_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
ALTER TABLE concept_mapping_revisions DROP CONSTRAINT IF EXISTS concept_mapping_revisions_changed_by_fkey;
ALTER TABLE concept_mapping_revisions ADD CONSTRAINT concept_mapping_revisions_changed_by_fkey
    FOREIGN KEY (changed_by) REFERENCES sys_user(utopia_uuid);
-- V0008__conversations.sql
ALTER TABLE conversations DROP CONSTRAINT IF EXISTS conversations_user_id_fkey;
ALTER TABLE conversations ADD CONSTRAINT conversations_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
-- V0009__alerts.sql
ALTER TABLE alert_reads DROP CONSTRAINT IF EXISTS alert_reads_user_id_fkey;
ALTER TABLE alert_reads ADD CONSTRAINT alert_reads_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
-- V0012__axiom_violations.sql
ALTER TABLE axiom_violations DROP CONSTRAINT IF EXISTS axiom_violations_decided_by_fkey;
ALTER TABLE axiom_violations ADD CONSTRAINT axiom_violations_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
-- V0013__reasoning.sql
ALTER TABLE ontology_defects DROP CONSTRAINT IF EXISTS ontology_defects_decided_by_fkey;
ALTER TABLE ontology_defects ADD CONSTRAINT ontology_defects_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
-- V0014__data_source_grants.sql
ALTER TABLE data_source_grants DROP CONSTRAINT IF EXISTS data_source_grants_granted_by_fkey;
ALTER TABLE data_source_grants ADD CONSTRAINT data_source_grants_granted_by_fkey
    FOREIGN KEY (granted_by) REFERENCES sys_user(utopia_uuid);
-- V0017__personal_tokens.sql
ALTER TABLE personal_tokens DROP CONSTRAINT IF EXISTS personal_tokens_user_id_fkey;
ALTER TABLE personal_tokens ADD CONSTRAINT personal_tokens_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
-- V0018__a_fact_awaiting_a_nod.sql
ALTER TABLE pending_facts DROP CONSTRAINT IF EXISTS pending_facts_proposed_by_fkey;
ALTER TABLE pending_facts ADD CONSTRAINT pending_facts_proposed_by_fkey
    FOREIGN KEY (proposed_by) REFERENCES sys_user(utopia_uuid);
ALTER TABLE rejected_facts DROP CONSTRAINT IF EXISTS rejected_facts_rejected_by_fkey;
ALTER TABLE rejected_facts ADD CONSTRAINT rejected_facts_rejected_by_fkey
    FOREIGN KEY (rejected_by) REFERENCES sys_user(utopia_uuid);
-- V0022__deleting_is_an_event.sql
ALTER TABLE document_deletions DROP CONSTRAINT IF EXISTS document_deletions_deleted_by_fkey;
ALTER TABLE document_deletions ADD CONSTRAINT document_deletions_deleted_by_fkey
    FOREIGN KEY (deleted_by) REFERENCES sys_user(utopia_uuid);
-- V0035__governance_reads_the_ledger.sql
ALTER TABLE agent_decisions DROP CONSTRAINT IF EXISTS agent_decisions_decided_by_fkey;
ALTER TABLE agent_decisions ADD CONSTRAINT agent_decisions_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid);
-- V0046__a_definition_can_be_written_by_hand.sql
ALTER TABLE concept_mappings DROP CONSTRAINT IF EXISTS concept_mappings_written_by_fkey;
ALTER TABLE concept_mappings ADD CONSTRAINT concept_mappings_written_by_fkey
    FOREIGN KEY (written_by) REFERENCES sys_user(utopia_uuid);
-- V0056__an_identity_can_be_linked_to_a_single_sign_on_subject.sql
ALTER TABLE oidc_identities DROP CONSTRAINT IF EXISTS oidc_identities_user_id_fkey;
ALTER TABLE oidc_identities ADD CONSTRAINT oidc_identities_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
ALTER TABLE oidc_flows DROP CONSTRAINT IF EXISTS oidc_flows_link_user_id_fkey;
ALTER TABLE oidc_flows ADD CONSTRAINT oidc_flows_link_user_id_fkey
    FOREIGN KEY (link_user_id) REFERENCES sys_user(utopia_uuid) ON DELETE CASCADE;
-- V0074__an_errata_agent_reviews_the_typed_graph.sql
ALTER TABLE errata_actions DROP CONSTRAINT IF EXISTS errata_actions_decided_by_fkey;
ALTER TABLE errata_actions ADD CONSTRAINT errata_actions_decided_by_fkey
    FOREIGN KEY (decided_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;
-- V0077__the_ontology_is_judged_by_its_questions.sql
ALTER TABLE competency_questions DROP CONSTRAINT IF EXISTS competency_questions_created_by_fkey;
ALTER TABLE competency_questions ADD CONSTRAINT competency_questions_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;
-- V0109__a_human_example_records_the_existing_alignment_outcome.sql
ALTER TABLE ontology_regression_cases DROP CONSTRAINT IF EXISTS ontology_regression_cases_created_by_fkey;
ALTER TABLE ontology_regression_cases ADD CONSTRAINT ontology_regression_cases_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES sys_user(utopia_uuid) ON DELETE SET NULL;

-- 旧身份主档退役（其 deactivated_by 自指 FK 随表消失；其余引用已全部改指上方）
DROP TABLE IF EXISTS users;
