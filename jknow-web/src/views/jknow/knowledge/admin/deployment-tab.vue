<!-- 部署（对照旧仓 pages/Settings.tsx 的 DeploymentAdmin）：注册开关（改即生效）、
     默认本体语言（不是界面语言）、worker 并发外层兜底、按模型的并发（真正的节流，
     每个在用模型一行：限额/重置）。数字要按一下才算数的，按钮在各自的行里。
     reskin-b4 模板层重造（script 零动）：四张卡统一表单卡语言（卡头标题+控制，
     卡身内容），接线与保存语义原样。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { adminDeployment, saveAdminDeployment } from "@/api/knowledge/admin";
import { LANG_NAMES } from "@/i18n";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";

const queryClient = useQueryClient();
const dep = useQuery({ queryKey: ["deployment"], queryFn: adminDeployment });

const workers = ref<number | null>(null);
const shownWorkers = computed(
  () => workers.value ?? dep.data.value?.worker_concurrency ?? 64
);
// 按模型的并发：缺省值 + 每个在用模型的覆盖
const modelDefault = ref<number | null>(null);
const shownDefault = computed(
  () => modelDefault.value ?? dep.data.value?.default_model_concurrency ?? 10
);
const perModel = ref<Record<string, number>>({});

const save = useMutation({
  mutationFn: (v: {
    open: boolean;
    workers?: number;
    defaultModel?: number;
    modelLimit?: {
      base_url: string;
      model: string;
      max_concurrent: number | null;
    };
    ontologyLang?: "en" | "zh";
  }) =>
    saveAdminDeployment(
      v.open,
      v.workers,
      v.defaultModel,
      v.modelLimit,
      v.ontologyLang
    ),
  onSuccess: () => {
    perModel.value = {};
    queryClient.invalidateQueries({ queryKey: ["deployment"] });
    toast.success(S.toast.saved);
  },
  onError: (e: Error) => toast.error(e.message)
});
const open = computed(() => dep.data.value?.open_registration ?? true);
const clamp = (v: number) => Math.max(1, Math.min(256, v || 1));
</script>

<template>
  <div class="space-y-4">
    <!-- 一件事一张卡，与库设置同一副排法（卡头标题+卡身内容）。改即生效的没有底栏 -->
    <div class="rounded-card border border-line bg-card">
      <div class="border-b border-line px-5 py-4">
        <div class="text-body font-semibold text-ink">{{ S.settings.cardAccounts }}</div>
      </div>
      <div class="px-5 py-4">
        <el-checkbox
          :model-value="open"
          :disabled="dep.isLoading.value || save.isPending.value"
          :label="S.settings.deployment.openReg"
          @change="(v: unknown) => save.mutate({ open: !!v })"
        />
        <div class="pl-6 text-aux text-ink-2">{{ S.settings.deployment.openRegHint }}</div>
      </div>
    </div>

    <!-- 新建库的本体语言。**不是界面语言**——界面语言在客户端，不经过后端 -->
    <div class="rounded-card border border-line bg-card">
      <div class="border-b border-line px-5 py-4">
        <div class="text-body font-semibold text-ink">{{ S.settings.deployment.ontologyLang }}</div>
        <div class="mt-0.5 text-sub text-ink-2">{{ S.settings.deployment.ontologyLangHint }}</div>
      </div>
      <div class="px-5 py-4">
        <div class="flex w-fit gap-1 rounded-ctl bg-fill p-1">
          <button
            v-for="l in (['en', 'zh'] as const)"
            :key="l"
            type="button"
            class="rounded-ctl px-3 py-1 text-sub font-medium transition-colors disabled:opacity-40"
            :class="(dep.data.value?.default_ontology_lang ?? 'en') === l ? 'bg-card text-primary shadow-card' : 'text-ink-2 hover:text-ink'"
            :disabled="dep.isLoading.value || save.isPending.value"
            @click="save.mutate({ open: open, ontologyLang: l })"
          >
            {{ LANG_NAMES[l] }}
          </button>
        </div>
      </div>
    </div>

    <!-- worker 并发：外层兜底，防任务无限堆积。单行卡（标题左、数字与按钮右） -->
    <div class="flex-bc gap-3 rounded-card border border-line bg-card px-5 py-4">
      <div class="min-w-0">
        <div class="text-body font-semibold text-ink">{{ S.settings.deployment.workers }}</div>
        <div class="mt-0.5 text-sub text-ink-2">{{ S.settings.deployment.workersHint }}</div>
      </div>
      <div class="flex shrink-0 items-center gap-3">
        <el-input
          :model-value="shownWorkers"
          type="number"
          :min="1"
          :max="256"
          size="small"
          class="tabular-nums"
          style="width: 4rem"
          :disabled="dep.isLoading.value"
          @update:model-value="(v: string | number) => (workers = clamp(Number(v)))"
        />
        <el-button
          size="small"
          :disabled="
            save.isPending.value ||
            workers === null ||
            workers === dep.data.value?.worker_concurrency
          "
          @click="save.mutate({ open: open, workers: shownWorkers })"
        >
          {{ S.settings.deployment.workersApply }}
        </el-button>
      </div>
    </div>

    <!-- 按模型的并发才是真正的节流：约束来自供应商的速率限制，按模型算的。
         卡头=缺省限额（标题+说明+数字/按钮），卡身=在用模型各自一行 -->
    <div class="rounded-card border border-line bg-card">
      <div class="flex-bc gap-3 border-b border-line px-5 py-4">
        <div class="min-w-0">
          <div class="text-body font-semibold text-ink">
            {{ S.settings.deployment.modelConcurrency }}
          </div>
          <div class="mt-0.5 text-sub text-ink-2">
            {{ S.settings.deployment.modelConcurrencyHint }}
          </div>
          <div class="mt-0.5 text-aux text-ink-2">{{ S.settings.deployment.modelDefault }}</div>
        </div>
        <div class="flex shrink-0 items-center gap-3">
          <el-input
            :model-value="shownDefault"
            type="number"
            :min="1"
            :max="256"
            size="small"
            class="tabular-nums"
            style="width: 4rem"
            :disabled="dep.isLoading.value"
            @update:model-value="(v: string | number) => (modelDefault = clamp(Number(v)))"
          />
          <el-button
            size="small"
            :disabled="
              save.isPending.value ||
              modelDefault === null ||
              modelDefault === dep.data.value?.default_model_concurrency
            "
            @click="save.mutate({ open: open, defaultModel: shownDefault })"
          >
            {{ S.settings.deployment.workersApply }}
          </el-button>
        </div>
      </div>
      <!-- 在用的模型各自一行：这一行的数字与按钮是这一行的事，不归底栏 -->
      <div
        v-if="dep.data.value?.models_in_use?.length"
        class="divide-y divide-line px-5 py-2"
      >
        <div
          v-for="m in dep.data.value.models_in_use"
          :key="`${m.base_url}|${m.model}`"
          class="flex items-center gap-2 py-3 text-sub first:pt-1.5 last:pb-1.5"
        >
          <span class="badge-soft shrink-0 px-2">{{ m.kind }}</span>
          <span class="truncate font-mono text-ink-2">{{ m.model }}</span>
          <span class="hidden truncate text-ink-2 sm:inline">{{ m.base_url }}</span>
          <div class="ml-auto flex shrink-0 items-center gap-3">
            <el-input
              :model-value="
                perModel[`${m.base_url}|${m.model}`] ??
                dep.data.value?.model_limits?.find(
                  l => l.base_url === m.base_url && l.model === m.model
                )?.max_concurrent ??
                shownDefault
              "
              type="number"
              :min="1"
              :max="256"
              size="small"
              class="tabular-nums"
              style="width: 4rem"
              @update:model-value="
                (v: string | number) =>
                  (perModel = {
                    ...perModel,
                    [`${m.base_url}|${m.model}`]: clamp(Number(v))
                  })
              "
            />
            <el-button
              size="small"
              :disabled="save.isPending.value || perModel[`${m.base_url}|${m.model}`] === undefined"
              @click="
                save.mutate({
                  open: open,
                  modelLimit: {
                    base_url: m.base_url,
                    model: m.model,
                    max_concurrent: perModel[`${m.base_url}|${m.model}`]
                  }
                })
              "
            >
              {{ S.settings.deployment.workersApply }}
            </el-button>
            <el-button
              v-if="
                dep.data.value?.model_limits?.find(
                  l => l.base_url === m.base_url && l.model === m.model
                )?.max_concurrent != null
              "
              size="small"
              :title="S.settings.deployment.modelResetHint"
              :disabled="save.isPending.value"
              @click="
                save.mutate({
                  open: open,
                  modelLimit: {
                    base_url: m.base_url,
                    model: m.model,
                    max_concurrent: null
                  }
                })
              "
            >
              {{ S.settings.deployment.modelReset }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
