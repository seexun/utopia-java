<!-- 法务页（对照旧仓 Legal.tsx）：/privacy、/terms，登录页页脚可达的公共页面。
     自部署语境：运营方是部署它的组织，这里的文本是随软件附带的缺省政策，
     口径务求诚实（存了什么、什么情况下数据会离开服务器、删除的真实语义）。
     （旧仓注释原话。）文本真源在 i18n 词典（S.legal.privacy / S.legal.terms）。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { S } from "@/i18n";

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
      <h1 class="legal-title">{{ doc.title }}</h1>
      <p class="legal-note">{{ doc.note }}</p>
      <div class="legal-sections">
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
.legal-page {
  min-height: 100vh;
  padding: 56px 16px;
  background: #f6f8fb;
}

.legal-wrap {
  width: 100%;
  max-width: 672px;
  margin: 0 auto;
}

.legal-back {
  font-size: 13px;
  color: #475569;
}

.legal-back:hover {
  color: #0f172a;
}

.legal-title {
  margin: 24px 0 0;
  font-size: 24px;
  font-weight: 400;
  color: #0f172a;
  letter-spacing: 0.04em;
}

.legal-note {
  margin: 12px 0 0;
  font-size: 13px;
  color: #475569;
}

.legal-sections {
  margin-top: 32px;
}

.legal-sections section {
  margin-top: 32px;
}

.legal-h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.legal-p {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.625;
  color: #475569;
}

.legal-bullets {
  margin: 8px 0 0;
  padding-left: 16px;
  list-style: none;
}
</style>
