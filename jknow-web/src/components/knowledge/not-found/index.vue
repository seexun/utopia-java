<!-- 迷失之城（对照旧仓 ServerDown.tsx 的 NotFound）：404 惩戒页。
     与登录页共用同一层品牌渐变背景（旧仓注释原话）。 -->
<script setup lang="ts">
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";
import Wordmark from "@/components/knowledge/wordmark/index.vue";
import HomeIcon from "~icons/lucide/home";

usePageTitle(S.app.name, "Punishment 404");

// 回首页 = 回 "/"（重定向到 /graph——旧路径接管页解析出库再跳）
function goHome() {
  window.location.hash = "#/";
}
</script>

<template>
  <div class="punishment-page">
    <div aria-hidden class="punishment-bg" />
    <div class="punishment-body">
      <h1 class="m-0 font-normal">
        <Wordmark />
      </h1>
      <p class="punishment-message">{{ S.nav.notFound }}</p>
      <div class="punishment-actions">
        <el-button size="default" @click="goHome">
          <HomeIcon width="12" height="12" style="margin-right: 6px" />
          {{ S.nav.returnHome }}
        </el-button>
        <a
          :href="`${S.login.githubUrl}/issues`"
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
  background: #f6f8fb;
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
  color: #475569;
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
  color: #475569;
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
