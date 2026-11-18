/* 文档检索结果里的摘录高亮（对照旧仓 pages/Docs.tsx 的 Highlighted，逐字移植）：
   摘录内高亮命中词（不区分大小写，全部命中都标），命中词用警示琥珀 mark。 */
import { defineComponent } from "vue";

export const Highlighted = defineComponent({
  name: "Highlighted",
  props: {
    text: { type: String, required: true },
    q: { type: String, required: true }
  },
  setup(props) {
    return () => {
      const needle = props.q.trim();
      if (!needle) return <>{props.text}</>;
      const parts = props.text.split(
        new RegExp(`(${needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")})`, "ig")
      );
      return (
        <>
          {parts.map((p, i) =>
            p.toLowerCase() === needle.toLowerCase() ? (
              /* 命中词用警示琥珀：结果列表里一眼定位到匹配处 */
              <mark key={i} class="mark">
                {p}
              </mark>
            ) : (
              <span key={i}>{p}</span>
            )
          )}
        </>
      );
    };
  }
});
