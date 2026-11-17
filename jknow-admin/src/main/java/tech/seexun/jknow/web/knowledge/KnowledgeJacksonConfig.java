package tech.seexun.jknow.web.knowledge;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务域（源 utopia-server，Jackson 2 语系）的 {@link com.fasterxml.jackson.databind.ObjectMapper}
 * bean（P1 任务 7）：Boot 4 的 MVC 序列化与自动装配走 Jackson 3（tools.jackson），
 * com.fasterxml 的 ObjectMapper 不再有自动配置——业务代码 46 处注入（树构建/解析/
 * 请求体读取）补这一个程序内用的实例。HTTP 出入口的序列化仍归若依 Jackson 3 转换器。
 */
@Configuration
public class KnowledgeJacksonConfig {

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
