package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;

/**
 * 界面上的一步（tools.rs Step 逐语义）。kind 与 label（数据：查询、实体名、来源名）照旧；
 * detail 是给存量消息和不做本地化的读者的英文。界面按结构化字段用读者的语言说（0004）：
 * status、count/total、时间点，以及个别步骤自己的几样。<b>成功的一步也写 status: ok</b>
 * ——有 status 的按字段说，没有的（这之前存下的消息）退回 detail。
 */
public final class ToolStep {

    private final ChatFrames frames;
    private final ObjectNode step;

    private ToolStep(ChatFrames frames, ObjectNode step) {
        this.frames = frames;
        this.step = step;
    }

    public static ToolStep of(ChatFrames frames, String kind, Object label, String detail) {
        ObjectNode step = frames.mapper.createObjectNode();
        step.put("kind", kind);
        step.set("label", frames.mapper.valueToTree(label));
        step.put("detail", detail);
        step.put("status", "ok");
        return new ToolStep(frames, step);
    }

    /** ok | failed | not_found | invalid。 */
    public ToolStep status(String status) {
        step.put("status", status);
        return this;
    }

    public ToolStep count(long n) {
        step.put("count", n);
        return this;
    }

    /** 列出来的少于有的：count of total。 */
    public ToolStep total(long n) {
        step.put("total", n);
        return this;
    }

    /** 参数不对，这次调用什么也没做。param 是哪一个——参数名是数据，界面照写。 */
    public ToolStep invalid(String param) {
        status("invalid");
        step.put("param", param);
        return this;
    }

    /** 参数没给，或给的是空的。 */
    public ToolStep missing(String param) {
        invalid(param);
        step.put("missing", true);
        return this;
    }

    /** 截在上限、总数不知道（「40+」）。 */
    public ToolStep more(boolean more) {
        if (more) {
            step.put("more", true);
        }
        return this;
    }

    /**
     * 世界轴的时刻写作 valid_at，记录轴写 before 或 as_of。给了 before 就只写 before：
     * 那是人问的那一刻，as_of 是从它减了一微秒算出来的。<b>世界轴那个不能叫 at</b>：
     * 对话落库的每一步都有 at——这一步发生时正文已有多长（UTF-16 码元），回放靠它把
     * 轨迹穿回正文；写进来会被它覆盖。
     */
    public ToolStep moments(OffsetDateTime at, OffsetDateTime asOf, OffsetDateTime before) {
        if (at != null) {
            step.put("valid_at", TimeText.instant(at));
        }
        if (before != null) {
            step.put("before", TimeText.instant(before));
        } else if (asOf != null) {
            step.put("as_of", TimeText.instant(asOf));
        }
        return this;
    }

    public ToolStep field(String key, Object value) {
        step.set(key, frames.mapper.valueToTree(value));
        return this;
    }

    public JsonNode json() {
        return step;
    }
}
