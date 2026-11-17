package tech.seexun.jknow.extract;

/**
 * JSON 取块与补括号（lib.rs:65-131 逐语义）：三个纯函数被 open/time/align/phrase_align
 * 的解析器共用——「从 LLM 回复里稳健地取出 JSON」是同一个问题，不该各抄一份。
 */
public final class JsonFuns {

    private JsonFuns() {
    }

    /**
     * lib.rs:65-79 json_text：回复里可能是 JSON 的那段——切掉推理模型的思考过程
     * （{@code </think>} 之后，#690）与代码围栏。前后的废话留给调用方按括号定位。
     */
    public static String jsonText(String raw) {
        String text = raw.strip();
        int pos = text.lastIndexOf("</think>");
        if (pos >= 0) {
            text = text.substring(pos + "</think>".length()).strip();
        }
        // trim_end_matches("```")：结尾的围栏记号（可重复）去掉
        String stripped = null;
        if (text.startsWith("```json")) {
            stripped = text.substring("```json".length());
        } else if (text.startsWith("```")) {
            stripped = text.substring("```".length());
        }
        if (stripped != null) {
            int end = stripped.length();
            while (end >= 3 && stripped.substring(end - 3, end).equals("```")) {
                end -= 3;
            }
            return stripped.substring(0, end);
        }
        return text;
    }

    /**
     * lib.rs:82-90 json_block：从 LLM 回复中取 JSON 块（第一个 {@code {} 到最后一个
     * {@code }}）。取不到抛 {@link IllegalArgumentException}（Rust bail「No JSON found
     * in LLM reply」）。
     */
    public static String jsonBlock(String raw) {
        String cleaned = jsonText(raw);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON found in LLM reply");
        }
        return cleaned.substring(start, end + 1);
    }

    /**
     * lib.rs:95-131 close_brackets：把 head 后面缺的括号补上。字符串字面量里的括号不算
     * ——{@code "a[b"} 不是一个开括号。返回 null = 结构本身就不对（括号已经多了，或断在
     * 字符串中间），不是「没写完」。
     */
    public static String closeBrackets(String head) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[' || c == '{') {
                stack.push(c);
            } else if (c == ']' || c == '}') {
                // Rust stack.pop() != Some(want)：空栈也是结构不对（多了闭括号），返回 null
                char want = c == ']' ? '[' : '{';
                if (stack.isEmpty() || stack.pop() != want) {
                    return null;
                }
            }
        }
        if (inStr) {
            return null; // 断在字符串中间，这一截不可用
        }
        StringBuilder out = new StringBuilder(head);
        while (!stack.isEmpty()) {
            out.append(stack.pop() == '[' ? ']' : '}');
        }
        return out.toString();
    }
}
