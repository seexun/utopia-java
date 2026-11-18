/* 对话里的复制（#936）：一段回答一个，每个代码块一个。（对照旧仓 chatCopy.tsx）
   控件是 kit 的 UIconButton；复制本身走 domain/clipboard 的 copyText，局域网里
   用 http 打开的部署上剪贴板 API 不存在，它会退回老办法，两样都不成就说出来。 */
import {
  type PropType,
  defineComponent,
  ref,
  watch,
  onScopeDispose
} from "vue";
import CheckIcon from "~icons/lucide/check";
import CopyIcon from "~icons/lucide/copy";
import { copyText } from "@/domain/clipboard";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { UIconButton } from "@/components/knowledge/kit";

/** 复制按钮。`text` 在按下时才取：代码块的字要从渲染好的 DOM 里读 */
export const UCopyButton = defineComponent({
  name: "UCopyButton",
  props: {
    label: { type: String, required: true },
    text: { type: Function as PropType<() => string>, required: true }
  },
  setup(props) {
    const copied = ref(false);
    let timer: number | undefined;
    watch(copied, v => {
      if (!v) return;
      timer = window.setTimeout(() => (copied.value = false), 1500);
    });
    onScopeDispose(() => window.clearTimeout(timer));
    return () => (
      <UIconButton
        label={copied.value ? S.ask.copied : props.label}
        onClick={async () => {
          if (await copyText(props.text())) copied.value = true;
          else toast.error(S.toast.copyFailed);
        }}
      >
        {copied.value ? (
          <CheckIcon width={12} height={12} />
        ) : (
          <CopyIcon width={12} height={12} />
        )}
      </UIconButton>
    );
  }
});

/** 回答里的代码块，右上角一个复制。复制的是代码本身：`<pre>` 里的字，
 *  高亮拆出来的那些 span 不影响它；围栏内容末尾那个换行不带走 */
export const UCodeBlock = defineComponent({
  name: "UCodeBlock",
  setup(_, { slots }) {
    const pre = ref<HTMLPreElement | null>(null);
    return () => (
      <div class="codeblock">
        <pre ref={pre}>{slots.default?.()}</pre>
        <div class="absolute top-1 right-1">
          <UCopyButton
            label={S.ask.copyCode}
            text={() => (pre.value?.textContent ?? "").replace(/\n$/, "")}
          />
        </div>
      </div>
    );
  }
});
