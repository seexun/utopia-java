<!-- 库切换器（menu-ia T2 自 d03dda4 复活）：若依侧栏左下角。
     源版住在 pure-admin 壳里，数据走壳管线（props kb/kbs + emit change），
     弹层是 el-popover 三段式（查找/库列表/新建）。复活适配三件事：
     1. 去 pure-admin 依赖——不再接 props，useKbContext 直读库列表/当前库
        （rebuildMenus 菜单管线 P3 已清，不引用）；
     2. 模板收敛为「当前库名 + el-dropdown 列表」——切换动作用
        useKbContext.switchKb（现役 API：写记忆 + 失效目标库事件流 +
        samePageInKb 导航 = 保留当前 section 换 kbId，与 chat 页切换器同源）；
     3. 样式对齐若依侧栏——配色随 sideTheme/isDark 走 Sidebar/Logo 同一副
        计算，折叠态（54px）收敛为首字徽标图标按钮 + el-tooltip 显全名。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useKbContext } from "@/composables/useKbContext";
import useAppStore from "@/store/modules/app";
import useSettingsStore from "@/store/modules/settings";
import variables from "@/assets/styles/variables.module.scss";
import LayersIcon from "~icons/lucide/layers";
import ChevronDownIcon from "~icons/lucide/chevron-down";
import CheckIcon from "~icons/lucide/check";

const { kb, kbs, switchKb } = useKbContext();

const appStore = useAppStore();
const settingsStore = useSettingsStore();

const collapse = computed(() => !appStore.sidebar.opened);
const sideTheme = computed(() => settingsStore.sideTheme);

const name = computed(() => kb.value?.name ?? "…");
// 折叠态徽标/提示的首字（源版无折叠形态，此为若依侧栏适配新增）
const initial = computed(() => (kb.value?.name ?? "").trim().charAt(0) || "·");

function onPick(id: string) {
  // 点当前库是确认不是切换；点别的库走 switchKb（kb 页内 = 换库停在当前
  // section；不在 /kb 作用域时只记一笔不拽走——useKbContext 既有语义）
  if (kb.value && id === kb.value.id) return;
  switchKb(id);
}

// 配色与 Sidebar/Logo 同一副计算：theme-dark 暗色 / theme-light 浅色 / html.dark
const switcherBg = computed(() => {
  if (settingsStore.isDark) return "var(--sidebar-bg)";
  return sideTheme.value === "theme-dark" ? variables.menuBg : variables.menuLightBg;
});
const switcherText = computed(() => {
  if (settingsStore.isDark) return "var(--sidebar-text)";
  return sideTheme.value === "theme-dark" ? variables.menuText : variables.menuLightText;
});
const switcherHover = computed(() => {
  if (settingsStore.isDark) return "var(--menu-hover)";
  return sideTheme.value === "theme-dark" ? variables.menuHover : variables.menuLightHover;
});
const switcherBorder = computed(() =>
  settingsStore.isDark || sideTheme.value === "theme-dark"
    ? "rgba(255, 255, 255, 0.08)"
    : "rgba(0, 0, 0, 0.08)"
);
</script>

<template>
  <!-- element-plus 2.14 的 el-dropdown API：默认槽=触发元素（纯 div，非
       splitButton 时经 OnlyChild 包成 trigger），菜单放 #dropdown 具名槽——
       #reference 旧写法不生效（menu 会落进 trigger，rovingFocus 注入丢失） -->
  <el-dropdown
    trigger="click"
    placement="top-start"
    popper-class="kb-switcher-popper"
    :teleported="true"
    class="kb-switcher-host"
  >
    <div v-if="collapse" class="kb-switcher kb-switcher--collapse" title="切换知识库">
      <el-tooltip :content="name" placement="right">
        <span class="kb-switcher-badge">{{ initial }}</span>
      </el-tooltip>
    </div>
    <div v-else class="kb-switcher" title="切换知识库">
      <LayersIcon width="15" height="15" class="kb-switcher-icon" />
      <span class="kb-switcher-name">{{ name }}</span>
      <ChevronDownIcon width="12" height="12" class="kb-switcher-icon" />
    </div>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="k in kbs"
          :key="k.id"
          :class="{ 'is-current': kb && k.id === kb.id }"
          @click="onPick(k.id)"
        >
          <span class="kb-switcher-item-name">{{ k.name }}</span>
          <CheckIcon v-if="kb && k.id === kb.id" width="13" height="13" />
        </el-dropdown-item>
        <el-dropdown-item v-if="kbs.length === 0" disabled>暂无知识库</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style lang="scss" scoped>
.kb-switcher-host {
  display: block;
  width: 100%;
  font-size: 14px; // 侧栏容器 font-size:0（sidebar.scss），组件面自恢复
}

.kb-switcher {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 50px;
  padding: 0 14px 0 20px;
  box-sizing: border-box;
  font-size: 14px;
  color: v-bind(switcherText);
  background: v-bind(switcherBg);
  border-top: 1px solid v-bind(switcherBorder);
  cursor: pointer;
  outline: none;

  &:hover {
    background: v-bind(switcherHover);
  }

  .kb-switcher-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .kb-switcher-icon {
    flex-shrink: 0;
    color: v-bind(switcherText);
    opacity: 0.65;
  }

  // 折叠态（侧栏 54px）：只留一枚首字徽标，全名交给 el-tooltip
  &.kb-switcher--collapse {
    justify-content: center;
    gap: 0;
    padding: 0;

    .kb-switcher-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border-radius: 6px;
      font-size: 15px;
      font-weight: 600;
      color: #fff;
      background: var(--current-color, var(--color-primary));
    }
  }
}
</style>

<style lang="scss">
/* teleported 弹层不吃 scoped（popper 挂 body 下）：popper-class 全局覆盖，
   宽度对齐侧栏展开宽（200px），库多时长列表滚动 */
.kb-switcher-popper {
  width: 200px;

  .el-dropdown-menu {
    max-height: 300px;
    overflow-y: auto;
    padding: 4px 0;
  }

  .el-dropdown-menu__item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 8px 16px;
    line-height: 24px;

    .kb-switcher-item-name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &.is-current {
      color: var(--el-color-primary);
    }
  }
}
</style>
