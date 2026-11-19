<!-- 单点登录管理（对照旧仓 pages/Sso.tsx 的 SsoAdmin）：三行配置只读 +
     身份表 + 解绑（打字前确认）。没有替人绑定的入口——那是一条冒充别人登录的路。
     解绑前先看 oidcStatus：没配的部署上整节是一句「未启用」。
     reskin-b4 模板层重造（script 零动）：三行只读配置入卡（与表格卡同款卡语言），
     身份表与解绑接线原样。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { oidcStatus } from "@/api/knowledge/auth";
import { oidcIdentities, oidcUnlink } from "@/api/knowledge/admin";
import type { OidcIdentity } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { DangerConfirm } from "@/components/knowledge/dialog";

const queryClient = useQueryClient();
const status = useQuery({ queryKey: ["oidc-status"], queryFn: oidcStatus });
const identities = useQuery({
  queryKey: ["oidc-identities"],
  queryFn: oidcIdentities,
  enabled: computed(() => !!status.data.value?.enabled)
});
const confirming = ref<{ userId: string; email: string } | null>(null);
const unlink = useMutation({
  mutationFn: (userId: string) => oidcUnlink(userId),
  onSuccess: () => {
    confirming.value = null;
    queryClient.invalidateQueries({ queryKey: ["oidc-identities"] });
    toast.success(S.toast.saved);
  },
  onError: (e: Error) => toast.error(e.message)
});

const rows = computed<OidcIdentity[]>(() => identities.data.value?.identities ?? []);
</script>

<template>
  <div class="space-y-4">
    <p class="text-sub text-ink-2">{{ S.settings.sso.hint }}</p>

    <div
      v-if="status.data.value && !status.data.value.enabled"
      class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
    >
      {{ S.settings.sso.disabled }}
    </div>

    <template v-else>
      <!-- 三行信息展示，只读——issuer/client_id/redirect_uri 由环境变量定
           （reskin-b4：入卡，与同 tab 的身份表格同一副卡语言） -->
      <div
        v-if="identities.data.value"
        class="rounded-card border border-line bg-card px-5 py-4"
      >
        <div
          class="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-aux text-ink-2"
        >
          <span>{{ S.settings.sso.issuer }}</span>
          <span class="truncate font-mono" :title="identities.data.value.issuer">
            {{ identities.data.value.issuer }}
          </span>
          <span>{{ S.settings.sso.clientId }}</span>
          <span class="truncate font-mono" :title="identities.data.value.client_id">
            {{ identities.data.value.client_id }}
          </span>
          <span>{{ S.settings.sso.redirectUri }}</span>
          <span class="truncate font-mono" :title="identities.data.value.redirect_uri">
            {{ identities.data.value.redirect_uri }}
          </span>
        </div>
      </div>

      <div
        v-if="rows.length === 0"
        class="rounded-card border border-line bg-card p-8 text-center text-body text-ink-2"
      >
        {{ S.settings.sso.empty }}
      </div>
      <div v-else class="overflow-hidden rounded-card border border-line bg-card">
        <table class="w-full text-body">
          <thead>
            <tr class="border-b border-line text-left text-sub text-ink-2">
              <th class="px-4 py-3 font-medium">{{ S.settings.sso.colUser }}</th>
              <th class="px-4 py-3 font-medium">{{ S.settings.sso.colSubject }}</th>
              <th class="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.user_id" class="border-b border-line last:border-0">
              <td class="px-4 py-3 text-ink">{{ r.email }}</td>
              <td class="max-w-xs truncate px-4 py-3 font-mono text-sub text-ink-2" :title="r.subject">
                {{ r.subject }}
              </td>
              <td class="whitespace-nowrap px-4 py-3 text-right">
                <button
                  type="button"
                  class="link-inline is-danger text-sub"
                  @click="confirming = { userId: r.user_id, email: r.email }"
                >
                  {{ S.settings.sso.unlink }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <DangerConfirm
      v-if="confirming"
      :title="S.settings.sso.unlinkTitle(confirming.email)"
      :hint="S.settings.sso.unlinkHint"
      :confirm-label="S.settings.sso.unlink"
      :cancel-label="S.members.cancel"
      :busy="unlink.isPending.value"
      @confirm="unlink.mutate(confirming!.userId)"
      @cancel="confirming = null"
    />
  </div>
</template>
