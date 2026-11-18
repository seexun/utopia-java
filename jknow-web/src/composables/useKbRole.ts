// 当前库的角色判定（W0）：对照旧仓 kb.tsx 及各页对 `my_role` 的门控口径。
// my_role 只在 kb 详情接口返回（GET /kbs/{id}）——useKbStore.load() 是它的进料口。
// 换库后必须重新 load，角色的四种取值与旧仓完全一致：
//   "viewer" | "editor" | "admin" | "owner" | null（详情没回来时是 null）
import { computed, type ComputedRef } from "vue";
// T3 接线：源仓 @/store/modules/kb 落位 src/store/knowledge/modules/kb
import { useKbContextHook } from "@/store/knowledge/modules/kb";

export type KbRole = "viewer" | "editor" | "admin" | "owner" | null;

export function useKbRole(): {
  /** 当前库与我在其中的角色（null = 详情未加载或无库） */
  role: ComputedRef<KbRole>;
  /** 当前库 id */
  kbId: ComputedRef<string>;
  /** 可写（上传文档/发起对话记入）：旧仓口径 `my_role !== "viewer"`——
   *  详情未加载（undefined/null）时放行，与旧仓 `kb?.my_role !== "viewer"` 一致 */
  canWrite: ComputedRef<boolean>;
  /** 可管理内容（改本体、审核裁决、重新抽取）：editor/admin/owner，旧仓 DocViewer/PendingFacts 口径 */
  canManage: ComputedRef<boolean>;
  /** 库管理员（成员/设置/删库/重建图谱）：admin/owner，旧仓 MyKbs 口径 */
  isKbAdmin: ComputedRef<boolean>;
} {
  const kbStore = useKbContextHook();
  const role = computed<KbRole>(
    () => (kbStore.kbDetail?.my_role ?? null) as KbRole
  );
  const canWrite = computed(() => role.value !== "viewer");
  const canManage = computed(() =>
    ["editor", "admin", "owner"].includes(role.value ?? "")
  );
  const isKbAdmin = computed(() =>
    ["admin", "owner"].includes(role.value ?? "")
  );
  return {
    role,
    kbId: computed(() => kbStore.kbId),
    canWrite,
    canManage,
    isKbAdmin
  };
}
