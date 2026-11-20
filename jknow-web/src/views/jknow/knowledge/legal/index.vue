<!-- 法务页（对照旧仓 Legal.tsx）：/privacy、/terms，登录页页脚可达的公共页面。
     自部署语境：运营方是部署它的组织，这里的文本是随软件附带的缺省政策，
     口径务求诚实（存了什么、什么情况下数据会离开服务器、删除的真实语义）。
     （旧仓注释原话。）文本真源在 i18n 词典（S.legal.privacy / S.legal.terms）。
     polish-harden 模板层重造（script 逻辑零动）：PageHeader（标题=当前政策、
     副题=缺省政策注记）+ 条文卡（anivia 卡语言）；硬编码色全清令牌化。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { S } from "@/i18n";
/* 重造新增 import（polish-harden）：页头件（七页现形态） */
import PageHeader from "@/components/PageHeader/index.vue";

const route = useRoute();
const doc = computed(() =>
  route.path === "/terms" ? S.legal.terms : S.legal.privacy
);

function backToSignIn() {
  window.location.hash = "#/login";
}
</script>

<template>
  <div class="knowledge-page legal-page">
    <div class="legal-wrap">
      <a class="legal-back" href="#/login" @click.prevent="backToSignIn">
        {{ S.legal.backToSignIn }}
      </a>
      <!-- 页头（七页现形态）：标题=当前政策名（隐私政策/使用条款）、副题=
           缺省政策注记（运营方可替换）；本页无写动作，extra 留空 -->
      <PageHeader :title="doc.title" :subtitle="doc.note" />
      <!-- 条文卡：小节标题 + 段落 + 要点列表（结构零动，仅卡化+令牌化） -->
      <div class="card legal-sections">
        <section v-for="(s, i) in doc.sections" :key="i">
          <h2 class="legal-h2">{{ s.h }}</h2>
          <p
            v-for="(p, j) in s.body"
            :key="j"
            class="legal-p"
          >
            {{ p }}
          </p>
          <ul v-if="s.bullets" class="legal-bullets">
            <li v-for="(b, k) in s.bullets" :key="k" class="legal-p">
              {{ b }}
            </li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 重造层（polish-harden）：只引令牌（--color-* / --radius-* / --el-bg-color），
   硬编码色归零；卡容器形态来自全局 .card（theme.scss） */

.legal-page {
  min-height: 100vh;
  padding: 56px 16px;
  background: var(--color-bg);
}

.legal-wrap {
  width: 100%;
  max-width: 672px;
  margin: 0 auto;
}

.legal-back {
  display: inline-block;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--color-ink-2);
}

.legal-back:hover {
  color: var(--color-ink);
}

/* 条文卡：小节之间分隔；首节不吃上距（PageHeader 已给） */
.legal-sections {
  padding: 28px 32px;
}

.legal-sections section {
  margin-top: 24px;
}

.legal-sections section:first-child {
  margin-top: 0;
}

.legal-h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}

.legal-p {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.625;
  color: var(--color-ink-2);
}

.legal-bullets {
  margin: 8px 0 0;
  padding-left: 16px;
  list-style: none;
}
</style>
