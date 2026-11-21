<template>
  <el-drawer v-model="visible" title="公告详情" direction="rtl" size="50%" append-to-body :before-close="handleClose" class="notice-detail-drawer">
    <div v-loading="loading" class="notice-detail-drawer__body">
      <div v-if="!detail" class="notice-empty">
        <el-icon><Document /></el-icon>
        <span>暂无数据</span>
      </div>
      <div v-else class="notice-page">
        <div class="notice-type-wrap">
          <span v-if="detail.noticeType === '1'" class="notice-type-tag type-notify">
            <el-icon><Bell /></el-icon> 通知
          </span>
          <span v-else-if="detail.noticeType === '2'" class="notice-type-tag type-announce">
            <el-icon><Message /></el-icon> 公告
          </span>
          <span v-else class="notice-type-tag type-notify">
            <el-icon><Document /></el-icon> 消息
          </span>
        </div>

        <h1 class="notice-title">{{ detail.noticeTitle }}</h1>

        <div class="notice-meta">
          <span class="meta-item">
            <el-icon><User /></el-icon>
            <span>{{ detail.createBy || '—' }}</span>
          </span>
          <span class="meta-item">
            <el-icon><Clock /></el-icon>
            <span>{{ detail.createTime || '—' }}</span>
          </span>
          <span class="meta-item">
            <span :class="['status-dot', isStatusNormal ? 'status-ok' : 'status-off']"></span>
            <span>{{ isStatusNormal ? '正常' : '已关闭' }}</span>
          </span>
        </div>

        <div class="notice-divider">
          <span class="notice-divider-dot"></span>
          <span class="notice-divider-dot"></span>
          <span class="notice-divider-dot"></span>
        </div>

        <div class="notice-body">
          <div v-if="hasContent" class="notice-content" v-html="detail.noticeContent" />
          <div v-else class="notice-empty notice-empty--inner">
            <el-icon><Document /></el-icon> 暂无内容
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { getNotice } from '@/api/system/notice'
import type { SysNotice } from '@/types/api/system/notice'

const visible = ref<boolean>(false)
const loading = ref<boolean>(false)
const detail: Ref<SysNotice | null> = ref<SysNotice | null>(null)

const isStatusNormal = computed<boolean>(() => {
  const status = detail.value && detail.value.status
  return status === '0' || status === 0
})

const hasContent = computed<boolean>(() => {
  const content = detail.value && detail.value.noticeContent
  return content != null && String(content).trim() !== ''
})

function open(payload: any) {
  let id = null
  let preset = null
  if (payload != null && typeof payload === 'object') {
    id = payload.noticeId
    if (payload.noticeContent != null) {
      preset = payload
    }
  } else {
    id = payload
  }
  visible.value = true
  if (preset) {
    detail.value = preset
    return
  }
  if (id == null || id === '') {
    detail.value = null
    return
  }
  loading.value = true
  detail.value = null
  getNotice(id).then(res => {
    detail.value = res.data
  }).catch(() => {
    detail.value = null
  }).finally(() => {
    loading.value = false
  })
}

function handleClose() {
  visible.value = false
  detail.value = null
  loading.value = false
}

defineExpose({
  open
})
</script>

<style lang="scss" scoped>
.notice-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 8px 8px 20px;
  animation: notice-fade-up 0.28s ease both;
}

@keyframes notice-fade-up {
  from { opacity: 0; transform: translateY(14px); }
  to   { opacity: 1; transform: translateY(0); }
}

.notice-type-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 12px;
  border-radius: 2px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  margin-bottom: 14px;
}

.type-notify {
  background: var(--notice-tag-notify-bg, var(--color-warning-soft));
  color: var(--notice-tag-notify-color, var(--color-warning-text));
  border-left: 3px solid var(--color-warning);
}

.type-announce {
  background: var(--notice-tag-announce-bg, var(--color-success-soft));
  color: var(--notice-tag-announce-color, var(--color-success-text));
  border-left: 3px solid var(--color-success);
}

.notice-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--notice-title-color, var(--color-ink));
  line-height: 1.45;
  margin: 0 0 16px;
  letter-spacing: -0.2px;
}

.notice-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--notice-border-color, var(--color-border));
  border-bottom: 1px solid var(--notice-border-color, var(--color-border));
  margin-bottom: 28px;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--notice-meta-color, var(--color-ink-2));
}

.meta-item .el-icon {
  font-size: 12px;
  color: var(--notice-meta-icon-color, var(--color-ink-3));
}

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 4px;
}

.status-ok  { background: var(--color-success); }
.status-off { background: var(--color-danger); }

.notice-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.notice-divider::before,
.notice-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--notice-divider-gradient, linear-gradient(to right, transparent, var(--color-border), transparent));
}

.notice-divider-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--notice-divider-dot-color, var(--color-border));
}

.notice-body {
  background: var(--notice-body-bg, var(--color-card));
  border-radius: 6px;
  padding: 28px 32px;
  box-shadow: var(--notice-body-shadow, 0 1px 4px rgba(0, 0, 0, 0.06), 0 0 0 1px rgba(0, 0, 0, 0.04));
  min-height: 120px;
}

.notice-content {
  font-size: 14px;
  line-height: 1.85;
  color: var(--notice-content-color, var(--color-ink));
  word-break: break-word;
}

.notice-content :deep(p) { margin: 0 0 1em; }

.notice-content :deep(h1),
.notice-content :deep(h2),
.notice-content :deep(h3) {
  font-weight: 700;
  color: var(--notice-title-color, var(--color-ink));
  margin: 1.4em 0 0.6em;
}

.notice-content :deep(h1) { font-size: 18px; }
.notice-content :deep(h2) { font-size: 16px; }
.notice-content :deep(h3) { font-size: 14px; }

.notice-content :deep(a) {
  color: var(--color-primary);
  text-decoration: underline;
}
.notice-content :deep(a:hover) { color: var(--color-primary-hover); }

.notice-content :deep(img) {
  max-width: 100%;
  border-radius: 4px;
  margin: 8px 0;
}

.notice-content :deep(ul),
.notice-content :deep(ol) {
  padding-left: 20px;
  margin: 0 0 1em;
}
.notice-content :deep(li) { margin-bottom: 4px; }

.notice-content :deep(blockquote) {
  border-left: 3px solid var(--notice-blockquote-border, var(--color-border));
  margin: 1em 0;
  padding: 6px 16px;
  color: var(--notice-meta-color, var(--color-ink-2));
  background: var(--notice-blockquote-bg, var(--color-neutral-soft));
}

.notice-content :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 1em 0;
  font-size: 13px;
}
.notice-content :deep(table th),
.notice-content :deep(table td) {
  border: 1px solid var(--notice-table-border, var(--color-border));
  padding: 7px 12px;
}
.notice-content :deep(table th) {
  background: var(--notice-table-header-bg, var(--color-neutral-soft));
  font-weight: 600;
}

.notice-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--notice-meta-icon-color, var(--color-ink-3));
  font-size: 13px;
}
.notice-empty .el-icon {
  font-size: 28px;
  display: inline-flex;
  margin-bottom: 10px;
}
.notice-empty--inner { padding: 32px 0; }

.notice-detail-drawer__body {
  height: 100%;
  overflow: auto;
  padding: 10px 16px 22px;
}
</style>

<style lang="scss">
.notice-detail-drawer {
  .el-drawer__header {
    margin-bottom: 0;
    padding: 16px 20px;
    border-bottom: 1px solid var(--notice-border-color, var(--color-border));
    font-size: 16px;
    font-weight: 600;
    color: var(--notice-title-color, var(--color-ink));
  }

  .el-drawer__body {
    background: var(--notice-drawer-body-bg, var(--color-bg));
    padding: 0;
  }
}
</style>
