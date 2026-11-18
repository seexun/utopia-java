<!-- 内置文档阅读器（对照旧仓 pages/Docs.tsx，任务 10 W3 收口迁入）：随应用打包
     （私有部署离线可用），版本与部署一致。公开路由——不依赖会话，登录前也可读
     （开发者接接口时未必有账号，旧仓注释原话）。/docs → /docs/ingest 重定向在
     路由表（M-3 收口）；本文档页替换 W1 以来的占位。
     布局：顶栏（Charter 字标 + 居中全文检索 + 回应用/登录态）+ 左栏文档清单 +
     正文 + 右侧目录（滚动跟随）。排版走对话面同一副 markdown 皮（chat-prose，
     与旧仓 shadcn typeset 面不同——有意演进，见走查对照表）；标题锚点 id 由
     UMarkdown 的 anchorId 回调落下，目录与正文同一 slugify 保证对得上。 -->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import { me as meApi } from "@/api/knowledge/auth";
import { health } from "@/api/knowledge/admin";
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";
import { UMarkdown } from "@/views/jknow/knowledge/chat/markdown";
import UserMenu from "@/components/knowledge/user-menu/index.vue";
import { DOCS, docBySlug, searchDocs, slugify, tocOf } from "./docsContent";
import { Highlighted } from "./parts";

defineOptions({ name: "UtopiaDocs" });

const route = useRoute();
const router = useRouter();

const doc = computed(() =>
  docBySlug(typeof route.params.slug === "string" ? route.params.slug : "")
);
const toc = computed(() => tocOf(doc.value.body));
const results = computed(() => searchDocs(q.value));

// 标题：`Utopia | {文章名}`——Charter 是门脸字标，标题直接给文章（旧仓同口径）
usePageTitle(S.app.name, doc.value.title);

// 公开页也感知登录态：已登录给用户菜单，未登录给 Sign in（旧仓同口径；
// me 自带 skipGlobal401——公开页 401 不触发全局硬跳）
const meQ = useQuery({ queryKey: ["me"], queryFn: meApi, retry: false });
const healthQ = useQuery({
  queryKey: ["health"],
  queryFn: health,
  staleTime: Infinity
});

/* ---- 全文检索：输入 → 下拉结果；点外/Escape 收起（旧仓同款交互面） ---- */
const q = ref("");
const inputRef = ref<HTMLInputElement | null>(null);
const searchRef = ref<HTMLElement | null>(null);

function onDocClick(slug: string) {
  q.value = "";
  router.push({ path: `/docs/${slug}` });
}
function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth" });
}
function onKeydown(e: KeyboardEvent) {
  if (e.key === "Escape") q.value = "";
}
function onMousedown(e: MouseEvent) {
  if (searchRef.value && !searchRef.value.contains(e.target as Node))
    q.value = "";
}
// 键盘呼起：⌘K / Ctrl+K 任何时候生效；`/` 仅在焦点不在输入框时（别劫持打字）
function onGlobalKey(e: KeyboardEvent) {
  const el = document.activeElement;
  const typing =
    el instanceof HTMLInputElement ||
    el instanceof HTMLTextAreaElement ||
    (el instanceof HTMLElement && el.isContentEditable);
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    inputRef.value?.focus();
    inputRef.value?.select();
  } else if (e.key === "/" && !typing) {
    e.preventDefault();
    inputRef.value?.focus();
  } else if (e.key === "Escape" && el === inputRef.value) {
    inputRef.value?.blur();
  }
}
onMounted(() => {
  document.addEventListener("keydown", onGlobalKey);
});
onBeforeUnmount(() => {
  document.removeEventListener("keydown", onGlobalKey);
  document.removeEventListener("keydown", onKeydown);
  document.removeEventListener("mousedown", onMousedown);
});
// q 在场才挂收起监听（旧仓 useEffect [q] 同款语义）
watch(q, v => {
  if (v) {
    document.addEventListener("mousedown", onMousedown);
    document.addEventListener("keydown", onKeydown);
  } else {
    document.removeEventListener("mousedown", onMousedown);
    document.removeEventListener("keydown", onKeydown);
  }
});

/* ---- 滚动跟随：视口上沿之上最近的标题为当前小节；滚到底强制激活最后一节
        （短末节永远越不过判定线，旧仓注释原话） ---- */
const mainRef = ref<HTMLElement | null>(null);
const activeHeading = ref<string | null>(null);
function onScrollSpy() {
  const main = mainRef.value;
  if (!main) return;
  const top = main.getBoundingClientRect().top;
  let current: string | null = null;
  for (const h of toc.value) {
    const el = document.getElementById(h.id);
    if (el && el.getBoundingClientRect().top - top <= 96) current = h.id;
  }
  if (
    main.scrollTop + main.clientHeight >= main.scrollHeight - 8 &&
    toc.value.length
  )
    current = toc.value[toc.value.length - 1].id;
  activeHeading.value = current;
}

// ⌘ 用图形符号；Ctrl 无图形符号（Windows 规范写法就是文字，旧仓注释原话）
const isMac = navigator.platform.toUpperCase().includes("MAC");
</script>

<template>
  <div class="knowledge-page docs-page">
    <header class="docs-header">
      <RouterLink
        to="/graph"
        class="docs-brand"
        :title="S.docs.backTitle as string"
      >
        {{ S.docs.brand }}
      </RouterLink>

      <!-- 居中搜索：检索打包在本地的全部文档；宽度对齐正文栏（旧仓同值） -->
      <div ref="searchRef" class="docs-search-wrap">
        <div class="relative">
          <SearchIcon class="docs-search-icon" />
          <input
            ref="inputRef"
            v-model="q"
            class="docs-search"
            :placeholder="S.docs.searchPlaceholder as string"
            type="text"
          />
          <kbd v-if="!q" class="docs-kbd"
            >{{ isMac ? "⌘" : "Ctrl" }}<span>K</span></kbd
          >
        </div>
        <div v-if="q.trim().length >= 2" class="docs-results menu-panel">
          <p v-if="results.length === 0" class="docs-noresults">
            {{ S.docs.noResults }}
          </p>
          <button
            v-for="(r, i) in results"
            :key="i"
            type="button"
            class="docs-result-row"
            @click="onDocClick(r.slug)"
          >
            <div class="docs-result-title">{{ r.title }}</div>
            <div class="docs-result-snippet">
              <Highlighted :text="r.snippet" :q="q" />
            </div>
          </button>
        </div>
      </div>

      <!-- 右侧与 App、账户页同一份：换页时不该动。没登录就只剩一个「登录」 -->
      <div class="docs-actions">
        <RouterLink to="/graph" class="docs-back">
          {{ S.account.backToApp }}
        </RouterLink>
        <a
          v-if="healthQ.data.value?.version"
          :href="S.login.githubUrl as string"
          target="_blank"
          rel="noreferrer"
          title="GitHub"
          class="docs-version"
          >v{{ healthQ.data.value.version }}</a
        >
        <UserMenu v-if="meQ.data.value" :user="meQ.data.value" />
        <RouterLink
          v-else-if="meQ.isError.value"
          to="/login"
          class="docs-signin"
          >{{ S.login.signIn }}</RouterLink
        >
      </div>
    </header>

    <!-- 整页滚动（滚动条贴窗口最右），侧栏 sticky 钉住——正规文档站布局 -->
    <main ref="mainRef" class="docs-main" @scroll="onScrollSpy">
      <div class="docs-body">
        <aside class="docs-nav">
          <RouterLink
            v-for="d in DOCS"
            :key="d.slug"
            :to="`/docs/${d.slug}`"
            class="docs-nav-row"
            :class="{ 'is-active': d.slug === doc.slug }"
          >
            {{ d.title }}
          </RouterLink>
        </aside>

        <!-- 上下都留足空白：标题不顶着头，末段内容能滚到屏幕中部——人读屏幕中间。
            排版接 chat-prose（对话面同一副 markdown 皮，有意演进）；标题锚点 id 由
            UMarkdown anchorId 落下；外链新开；表格横向滚动容器 -->
        <article class="docs-article">
          <UMarkdown :text="doc.body" :anchor-id="slugify" />
        </article>

        <!-- 右侧目录：sticky 钉住，与正文标题同一水平线起头 -->
        <aside v-if="toc.length > 0" class="docs-toc">
          <nav class="space-y-1">
            <button
              v-for="h in toc"
              :key="h.id"
              type="button"
              class="docs-toc-row"
              :class="{
                'is-l3': h.level === 3,
                'is-active': activeHeading === h.id
              }"
              @click="scrollTo(h.id)"
            >
              {{ h.text }}
            </button>
          </nav>
        </aside>
      </div>
    </main>
  </div>
</template>

<style scoped>
.docs-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-bg, #f6f8fb);
  color: var(--color-ink, #0f172a);
}

.docs-header {
  position: relative;
  z-index: 40;
  height: 3rem;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 0.5rem;
  background: #fff;
  border-bottom: 1px solid var(--color-line, #e2e8f0);
}

.docs-brand {
  padding-left: 0.75rem;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #0f172a;
}

.docs-search-wrap {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: min(44rem, 55vw);
}

.docs-search-icon {
  position: absolute;
  left: 0.625rem;
  top: 50%;
  transform: translateY(-50%);
  width: 13px;
  height: 13px;
  color: #94a3b8;
  pointer-events: none;
}

.docs-search {
  width: 100%;
  height: 32px;
  padding: 0 2.4rem 0 2rem;
  border: 1px solid var(--color-line, #e2e8f0);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: #0f172a;
}

.docs-search:focus {
  outline: none;
  border-color: #1677ff;
}

.docs-kbd {
  pointer-events: none;
  position: absolute;
  right: 0.625rem;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--color-line, #e2e8f0);
  border-radius: 6px;
  background: #fff;
  font-family: inherit;
  font-size: 12px;
  color: #94a3b8;
}

.docs-results {
  position: absolute;
  inset-inline: 0;
  top: 100%;
  margin-top: 8px;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--color-line, #e2e8f0);
  border-radius: 10px;
  box-shadow: 0 12px 32px rgb(15 23 42 / 10%);
}

.docs-noresults {
  padding: 12px 16px;
  font-size: 13px;
  color: #64748b;
}

.docs-result-row {
  display: block;
  width: 100%;
  padding: 12px 16px;
  border: 0;
  border-bottom: 1px solid var(--color-line, #e2e8f0);
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.docs-result-row:last-child {
  border-bottom: 0;
}

.docs-result-row:hover {
  background: #f8fafc;
}

.docs-result-title {
  font-size: 12px;
  color: #64748b;
}

.docs-result-snippet {
  margin-top: 2px;
  font-size: 13px;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.docs-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding-right: 8px;
}

.docs-back,
.docs-signin {
  font-size: 13px;
  color: #475569;
}

.docs-back:hover,
.docs-signin:hover {
  color: #0f172a;
}

.docs-version {
  font-size: 12px;
  color: #64748b;
  border: 1px solid var(--color-line, #e2e8f0);
  border-radius: 999px;
  padding: 2px 10px;
}

.docs-main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.docs-body {
  display: flex;
  align-items: flex-start;
}

.docs-nav {
  width: 16rem;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  height: calc(100vh - 3.5rem);
  padding: 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: #fff;
  border-right: 1px solid var(--color-line, #e2e8f0);
}

.docs-nav-row {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: #475569;
}

.docs-nav-row:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.docs-nav-row.is-active {
  background: #e6f4ff;
  color: #0958d9;
}

.docs-article {
  flex: 1;
  min-width: 0;
  max-width: 48rem;
  margin: 0 auto;
  padding: 64px 32px 40vh;
}

/* 文档排版接 chat-prose（对话面同一副 markdown 皮）：16px 基准、
   标题锚点留出顶栏高度、宽表横向滚动（旧仓 table-box 同语义） */
.docs-article :deep(.chat-prose) {
  font-size: 16px;
  line-height: 1.75;
}

.docs-article :deep(.chat-prose h1),
.docs-article :deep(.chat-prose h2),
.docs-article :deep(.chat-prose h3) {
  scroll-margin-top: 24px;
}

.docs-article :deep(.chat-prose a) {
  color: #1677ff;
}

.docs-article :deep(.chat-prose a:hover) {
  text-decoration: underline;
}

.docs-toc {
  display: none;
  width: 16rem;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  padding-top: 64px;
  padding-right: 32px;
}

@media (min-width: 1024px) {
  .docs-toc {
    display: block;
  }
}

.docs-toc-row {
  display: block;
  width: 100%;
  padding: 8px 0;
  border: 0;
  background: none;
  text-align: left;
  font-size: 13px;
  line-height: 1.4;
  color: #64748b;
  cursor: pointer;
  white-space: normal;
}

.docs-toc-row.is-l3 {
  padding-left: 24px;
}

.docs-toc-row:hover {
  color: #0f172a;
}

.docs-toc-row.is-active {
  color: #0f172a;
}
</style>
