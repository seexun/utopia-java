<template>
  <div v-loading="loading" :style="'height:' + height">
    <iframe 
      :src="url" 
      frameborder="no" 
      style="width: 100%; height: 100%" 
      scrolling="auto" />
  </div>
</template>

<script setup lang="ts">
const props = defineProps({
  src: {
    type: String,
    required: true
  }
})

/* 偏移单源 tokens.scss --offset-app-content（124 = 54+38+16×2）；fallback 兜底 */
function appOffset(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--offset-app-content')
  const parsed = parseFloat(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 124
}
const height = ref<string>(document.documentElement.clientHeight - appOffset() + "px;")
const loading = ref<boolean>(true)
const url = computed(() => props.src)

onMounted(() => {
  setTimeout(() => {
    loading.value = false
  }, 300)
  window.onresize = function temp(): void {
    height.value = document.documentElement.clientHeight - appOffset() + "px;"
  }
})
</script>
