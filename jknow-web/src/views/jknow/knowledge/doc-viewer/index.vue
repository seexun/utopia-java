<!-- 文档查看页（对照旧仓 pages/DocViewer.tsx，W1 读面）：来源栏 + 分块全文 +
     每块的抽取对照栏（事实三元组，实体可跳图谱）+ 引用跳转点亮。
     「做成案例」（AddOntologyRegressionDialog）是写流，属任务 6，不渲染。
     final-sprint T2 模板层重造（script 逻辑零动，仅 import + 纯展示 computed）：
     页头落 PageHeader（文档名·库名，回文库留 extra）；分块原文落 .card 读页卡；
     ?quote= 的引文在目标分块里用检索命中同款琥珀 .mark 标出（highlightSegments
     归一切段——对不上的引文不标）；引用跳转锚（滚动+点亮）原样。 -->
<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { documentDetail, documentExtractions } from "@/api/knowledge/documents";
import type { ChunkFact } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { fmtTime } from "@/domain/time";
import { originHint, originLabel } from "@/domain/origin";
import { SourcesRail } from "@/components/knowledge/sources-rail";
import { useKbScope } from "@/composables/useKbScope";
import { UPager } from "@/components/knowledge/kit";
/* 引文高亮切段（reskin-b4 归一）+ PageHeader（reskin-b1 自 gvdata 迁入） */
import { highlightSegments } from "@/utils/highlightSegments";
import PageHeader from "@/components/PageHeader/index.vue";

const DOC_PAGE = 12;

const route = useRoute();
const router = useRouter();
// KbScope 对等物（每个 kb 页面 setup 调一次）；库名给页头副题（纯展示）
const scope = useKbScope();
const kbName = computed(() => scope.kb.value?.name ?? "");
const kbId = computed(
  () => (route.params as { kbId?: string } | undefined)?.kbId ?? ""
);
const docId = computed(
  () => (route.params as { docId?: string } | undefined)?.docId ?? ""
);
const chunk = computed(() =>
  typeof route.query.chunk === "string" ? route.query.chunk : ""
);
const quote = computed(() =>
  typeof route.query.quote === "string" ? route.query.quote : ""
);
/** 引文高亮词条（纯展示，final-sprint T2）：?quote= 的那一句交给
    highlightSegments 在目标分块里切出命中段（空/超长的进不了地址栏——
    quoteParam 的闸在前，这里只管展示） */
const quoteTerms = computed(() => (quote.value ? [quote.value] : []));

const detail = useQuery({
  queryKey: computed(() => ["docDetail", docId.value]),
  queryFn: () => documentDetail(docId.value),
  enabled: computed(() => !!docId.value)
});
// 反向证据链：各分块抽出的事实（一次取整文档，按 chunk 分组）
const extractions = useQuery({
  queryKey: computed(() => ["docExtractions", docId.value]),
  queryFn: () => documentExtractions(docId.value),
  enabled: computed(() => !!docId.value)
});
const factsByChunk = computed(() => {
  const map = new Map<string, ChunkFact[]>();
  for (const f of extractions.data.value?.facts ?? []) {
    if (!map.has(f.chunk_id)) map.set(f.chunk_id, []);
    map.get(f.chunk_id)!.push(f);
  }
  return map;
});

// null = 自动定位：有引用跳转时翻到目标分块所在页
const page = ref<number | null>(null);
watch([chunk, docId], () => (page.value = null));

const highlightRef = ref<HTMLElement | null>(null);
// 引用跳转：滚动到目标分块点亮一下，稍候淡出恢复普通状态（不常驻高亮）
const flash = ref(false);
watch(
  [() => detail.data.value, chunk],
  async () => {
    if (detail.data.value && chunk.value) {
      await nextTick();
      highlightRef.value?.scrollIntoView({
        behavior: "smooth",
        block: "center"
      });
      flash.value = true;
      // 平滑滚动本身耗几百毫秒，点亮窗口要留足滚动后的可视时间
      setTimeout(() => (flash.value = false), 2600);
    }
  },
  { immediate: true }
);

const doc = computed(() => detail.data.value?.document);
const chunks = computed(() => detail.data.value?.chunks ?? []);
const targetIdx = computed(() =>
  chunk.value ? chunks.value.findIndex(c => c.id === chunk.value) : -1
);
const curPage = computed(
  () => page.value ?? (targetIdx.value >= 0 ? Math.floor(targetIdx.value / DOC_PAGE) : 0)
);
const paged = computed(() => {
  const items = chunks.value;
  const pageCount = Math.max(1, Math.ceil(items.length / DOC_PAGE));
  const safe = Math.min(curPage.value, pageCount - 1);
  return { rows: items.slice(safe * DOC_PAGE, (safe + 1) * DOC_PAGE), safe };
});

function factRange(f: ChunkFact): string | null {
  const ym = (iso: string | null) => (iso ? iso.slice(0, 7) : null);
  if (!f.valid_from && !f.valid_to) return null;
  return `${ym(f.valid_from) ?? "…"} → ${ym(f.valid_to) ?? S.doc.ongoing}`;
}

const docDate = computed(() =>
  doc.value ? fmtTime(doc.value.doc_time, "day") : null
);
</script>

<template>
  <div class="knowledge-page flex h-full">
    <!-- 来源栏常驻：当前文档所属文件夹高亮，点其他文件夹跳回 Library 对应视图 -->
    <SourcesRail
      v-if="doc"
      :kb-id="doc.kb_id"
      :active="doc.source_id ?? 'uploads'"
      @select="
        (sel: string) =>
          router.push({ path: `/kb/${kbId}/library`, query: { src: sel } })
      "
    />
    <div class="min-w-0 flex-1 overflow-y-auto overscroll-contain">
      <!-- 靠左排，与文库列表同一个内距（px-8 py-6），铺满栏右的整个宽度 -->
      <div class="px-8 py-6">
        <div v-if="detail.isPending.value" class="p-8 text-body text-ink-2">
          {{ S.doc.loading }}
        </div>
        <div v-else-if="detail.isError.value" class="p-8 text-body text-danger">
          {{ String(detail.error.value) }}
        </div>
        <template v-else-if="doc">
          <!-- 页头（重造要素一）：标题=文档名、副题=库名；extra=回文库。
               原 h1 行的元信息（块数/大小/文档日期/上传日）落在页头下 -->
          <PageHeader class-name="mb-1" :title="doc.filename" :subtitle="kbName">
            <template #extra>
              <RouterLink
                :to="`/kb/${kbId}/library`"
                class="link-inline shrink-0 text-body"
              >
                {{ S.doc.backToLibrary }}
              </RouterLink>
            </template>
          </PageHeader>
          <p class="mb-6 text-sub text-ink-2">
            {{ chunks.length }} {{ S.doc.sections }} ·
            {{ (doc.size_bytes / 1024).toFixed(0) }} KB ·
            <template v-if="docDate">
              <span :title="S.doc.docDateFromContent">
                {{ S.doc.docDate }}: {{ docDate }}
              </span>
              ·
            </template>
            {{ S.doc.uploadedAt }}
            {{ new Date(doc.created_at).toLocaleDateString() }}
          </p>

          <div class="space-y-3">
            <div
              v-for="c in paged.rows"
              :key="c.id"
              :ref="
                (el: any) => {
                  if (c.id === chunk) highlightRef = el;
                }
              "
              class="flex gap-3"
            >
              <!-- 原文卡（重造要素二）：chunk 分块排版（.card + .chunk 过渡）；
                   引用跳转点亮在 scoped 落令牌（.card 未分层压过 utilities，
                   原 border-line-strong/bg-fill 两态在这里同值兑现） -->
              <div
                class="chunk card min-w-0 flex-1 whitespace-pre-wrap text-body leading-relaxed"
                :class="c.id === chunk && flash ? 'is-flash' : ''"
              >
                <div class="mb-2 text-sub text-ink-2">
                  {{ S.doc.section }} {{ c.seq + 1 }}
                  <!-- 认出来、转写出来的字标一句出处：可能认错一个数、听错一个名字 -->
                  <span
                    v-if="originLabel(c.origin, c.anchor)"
                    :title="originHint(c.origin, c.origin_model)"
                  >
                    · {{ originLabel(c.origin, c.anchor) }}
                  </span>
                  <span
                    v-if="c.id === chunk"
                    class="fade-slow ml-2 text-primary"
                    :class="flash ? 'opacity-100' : 'opacity-0'"
                  >
                    {{ S.doc.citedHere }}
                  </span>
                </div>
                <!-- 引文高亮（重造要素三）：?quote= 的那一句在目标分块里用检索
                     命中同款琥珀标出（reskin-b4 归一的 highlightSegments——
                     对不上的引文不标；非目标分块整段原样） -->
                <template
                  v-for="(seg, i) in highlightSegments(c.text, c.id === chunk ? quoteTerms : [])"
                  :key="i"
                >
                  <mark v-if="seg.hit" class="mark">{{ seg.text }}</mark>
                  <span v-else>{{ seg.text }}</span>
                </template>
              </div>

              <!-- 抽取对照栏：这个分块产出了哪些事实（实体可跳图谱）。
                  宽度跟着屏幕走（xl:w-80 2xl:w-96 同旧仓） -->
              <aside
                v-if="(factsByChunk.get(c.id) ?? []).length > 0"
                class="w-64 shrink-0 rounded-card border border-line bg-card p-3 xl:w-80 2xl:w-96"
              >
                <div class="mb-2 flex items-center gap-2 text-sub font-medium text-ink-2">
                  {{ S.doc.extracted }}
                  <span class="tabular-nums">
                    {{ (factsByChunk.get(c.id) ?? []).length }}
                  </span>
                </div>
                <!-- 条与条之间画一条线：一条折了三行、下一条折了两行，只靠间距分不开 -->
                <div class="divide-y divide-line">
                  <div
                    v-for="f in factsByChunk.get(c.id) ?? []"
                    :key="f.fact_id"
                    class="py-2 text-sub leading-snug first:pt-0 last:pb-0"
                  >
                    <div>
                      <RouterLink
                        :to="{
                          path: `/kb/${kbId}/graph`,
                          query: { entity: f.subject_id }
                        }"
                        class="link-inline text-ink"
                      >
                        {{ f.subject }}
                      </RouterLink>
                      <span
                        :class="
                          f.predicate === null
                            ? 'italic text-ink-2'
                            : 'text-ink-2'
                        "
                        :title="
                          f.predicate && f.inferred
                            ? S.graph.inferredPredicate
                            : undefined
                        "
                      >
                        {{ f.predicate ?? S.graph.unknownPredicate }}
                      </span>
                      <RouterLink
                        v-if="f.object_id"
                        :to="{
                          path: `/kb/${kbId}/graph`,
                          query: { entity: f.object_id }
                        }"
                        class="link-inline text-ink"
                      >
                        {{ f.object }}
                      </RouterLink>
                      <!-- 字面值的宾语与实体的宾语一样深：浅一档三元组就断不开 -->
                      <span v-else class="text-ink">{{ f.object ?? "" }}</span>
                    </div>
                    <div v-if="factRange(f)" class="tabular-nums text-aux text-ink-2">
                      {{ factRange(f) }}
                    </div>
                  </div>
                </div>
              </aside>
            </div>
          </div>
          <UPager
            :total="chunks.length"
            :page-size="DOC_PAGE"
            :page="paged.safe"
            @page="(p: number) => (page = p)"
          />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/* 引用跳转点亮（final-sprint T2）：chunk 卡的基态皮 .card 在 theme.scss
   （未分层，恒压 tailwind utilities 层）——点亮/常态两态在这里落令牌
   （原 border-line-strong/bg-fill 同值；.chunk 的 700ms 过渡挂在
   utopia-pages.css，对 scoped 覆盖同样生效） */
.chunk.is-flash {
  border-color: var(--color-line-strong);
  background: var(--color-fill);
}
</style>
