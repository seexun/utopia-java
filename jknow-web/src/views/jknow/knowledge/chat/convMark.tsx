/* 左栏会话行的状态记号（对照旧仓 ui/index.tsx 的 ConvMark）：
   正在写（三点跳）/ 写完了还没看（实心点）/ 看过了（空心点）。 */
import { type PropType, defineComponent } from "vue";

export const UConvMark = defineComponent({
  name: "UConvMark",
  props: {
    state: {
      type: String as PropType<"rest" | "live" | "unread">,
      required: true
    }
  },
  setup(props) {
    return () => (
      // `flex` 不是 `inline-flex`：URow 把图标包在一个 span 里，inline 的盒会坐在
      // 那一行字的基线上、底下还垫着行高，于是整个记号偏下（三个点里没有字，
      // 基线就是底边，偏得最明显）。块级的盒没有行框，交给 URow 的 items-center 居中
      <span class="flex-c size-3.5  shrink-0  " aria-hidden>
        {props.state === "live" ? (
          <span class="dots">
            <span />
            <span />
            <span />
          </span>
        ) : (
          <span
            class={[
              "size-1.5  rounded-full border",
              props.state === "unread"
                ? "border-primary bg-primary"
                : "border-ink-2"
            ]}
          />
        )}
      </span>
    );
  }
});
