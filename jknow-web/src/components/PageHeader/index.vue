<!-- 页面标配页头：22px/800 标题 + 13px 副标题 + 右侧动作区（主行动每页唯一） -->
<template>
  <div class="page-header">
    <div class="page-header-text">
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.extra" class="page-header-extra">
      <slot name="extra" />
    </div>
  </div>
</template>

<script setup name="PageHeader">
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;

  .page-title {
    margin: 0;
    font-size: 22px;
    font-weight: 800;
    line-height: 1.3;
    color: var(--color-ink);
  }

  .page-subtitle {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--color-ink-3);
  }

  .page-header-extra {
    display: flex;
    gap: 8px;
    flex-shrink: 0;
  }
}
</style>
