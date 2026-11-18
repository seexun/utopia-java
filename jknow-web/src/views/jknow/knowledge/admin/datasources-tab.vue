<!-- 系统层数据源（对照旧仓 DataSourcesAdmin + NewDataSourceDialog + SourceGrants）：
     凭据只进不出，列表只显示 host:port/db 摘要；注册按引擎填几格拼连接串
     （dsSpecs——domain/pages/Settings.ts 随迁件），试连**不落库**；
     授权给工作区（0014：授权与挂载是两层），收回时连已挂上的一起卸掉。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import {
  adminDataSources,
  adminCreateDataSource,
  adminDeleteDataSource,
  adminTestDataSource,
  adminTestConnString,
  dataSourceGrants,
  grantDataSource,
  revokeDataSource
} from "@/api/knowledge/admin";
import { workspaces as workspacesApi } from "@/api/knowledge/workspaces";
import { dsSpecs } from "@/domain/pages/Settings";
import type { DataSourceView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { UDialog } from "@/components/knowledge/dialog";

const queryClient = useQueryClient();
const list = useQuery({ queryKey: ["dataSources"], queryFn: adminDataSources });
const wss = useQuery({ queryKey: ["workspaces"], queryFn: workspacesApi });

const creating = ref(false);
// 授权是按工作区的，单租户部署里那一个工作区谁也没见过——所以它不占表里一列，
// 点「可用于」那一格才展开
const grantsFor = ref<DataSourceView | null>(null);
const invalidate = () =>
  queryClient.invalidateQueries({ queryKey: ["dataSources"] });
const onErr = (e: unknown) =>
  toast.error(e instanceof Error ? e.message : String(e));

const remove = useMutation({
  mutationFn: (id: string) => adminDeleteDataSource(id),
  onSettled: invalidate
});
const test = useMutation({
  mutationFn: (id: string) => adminTestDataSource(id),
  onSettled: invalidate,
  onError: onErr
});

const rows = computed(() => list.data.value?.data_sources ?? []);

/* ---- 注册弹窗：引擎 → 名称 → 字段 → 连接串自动拼 → 试连 → 存 ---- */
const specs = dsSpecs();
const dsName = ref("");
const engineId = ref(specs[0]?.id ?? "postgres");
const values = ref<Record<string, string>>({});
const spec = computed(
  () => specs.find(s => s.id === engineId.value) ?? specs[0]
);
const conn = computed(() => {
  try {
    return spec.value.build(values.value);
  } catch {
    // build 对缺席的键容忍（#573）；这里再兜一层
    return "";
  }
});
const filled = computed(() =>
  spec.value.fields.every(f => f.optional || (values.value[f.key] ?? "").trim())
);
const ready = computed(() => !!dsName.value.trim() && filled.value);
// 对话框上一次只说一个结果：试连和新增各有自己的错误，两个都留着就是
// 同一句话出现两次（#921）。改字段、换动作，前一个结果就不作数了
const probe = ref<{ ok: boolean; error?: string } | null>(null);
const probeErr = ref<string | null>(null);
const probing = ref(false);
const createErr = ref<string | null>(null);
const creatingBusy = ref(false);
function resetResults() {
  probe.value = null;
  probeErr.value = null;
  createErr.value = null;
}
function setField(key: string, v: string) {
  resetResults();
  values.value = { ...values.value, [key]: v };
}
async function probeConn() {
  probing.value = true;
  createErr.value = null;
  try {
    probe.value = await adminTestConnString(conn.value);
  } catch (e) {
    probeErr.value = e instanceof Error ? e.message : String(e);
  }
  probing.value = false;
}
async function create() {
  creatingBusy.value = true;
  try {
    await adminCreateDataSource({
      name: dsName.value.trim(),
      conn_string: conn.value
    });
    dsName.value = "";
    values.value = {};
    resetResults();
    creating.value = false;
    invalidate();
    toast.success(S.toast.created);
  } catch (e) {
    createErr.value = e instanceof Error ? e.message : String(e);
  }
  creatingBusy.value = false;
}
/** 回显时把密码位打码：`postgres://user:••••@host/db` */
const maskedConn = computed(() =>
  conn.value.replace(
    /(\/\/[^:@/]+:)([^@/]+)(@)/,
    (_m, head: string, _pw: string, tail: string) => `${head}••••${tail}`
  )
);

/* ---- 授权面板（SourceGrants 对等） ---- */
const grants = useQuery({
  queryKey: computed(() => ["dataSourceGrants", grantsFor.value?.id]),
  queryFn: () => dataSourceGrants(grantsFor.value!.id),
  enabled: computed(() => !!grantsFor.value)
});
const grantPick = ref("");
const grantNotice = ref<string | null>(null);
const grant = useMutation({
  mutationFn: (wsId: string) => grantDataSource(grantsFor.value!.id, wsId),
  onSuccess: () => {
    grantPick.value = "";
    grantNotice.value = null;
    queryClient.invalidateQueries({
      queryKey: ["dataSourceGrants", grantsFor.value?.id]
    });
  },
  onError: onErr
});
const revokeM = useMutation({
  mutationFn: (wsId: string) => revokeDataSource(grantsFor.value!.id, wsId),
  // 卸了几个要说出来：收回授权会顺带断掉正在用的挂载，悄悄断比断本身更糟
  onSuccess: r => {
    grantNotice.value = S.settings.datasources.grantRevoked(r.unmounted);
    queryClient.invalidateQueries({
      queryKey: ["dataSourceGrants", grantsFor.value?.id]
    });
  },
  onError: onErr
});
const granted = computed(() => grants.data.value?.workspaces ?? []);
const grantedIds = computed(() => new Set(granted.value.map(w => w.id)));
const grantable = computed(() =>
  (wss.data.value ?? []).filter(w => !grantedIds.value.has(w.id))
);
</script>

<template>
  <div class="space-y-4">
    <!-- 登记在名单上面、靠右：动作在内容之前 -->
    <div class="flex items-start gap-4">
      <p class="min-w-0 flex-1 text-sub text-ink-2">
        {{ S.settings.datasources.hint }}
      </p>
      <el-button
        type="primary"
        size="small"
        class="shrink-0"
        @click="creating = true"
      >
        <Icon icon="lucide:plus" width="12" height="12" class="mr-1" />
        {{ S.settings.datasources.add }}
      </el-button>
    </div>

    <div
      v-if="rows.length === 0"
      class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
    >
      {{ S.settings.datasources.empty }}
    </div>
    <div v-else class="overflow-hidden rounded-card border border-line bg-card">
      <table class="w-full text-body">
        <thead>
          <tr class="border-b border-line text-left text-sub text-ink-2">
            <th class="px-4 py-3 font-medium">
              {{ S.settings.datasources.name }}
            </th>
            <th class="px-4 py-3 font-medium">
              {{ S.settings.datasources.engine }}
            </th>
            <th class="px-4 py-3 font-medium">
              {{ S.settings.datasources.colConn }}
            </th>
            <th class="px-4 py-3 font-medium">
              {{ S.settings.datasources.grants }}
            </th>
            <th class="px-4 py-3 font-medium">
              {{ S.settings.datasources.colStatus }}
            </th>
            <th class="px-4 py-3" />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="d in rows"
            :key="d.id"
            class="border-b border-line last:border-0"
          >
            <td class="px-4 py-3 text-ink">{{ d.name }}</td>
            <td class="px-4 py-3 text-sub text-ink-2">{{ d.engine }}</td>
            <td
              class="max-w-xs truncate px-4 py-3 font-mono text-sub text-ink-2"
              :title="d.summary"
            >
              {{ d.summary }}
            </td>
            <td class="px-4 py-3">
              <!-- 表里那一格：授权给了谁。点它展开授权面板——**没授权要看得见**，
                  那条源谁也挂不上，而这是它唯一说得出口的地方 -->
              <button
                type="button"
                class="link-inline text-sub"
                @click="grantsFor = d"
              >
                {{
                  grantedIds.has(d.id)
                    ? "…"
                    : S.settings.datasources.grantsNoneShort
                }}
              </button>
            </td>
            <td class="px-4 py-3">
              <span
                class="badge-soft"
                :class="
                  d.last_test_ok === true
                    ? 'badge-soft-success'
                    : d.last_test_ok === false
                      ? 'badge-soft-danger'
                      : ''
                "
              >
                {{
                  d.last_test_ok === true
                    ? S.settings.datasources.testOk
                    : d.last_test_ok === false
                      ? S.settings.datasources.testFail
                      : S.settings.datasources.neverTested
                }}
              </span>
            </td>
            <td class="whitespace-nowrap px-4 py-3 text-right">
              <button
                type="button"
                class="link-inline text-sub"
                :disabled="test.isPending.value"
                @click="test.mutate(d.id)"
              >
                {{ S.settings.datasources.test }}
              </button>
              <button
                type="button"
                class="link-inline is-danger ml-3 text-sub"
                :disabled="remove.isPending.value"
                @click="remove.mutate(d.id)"
              >
                {{ S.settings.datasources.remove }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 注册弹窗：试连在左边——它不是「完成」，是完成之前的那一步 -->
    <UDialog
      :open="creating"
      :close-label="S.ui.close"
      width="lg"
      @update:open="(o: boolean) => !o && (creating = false)"
    >
      <template #title>
        <span>{{ S.settings.datasources.newTitle }}</span>
      </template>
      <template #default>
        <div class="space-y-3">
          <div class="grid grid-cols-2 gap-3">
            <div>
              <div class="mb-1 text-sub font-medium text-ink-2">
                {{ S.settings.datasources.name }}
              </div>
              <el-input v-model="dsName" class="w-full" />
            </div>
            <div>
              <div class="mb-1 text-sub font-medium text-ink-2">
                {{ S.settings.datasources.engine }}
              </div>
              <el-select
                :model-value="engineId"
                class="w-full"
                @update:model-value="
                  (v: string) => {
                    engineId = v;
                    values = {};
                    resetResults();
                  }
                "
              >
                <el-option
                  v-for="s in specs"
                  :key="s.id"
                  :value="s.id"
                  :label="s.label"
                />
              </el-select>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div
              v-for="f in spec.fields"
              :key="f.key"
              :class="f.key === 'conn' ? 'col-span-2' : ''"
            >
              <div class="mb-1 text-sub font-medium text-ink-2">
                {{
                  f.optional
                    ? `${f.label} ${S.settings.datasources.optional}`
                    : f.label
                }}
              </div>
              <el-input
                :model-value="values[f.key] ?? ''"
                :type="f.secret ? 'password' : 'text'"
                :show-password="f.secret"
                :autocomplete="f.secret ? 'new-password' : 'off'"
                :placeholder="f.placeholder"
                class="w-full"
                @update:model-value="(v: string) => setField(f.key, v)"
              />
            </div>
          </div>
          <!-- 拼出来的那条串给人看一眼——密码位打码。填错端口、库名多一个斜杠，
              在这里一眼就看得出来，不必等到试连 -->
          <p
            v-if="engineId !== 'raw' && filled"
            class="truncate font-mono text-aux text-ink-2"
            :title="maskedConn"
          >
            {{ maskedConn }}
          </p>
          <p
            v-if="probe"
            class="text-sub"
            :class="probe.ok ? 'text-success' : 'text-danger'"
          >
            {{
              probe.ok
                ? S.settings.datasources.testOk
                : (probe.error ?? S.settings.datasources.testFail)
            }}
          </p>
          <p v-if="probeErr" class="text-sub text-danger">{{ probeErr }}</p>
          <p v-if="createErr" class="text-sub text-danger">{{ createErr }}</p>
        </div>
      </template>
      <template #footer>
        <div class="foot-danger">
          <el-button
            size="small"
            :disabled="!filled || probing"
            @click="probeConn"
          >
            {{
              probing
                ? S.settings.datasources.testing
                : S.settings.datasources.testConn
            }}
          </el-button>
        </div>
        <el-button size="small" @click="creating = false">{{
          S.members.cancel
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!ready || creatingBusy"
          @click="create"
        >
          {{ S.settings.datasources.add }}
        </el-button>
      </template>
    </UDialog>

    <!-- 授权弹窗：给谁用；收回连已挂上的一起卸（说卸了几个） -->
    <UDialog
      :open="!!grantsFor"
      :close-label="S.ui.close"
      width="md"
      @update:open="(o: boolean) => !o && (grantsFor = null)"
    >
      <template #title>
        <span>{{ grantsFor?.name ?? "" }}</span>
      </template>
      <template #description>
        <span>{{ S.settings.datasources.grantsHint }}</span>
      </template>
      <template #default>
        <div class="space-y-2">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-aux text-ink-2">{{
              S.settings.datasources.grants
            }}</span>
            <span v-if="granted.length === 0" class="text-aux text-ink-2">
              {{ S.settings.datasources.grantsNone }}
            </span>
            <span v-else class="tabular-nums text-aux text-ink-2">
              {{ S.settings.datasources.grantsCount(granted.length) }}
            </span>
          </div>
          <div v-if="granted.length > 0" class="flex flex-wrap gap-2">
            <span
              v-for="w in granted"
              :key="w.id"
              class="badge-soft badge-soft-info flex items-center gap-1"
            >
              {{ w.name }}
              <button
                type="button"
                class="text-ink-2 hover:text-ink"
                :disabled="revokeM.isPending.value"
                :aria-label="S.settings.datasources.grantRevoke"
                @click="revokeM.mutate(w.id)"
              >
                <Icon icon="lucide:x" width="10" height="10" />
              </button>
            </span>
          </div>
          <el-select
            v-if="grantable.length > 0"
            v-model="grantPick"
            filterable
            class="w-full"
            :placeholder="S.settings.datasources.grantAdd"
            @change="(v: string) => v && grant.mutate(v)"
          >
            <el-option
              v-for="w in grantable"
              :key="w.id"
              :value="w.id"
              :label="w.name"
            />
          </el-select>
          <p v-if="grantNotice" class="text-aux text-ink-2">
            {{ grantNotice }}
          </p>
        </div>
      </template>
      <template #footer>
        <el-button size="small" type="primary" @click="grantsFor = null">
          {{ S.ui.close }}
        </el-button>
      </template>
    </UDialog>
  </div>
</template>
