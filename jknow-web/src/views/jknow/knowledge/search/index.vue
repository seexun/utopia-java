<!-- 搜索页（对照旧仓 pages/Search.tsx 逐行）：输入 + 结果列表 + 分页。
     已提交的查询词以 URL 为唯一事实源（?q=）：刷新、返回、分享链接都从地址栏
     重建，结果由 useQuery 自动重取；打字是本地事，提交才写地址。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import { search } from "@/api/knowledge/documents";
import { readiness as readinessApi } from "@/api/knowledge/kbs";
import { me as meApi } from "@/api/knowledge/auth";
import type { SearchResult } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { nextStep } from "@/domain/nextStep";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbScope } from "@/composables/useKbScope";
import { UPager } from "@/components/knowledge/kit";

const RESULT_PAGE = 10;

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
// KbScope 对等物（每个 kb 页面 setup 调一次）
const scope = useKbScope();
const kbId = scope.kbId;
const kb = computed(() => kbStore.kbDetail ?? scope.kb.value);
/* URL 是已提交查询词的唯一事实源 */
const query = computed(() =>
  typeof route.query.q === "string" ? route.query.q : ""
);
const input = ref(query.value ?? "");
// 前进/后退到别的 q 时输入框跟着走
watch(query, v => (input.value = v ?? ""));
const page = ref(0);
// 新查询换一批结果，从第一页看起
watch(query, () => (page.value = 0));

const me = useQuery({ queryKey: ["me"], queryFn: meApi });
// 搜之前就该说清楚库里有没有东西可搜（#313）
const readiness = useQuery({
  queryKey: computed(() => ["readiness", kbId.value]),
  queryFn: () => readinessApi(kbId.value),
  enabled: computed(() => !!kbId.value),
  // 上传、抽取都会改它，但没必要盯着轮询：页面切回来时重取就够
  staleTime: 10_000
});
const step = computed(() =>
  nextStep(readiness.data.value, {
    kbId: kbId.value,
    isAdmin: !!me.data.value?.is_admin,
    canUpload: kb.value?.my_role !== "viewer"
  })
);

const results = useQuery<{ results: SearchResult[] }>({
  queryKey: computed(() => ["search", kb.value?.id, query.value]),
  queryFn: () => search(kb.value!.id, query.value!),
  enabled: computed(() => !!kb.value && !!query.value)
});

/* 提交 = 换地址，不是换 state */
function submit() {
  const q = input.value.trim();
  if (!q) return;
  router.push({ path: `/kb/${kbId.value}/search`, query: { q } });
}

const rows = computed(() => results.data.value?.results ?? []);
const paged = computed(() => {
  const pageCount = Math.max(1, Math.ceil(rows.value.length / RESULT_PAGE));
  const safe = Math.min(page.value, pageCount - 1);
  return {
    rows: rows.value.slice(safe * RESULT_PAGE, (safe + 1) * RESULT_PAGE),
    safe
  };
});
</script>

<template>
  <div class="knowledge-page h-full overflow-y-auto p-6">
    <div class="mx-auto max-w-2xl">
      <div class="mb-6 flex gap-2">
        <el-input
          v-model="input"
          class="flex-1"
          :placeholder="S.search.placeholder"
          @keydown.enter.prevent="submit"
        >
          <template #prefix>
            <SearchIcon class="text-ink-3" />
          </template>
        </el-input>
        <el-button type="primary" :disabled="!input.trim()" @click="submit">
          {{ S.search.button }}
        </el-button>
      </div>

      <!-- 一次都还没搜、而库里本来就没东西：与其等他敲一个词再回"没有结果"，
          不如现在就说下一步（#313） -->
      <div v-if="!query && step" class="grid place-items-center py-8">
        <div class="max-w-xs text-center text-body text-ink-2">
          {{ step.line }}
          <RouterLink
            v-if="step.action"
            class="link-inline mt-3 block"
            :to="{ path: step.action.to, query: step.action.search as never }"
          >
            {{ step.action.label }}
          </RouterLink>
        </div>
      </div>

      <p v-if="results.isFetching.value" class="text-body text-ink-2">
        {{ S.search.searching }}
      </p>
      <p v-if="results.isError.value" class="text-body text-danger">
        {{ String(results.error.value) }}
      </p>
      <p
        v-if="results.data.value && results.data.value.results.length === 0"
        class="text-body text-ink-2"
      >
        {{ S.search.noResults }}
      </p>

      <!-- 一个面板装多行：悬停归行，槽不响应指针 -->
      <div class="divide-y divide-line rounded-card border border-line bg-card">
        <RouterLink
          v-for="r in paged.rows"
          :key="r.id"
          :to="{
            path: `/kb/${kbId}/doc/${r.document_id}`,
            query: { chunk: r.id }
          }"
          class="block p-4 rounded-ctl transition-colors hover:bg-fill"
        >
          <div class="mb-2 text-sub text-ink-2">
            {{ S.search.chunkOf(r.filename, r.seq + 1) }}
          </div>
          <p
            class="line-clamp-4 whitespace-pre-wrap text-body leading-relaxed text-ink-2"
          >
            {{ r.text }}
          </p>
        </RouterLink>
      </div>
      <UPager
        :total="rows.length"
        :page-size="RESULT_PAGE"
        :page="paged.safe"
        @page="(p: number) => (page = p)"
      />
    </div>
  </div>
</template>
