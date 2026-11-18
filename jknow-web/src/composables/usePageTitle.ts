/* 网页标题系统（对照旧仓 useTitle.ts 逐字语义）：`{域} | {页面}`，品牌在前
   （产品决策；代价是多标签截断后同前缀）。
   域：Utopia（主应用）/ Utopia Charter（文档）/ Utopia Persona（账户）。
   Vue 面：part 可以是响应式的（随路由/当前库变化），空值段自动跳过。 */
import { watchEffect, toValue, type MaybeRefOrGetter } from "vue";

export function usePageTitle(...parts: MaybeRefOrGetter<string | null | undefined>[]) {
  watchEffect(() => {
    const title = parts
      .map(p => toValue(p))
      .filter(Boolean)
      .join(" | ");
    if (title) document.title = title;
  });
}
