<!-- 惩戒页（对照旧仓 ServerDown.tsx）：500 服务器失联。
     与登录页共用同一层品牌渐变背景——报错也保持门面体面
     （自托管场景里这页常是运维第一现场）。（旧仓注释原话。） -->
<script setup lang="ts">
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";
import Wordmark from "@/components/knowledge/wordmark/index.vue";
import RefreshCwIcon from "~icons/lucide/refresh-cw";

const reportUrl = `${S.login.githubUrl}/issues`;

usePageTitle(S.app.name, "Punishment 500");

function reload() {
  window.location.reload();
}
</script>

<template>
  <div class="punishment-page">
    <!-- 品牌渐变背景层：与登录页同一副（D6），原巨构 canvas 已删 -->
    <div aria-hidden class="punishment-bg" />
    <div class="punishment-body">
      <h1 class="m-0 font-normal">
        <Wordmark />
      </h1>
      <p class="punishment-message">{{ S.nav.serverUnreachable }}</p>
      <div class="punishment-actions">
        <el-button size="default" @click="reload">
          <RefreshCwIcon width="12" height="12" style="margin-right: 6px" />
          {{ S.nav.refresh }}
        </el-button>
        <a
          :href="reportUrl"
          target="_blank"
          rel="noreferrer"
          class="punishment-link"
        >
          {{ S.nav.reportIssue }}
        </a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.punishment-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 0 16px;
  background: var(--color-bg);
}

.punishment-bg {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(135deg, rgb(37 99 235 / 10%), rgb(6 182 212 / 10%));
}

.punishment-body {
  position: relative;
  z-index: 10;
  text-align: center;
}

.punishment-message {
  max-width: 420px;
  margin: 16px auto 0;
  font-size: 14px;
  color: var(--color-ink-2);
}

.punishment-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
}

.punishment-link {
  font-size: 13px;
  color: var(--color-ink-2);
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
