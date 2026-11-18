<script setup lang="ts">
// W0 占位骨架：W1-W3 按页族逐一替换。三栏壳（任务 4）落地前先给菜单一个落点。
import { computed } from "vue";
import { useRoute } from "vue-router";
import { S } from "@/i18n";

defineOptions({
  name: "UtopiaPlaceholder"
});

const route = useRoute();
const title = computed(() => (route.meta?.title as string) ?? "");
</script>

<template>
  <div class="placeholder">
    <div class="panel">
      <h2 class="title">{{ title || S.app.name }}</h2>
      <p class="hint">{{ S.nav.loading }}</p>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}

.panel {
  text-align: center;
  color: #94a3b8;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: #475569;
  margin: 0 0 8px;
}

.hint {
  font-size: 13px;
  margin: 0;
}
</style>
