// useSyncExternalStore 的 Vue 对等物（任务 8）：订阅外部 store，把它的快照
// 折成一个 ref。liveAnswer / convMarks 这两张表活在组件之外（见各文件头注），
// 它们的通知节奏（33ms 帧合并、引用相等跳过）由 store 自己负责——这里只做
// 「订阅 → 写 ref」这一层接线。setup 作用域里调用，卸载自动退订。
import { onScopeDispose, ref, type Ref } from "vue";

export function useSyncExternal<T>(
  subscribe: (listener: () => void) => () => void,
  getSnapshot: () => T
): Ref<T> {
  const snap = ref(getSnapshot()) as Ref<T>;
  const unsubscribe = subscribe(() => {
    snap.value = getSnapshot();
  });
  onScopeDispose(unsubscribe);
  return snap;
}
