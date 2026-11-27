<!-- 搜索页（reskin-b1 照 gvdata styleGuide 范式重造：PageHeader + 卡容器；
     script 逻辑零动——仅 import 随模板换装 + 文件尾追加纯展示辅助）：
     输入 + 结果列表 + 分页。
     已提交的查询词以 URL 为唯一事实源（?q=）：刷新、返回、分享链接都从地址栏
     重建，结果由 useQuery 自动重取；打字是本地事，提交才写地址。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import CompassIcon from "~icons/lucide/compass";
import CircleAlertIcon from "~icons/lucide/circle-alert";
import { search } from "@/api/knowledge/documents";
import { readiness as readinessApi } from "@/api/knowledge/kbs";
import { me as meApi } from "@/api/knowledge/auth";
import type { SearchResult } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { nextStep } from "@/domain/nextStep";
import { highlightSegments } from "@/utils/highlightSegments";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbScope } from "@/composables/useKbScope";
import { Spinner } from "@/components/knowledge/kit";
import PageHeader from "@/components/PageHeader/index.vue";

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

/* ---------- 重造新增的纯展示辅助（reskin-b1）：只读上面已有的展示态，
   不碰 useQuery/submit/step/#313 任何语义 ---------- */

/** 命中词（纯展示）：提交过的查询词按空白拆开，交 highlightSegments 切段
    （长词优先/转义/全命中都标），只服务引文 mark 高亮，不影响查询本身——
    混合检索的语义命中没有字面命中时，引文整段原样（无 mark） */
const hitTerms = computed(() => query.value.split(/\s+/).filter(Boolean));

/** 来源徽章色档（纯展示）：--cat-1..6 按 document_id 稳定轮转——
    同一文档的分块同色，翻页/换词颜色不闪变 */
function srcCat(documentId: string): string {
  let h = 0;
  for (const ch of documentId) h = (h * 31 + (ch.codePointAt(0) ?? 0)) >>> 0;
  return `src-cat-${(h % 6) + 1}`;
}
</script>

<template>
  <div class="knowledge-page h-full overflow-y-auto p-6">
    <div class="mx-auto max-w-3xl">
      <!-- 页头（PageHeader 自 gvdata 零改写迁入）：22/800 标题；库名副题已撤
           （左下角库切换器常驻显示，用户走查）。检索页的唯一主行动是查询卡里
           的提交按钮，页头不放 extra -->
      <PageHeader :title="S.nav.search" />

      <div class="flex flex-col gap-4">
        <!-- 查询卡：大号输入 + 主行动（每页唯一）。focus 光晕由全局主题层提供
             （theme.scss：.el-input__wrapper.is-focus → --color-primary-glow） -->
        <div class="card query-card">
          <el-input
            v-model="input"
            size="large"
            class="flex-1"
            :placeholder="S.search.placeholder"
            clearable
            @keydown.enter.prevent="submit"
          >
            <template #prefix>
              <SearchIcon class="text-ink-3" />
            </template>
          </el-input>
          <el-button
            type="primary"
            size="large"
            :disabled="!input.trim()"
            @click="submit"
          >
            {{ S.search.button }}
          </el-button>
        </div>

        <!-- 一次都还没搜、而库里本来就没东西：与其等他敲一个词再回"没有结果"，
             不如现在就说下一步（#313 语义原样，形态升为 anivia 空态卡） -->
        <div v-if="!query && step" class="card state-card">
          <CompassIcon class="state-icon" />
          <p class="state-line">{{ step.line }}</p>
          <RouterLink
            v-if="step.action"
            class="link-inline"
            :to="{ path: step.action.to, query: step.action.search as never }"
          >
            {{ step.action.label }}
          </RouterLink>
        </div>

        <!-- 三态（加载/错误/空）：独立 v-if，语义与重造前一致 -->
        <div v-if="results.isFetching.value" class="card state-card">
          <Spinner :label="S.search.searching" />
        </div>
        <div v-if="results.isError.value" class="card state-card is-error">
          <CircleAlertIcon class="state-icon" />
          <p class="state-line">{{ String(results.error.value) }}</p>
        </div>
        <div
          v-if="results.data.value && results.data.value.results.length === 0"
          class="card state-card"
        >
          <el-empty :description="S.search.noResults" :image-size="72" />
        </div>

        <!-- 结果卡：整卡可点进分块（跳转语义原样：/doc/:document_id?chunk=:id）。
             头行 = 来源徽章（--cat-N 轮转色点，同文档同色）+ 文档名（ink 标题阶）
             + 段位徽章（primary-soft 底/primary-text 字，悬停有全称提示） -->
        <RouterLink
          v-for="r in paged.rows"
          :key="r.id"
          :to="{
            path: `/kb/${kbId}/doc/${r.document_id}`,
            query: { chunk: r.id }
          }"
          class="card result-card"
        >
          <div class="result-head">
            <span class="src-dot" :class="srcCat(r.document_id)" />
            <span class="result-title">{{ r.filename }}</span>
            <span
              class="badge-soft badge-soft-primary result-chunk"
              :title="S.search.chunkOf(r.filename, r.seq + 1)"
            >
              #{{ r.seq + 1 }}
            </span>
          </div>
          <p class="result-quote">
            <template v-for="(seg, i) in highlightSegments(r.text, hitTerms)" :key="i">
              <mark v-if="seg.hit">{{ seg.text }}</mark>
              <template v-else>{{ seg.text }}</template>
            </template>
          </p>
        </RouterLink>

        <!-- 分页（table-style 分页条形态）：page 仍是 0 基，此处映射成 el 的
             1 基；换词归零与页号钳制（watch + paged.safe）语义原样 -->
        <div v-if="rows.length > RESULT_PAGE" class="pager-row">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :total="rows.length"
            :page-size="RESULT_PAGE"
            :current-page="paged.safe + 1"
            @current-change="(p: number) => (page = p - 1)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 重造层（reskin-b1）：只引令牌（--color-* / --cat-N / --radius-* /
   --shadow-* / --transition-base），禁硬编码色值；卡容器形态来自全局
   .card（theme.scss），徽章形态来自全局 .badge-soft（theme.scss） */
.query-card {
  display: flex;
  gap: 12px;
  align-items: center;
}

/* 三态卡（#313 引导 / 加载 / 错误 / 空结果）：图标 + 文案居中 */
.state-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 32px 24px;
  text-align: center;
}

.state-icon {
  width: 26px;
  height: 26px;
  color: var(--color-ink-3);
}

.state-line {
  max-width: 20rem;
  margin: 0;
  font-size: 14px;
  color: var(--color-ink-2);
}

.state-card.is-error .state-icon,
.state-card.is-error .state-line {
  color: var(--color-danger);
}

/* 结果卡：整卡是链接，悬停以主色描边 + 光晕呼应输入控件的 focus 语言 */
.result-card {
  transition:
    border-color var(--transition-base),
    box-shadow var(--transition-base);

  &:hover {
    border-color: var(--color-primary);
    box-shadow:
      var(--shadow-card),
      0 0 0 3px var(--color-primary-glow);
  }
}

.result-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

/* 来源徽章：--cat-N 轮转色点（色档取法见 script 的 srcCat） */
.src-dot {
  flex-shrink: 0;
  width: 10px;
  height: 10px;
  border-radius: var(--radius-full);
}

.src-cat-1 { background: var(--cat-1); }
.src-cat-2 { background: var(--cat-2); }
.src-cat-3 { background: var(--cat-3); }
.src-cat-4 { background: var(--cat-4); }
.src-cat-5 { background: var(--cat-5); }
.src-cat-6 { background: var(--cat-6); }

/* 文档名：ink 标题阶（16px/600，对应字阶 --text-title） */
.result-title {
  min-width: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-chunk {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* 引文：四行截断；命中词 mark 形态 = 主色 glow 底 + 主色深档字
   （reskin-b3 双候选择优：白卡上可感知的强调底，仍是 badge-soft 同语言的
   软底系——与 graph 浮搜建议 mark 同一形态，两消费点同改勿单边漂移）；
   语义命中无字面命中时整段原样 */
.result-quote {
  display: -webkit-box;
  overflow: hidden;
  font-size: 14px;
  line-height: 22px;
  color: var(--color-ink-2);
  white-space: pre-wrap;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;

  mark {
    padding: 0 2px;
    font-weight: 600;
    color: var(--color-primary-text);
    background: var(--color-primary-glow);
    border-radius: 4px;
  }
}

/* 分页条（table-style 形态：右对齐，同 uiKit §5 的 .uk-pager） */
.pager-row {
  display: flex;
  justify-content: flex-end;
}
</style>
