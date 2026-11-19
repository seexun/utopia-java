<!-- 个人令牌页（对照旧仓 pages/Tokens.tsx 全量）：我发过的令牌表 + 发放弹窗 +
     撤销（两击确认）。明文只在发放那一次的响应里出现——所以发放的下一秒就是
     「把它抄走」：一个窗口两副内容，先是表单，发出来之后是明文 + MCP 配置片段，
     中间不关窗，关掉就再也看不到它了。撤销过的沉底但仍然列着。
     reskin-b4 模板层重造（script 逻辑零动，仅 import 换装）：h1 行升 PageHeader
     （hint 作副题、新令牌主行动入 extra），表格/发放弹窗结构与接线原样。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import KeyRoundIcon from "~icons/lucide/key-round";
import CheckIcon from "~icons/lucide/check";
import CopyIcon from "~icons/lucide/copy";
import { tokens as tokensApi, issueToken, revokeToken } from "@/api/knowledge/me";
import { kbs as kbsApi } from "@/api/knowledge/kbs";
import type { Kb, TokenView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { copyAndSay } from "@/domain/clipboard";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { UDialog } from "@/components/knowledge/dialog";
/* PageHeader（reskin-b1 自 gvdata 零改写迁入）：22/800 标题 + 副题 + extra 动作区 */
import PageHeader from "@/components/PageHeader/index.vue";

defineOptions({ name: "AccountTokens" });

const EXPIRY_CHOICES = [30, 90, 365, 0] as const;

const kbStore = useKbContextHook();
const queryClient = useQueryClient();
const list = useQuery({ queryKey: ["tokens"], queryFn: tokensApi });
// 库范围选择与 MCP 片段要库的名字
const kbsQ = useQuery({
  queryKey: computed(() => ["kbs", kbStore.workspaceId]),
  queryFn: () => kbsApi(kbStore.workspaceId),
  enabled: computed(() => !!kbStore.workspaceId)
});
const kbList = computed<Kb[]>(() => kbsQ.data.value ?? []);

const ymd = (iso: string) => iso.slice(0, 10);

// 活的在前、按新到旧；撤销过的沉底但仍然列着
const rows = computed(() =>
  [...(list.data.value?.tokens ?? [])].sort((a, b) => {
    if (!!a.revoked_at !== !!b.revoked_at) return a.revoked_at ? 1 : -1;
    return b.created_at.localeCompare(a.created_at);
  })
);

/* ---- 发放：一个窗口两副内容 ---- */
const creating = ref(false);
const issued = ref<{ token: string; info: TokenView } | null>(null);
const name = ref("");
const scope = ref<"read" | "write">("read");
const picked = ref(new Set<string>());
const days = ref<number>(90);
const issueErr = ref<string | null>(null);
const issuing = ref(false);

const issue = useMutation({
  mutationFn: () =>
    issueToken({
      name: name.value.trim(),
      scope: scope.value,
      // 缺省 = 这个人能进的全部库
      kb_ids: picked.value.size ? Array.from(picked.value) : null,
      expires_in_days: days.value
    }),
  onSuccess: res => {
    // 同一个窗口换一副内容：发完立刻是"把它抄走"
    issued.value = res;
    name.value = "";
    picked.value = new Set();
    issueErr.value = null;
    queryClient.invalidateQueries({ queryKey: ["tokens"] });
  },
  onError: (e: Error) => (issueErr.value = e.message)
});
function close() {
  creating.value = false;
  issued.value = null;
}

/* ---- 撤销：不可撤回，但代价只是重发一枚——轻确认（二次点击） ---- */
const armed = ref<string | null>(null);
const revoke = useMutation({
  mutationFn: (id: string) => revokeToken(id),
  onSettled: () => queryClient.invalidateQueries({ queryKey: ["tokens"] }),
  onSuccess: () => toast.success(S.toast.saved),
  onError: (e: Error) => toast.error(e.message)
});

const kbName = computed(() => {
  const m = new Map(kbList.value.map(k => [k.id, k.name]));
  return (id: string) => m.get(id) ?? id.slice(0, 8);
});

/* ---- 发出来的那枚：明文 + 配置片段（0014：每个库一个端点） ---- */
const copyDone = ref(false);
async function copy(text: string) {
  // 复制成了才换成「已复制」——先换字再复制，在 http 页面上什么也没复制
  if (!(await copyAndSay(text, S.account.copied))) return;
  copyDone.value = true;
  window.setTimeout(() => (copyDone.value = false), 1500);
}
function mcpSnippet(kbId: string, kbName: string, token: string): string {
  const slug =
    kbName
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "") || "kb";
  const cfg = {
    mcpServers: {
      [`utopia-${slug}`]: {
        type: "http",
        url: `${window.location.origin}/api/v1/kbs/${kbId}/mcp`,
        headers: { Authorization: `Bearer ${token}` }
      }
    }
  };
  return JSON.stringify(cfg, null, 2);
}
const snippetKbId = ref("");
const snippet = computed(() => {
  if (!issued.value) return "";
  // 片段默认指向令牌限定的第一个库；没限定就取列表第一个
  const ids = issued.value.info.kb_ids ?? [];
  const candidates = ids.length ? kbList.value.filter(k => ids.includes(k.id)) : kbList.value;
  const id = snippetKbId.value || candidates[0]?.id || kbList.value[0]?.id || "";
  const kb = kbList.value.find(k => k.id === id);
  return kb ? mcpSnippet(kb.id, kb.name, issued.value.token) : "";
});
const snippetCandidates = computed(() => {
  if (!issued.value) return [];
  const ids = issued.value.info.kb_ids ?? [];
  return ids.length ? kbList.value.filter(k => ids.includes(k.id)) : kbList.value;
});
</script>

<template>
  <div class="px-8 py-6">
    <div class="mx-auto w-full max-w-4xl">
      <!-- 页头（reskin-b4）：hint 升副题；唯一主行动=新令牌（发放是个动作，
           不是这一页的常驻内容：表单住在弹窗里） -->
      <PageHeader :title="S.account.tokensTitle" :subtitle="S.account.tokensHint">
        <template #extra>
          <el-button type="primary" size="small" @click="creating = true">
            {{ S.account.newToken }}
          </el-button>
        </template>
      </PageHeader>

      <div
        v-if="list.data.value && rows.length === 0"
        class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
      >
        {{ S.account.noTokens }}
      </div>
      <div v-else class="overflow-hidden rounded-card border border-line bg-card">
        <table class="w-full text-body">
          <thead>
            <tr class="text-left text-sub text-ink-2">
              <th class="px-4 py-3 font-medium">{{ S.account.tokenName }}</th>
              <th class="px-4 py-3 font-medium">{{ S.account.tokenScope }}</th>
              <th class="px-4 py-3 font-medium">{{ S.account.tokenKbs }}</th>
              <th class="px-4 py-3 font-medium">{{ S.account.colLastUsed }}</th>
              <th class="px-4 py-3 font-medium">{{ S.account.tokenExpires }}</th>
              <th class="px-4 py-3 font-medium">{{ S.account.colCreated }}</th>
              <th class="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in rows"
              :key="t.id"
              :class="['border-t border-line', t.revoked_at && 'opacity-55']"
            >
              <td class="px-4 py-3">
                <div class="flex items-center gap-2">
                  <KeyRoundIcon width="13" height="13" class="shrink-0 text-ink-2" />
                  <span class="truncate text-body text-ink">{{ t.name }}</span>
                  <code class="shrink-0 font-mono text-aux text-ink-2">
                    {{ t.token_prefix }}…
                  </code>
                </div>
              </td>
              <td class="px-4 py-3">
                <span
                  class="badge-soft"
                  :class="t.scope === 'write' ? 'badge-soft-warning' : 'badge-soft-info'"
                >
                  {{ t.scope === "write" ? S.account.scopeWrite : S.account.scopeRead }}
                </span>
              </td>
              <td
                class="px-4 py-3 text-sub text-ink-2"
                :title="
                  t.kb_ids?.length
                    ? t.kb_ids.map(kbName).join(' · ')
                    : undefined
                "
              >
                {{ t.kb_ids?.length ? S.account.nBases(t.kb_ids.length) : S.account.allBases }}
              </td>
              <td class="px-4 py-3 tabular-nums text-sub text-ink-2">
                {{ t.last_used_at ? ymd(t.last_used_at) : S.account.neverUsed }}
              </td>
              <td class="px-4 py-3 tabular-nums text-sub text-ink-2">
                {{ t.expires_at ? ymd(t.expires_at) : S.account.noExpiry }}
              </td>
              <td class="px-4 py-3 tabular-nums text-sub text-ink-2">
                {{ ymd(t.created_at) }}
              </td>
              <td class="px-4 py-3 text-right">
                <span v-if="t.revoked_at" class="badge-soft badge-soft-danger">
                  {{ S.account.revokedOn(ymd(t.revoked_at)) }}
                </span>
                <el-button
                  v-else-if="armed === t.id"
                  type="danger"
                  size="small"
                  :disabled="revoke.isPending.value"
                  @click="revoke.mutate(t.id)"
                  @blur="armed = null"
                >
                  {{ S.account.revokeConfirm }}
                </el-button>
                <el-button
                  v-else
                  size="small"
                  :disabled="revoke.isPending.value"
                  @click="armed = t.id"
                >
                  {{ S.account.revoke }}
                </el-button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 一个窗口两副内容：先是表单，发出来之后是那一枚明文。中间不关窗 -->
    <UDialog
      :open="creating || !!issued"
      :close-label="S.ui.close"
      :width="issued ? 'lg' : 'md'"
      @update:open="(o: boolean) => !o && close()"
    >
      <template #title>
        <span>{{ issued ? S.account.issuedTitle : S.account.newToken }}</span>
      </template>
      <template #description>
        <span v-if="issued">{{ S.account.issuedHint }}</span>
      </template>
      <template #default>
        <!-- 发出来的那一枚：明文 + 配置片段，关掉就再也看不到 -->
        <div v-if="issued">
          <div class="flex items-center gap-2">
            <code
              class="min-w-0 flex-1 truncate rounded-ctl bg-fill px-3 py-2 font-mono text-sub text-ink"
            >
              {{ issued.token }}
            </code>
            <el-button size="small" @click="copy(issued.token)">
              <CheckIcon v-if="copyDone" width="12" height="12" class="mr-1" />
              <CopyIcon v-else width="12" height="12" class="mr-1" />
              {{ copyDone ? S.account.copied : S.account.copy }}
            </el-button>
          </div>
          <div class="mt-6 flex flex-wrap items-baseline justify-between gap-3">
            <div class="text-body font-medium text-ink">{{ S.account.mcpTitle }}</div>
            <span
              v-if="snippetCandidates.length > 1"
              class="flex items-center gap-2 text-sub text-ink-2"
            >
              {{ S.account.mcpBase }}
              <el-select
                v-model="snippetKbId"
                size="small"
                style="width: 10rem"
                :placeholder="snippetCandidates[0]?.name"
              >
                <el-option
                  v-for="k in snippetCandidates"
                  :key="k.id"
                  :value="k.id"
                  :label="k.name"
                />
              </el-select>
            </span>
          </div>
          <p class="mt-1 text-sub text-ink-2">{{ S.account.mcpHint }}</p>
          <div class="relative mt-2">
            <pre
              class="overflow-x-auto overscroll-contain rounded-card bg-fill px-3 py-2 font-mono text-sub text-ink-2"
              >{{ snippet }}</pre
            >
            <div class="absolute right-1.5 top-1.5">
              <el-button size="small" @click="copy(snippet)">
                <CheckIcon v-if="copyDone" width="12" height="12" class="mr-1" />
                <CopyIcon v-else width="12" height="12" class="mr-1" />
                {{ copyDone ? S.account.copied : S.account.copy }}
              </el-button>
            </div>
          </div>
        </div>
        <!-- 表单：名字 / 范围与有效期并排 / 库搜着选 -->
        <div v-else>
          <div class="mb-3">
            <div class="mb-1 text-sub font-medium text-ink-2">{{ S.account.tokenName }}</div>
            <el-input
              v-model="name"
              :placeholder="S.account.tokenNamePlaceholder"
              maxlength="64"
            />
          </div>
          <div class="mb-3 grid grid-cols-2 gap-4">
            <div>
              <div class="mb-1 text-sub font-medium text-ink-2">
                {{ S.account.tokenScope }}
                <span class="ml-2 text-aux text-ink-2">{{ S.account.scopeHint }}</span>
              </div>
              <el-radio-group v-model="scope">
                <el-radio-button value="read">{{ S.account.scopeRead }}</el-radio-button>
                <el-radio-button value="write">{{ S.account.scopeWrite }}</el-radio-button>
              </el-radio-group>
            </div>
            <div>
              <div class="mb-1 text-sub font-medium text-ink-2">{{ S.account.tokenExpires }}</div>
              <el-select v-model="days" class="w-full">
                <el-option
                  v-for="d in EXPIRY_CHOICES"
                  :key="d"
                  :value="d"
                  :label="d === 0 ? S.account.expiresNever : S.account.expiresDays(d)"
                />
              </el-select>
            </div>
          </div>
          <!-- 库可以很多：搜着选。**不选就是全部**——placeholder 自己写着 allBases -->
          <div>
            <div class="mb-1 text-sub font-medium text-ink-2">{{ S.account.tokenKbs }}</div>
            <el-select
              multiple
              filterable
              class="w-full"
              :model-value="[...picked]"
              :placeholder="S.account.allBases"
              @update:model-value="(v: string[]) => (picked = new Set(v))"
            >
              <el-option v-for="k in kbList" :key="k.id" :value="k.id" :label="k.name" />
            </el-select>
          </div>
          <p v-if="issueErr" class="mt-2 text-sub text-danger">{{ issueErr }}</p>
        </div>
      </template>
      <template #footer>
        <template v-if="issued">
          <el-button type="primary" size="small" @click="close">
            {{ S.account.tokenDone }}
          </el-button>
        </template>
        <template v-else>
          <el-button size="small" @click="close">{{ S.account.cancel }}</el-button>
          <el-button
            type="primary"
            size="small"
            :disabled="!name.trim() || issuing"
            @click="issue.mutate()"
          >
            {{ S.account.issueToken }}
          </el-button>
        </template>
      </template>
    </UDialog>
  </div>
</template>
