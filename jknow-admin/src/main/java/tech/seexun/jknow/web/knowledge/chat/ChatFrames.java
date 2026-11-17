package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.web.knowledge.chat.LiveRegistry.Frame;

/**
 * 聊天域共用的 JSON 面板与 SSE 帧助记（chat.rs 的 delta_event/error_event 同一形状）。
 * 帧名与错误信封是前端的契约（0004）：{@code {error, code}}——服务端不出显示文本，
 * code 给界面去 {@code err.*} 表里查措辞，英文原句留给日志、MCP 和不做本地化的客户端。
 */
@Component
public class ChatFrames {

    public final ObjectMapper mapper = new ObjectMapper();

    JsonNode parse(String data) {
        try {
            return mapper.readTree(data);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    /** delta 帧的 data：{@code {"text": …}}。 */
    public Frame deltaEvent(String text) {
        ObjectNode out = mapper.createObjectNode();
        out.put("text", text);
        return new Frame("delta", out.toString());
    }

    /** 与请求被拒同一个信封：error 是英文原句，code 给界面查措辞（0004）。 */
    public Frame errorEvent(String code, String message) {
        ObjectNode out = mapper.createObjectNode();
        out.put("error", message);
        out.put("code", code);
        return new Frame("error", out.toString());
    }
}
