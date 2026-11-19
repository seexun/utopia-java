/* 文档检索结果里的摘录高亮（对照旧仓 pages/Docs.tsx 的 Highlighted；切段
   reskin-b4 归一到 utils/highlightSegments——不区分大小写，全部命中都标）：
   命中词用警示琥珀 mark。 */
import { defineComponent } from "vue";
import { highlightSegments } from "@/utils/highlightSegments";

export const Highlighted = defineComponent({
  name: "Highlighted",
  props: {
    text: { type: String, required: true },
    q: { type: String, required: true }
  },
  setup(props) {
    return () => (
      <>
        {highlightSegments(props.text, [props.q]).map((seg, i) =>
          seg.hit ? (
            /* 命中词用警示琥珀：结果列表里一眼定位到匹配处 */
            <mark key={i} class="mark">
              {seg.text}
            </mark>
          ) : (
            <span key={i}>{seg.text}</span>
          )
        )}
      </>
    );
  }
});
