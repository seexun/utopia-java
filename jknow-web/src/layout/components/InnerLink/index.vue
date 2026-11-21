<template>
  <div :style="'height:' + height" v-loading="loading" element-loading-text="正在加载页面，请稍候！">
    <iframe
      :id="iframeId"
      style="width: 100%; height: 100%"
      :src="src"
      ref="iframeRef"
      frameborder="no"
    ></iframe>
  </div>
</template>

<script setup lang="ts">
const props = defineProps({
  src: {
    type: String,
    default: "/"
  },
  iframeId: {
    type: String
  }
})

const loading = ref<boolean>(true)
/* 偏移单源 tokens.scss --offset-app-content（124 = 54+38+16×2）；fallback 兜底 */
function appOffset(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--offset-app-content')
  const parsed = parseFloat(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 124
}
const height = ref<string>(document.documentElement.clientHeight - appOffset() + 'px')
const iframeRef = ref<HTMLIFrameElement | null>(null)

onMounted(() => {
  if (iframeRef.value) {
    iframeRef.value.onload = () => {
      loading.value = false
    }
  }
})
</script>
