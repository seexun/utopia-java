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

/* 124 = 顶栏 54 + 标签栏 38（§B 尺寸校准）+ .app-main 上下 padding 16×2 */
const height = ref<string>(document.documentElement.clientHeight - 124 + "px;")
const loading = ref<boolean>(true)
const url = computed(() => props.src)

onMounted(() => {
  setTimeout(() => {
    loading.value = false
  }, 300)
  window.onresize = function temp(): void {
    height.value = document.documentElement.clientHeight - 124 + "px;"
  }
})
</script>
