<!-- 个人资料页（对照旧仓 pages/Account.tsx + Sso.tsx 的 SsoAccount）：头像、显示名、
     邮箱、改密码、单点登录绑定（写面：绑定跳身份提供方由本人完成，解绑走确认）。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { me as meApi, updateMe, changePassword, oidcMe, oidcUnlinkMe } from "@/api/knowledge/auth";
import { S } from "@/i18n";
import { message } from "@/utils/message";
import { toast } from "@/domain/toast";
import { navigation } from "@/api/knowledge/client";
import { DangerConfirm } from "@/components/knowledge/dialog";

const confirmUnlink = ref(false);
const unlinkMe = useMutation({
  mutationFn: oidcUnlinkMe,
  onSuccess: () => {
    confirmUnlink.value = false;
    queryClient.invalidateQueries({ queryKey: ["oidc-me"] });
    toast.success(S.account.sso.unlinked as string);
  },
  onError: (e: Error) => toast.error(e.message)
});

/** 从身份提供方回来（?sso=linked / ?sso_error=）：提示一次，再把地址洗干净，
 *  免得刷新一次又提示一次（对照旧仓 SsoAccount 的 useEffect，改用 hash 查询串） */
const queryClient = useQueryClient();
{
  const params = new URLSearchParams(window.location.hash.split("?")[1] ?? "");
  const linked = params.get("sso") === "linked";
  const ssoErr = params.get("sso_error");
  if (linked) toast.success(S.account.sso.linked as string);
  if (ssoErr) toast.error(ssoErr);
  if (linked || ssoErr) {
    params.delete("sso");
    params.delete("sso_error");
    const rest = params.toString();
    const base = window.location.hash.split("?")[0] ?? "/";
    window.location.hash = base + (rest ? `?${rest}` : "");
  }
}

defineOptions({ name: "AccountProfile" });

const me = useQuery({ queryKey: ["me"], queryFn: meApi });
// 单点登录（0056）：绑定由本人完成；没配 SSO 的部署上整节不出现
const sso = useQuery({ queryKey: ["oidc-me"], queryFn: oidcMe });

const name = ref<string | null>(null);
const curPw = ref("");
const newPw = ref("");

const saveName = useMutation({
  mutationFn: (n: string) => updateMe(n),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ["me"] });
    name.value = null;
    message(S.toast.saved as string, { type: "success" });
  },
  onError: (e: Error) => message(e.message, { type: "error" })
});

const changePw = useMutation({
  mutationFn: () => changePassword(curPw.value, newPw.value),
  onSuccess: () => {
    curPw.value = "";
    newPw.value = "";
    message(S.account.passwordChanged as string, { type: "success" });
  },
  onError: (e: Error) => message(e.message, { type: "error" })
});

const displayName = computed(() => name.value ?? me.data.value?.display_name ?? "");
const nameDirty = computed(
  () =>
    displayName.value.trim() !== me.data.value?.display_name &&
    displayName.value.trim().length > 0
);
const initials = computed(() => {
  const n = (me.data.value?.display_name ?? "").trim();
  if (!n) return "?";
  // 拉丁取词首两枚 / CJK 前两字（UserMenu 的 Avatar 同口径）
  const parts = n.split(/\s+/);
  if (/[\u4e00-\u9fff]/.test(n)) return n.slice(0, 2);
  return parts
    .slice(0, 2)
    .map(p => p[0]?.toUpperCase() ?? "")
    .join("");
});

function field(label: string) {
  return label;
}
void field;
</script>

<template>
  <div class="px-8 py-6">
    <!-- 与管理页同一副身材：内容居中限宽，行长不随窗口拉长 -->
    <div class="mx-auto w-full max-w-4xl">
      <div class="mb-6">
        <div class="flex items-baseline justify-between gap-4">
          <h1 class="min-w-0 break-words text-page font-extrabold text-ink">
            {{ S.account.profileTitle }}
          </h1>
        </div>
      </div>

      <div class="mb-4 rounded-card border border-line bg-card p-6">
        <div class="max-w-xl">
          <div class="mb-6 flex items-center gap-4">
            <span
              class="grid h-14 w-14 shrink-0 place-items-center rounded-full text-title font-bold text-white"
              style="background: linear-gradient(135deg, #1677ff, #06b6d4)"
            >
              {{ initials }}
            </span>
            <p class="text-sub text-ink-2">{{ S.account.avatarHint }}</p>
          </div>

          <div class="mb-4">
            <div class="mb-1 text-aux font-medium text-ink-2">
              {{ S.account.displayName }}
            </div>
            <div class="flex gap-2">
              <el-input v-model="displayName" class="flex-1" />
              <el-button
                type="primary"
                size="small"
                :disabled="!nameDirty || saveName.isPending.value"
                @click="saveName.mutate(displayName.trim())"
              >
                {{ S.account.save }}
              </el-button>
            </div>
          </div>

          <div class="mb-1 text-aux font-medium text-ink-2">
            {{ S.account.email }}
          </div>
          <div class="px-1 text-body text-ink-2">{{ me.data.value?.email }}</div>
        </div>
      </div>

      <div class="rounded-card border border-line bg-card p-6">
        <div class="max-w-xl">
          <h2 class="mb-4 text-body font-medium text-ink">
            {{ S.account.passwordTitle }}
          </h2>
          <div class="mb-4">
            <div class="mb-1 text-aux font-medium text-ink-2">
              {{ S.account.currentPassword }}
            </div>
            <el-input
              v-model="curPw"
              class="w-full"
              type="password"
              show-password
              autocomplete="current-password"
            />
          </div>
          <div class="mb-4">
            <div class="mb-1 text-aux font-medium text-ink-2">
              {{ S.account.newPassword }}
            </div>
            <el-input
              v-model="newPw"
              class="w-full"
              type="password"
              show-password
              autocomplete="new-password"
            />
          </div>
          <div class="flex justify-end">
            <el-button
              type="primary"
              size="small"
              :disabled="!curPw || newPw.length < 8 || changePw.isPending.value"
              @click="changePw.mutate()"
            >
              {{ S.account.changePassword }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 单点登录：我自己的绑定（0056）。**绑定由本人完成**——跳到身份提供方，
          回来时服务端核对还是那个已登录的人才写绑定；解绑走确认 -->
      <div
        v-if="sso.data.value?.enabled"
        class="mt-4 rounded-card border border-line bg-card p-6"
      >
        <div class="max-w-xl">
          <h2 class="mb-2 text-body font-medium text-ink">
            {{ S.account.sso.title }}
          </h2>
          <p class="mb-4 text-sub text-ink-2">{{ S.account.sso.hint }}</p>
          <div class="flex items-center gap-3">
            <span class="min-w-0 flex-1 truncate text-body text-ink-2">
              {{
                sso.data.value.linked && sso.data.value.subject
                  ? S.account.sso.linkedAs(sso.data.value.subject)
                  : S.account.sso.notLinked
              }}
            </span>
            <el-button
              v-if="sso.data.value.linked"
              size="small"
              @click="confirmUnlink = true"
            >
              {{ S.account.sso.unlink }}
            </el-button>
            <el-button
              v-else
              type="primary"
              size="small"
              @click="navigation.to('/api/v1/auth/oidc/start?link=1')"
            >
              {{ S.account.sso.link }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 从身份提供方回来时带着结果（?sso=linked 或 ?sso_error=）：提示一次再把地址洗净 -->
      <DangerConfirm
        v-if="confirmUnlink"
        :title="S.account.sso.unlinkTitle"
        :hint="S.account.sso.unlinkHint"
        :confirm-label="S.account.sso.unlink"
        :cancel-label="S.account.cancel"
        :busy="unlinkMe.isPending.value"
        @confirm="unlinkMe.mutate()"
        @cancel="confirmUnlink = false"
      />
    </div>
  </div>
</template>
