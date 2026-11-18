<!-- 文档查看页（对照旧仓 pages/DocViewer.tsx，W1 读面）：来源栏 + 分块全文 +
     每块的抽取对照栏（事实三元组，实体可跳图谱）+ 引用跳转点亮。
     「做成案例」（AddOntologyRegressionDialog）是写流，属任务 6，不渲染。 -->
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

const DOC_PAGE = 12;

const route = useRoute();
const router = useRouter();
// KbScope 对等物（每个 kb 页面 setup 调一次）
useKbScope();
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
          <div class="mb-6">
            <div class="flex items-baseline justify-between gap-4">
              <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
                {{ doc.filename }}
              </h1>
              <RouterLink
                :to="`/kb/${kbId}/library`"
                class="link-inline shrink-0 text-body"
              >
                {{ S.doc.backToLibrary }}
              </RouterLink>
            </div>
            <p class="mt-1 text-sub text-ink-2">
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
          </div>

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
              <div
                class="chunk min-w-0 flex-1 whitespace-pre-wrap rounded-card border p-4 text-body leading-relaxed"
                :class="
                  c.id === chunk && flash
                    ? 'border-line-strong bg-fill'
                    : 'border-line bg-card'
                "
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
                {{ c.text }}
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
