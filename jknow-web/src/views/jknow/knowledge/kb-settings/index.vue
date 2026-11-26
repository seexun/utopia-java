<!-- 知识库设置（对照旧仓 pages/KbSettings.tsx 全量）：分节（General /
     Members / Activity / Danger）。**每张卡一个保存**——PATCH 本来就是部分更新，
     改名字那一下不该把下面的开关一起送上去（九字段分四卡：name/description /
     visibility / materialize_inferences+auto_type_resolution+governance+
     inference_interval_minutes / ontology_lang）。Members 是成员管理写面
     （加人/改角色/移出——open 库没有 viewer 可授）；Activity 是审计台账
     （筛选 + 分页）；Danger 是删库（打字级确认，默认库无此节）。
     reskin-b4 模板层重造（script 逻辑零动，仅 import 换装）：左栏 rail 退役，
     分节上移为页头（设置·库名）下的 el-tabs（全局形态自动跟随），
     四节内容/表单卡/弹窗的结构与接线原样。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import {
  kbDetail as kbDetailApi,
  updateKb,
  deleteKb,
  kbMembers,
  setKbMember,
  removeKbMember,
  kbAudit
} from "@/api/knowledge/kbs";
import { orgUsers } from "@/api/knowledge/workspaces";
import { failedJobs, requeueJobs } from "@/api/knowledge/alerts";
import type { AuditEvent, KbMember } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { LANG_NAMES } from "@/i18n";
import { toast } from "@/domain/toast";
import { localDateTime } from "@/domain/ui";
import { useKbScope } from "@/composables/useKbScope";
import { useKbRole } from "@/composables/useKbRole";
import { DangerConfirm } from "@/components/knowledge/dialog";
import { UPager } from "@/components/knowledge/kit";
/* PageHeader（reskin-b1 自 gvdata 零改写迁入）：22/800 标题 + 库名副题 */
import PageHeader from "@/components/PageHeader/index.vue";

type Section = "general" | "members" | "activity" | "danger";

const route = useRoute();
const router = useRouter();
const queryClient = useQueryClient();
// KbScope 对等物（每个 kb 页面 setup 调一次）
const scope = useKbScope();
const kbId = scope.kbId;
const { isKbAdmin } = useKbRole();

const kb = useQuery({
  queryKey: computed(() => ["kbOne", kbId.value]),
  queryFn: () => kbDetailApi(kbId.value),
  enabled: computed(() => !!kbId.value)
});

// 失败任务数与重排（#216）。查询键带库 id，重排后失效重取
const jobs = useQuery({
  queryKey: computed(() => ["jobs", "failed", kbId.value]),
  queryFn: () => failedJobs(kbId.value),
  enabled: computed(() => !!kbId.value)
});
const requeue = useMutation({
  mutationFn: () => requeueJobs(kbId.value),
  onSuccess: r => {
    toast.success(S.kbset.requeued(r.requeued));
    queryClient.invalidateQueries({ queryKey: ["jobs", "failed", kbId.value] });
  },
  onError: (e: Error) => toast.error(e.message)
});

const section = ref<Section>("general");

/* ---------- 四张卡，四个保存（九字段各归各卡） ---------- */
const name = ref("");
const desc = ref("");
const visibility = ref<"open" | "restricted">("open");
// **默认关**：推理往账本里写事实，而声明可能是错的
const materialize = ref(false);
// 类型消解自动跑（0016 C2）：只自动落地子树内精化的那一档
const autoResolve = ref(false);
// 治理（0025）：**缺省关**——它会合并实体；打开就开始，关掉就停
const governance = ref(false);
const inferMins = ref(60);
const ontoLang = ref<"en" | "zh">("en");
const confirmDelete = ref(false);
const error = ref<string | null>(null);

watch(
  () => kb.data.value,
  k => {
    if (!k) return;
    name.value = k.name;
    desc.value = k.description ?? "";
    visibility.value = k.visibility;
    materialize.value = k.materialize_inferences;
    autoResolve.value = k.auto_type_resolution;
    governance.value = k.governance;
    inferMins.value = k.inference_interval_minutes;
    ontoLang.value = k.ontology_lang;
  },
  { immediate: true }
);

/** 一张卡的保存：**只送自己那几项**。把整张表一起送过去，等于用改名字的
 *  那一下覆盖别人刚改的开关。 */
function patch(fields: Record<string, unknown>) {
  error.value = null;
  return updateKb(kbId.value, fields)
    .then(() => {
      // 设置页的 mutation 自己失效（kbOne 会话键 + 库列表）
      queryClient.invalidateQueries({ queryKey: ["kbOne", kbId.value] });
      queryClient.invalidateQueries({ queryKey: ["kbs"] });
    })
    .catch((e: Error) => {
      error.value = e.message;
      toast.error(e.message);
    });
}

const saving = ref(false);
const identityDirty = computed(
  () =>
    !!kb.data.value &&
    (name.value.trim() !== kb.data.value.name ||
      desc.value.trim() !== (kb.data.value.description ?? ""))
);
const visibilityDirty = computed(
  () => !!kb.data.value && visibility.value !== kb.data.value.visibility
);
const automationDirty = computed(
  () =>
    !!kb.data.value &&
    (materialize.value !== kb.data.value.materialize_inferences ||
      autoResolve.value !== kb.data.value.auto_type_resolution ||
      governance.value !== kb.data.value.governance ||
      inferMins.value !== kb.data.value.inference_interval_minutes)
);
const langDirty = computed(
  () => !!kb.data.value && ontoLang.value !== kb.data.value.ontology_lang
);
const justSaved = ref("");
async function save(card: string, fields: Record<string, unknown>) {
  saving.value = true;
  await patch(fields);
  saving.value = false;
  justSaved.value = card;
  window.setTimeout(() => (justSaved.value = ""), 2500);
}

const removeKb = useMutation({
  mutationFn: () => deleteKb(kbId.value),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ["kbs"] });
    toast.success(S.toast.deleted);
    router.push(`/kb/${kbId.value}/library`);
  },
  onError: (e: Error) => toast.error(e.message)
});

const isDefault = computed(() => kb.data.value?.is_default ?? false);
const sections = computed(() => {
  const out: { key: Section; label: string; icon: string; warn?: boolean }[] = [
    { key: "general", label: S.kbset.general, icon: "lucide:settings-2" },
    { key: "members", label: S.kbset.members, icon: "lucide:users" },
    { key: "activity", label: S.kbset.activity, icon: "lucide:history" }
  ];
  // 默认库不可删除：danger 节整个不出现
  if (!isDefault.value)
    out.push({
      key: "danger",
      label: S.kbset.danger,
      icon: "lucide:triangle-alert",
      warn: true
    });
  return out;
});

function auditDetailName(e: AuditEvent): string {
  const d = e.detail;
  const cand = [d.label, d.name, d.filename, d.key, d.role];
  const hit = cand.find(v => typeof v === "string" && v);
  return typeof hit === "string" ? hit : "";
}

const AUDIT_PAGE = 50;

/* ---------- 成员（写面）：open 库没有 viewer 可授 ---------- */
const KB_ROLES = [
  { value: "viewer", label: S.kbset.roles.viewer },
  { value: "editor", label: S.kbset.roles.editor },
  { value: "admin", label: S.kbset.roles.admin }
];
function rolesFor(isOpen: boolean) {
  return isOpen ? KB_ROLES.filter(r => r.value !== "viewer") : KB_ROLES;
}
const members = useQuery({
  queryKey: computed(() => ["kbMembers", kbId.value]),
  queryFn: () => kbMembers(kbId.value),
  enabled: computed(() => !!kbId.value && section.value === "members")
});
const users = useQuery({
  queryKey: ["orgUsers"],
  queryFn: orgUsers,
  enabled: computed(() => section.value === "members")
});
const memberFilter = ref("");
const memberRoleFilter = ref("all");
const adding = ref(false);
const addUserId = ref("");
const addRole = ref("editor");
const editingRole = ref<string | null>(null);

const isOpenKb = computed(() => kb.data.value?.visibility === "open");
watch(isOpenKb, open => (addRole.value = open ? "editor" : "viewer"), {
  immediate: true
});

/** open 库里 role=viewer 的一行等价于没有这一行；不算进 memberIds 是配套的一半 */
const listed = computed(() =>
  (members.data.value?.members ?? []).filter(
    m => !isOpenKb.value || m.role !== "viewer"
  )
);
const memberIds = computed(() => new Set(listed.value.map(m => m.user_id)));
const addable = computed(() =>
  (users.data.value ?? []).filter(u => !memberIds.value.has(u.id))
);
const memberQ = computed(() => memberFilter.value.trim().toLowerCase());
const shownMembers = computed(() =>
  listed.value
    .filter(
      m => memberRoleFilter.value === "all" || m.role === memberRoleFilter.value
    )
    .filter(
      m =>
        !memberQ.value ||
        m.display_name.toLowerCase().includes(memberQ.value) ||
        m.email.toLowerCase().includes(memberQ.value)
    )
);

async function setMember(userId: string, role: string) {
  try {
    await setKbMember(kbId.value, userId, role);
    addUserId.value = "";
    queryClient.invalidateQueries({ queryKey: ["kbMembers", kbId.value] });
    toast.success(S.toast.saved);
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}
async function removeMember(userId: string) {
  try {
    await removeKbMember(kbId.value, userId);
    queryClient.invalidateQueries({ queryKey: ["kbMembers", kbId.value] });
    toast.success(S.toast.deleted);
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

/* ---------- 活动台账（筛选按真实查法来） ---------- */
const auditAction = ref("");
const auditSince = ref("");
const auditUntil = ref("");
const auditPage = ref(0);
const audit = useQuery({
  queryKey: computed(() => [
    "kbAudit",
    kbId.value,
    auditAction.value,
    auditSince.value,
    auditUntil.value,
    auditPage.value
  ]),
  queryFn: () =>
    kbAudit(kbId.value, {
      action: auditAction.value || undefined,
      since: auditSince.value || undefined,
      until: auditUntil.value || undefined,
      limit: AUDIT_PAGE,
      offset: auditPage.value * AUDIT_PAGE
    }),
  enabled: computed(() => !!kbId.value && section.value === "activity"),
  placeholderData: (prev: unknown) => prev as never
});
const auditEvents = computed(() => audit.data.value?.events ?? []);
const auditTotal = computed(() => audit.data.value?.total ?? 0);
const auditFiltered = computed(
  () => !!(auditAction.value || auditSince.value || auditUntil.value)
);
function resetAudit(fn: () => void) {
  fn();
  auditPage.value = 0;
}
</script>

<template>
  <div class="knowledge-page h-full overflow-y-auto overscroll-contain px-8 py-6">
    <!-- reskin-b4：整页单列滚动（检索/文库/审查同款根）；左栏分节 rail 退役，
         分节上移为页头下的 el-tabs（全局形态自动跟随）。section ref 仍是
         分节的唯一事实源——四节内容、成员/台账查询的 enabled 闸、弹窗接线
         原样（每张卡一个保存的语义不变）。注意：本注释必须留在根 div 内——
         dev 模式根级注释是真实节点，会让组件根变 Fragment，AppMain 的
         <Transition mode="out-in"> 离场卡死后吞掉 enter，渲染空白主区。 -->
    <!-- 设置是读一列字段，不是铺一张桌子：内容居中限宽 -->
    <div class="mx-auto w-full max-w-4xl">
      <!-- 页头（重造要素一）：标题=设置，副题=库名（独立路由位 /kb/:kbId/settings）；
           页级无主行动——每张卡的保存是卡内动作 -->
      <PageHeader :title="S.kbset.title" :subtitle="kb.data.value?.name ?? ''" />

      <!-- 分节页签（重造要素二）：danger 节默认库不出现（sections computed 原样，
           图标与警示色随迁 label 插槽） -->
      <el-tabs
        :model-value="section"
        class="mb-4"
        @tab-change="(name: string | number) => (section = name as Section)"
      >
        <el-tab-pane v-for="s in sections" :key="s.key" :name="s.key">
          <template #label>
            <span class="flex items-center gap-1.5">
              <Icon
                :icon="s.icon"
                width="14"
                height="14"
                :class="s.warn ? 'text-warning' : undefined"
              />
              {{ s.label }}
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

        <!-- ======== General：一张卡是一个保存单位 ======== -->
        <div v-if="section === 'general'" class="space-y-4">
          <!-- 身份：名字 + 描述 -->
          <div class="rounded-card border border-line bg-card">
            <div class="flex-bc gap-3 border-b border-line px-5 py-4">
              <div class="min-w-0">
                <div class="text-body font-semibold text-ink">
                  {{ S.kbset.cardIdentity }}
                </div>
                <div class="mt-0.5 text-sub text-ink-2">
                  {{ S.kbset.cardIdentityNote }}
                </div>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <span
                  v-if="justSaved === 'identity' && !identityDirty"
                  class="text-sub text-ink-2"
                  >{{ S.kbset.saved }}</span
                >
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!name.trim() || !identityDirty || saving"
                  @click="
                    save('identity', {
                      name: name.trim(),
                      // 空串才是「清空描述」：null 在服务端是 COALESCE 的「这项不改」
                      description: desc.trim()
                    })
                  "
                >
                  {{ S.kbset.save }}
                </el-button>
              </div>
            </div>
            <div class="space-y-3 px-5 py-4">
              <div>
                <label class="mb-1 block text-sub font-medium text-ink-2">{{
                  S.settings.kbs.name
                }}</label>
                <el-input v-model="name" class="w-full" />
              </div>
              <div>
                <label class="mb-1 block text-sub font-medium text-ink-2">{{
                  S.settings.kbs.description
                }}</label>
                <el-input v-model="desc" class="w-full" />
              </div>
            </div>
          </div>

          <!-- 可见性：默认库锁死（卡还在，但没有保存——没有可改的东西就不摆按钮） -->
          <div class="rounded-card border border-line bg-card">
            <div class="flex-bc gap-3 border-b border-line px-5 py-4">
              <div class="min-w-0">
                <div class="text-body font-semibold text-ink">
                  {{ S.settings.kbs.visibility }}
                </div>
                <div class="mt-0.5 text-sub text-ink-2">
                  {{
                    isDefault
                      ? S.kbset.defaultOpenNote
                      : S.kbset.cardVisibilityNote
                  }}
                </div>
              </div>
              <div v-if="!isDefault" class="flex shrink-0 items-center gap-2">
                <span
                  v-if="justSaved === 'visibility' && !visibilityDirty"
                  class="text-sub text-ink-2"
                  >{{ S.kbset.saved }}</span
                >
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!visibilityDirty || saving"
                  @click="save('visibility', { visibility })"
                >
                  {{ S.kbset.save }}
                </el-button>
              </div>
            </div>
            <div class="px-5 py-4">
              <div
                v-if="isDefault"
                class="flex w-fit items-center gap-2 rounded-ctl border border-line px-3 py-2 text-aux text-ink-2"
              >
                <Icon
                  icon="lucide:lock"
                  width="11"
                  height="11"
                  class="shrink-0 text-ink-2"
                />
                {{ S.kbset.defaultOpenLabel }}
              </div>
              <div v-else class="flex w-fit gap-1 rounded-ctl bg-fill p-1">
                <button
                  v-for="v in [
                    { k: 'open', label: 'Open' },
                    { k: 'restricted', label: S.settings.kbs.visRestricted }
                  ] as const"
                  :key="v.k"
                  type="button"
                  class="rounded-ctl px-3 py-1 text-sub font-medium transition-colors"
                  :class="
                    visibility === v.k
                      ? 'bg-card text-primary shadow-card'
                      : 'text-ink-2 hover:text-ink'
                  "
                  @click="visibility = v.k"
                >
                  {{ v.label }}
                </button>
              </div>
            </div>
          </div>

          <!-- 自动化：推理 / 类型消解 / 治理（0025）。四字段一次保存 -->
          <div class="rounded-card border border-line bg-card">
            <div class="flex-bc gap-3 border-b border-line px-5 py-4">
              <div class="min-w-0">
                <div class="text-body font-semibold text-ink">
                  {{ S.kbset.cardAutomation }}
                </div>
                <div class="mt-0.5 text-sub text-ink-2">
                  {{ S.kbset.cardAutomationNote }}
                </div>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <span
                  v-if="justSaved === 'automation' && !automationDirty"
                  class="text-sub text-ink-2"
                  >{{ S.kbset.saved }}</span
                >
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!automationDirty || saving"
                  @click="
                    save('automation', {
                      materialize_inferences: materialize,
                      auto_type_resolution: autoResolve,
                      governance,
                      inference_interval_minutes: inferMins
                    })
                  "
                >
                  {{ S.kbset.save }}
                </el-button>
              </div>
            </div>
            <div class="space-y-3 px-5 py-4">
              <!-- 物化推理：**默认关**。它动的是账本——按公理往图里写事实 -->
              <div>
                <el-checkbox
                  v-model="materialize"
                  :label="S.kbset.materialize"
                />
                <div class="pl-6 text-aux text-ink-2">
                  {{ S.kbset.materializeNote }}
                </div>
              </div>
              <!-- 重推间隔。**只在开着的时候露出来**——关着时它不影响任何事 -->
              <div
                v-if="materialize"
                class="flex flex-wrap items-center gap-2 pl-6"
              >
                <label class="text-sub text-ink-2">{{
                  S.kbset.inferEvery
                }}</label>
                <el-input
                  v-model.number="inferMins"
                  type="number"
                  :min="5"
                  :max="10080"
                  class="tabular-nums"
                  style="width: 6rem"
                  size="small"
                />
                <span class="text-sub text-ink-2">{{ S.kbset.minutes }}</span>
                <span
                  v-if="kb.data.value?.last_inference_at"
                  class="text-aux text-ink-2"
                >
                  {{
                    S.kbset.lastInference(
                      localDateTime(kb.data.value.last_inference_at)
                    )
                  }}
                </span>
              </div>
              <!-- 类型消解自动跑：只自动改子树内精化的那一档，跨轴的仍留给人 -->
              <div>
                <el-checkbox
                  v-model="autoResolve"
                  :label="S.kbset.autoResolveTypes"
                />
                <div class="pl-6 text-aux text-ink-2">
                  {{ S.kbset.autoResolveTypesNote }}
                </div>
              </div>
              <!-- 治理（0025）：agent 按先进先出过等人的重复对，先读台账里人的先例再裁 -->
              <div>
                <el-checkbox v-model="governance" :label="S.kbset.governance" />
                <div class="pl-6 text-aux text-ink-2">
                  {{ S.kbset.governanceNote }}
                </div>
              </div>
            </div>
          </div>

          <!-- 语料语言。**不是界面语言**——类描述逐字进抽取提示词，跟文档走不跟读者走 -->
          <div class="rounded-card border border-line bg-card">
            <div class="flex-bc gap-3 border-b border-line px-5 py-4">
              <div class="min-w-0">
                <div class="text-body font-semibold text-ink">
                  {{ S.kbset.ontologyLang }}
                </div>
                <div class="mt-0.5 text-sub text-ink-2">
                  {{ S.kbset.ontologyLangNote }}
                </div>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <span
                  v-if="justSaved === 'lang' && !langDirty"
                  class="text-sub text-ink-2"
                  >{{ S.kbset.saved }}</span
                >
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!langDirty || saving"
                  @click="save('lang', { ontology_lang: ontoLang })"
                >
                  {{ S.kbset.save }}
                </el-button>
              </div>
            </div>
            <div class="px-5 py-4">
              <div class="flex w-fit gap-1 rounded-ctl bg-fill p-1">
                <button
                  v-for="l in ['en', 'zh'] as const"
                  :key="l"
                  type="button"
                  class="rounded-ctl px-3 py-1 text-sub font-medium transition-colors"
                  :class="
                    ontoLang === l
                      ? 'bg-card text-primary shadow-card'
                      : 'text-ink-2 hover:text-ink'
                  "
                  @click="ontoLang = l"
                >
                  {{ LANG_NAMES[l] }}
                </button>
              </div>
            </div>
          </div>

          <!-- 失败的任务（#216）：有才露出来。不是设置，是一个动作 -->
          <div
            v-if="(jobs.data.value?.failed ?? 0) > 0"
            class="flex-bc gap-3 rounded-card border border-line bg-card px-5 py-4"
          >
            <div class="text-body font-semibold text-ink">
              {{ S.kbset.cardJobs }}
              <span class="ml-2 font-normal text-ink-2">
                {{ S.kbset.failedJobs(jobs.data.value!.failed) }}
              </span>
            </div>
            <el-button
              size="small"
              :disabled="requeue.isPending.value"
              @click="requeue.mutate()"
            >
              {{ S.kbset.requeue }}
            </el-button>
          </div>
        </div>

        <!-- ======== Members（写面） ======== -->
        <div v-if="section === 'members'" class="space-y-3">
          <p class="text-body text-ink-2">
            {{
              isOpenKb ? S.kbset.membersHintOpen : S.kbset.membersHintRestricted
            }}
          </p>
          <!-- 筛名单的东西与加人的按钮都在表格外面（DESIGN.md 6） -->
          <div class="flex flex-wrap items-center gap-2">
            <el-input
              v-model="memberFilter"
              :placeholder="S.settings.searchUsers"
              style="width: 16rem"
            />
            <el-select v-model="memberRoleFilter" style="width: 8rem">
              <el-option value="all" :label="S.members.filterAllRoles" />
              <el-option
                v-for="r in rolesFor(isOpenKb)"
                :key="r.value"
                :value="r.value"
                :label="r.label"
              />
            </el-select>
            <div class="ml-auto">
              <el-button type="primary" size="small" @click="adding = true">
                {{ S.kbset.addMemberTitle }}
              </el-button>
            </div>
          </div>
          <div class="overflow-hidden rounded-card border border-line bg-card">
            <table class="w-full text-body">
              <thead>
                <tr class="border-b border-line text-left text-sub text-ink-2">
                  <th class="px-4 py-3 font-medium">
                    {{ S.members.userLabel }}
                  </th>
                  <th class="px-4 py-3 font-medium">
                    {{ S.members.roleLabel }}
                  </th>
                  <th class="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="m in shownMembers"
                  :key="m.user_id"
                  class="border-b border-line last:border-0"
                >
                  <td class="px-4 py-3">
                    <div class="truncate text-body text-ink">
                      {{ m.display_name }}
                    </div>
                    <div class="truncate text-sub text-ink-2">
                      {{ m.email }}
                    </div>
                  </td>
                  <td class="px-4 py-3">
                    <!-- 静态文字，点一下才变成下拉：一列下拉框会把只读名单看成待填的表 -->
                    <el-select
                      v-if="editingRole === m.user_id"
                      size="small"
                      style="width: 7rem"
                      :model-value="m.role"
                      @change="
                        (r: string) => {
                          editingRole = null;
                          if (r !== m.role) setMember(m.user_id, r);
                        }
                      "
                    >
                      <el-option
                        v-for="r in rolesFor(isOpenKb)"
                        :key="r.value"
                        :value="r.value"
                        :label="r.label"
                      />
                    </el-select>
                    <button
                      v-else
                      type="button"
                      class="link-inline text-sub"
                      @click="editingRole = m.user_id"
                    >
                      {{
                        S.kbset.roles[m.role as keyof typeof S.kbset.roles] ??
                        m.role
                      }}
                    </button>
                  </td>
                  <td class="whitespace-nowrap px-4 py-3 text-right">
                    <button
                      type="button"
                      class="link-inline is-danger text-sub"
                      @click="removeMember(m.user_id)"
                    >
                      {{ S.kbset.remove }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <!-- 一个人都没有，与「筛没了」是两回事 -->
            <p
              v-if="shownMembers.length === 0"
              class="px-4 py-6 text-body text-ink-2"
            >
              {{
                listed.length === 0
                  ? isOpenKb
                    ? S.kbset.noWriters
                    : S.kbset.noMembers
                  : S.ui.noMatches
              }}
            </p>
          </div>
        </div>

        <!-- ======== Activity（审计台账） ======== -->
        <div v-if="section === 'activity'" class="space-y-3">
          <div class="flex flex-wrap items-center gap-2">
            <el-select
              v-model="auditAction"
              style="width: 12rem"
              @change="resetAudit(() => {})"
            >
              <el-option value="" :label="S.kbset.auditAllActions" />
              <el-option
                v-for="a in audit.data.value?.actions ?? []"
                :key="a"
                :value="a"
                :label="a"
              />
            </el-select>
            <el-input
              v-model="auditSince"
              type="date"
              style="width: 9rem"
              size="small"
              class="tabular-nums"
              :title="S.kbset.auditSince"
              @change="resetAudit(() => {})"
            />
            <span class="text-sub text-ink-2">→</span>
            <el-input
              v-model="auditUntil"
              type="date"
              style="width: 9rem"
              size="small"
              class="tabular-nums"
              :title="S.kbset.auditUntil"
              @change="resetAudit(() => {})"
            />
            <el-button
              v-if="auditFiltered"
              size="small"
              @click="
                resetAudit(() => {
                  auditAction = '';
                  auditSince = '';
                  auditUntil = '';
                })
              "
            >
              {{ S.kbset.auditClear }}
            </el-button>
            <span class="ml-auto tabular-nums text-aux text-ink-2">
              {{ S.kbset.auditTotal(auditTotal) }}
            </span>
          </div>
          <div class="rounded-card border border-line bg-card px-5 py-4">
            <div class="mb-3 text-body font-semibold text-ink">
              {{ S.kbset.activity }}
            </div>
            <p v-if="audit.isLoading.value" class="text-sub text-ink-2">
              {{ S.nav.loading }}
            </p>
            <p v-else-if="auditEvents.length === 0" class="text-sub text-ink-2">
              {{ S.kbset.activityEmpty }}
            </p>
            <div v-else>
              <div
                v-for="e in auditEvents"
                :key="e.id"
                class="flex items-baseline gap-3 border-b border-line py-2 text-body first:pt-0 last:border-0"
              >
                <span class="shrink-0 tabular-nums text-aux text-ink-2">
                  {{ localDateTime(e.created_at) }}
                </span>
                <span class="min-w-0 truncate">
                  <span class="text-ink">
                    {{
                      e.actor_name ??
                      (e.actor_id
                        ? S.kbset.deletedUser
                        : e.action.startsWith("review.")
                          ? S.kbset.adjudicator
                          : S.kbset.engine)
                    }}
                  </span>
                  <span class="text-ink-2">
                    {{
                      S.kbset.auditActions[
                        e.action as keyof typeof S.kbset.auditActions
                      ] ?? e.action
                    }}
                  </span>
                  <span v-if="auditDetailName(e)" class="text-ink-2">
                    “{{ auditDetailName(e) }}”
                  </span>
                </span>
              </div>
            </div>
            <UPager
              :total="auditTotal"
              :page-size="AUDIT_PAGE"
              :page="auditPage"
              @page="(p: number) => (auditPage = p)"
            />
          </div>
        </div>

        <!-- ======== Danger（删库：打字级确认） ======== -->
        <div
          v-if="section === 'danger'"
          class="flex-bc gap-4 rounded-card border border-line bg-card px-6 py-4"
        >
          <div class="min-w-0">
            <div class="text-body font-medium text-ink">
              {{ S.kbset.deleteRowTitle }}
            </div>
            <div class="mt-1 text-sub text-ink-2">
              {{ S.kbset.deleteRowHint }}
            </div>
          </div>
          <!-- 这里只打开确认框，红色在确认框里 -->
          <el-button
            size="small"
            class="shrink-0"
            @click="confirmDelete = true"
          >
            {{ S.kbset.deleteRowBtn }}
          </el-button>
        </div>

        <p v-if="error" class="mt-3 text-body text-danger">{{ error }}</p>
    </div>

    <!-- 把一个已有账号加进这个库。picker 在弹窗里也仍然常驻：没人可加时它自己会说 -->
    <el-dialog
      v-model="adding"
      :title="S.kbset.addMemberTitle"
      width="30rem"
      append-to-body
    >
      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.userLabel }}
          </div>
          <el-select
            v-model="addUserId"
            filterable
            class="w-full"
            :placeholder="S.kbset.addMember"
            :no-data-text="S.ui.noMatches"
          >
            <el-option
              v-for="u in addable"
              :key="u.id"
              :value="u.id"
              :label="u.display_name"
            >
              <span class="flex flex-col">
                <span>{{ u.display_name }}</span>
                <span class="text-aux text-ink-2">{{ u.email }}</span>
              </span>
            </el-option>
          </el-select>
        </div>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.roleLabel }}
          </div>
          <el-select v-model="addRole" class="w-full">
            <el-option
              v-for="r in rolesFor(isOpenKb)"
              :key="r.value"
              :value="r.value"
              :label="r.label"
            />
          </el-select>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="adding = false">{{
          S.members.cancel
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!addUserId"
          @click="
            () => {
              setMember(addUserId, addRole);
              adding = false;
            }
          "
        >
          {{ S.members.add }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 删库：打字级确认（要库自己的名字解锁；默认库没有这一节） -->
    <DangerConfirm
      v-if="confirmDelete && kb.data.value"
      :title="S.kbset.deleteKb"
      :hint="S.kbset.deleteHint(kb.data.value.name)"
      :require-text="kb.data.value.name"
      :confirm-label="S.kbset.deleteBtn"
      :cancel-label="S.library.cancel"
      :busy="removeKb.isPending.value"
      @confirm="removeKb.mutate()"
      @cancel="confirmDelete = false"
    />
  </div>
</template>
