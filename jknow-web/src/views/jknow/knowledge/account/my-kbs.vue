<!-- 我的知识库页（对照旧仓 pages/MyKbs.tsx 全量）：可访问的库 + 我的角色 +
     加入信息 + 概览统计 + 新建知识库弹窗（缺省 restricted，不污染全员库列表；
     建库不是系统管理员专属——工作区 Admin 起就能建）。?create=true 落地即开表单
     （「新建」深链指到这里）。
     reskin-b4 模板层重造（script 逻辑零动，仅 import 换装）：h1 行升 PageHeader
     （新建知识库主行动入 extra，权限闸原样），表格/弹窗结构与接线原样。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import LockIcon from "~icons/lucide/lock";
import { myKbs, workspaceRole } from "@/api/knowledge/workspaces";
import { createKb, ontologyPacks } from "@/api/knowledge/kbs";
import { me as meApi } from "@/api/knowledge/auth";
import type { MyKb } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { UDialog } from "@/components/knowledge/dialog";
/* PageHeader（reskin-b1 自 gvdata 零改写迁入）：22/800 标题 + 副题 + extra 动作区 */
import PageHeader from "@/components/PageHeader/index.vue";

defineOptions({ name: "AccountMyKbs" });

const router = useRouter();
const route = useRoute();
const queryClient = useQueryClient();
const kbStore = useKbContextHook();
const workspaceId = computed(() => kbStore.workspaceId);
const filter = ref("");

const mine = useQuery({
  queryKey: computed(() => ["myKbs", workspaceId.value]),
  queryFn: () => myKbs(workspaceId.value!),
  enabled: computed(() => !!workspaceId.value)
});
const me = useQuery({ queryKey: ["me"], queryFn: meApi });
// 能不能建库：系统管理员，或者在这个工作区里是 Admin+
const wsRole = useQuery({
  queryKey: computed(() => ["workspaceRole", workspaceId.value]),
  queryFn: () => workspaceRole(workspaceId.value!),
  enabled: computed(() => !!workspaceId.value)
});
// 建库的入口在别处（「新建」深链），带着 ?create=true 落地就直接开表单
const creating = ref(String(route.query.create ?? "") === "true");
/* I-1（W3 审查收口）：「新建知识库」是站内 push（path 不变、
   只改 query）——组件不重挂（lay-content 按 path 定 key），onMounted 那次
   消费接不住第二回。改 watch query：来了就开表单并清掉地址栏参数；
   前进/后退到带参地址同样生效 */
watch(
  () => route.query.create,
  v => {
    if (String(v ?? "") !== "true") return;
    creating.value = true;
    router.replace({ path: "/account/kbs" });
  },
  // 首挂带参（刷新/分享的深链）同样消费并清地址栏——原 onMounted 形态有此语义
  { immediate: true }
);

const ymd = (iso: string) => iso.slice(0, 10);
function joinInfo(row: MyKb): string {
  if (row.my_role === "owner") return S.account.deploymentAdmin as string;
  if (row.joined_at) {
    return row.added_by_name
      ? S.account.addedBy(row.added_by_name, ymd(row.joined_at))
      : S.account.joinedOn(ymd(row.joined_at));
  }
  return S.account.openToEveryone as string;
}

const q = computed(() => filter.value.trim().toLowerCase());
const rows = computed(() =>
  (mine.data.value?.kbs ?? []).filter(
    r =>
      !q.value ||
      r.kb.name.toLowerCase().includes(q.value) ||
      (r.kb.description ?? "").toLowerCase().includes(q.value)
  )
);

function openKb(id: string) {
  kbStore.remember(id);
  router.push(`/kb/${id}/graph`);
}

/* ---- 新建知识库弹窗（NewKbModal 对等） ---- */
const name = ref("");
const desc = ref("");
const restricted = ref(true);
// 默认一个包都不勾（#580）：量过之后本体从文档里长出来的是库自己的十几个类，
// 915 个类的包让每块抽取提示词从 2k 涨到 18k tokens。要包的人在这里勾
const packs = ref<string[]>([]);
const packsQ = useQuery({ queryKey: ["ontologyPacks"], queryFn: ontologyPacks });
const creatingBusy = ref(false);
const createErr = ref<string | null>(null);
// 勾选顺序即安装顺序：第一个包的类会认领同名的种子类。取消再勾会排到末尾——这是对的
function togglePack(id: string) {
  packs.value = packs.value.includes(id)
    ? packs.value.filter(p => p !== id)
    : [...packs.value, id];
}
async function create() {
  creatingBusy.value = true;
  createErr.value = null;
  try {
    const kb = await createKb(workspaceId.value!, {
      name: name.value.trim(),
      description: desc.value.trim() || null,
      visibility: restricted.value ? "restricted" : "open",
      ontology_packs: packs.value
    });
    toast.success(S.toast.created);
    queryClient.invalidateQueries({ queryKey: ["myKbs", workspaceId.value] });
    queryClient.invalidateQueries({ queryKey: ["kbs", workspaceId.value] });
    creating.value = false;
    // 建完直达库设置：下一步几乎总是邀人/配置（旧仓 onDone 原话）
    kbStore.remember(kb.id);
    router.push(`/kb/${kb.id}/settings`);
  } catch (e) {
    createErr.value = e instanceof Error ? e.message : String(e);
  }
  creatingBusy.value = false;
}
</script>

<template>
  <div class="px-8 py-6">
    <div class="mx-auto w-full max-w-4xl">
      <!-- 页头（reskin-b4）：唯一主行动=新建知识库，建库权限闸原样
           （建库不是系统管理员专属——工作区 Admin 起就能建，api/kbs.rs 的 create） -->
      <PageHeader :title="S.account.kbsTitle">
        <template #extra>
          <el-button
            v-if="me.data.value?.is_admin || ['admin', 'owner'].includes(wsRole.data.value?.role ?? '')"
            type="primary"
            size="small"
            @click="creating = true"
          >
            {{ S.settings.kbs.newKb }}
          </el-button>
        </template>
      </PageHeader>

      <!-- 筛这份名单的东西在面板外面（DESIGN.md 6） -->
      <div class="relative mb-4">
        <SearchIcon
          class="pointer-events-none absolute left-3 top-1/2 z-10 -translate-y-1/2 text-ink-2"
          width="13"
          height="13"
        />
        <el-input
          v-model="filter"
          class="mykbs-filter"
          style="width: 16rem"
          :placeholder="S.account.kbsFilter"
        />
      </div>

      <!-- 一张表，不是一列卡片：每个库的字段都一样，人来这儿是横着比 -->
      <div
        class="overflow-hidden rounded-card border border-line bg-card"
      >
        <table class="w-full text-body">
          <thead>
            <tr class="text-left text-sub text-ink-2">
              <th class="px-4 py-3 font-medium">
                {{ S.account.kbNameLabel }}
              </th>
              <th class="px-4 py-3 font-medium">
                {{ S.account.kbRoleLabel }}
              </th>
              <th class="px-4 py-3 text-right font-medium">
                {{ S.account.kbDocsLabel }}
              </th>
              <th class="px-4 py-3 text-right font-medium">
                {{ S.account.kbMembersLabel }}
              </th>
              <th class="px-4 py-3 font-medium">
                {{ S.account.kbAccessLabel }}
              </th>
              <th class="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.kb.id"
              class="border-t border-line"
            >
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <span class="truncate text-body text-ink">
                    {{ row.kb.name }}
                  </span>
                  <!-- 这两个是库自己的性质，不是一列数据：贴在名字旁边 -->
                  <span v-if="row.kb.is_default" class="badge-soft badge-soft-info">
                    {{ S.settings.kbs.defaultChip }}
                  </span>
                  <span
                    v-if="row.kb.visibility === 'restricted'"
                    class="flex shrink-0 items-center gap-1 text-aux text-ink-2"
                    :title="S.account.kbRestricted"
                  >
                    <LockIcon width="10" height="10" />
                    {{ S.account.kbRestricted }}
                  </span>
                </div>
              </td>
              <td class="px-4 py-3 text-ink-2">
                {{
                  row.my_role
                    ? (S.account.roleNames[
                        row.my_role as keyof typeof S.account.roleNames
                      ] ?? row.my_role)
                    : "—"
                }}
              </td>
              <td class="px-4 py-3 text-right tabular-nums text-ink-2">
                {{ row.doc_count }}
              </td>
              <td class="px-4 py-3 text-right tabular-nums text-ink-2">
                {{ row.member_count }}
              </td>
              <td class="px-4 py-3 text-sub text-ink-2">
                {{ joinInfo(row) }}
              </td>
              <!-- 设置在前、打开在后（设置入口随任务 6——要库 admin） -->
              <td class="whitespace-nowrap px-4 py-3 text-right">
                <button
                  type="button"
                  class="link-inline text-sub"
                  @click="openKb(row.kb.id)"
                >
                  {{ S.account.openKb }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div
          v-if="mine.data.value && rows.length === 0"
          class="px-4 py-6 text-body text-ink-2"
        >
          {{ S.ui.noMatches }}
        </div>
      </div>
    </div>

    <!-- 新建知识库弹窗：缺省 restricted，不污染全员切换器 -->
    <UDialog
      :open="creating"
      :close-label="S.ui.close"
      width="sm"
      @update:open="(o: boolean) => !o && (creating = false)"
    >
      <template #title>
        <span>{{ S.settings.kbs.newKb }}</span>
      </template>
      <template #default>
        <div>
          <el-input
            v-model="name"
            class="mb-2"
            :placeholder="S.settings.kbs.name"
          />
          <el-input
            v-model="desc"
            class="mb-3"
            :placeholder="S.settings.kbs.description"
          />
          <el-checkbox
            v-model="restricted"
            class="mb-4"
            :label="S.settings.kbs.visRestricted"
          />
          <!-- 包是可选的，而且**多半只装一个**：换成搜着选 -->
          <div>
            <div class="mb-1 text-sub font-medium text-ink-2">
              {{ S.settings.kbs.packsLabel }}
              <span class="ml-2 text-aux text-ink-2">{{ S.settings.kbs.packsHint }}</span>
            </div>
            <el-select
              multiple
              filterable
              class="w-full"
              :model-value="packs"
              :placeholder="S.settings.kbs.packsPick"
              :no-data-text="S.settings.kbs.packsNone"
              @update:model-value="(v: string[]) => (packs = v)"
            >
              <el-option
                v-for="p in packsQ.data.value?.packs ?? []"
                :key="p.id"
                :value="p.id"
                :label="p.name"
              >
                <span class="flex flex-col">
                  <span>{{ p.name }}</span>
                  <span class="text-aux text-ink-2">
                    {{ S.settings.kbs.packsCount(p.classes, p.properties) }}
                  </span>
                </span>
              </el-option>
            </el-select>
          </div>
          <p v-if="createErr" class="mt-2 text-sub text-danger">{{ createErr }}</p>
        </div>
      </template>
      <template #footer>
        <el-button size="small" @click="creating = false">{{ S.library.cancel }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!name.trim() || creatingBusy"
          @click="create"
        >
          {{ S.settings.kbs.create }}
        </el-button>
      </template>
    </UDialog>
  </div>
</template>

<style scoped>
.mykbs-filter :deep(.el-input__wrapper) {
  padding-left: 32px;
}
</style>
