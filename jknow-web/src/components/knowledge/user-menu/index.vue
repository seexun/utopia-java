<!-- 用户菜单（对照旧仓 UserMenu.tsx）：顶栏右侧的头像胶囊 + 弹出面板
     （个人信息 / 系统管理 / 语言 / 登出）。Shell 与账户层共用。
     语言切换走 setLang（整页重载，旧仓同一决定；S 冻结于模块加载）。 -->
<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { useUserStoreHook } from "@/store/knowledge/modules/user";
import { useShellStoreHook } from "@/store/knowledge/modules/shell";
import {
  S,
  lang,
  LANGS,
  LANG_NAMES,
  setLang,
  type Lang
} from "@/i18n";
import type { User } from "@/api/knowledge/types";
import UserRoundIcon from "~icons/lucide/user-round";
import LayersIcon from "~icons/lucide/layers";
import ShieldCheckIcon from "~icons/lucide/shield-check";
import LanguagesIcon from "~icons/lucide/languages";
import LogOutIcon from "~icons/lucide/log-out";

const props = defineProps<{ user: User }>();

const visible = ref(false);
const router = useRouter();

/** 首字母头像：品牌渐变底（avatar-gradient），拉丁取词首两枚，CJK 取前两字。 */
const initials = computed(() => {
  const trimmed = props.user.display_name.trim();
  const words = trimmed.split(/\s+/).filter(Boolean);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  return [...trimmed].slice(0, 2).join("").toUpperCase();
});

const go = (to: string) => {
  visible.value = false;
  router.push(to);
};

const logout = async () => {
  visible.value = false;
  await useUserStoreHook().logOut();
  // 登出是会话边界：壳级 ServerDown 态一并复位（W1）
  useShellStoreHook().markAlive();
};

function onLang(next: Lang) {
  // setLang 整页重载——菜单开合状态不必管
  setLang(next);
}
</script>

<template>
  <el-dropdown
    v-model:visible="visible"
    trigger="click"
    popper-class="shell-pop user-menu-pop"
  >
    <div class="user-chip">
      <span
        class="avatar-gradient"
        :style="{ width: '24px', height: '24px', fontSize: '9px' }"
        >{{ initials }}</span
      >
      <span class="text-sm" style="color: #475569">{{ user.display_name }}</span>
    </div>
    <template #dropdown>
      <el-dropdown-menu>
        <!-- 身份头：不是一个可点的项，只说"你是谁"（旧仓注释原话） -->
        <li class="user-menu-head">
          <span
            class="avatar-gradient"
            :style="{ width: '32px', height: '32px', fontSize: '12px' }"
            >{{ initials }}</span
          >
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="truncate text-sm font-medium">{{ user.display_name }}</span>
              <span
                v-if="user.is_admin"
                class="rounded px-1.5 py-0.5 text-xs"
                style="background: #f1f5f9; color: #475569"
                >{{ S.account.adminChip }}</span
              >
            </div>
            <div class="truncate text-xs" style="color: #94a3b8">{{ user.email }}</div>
          </div>
        </li>
        <el-dropdown-item @click="go('/account')">
          <UserRoundIcon width="13" height="13" />
          {{ S.account.profile }}
        </el-dropdown-item>
        <!-- 人人可看：全部可见库 + 我在每个库的身份（旧仓注释原话） -->
        <el-dropdown-item @click="go('/account/kbs')">
          <LayersIcon width="13" height="13" />
          {{ S.account.kbsNav }}
        </el-dropdown-item>
        <el-dropdown-item v-if="user.is_admin" @click="go('/admin')">
          <ShieldCheckIcon width="13" height="13" />
          {{ S.account.administration }}
        </el-dropdown-item>
        <!-- 语言：在值那一头弹出来。收进二级——语言以后会有好几种，全都平铺在
             一级里，这张菜单会越长越长（旧仓注释原话） -->
        <li class="user-menu-lang">
          <LanguagesIcon width="13" height="13" />
          <span>{{ S.account.language }}</span>
          <el-select
            :model-value="lang"
            size="small"
            class="ml-auto"
            style="width: 110px"
            @change="onLang"
          >
            <el-option
              v-for="l in LANGS"
              :key="l"
              :value="l"
              :label="LANG_NAMES[l]"
            />
          </el-select>
        </li>
        <el-dropdown-item divided @click="logout">
          <LogOutIcon width="13" height="13" />
          {{ S.nav.signOut }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.user-menu-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #e5eaf1;
  cursor: default;
}

.user-menu-lang {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 8px 16px;
  font-size: 14px;
  white-space: nowrap;
  border-top: 1px solid #e5eaf1;
  border-bottom: 1px solid #e5eaf1;
  cursor: default;
}
</style>
