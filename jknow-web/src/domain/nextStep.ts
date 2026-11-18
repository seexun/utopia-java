/* 【任务5抽离】自旧仓 web/src/pages/NextStep.tsx 取纯判断函数 nextStep（#313
   「这个库走到哪一步」的空状态判定）；useReadiness 与 NextStep 组件面留 Vue 页
   （Ⅲ类-重写）。除本头注外逐字原样（import 按本仓路径调整）。 */

import { S } from "@/i18n";
import type { Readiness } from "@/api/knowledge/types";

/** 这个库的下一步是什么。`canUpload` 交给调用方——它知道自己的角色。 */
export function nextStep(
  r: Readiness | undefined,
  opts: { kbId: string; isAdmin: boolean; canUpload: boolean }
): {
  line: string;
  action?: {
    label: string;
    to: string;
    params?: Record<string, string>;
    search?: Record<string, unknown>;
  };
} | null {
  if (!r) return null;
  // 顺序即优先级：没有模型，上传了也抽不出东西；没有文档，谈不上处理中
  if (!r.has_chat_model) {
    return opts.isAdmin
      ? {
          line: S.steps.noModel,
          action: {
            label: S.steps.configureModel,
            to: "/admin",
            search: { tab: "models" }
          }
        }
      : { line: S.steps.noModelAsk };
  }
  if (r.documents === 0) {
    return opts.canUpload
      ? {
          line: S.steps.noDocs,
          action: {
            label: S.steps.upload,
            to: "/kb/$kbId/library",
            params: { kbId: opts.kbId }
          }
        }
      : { line: S.steps.noDocs };
  }
  if (r.processing > 0) {
    return {
      line: S.steps.processing(r.processing),
      action: {
        label: S.steps.viewProgress,
        to: "/kb/$kbId/library",
        params: { kbId: opts.kbId }
      }
    };
  }
  // 处理完了却一个失败都没修，值得说一句——否则「什么都没抽出来」会被当成产品坏了
  if (r.failed > 0 && r.entities === 0) {
    return {
      line: S.steps.someFailed(r.failed),
      action: {
        label: S.steps.openLibrary,
        to: "/kb/$kbId/library",
        params: { kbId: opts.kbId }
      }
    };
  }
  return null;
}
