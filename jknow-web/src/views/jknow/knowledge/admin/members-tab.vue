<!-- 工作区成员（对照旧仓 pages/Members.tsx 全量）：一张表 + 行尾铅笔编辑弹窗
     （改角色/移出）、管理员代开账号（注册关闭后的唯一入口）、停用与恢复
     （部署级，只有管理员看得到）。停用不是另一张表，是这份名单里的一种状态。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import {
  members as membersApi,
  orgUsers,
  setMemberRole,
  removeMember
} from "@/api/knowledge/workspaces";
import {
  adminCreateUser,
  deactivatedUsers,
  adminDeactivateUser,
  adminReactivateUser
} from "@/api/knowledge/admin";
import { me as meApi } from "@/api/knowledge/auth";
import type { Member, OrgUser } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { DangerConfirm, UDialog } from "@/components/knowledge/dialog";
import { UPager } from "@/components/knowledge/kit";

const ROLES = ["owner", "admin", "editor", "viewer"] as const;
const ROLE_OPTIONS = ROLES.map(r => ({
  value: r,
  label: S.members.roles[r as keyof typeof S.members.roles]
}));
const MEMBER_PAGE = 10;

const kbStore = useKbContextHook();
const workspaceId = computed(() => kbStore.workspaceId);

const queryClient = useQueryClient();
const me = useQuery({ queryKey: ["me"], queryFn: meApi });
const membersQ = useQuery({
  queryKey: computed(() => ["members", workspaceId.value]),
  queryFn: () => membersApi(workspaceId.value!),
  enabled: computed(() => !!workspaceId.value)
});
const usersQ = useQuery({ queryKey: ["orgUsers"], queryFn: orgUsers });
/** 停用的账号是部署级的，成员表里查不到——另取一次并进同一份名单。
 *  **只有管理员看得到**：恢复也只有他能做 */
const deactQ = useQuery({
  queryKey: ["deactivatedUsers"],
  queryFn: deactivatedUsers,
  enabled: computed(() => !!me.data.value?.is_admin)
});

const filter = ref("");
const role = ref("all");
const status = ref<"all" | "active" | "deactivated">("active");
const memberPage = ref(0);
const adding = ref(false);
const creating = ref(false);
const addUserId = ref("");
const addRole = ref("viewer");
const editing = ref<Person | null>(null);
const deactivating = ref<{ id: string; name: string } | null>(null);
const editRole = ref("");

function refresh() {
  queryClient.invalidateQueries({ queryKey: ["members", workspaceId.value] });
}
function onErr(e: unknown) {
  toast.error(e instanceof Error ? e.message : String(e));
}

async function saveRole(userId: string, r: string) {
  try {
    await setMemberRole(workspaceId.value!, userId, r);
    toast.success(S.toast.saved);
    refresh();
  } catch (e) {
    onErr(e);
  }
}
async function doRemove(userId: string) {
  try {
    await removeMember(workspaceId.value!, userId);
    toast.success(S.toast.deleted);
    refresh();
  } catch (e) {
    onErr(e);
  }
}
async function doDeactivate(userId: string) {
  try {
    await adminDeactivateUser(userId);
    refresh();
    queryClient.invalidateQueries({ queryKey: ["deactivatedUsers"] });
    toast.success(S.toast.saved);
  } catch (e) {
    onErr(e);
  }
}
const revive = useMutation({
  mutationFn: (userId: string) => adminReactivateUser(userId),
  onSuccess: () => {
    refresh();
    queryClient.invalidateQueries({ queryKey: ["deactivatedUsers"] });
    queryClient.invalidateQueries({ queryKey: ["orgUsers"] });
    toast.success(S.toast.saved);
  },
  onError: onErr
});

/** 名单里的一行。停用的账号也是一行——它没有工作区角色，别的都一样 */
type Person = Member & { deactivated: boolean };
const people = computed<Person[]>(() => [
  ...(membersQ.data.value ?? []).map(m => ({ ...m, deactivated: false })),
  ...(deactQ.data.value ?? []).map((u: OrgUser): Person => ({
    user_id: u.id,
    display_name: u.display_name,
    email: u.email,
    role: "",
    is_admin: u.is_admin,
    deactivated: true
  }))
]);
const needle = computed(() => filter.value.trim().toLowerCase());
const memberList = computed(() =>
  people.value
    .filter(
      p =>
        status.value === "all" ||
        (status.value === "deactivated") === p.deactivated
    )
    .filter(p => role.value === "all" || p.role === role.value)
    .filter(
      p =>
        !needle.value ||
        p.display_name.toLowerCase().includes(needle.value) ||
        p.email.toLowerCase().includes(needle.value)
    )
    // 停用的沉底：他们仍然列着，但不该排在还在用的人前面
    .sort((a, b) => Number(a.deactivated) - Number(b.deactivated))
);
/** 分页切片（页码越界回落最后一页——筛完名单变短时不再空屏） */
const paged = computed(() => {
  const pageCount = Math.max(
    1,
    Math.ceil(memberList.value.length / MEMBER_PAGE)
  );
  const safe = Math.min(memberPage.value, pageCount - 1);
  return {
    rows: memberList.value.slice(safe * MEMBER_PAGE, (safe + 1) * MEMBER_PAGE),
    safe
  };
});
const pagedMembers = computed(() => paged.value.rows);
const safeMemberPage = computed(() => paged.value.safe);

const memberIds = computed(
  () => new Set((membersQ.data.value ?? []).map(m => m.user_id))
);
const addable = computed(() =>
  (usersQ.data.value ?? []).filter(u => !memberIds.value.has(u.id))
);

/* 代开账号弹窗的状态 */
const nuEmail = ref("");
const nuName = ref("");
const nuPassword = ref("");
const nuRole = ref("editor");
const nuError = ref<string | null>(null);
const nuValid = computed(
  () =>
    nuEmail.value.includes("@") &&
    nuName.value.trim() &&
    nuPassword.value.length >= 8
);
const creatingBusy = ref(false);
async function createUser() {
  creatingBusy.value = true;
  nuError.value = null;
  try {
    await adminCreateUser({
      email: nuEmail.value.trim(),
      display_name: nuName.value.trim(),
      password: nuPassword.value,
      role: nuRole.value
    });
    nuEmail.value = "";
    nuName.value = "";
    nuPassword.value = "";
    queryClient.invalidateQueries({ queryKey: ["orgUsers"] });
    creating.value = false;
    refresh();
    toast.success(S.toast.created);
  } catch (e) {
    nuError.value = e instanceof Error ? e.message : String(e);
  }
  creatingBusy.value = false;
}

function reset(fn: () => void) {
  fn();
  memberPage.value = 0;
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛这份名单的东西在表格外面（DESIGN.md 6）：三个筛子同一副身材 -->
    <div class="flex flex-wrap items-center gap-2">
      <el-input
        v-model="filter"
        :placeholder="S.settings.searchUsers"
        style="width: 16rem"
        @input="reset(() => {})"
      />
      <el-select v-model="role" style="width: 8rem" @change="reset(() => {})">
        <el-option value="all" :label="S.members.filterAllRoles" />
        <el-option
          v-for="r in ROLE_OPTIONS"
          :key="r.value"
          :value="r.value"
          :label="r.label"
        />
      </el-select>
      <el-select
        v-if="me.data.value?.is_admin"
        v-model="status"
        style="width: 9rem"
        @change="reset(() => {})"
      >
        <el-option value="active" :label="S.members.filterActive" />
        <el-option value="deactivated" :label="S.members.filterDeactivated" />
        <el-option value="all" :label="S.members.filterAll" />
      </el-select>
      <div class="ml-auto flex items-center gap-2">
        <el-button size="small" @click="adding = true">{{
          S.members.addExisting
        }}</el-button>
        <el-button
          v-if="me.data.value?.is_admin"
          type="primary"
          size="small"
          @click="creating = true"
        >
          <Icon icon="lucide:plus" width="12" height="12" class="mr-1" />
          {{ S.settings.newUser }}
        </el-button>
      </div>
    </div>

    <div class="overflow-hidden rounded-card border border-line bg-card">
      <table class="w-full text-body">
        <thead>
          <tr class="border-b border-line text-left text-sub text-ink-2">
            <th class="px-4 py-3 font-medium">{{ S.members.userLabel }}</th>
            <th class="px-4 py-3 font-medium">{{ S.members.roleLabel }}</th>
            <th class="px-4 py-3 font-medium">{{ S.members.statusLabel }}</th>
            <th class="px-4 py-3" />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="m in pagedMembers"
            :key="m.user_id"
            class="border-b border-line last:border-0"
            :class="m.deactivated ? 'opacity-55' : ''"
          >
            <td class="px-4 py-3">
              <div class="flex items-center gap-2">
                <span class="truncate text-body text-ink">{{
                  m.display_name
                }}</span>
                <span v-if="m.is_admin" class="badge-soft badge-soft-info">
                  {{ S.members.systemAdmin }}
                </span>
              </div>
              <div class="truncate text-sub text-ink-2">{{ m.email }}</div>
            </td>
            <td class="px-4 py-3 text-ink-2">
              {{
                m.deactivated
                  ? "—"
                  : (S.members.roles[m.role as keyof typeof S.members.roles] ??
                    m.role)
              }}
            </td>
            <td class="px-4 py-3">
              <span v-if="m.deactivated" class="badge-soft badge-soft-danger">
                {{ S.members.filterDeactivated }}
              </span>
              <span v-else class="text-sub text-ink-2">{{
                S.members.filterActive
              }}</span>
            </td>
            <td class="px-4 py-3 text-right">
              <!-- 停用的账号这一行只有一件事可做：直接摆出来，不必藏进弹窗 -->
              <el-button
                v-if="m.deactivated"
                size="small"
                :disabled="revive.isPending.value"
                @click="revive.mutate(m.user_id)"
              >
                {{ S.members.reactivate }}
              </el-button>
              <el-button
                v-else
                size="small"
                text
                :aria-label="S.members.editMember"
                @click="
                  editing = m;
                  editRole = m.role;
                "
              >
                <Icon icon="lucide:pencil" width="13" height="13" />
              </el-button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="memberList.length === 0" class="px-4 py-6 text-body text-ink-2">
        {{ S.ui.noMatches }}
      </p>
      <UPager
        :total="memberList.length"
        :page-size="MEMBER_PAGE"
        :page="safeMemberPage"
        @page="(p: number) => (memberPage = p)"
      />
    </div>

    <!-- 把一个**已有账号**加进这个工作区：凭空造一个人是另一个动作 -->
    <el-dialog
      v-model="adding"
      :title="S.members.addExisting"
      width="30rem"
      append-to-body
    >
      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.userLabel }}
          </div>
          <el-select
            v-model="addUserId"
            filterable
            class="w-full"
            :placeholder="S.members.pickUser"
            :no-data-text="S.ui.noMatches"
          >
            <el-option
              v-for="u in addable"
              :key="u.id"
              :value="u.id"
              :label="u.display_name"
            >
              <span class="flex flex-col">
                <span>{{ u.display_name }}</span>
                <span class="text-aux text-ink-2">{{ u.email }}</span>
              </span>
            </el-option>
          </el-select>
        </div>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.roleLabel }}
          </div>
          <el-select v-model="addRole" class="w-full">
            <el-option
              v-for="r in ROLE_OPTIONS"
              :key="r.value"
              :value="r.value"
              :label="r.label"
            />
          </el-select>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="adding = false">{{
          S.members.cancel
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!addUserId"
          @click="
            () => {
              saveRole(addUserId, addRole);
              adding = false;
            }
          "
        >
          {{ S.members.add }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 管理员代开账号。这是**给别人开账号**，不是登录 -->
    <el-dialog
      v-model="creating"
      :title="S.settings.newUser"
      width="32rem"
      append-to-body
    >
      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.login.email }}
          </div>
          <el-input v-model="nuEmail" autocomplete="off" />
        </div>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.login.displayName }}
          </div>
          <el-input v-model="nuName" autocomplete="off" />
        </div>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.settings.initialPassword }}
          </div>
          <el-input
            v-model="nuPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </div>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.roleLabel }}
          </div>
          <el-select v-model="nuRole" class="w-full">
            <el-option value="admin" :label="S.members.roles.admin" />
            <el-option value="editor" :label="S.members.roles.editor" />
            <el-option value="viewer" :label="S.members.roles.viewer" />
          </el-select>
        </div>
      </div>
      <p v-if="nuError" class="mt-3 text-sub text-danger">{{ nuError }}</p>
      <template #footer>
        <el-button size="small" @click="creating = false">{{
          S.members.cancel
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!nuValid || creatingBusy"
          @click="createUser"
        >
          {{ S.settings.createUserBtn }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 一个成员的弹窗：改身份在正文，停用与移出在左下角。**停用和移出是两件事** -->
    <UDialog
      :open="!!editing"
      :close-label="S.members.close"
      width="sm"
      @update:open="(o: boolean) => !o && (editing = null)"
    >
      <template #title>
        <span>{{ editing?.display_name }}</span>
      </template>
      <template #description>
        <span>{{ editing?.email }}</span>
      </template>
      <template #default>
        <div>
          <div class="mb-1 text-sub font-medium text-ink-2">
            {{ S.members.roleLabel }}
          </div>
          <el-select v-model="editRole" class="w-full">
            <el-option
              v-for="r in ROLE_OPTIONS"
              :key="r.value"
              :value="r.value"
              :label="r.label"
            />
          </el-select>
        </div>
      </template>
      <template #footer>
        <div class="foot-danger">
          <el-button
            v-if="
              me.data.value?.is_admin && me.data.value.id !== editing?.user_id
            "
            size="small"
            :title="S.members.deactivateHint"
            @click="
              () => {
                deactivating = {
                  id: editing!.user_id,
                  name: editing!.display_name
                };
                editing = null;
              }
            "
          >
            {{ S.members.deactivate }}
          </el-button>
          <el-button
            size="small"
            @click="
              () => {
                doRemove(editing!.user_id);
                editing = null;
              }
            "
          >
            {{ S.members.remove }}
          </el-button>
        </div>
        <el-button size="small" @click="editing = null">{{
          S.members.cancel
        }}</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="editRole === editing?.role"
          @click="
            () => {
              if (editing && editRole !== editing.role)
                saveRole(editing.user_id, editRole);
              editing = null;
            }
          "
        >
          {{ S.members.save }}
        </el-button>
      </template>
    </UDialog>

    <!-- 停用先问一句——用全站的对话框而不是浏览器原生 confirm() -->
    <DangerConfirm
      v-if="deactivating"
      :title="S.members.deactivate"
      :hint="S.members.deactivateConfirm(deactivating.name)"
      :confirm-label="S.members.deactivate"
      :cancel-label="S.members.cancel"
      @confirm="
        () => {
          doDeactivate(deactivating!.id);
          deactivating = null;
        }
      "
      @cancel="deactivating = null"
    />
  </div>
</template>
