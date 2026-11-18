<!-- 品牌字标（对照旧仓 ui/index.tsx Wordmark）：渐变色块里一个 "U" + 字名。
     侧栏（logo 位）、故障页共用同一副锁形（.logo-mark/.logo-name 在 utopia.scss）。 -->
<script setup lang="ts">
import { S } from "@/i18n";

const name = S.app.name as string;
const mark = [...name][0] ?? "U";
</script>

<template>
  <a
    href="https://utopia.bi"
    target="_blank"
    rel="noreferrer"
    title="utopia.bi"
    class="inline-flex items-center gap-2.5"
  >
    <span class="logo-mark">{{ mark }}</span>
    <span class="logo-name">{{ name }}</span>
  </a>
</template>
