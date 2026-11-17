package tech.seexun.jknow.web.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 业务域（源 utopia-server，Jackson 2 语系 + 全域 SNAKE_CASE）的序列化基座
 * （P1 任务 7 落程序内 bean；P1 任务 8 补 HTTP 出入口并恢复源命名契约）：
 *
 * <ul>
 *   <li><b>程序内</b>：com.fasterxml 的 ObjectMapper bean——Boot 4 的 MVC 序列化与自动
 *       装配走 Jackson 3（tools.jackson），com.fasterxml 的 ObjectMapper 不再有自动配置，
 *       业务代码 46 处注入（树构建/解析/请求体读取）补这一个实例；</li>
 *   <li><b>HTTP 出入口</b>（任务 8 修正）：业务 controller 的 {@code @RequestBody}/{@code
 *       ResponseEntity} 是 Jackson 2 语系——树模型（JsonNode，14 个文件，TokensController
 *       签发面在内）、SNAKE_CASE 命名契约（expires_at/created_at/top_k……源 application.yml
 *       全局策略，前端 utopia-web 按此读）、自定义反序列化器（SearchReq 的 serde 对等
 *       文案）。若依 Jackson 3 转换器认不得这些（Boot 4 实测 500/命名漂移成 camelCase），
 *       Spring Framework 7 又已删 Jackson 2 转换器——补 {@link BusinessJackson2HttpMessageConverter}，
 *       prepend 到转换器链最前：<b>业务类型走 Jackson 2（源契约），若依面（AjaxResult/
 *       SysXxx DTO，静态类型不在声明集内）照旧 Jackson 3</b>，互不越界。</li>
 * </ul>
 */
@Configuration
public class KnowledgeJacksonConfig implements WebMvcConfigurer {

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper() {
        // 源 application.yml 的全局 jackson 段逐键对齐：SNAKE_CASE（API 契约）+
        // fail-on-missing-creator-properties（缺必填 → serde 对等文案，AppExceptionHandler 译）；
        // 时间渲染 ISO 字符串（Boot 自动配置对 jackson2 的缺省 = WRITE_DATES_AS_TIMESTAMPS 关，
        // 终版双通实测 epoch 小数漂移后补齐）；未知字段忽略（Boot Jackson2ObjectMapperBuilder
        // 缺省关闭 FAIL_ON_UNKNOWN_PROPERTIES——对齐，别让多带字段 422）
        return JsonMapper.builder()
                .findAndAddModules()
                .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new BusinessJackson2HttpMessageConverter(jackson2ObjectMapper()));
    }

    /**
     * 业务 HTTP 出入口的 Jackson 2 转换器：声明集 = JsonNode 子树 + 业务包类型
     * （web.knowledge/store/search/extract/reason/common 非若依系）+ 业务 controller 的
     * 裸 Map/List 返回（若依面全走 AjaxResult/SysXxx DTO，静态类型不在集合内，不受影响）。
     */
    static class BusinessJackson2HttpMessageConverter extends AbstractHttpMessageConverter<Object> {

        private final com.fasterxml.jackson.databind.ObjectMapper mapper;

        BusinessJackson2HttpMessageConverter(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
            this.mapper = mapper;
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return businessType(clazz);
        }

        /**
         * 声明集（canWrite 拿到的是<b>运行时类</b>——ImmutableCollections$MapN 这类，
         * 匹配必须 isAssignableFrom）：JsonNode 子树 + Map/List 子型 + 业务包类型。
         * 若依系（AjaxResult 是 HashMap 子类、SysXxx DTO、TableDataInfo 分页信封）由包名
         * 前缀先行排除——若依 controller 面全走 AjaxResult/SysXxx/TableDataInfo
         * （web/controller 下无裸 Map/List 返回，已核对），业务 controller 的 Map.of/List
         * 返回由本转换器接走（源 snake 契约）。
         */
        static boolean businessType(Class<?> clazz) {
            String name = clazz.getName();
            if (name.startsWith("tech.seexun.jknow.common.core.domain.")
                    || name.startsWith("tech.seexun.jknow.common.core.page.")
                    || name.startsWith("tech.seexun.jknow.common.core.model.")
                    || name.startsWith("tech.seexun.jknow.web.controller.")) {
                return false;
            }
            return JsonNode.class.isAssignableFrom(clazz)
                    || java.util.Map.class.isAssignableFrom(clazz)
                    || java.util.List.class.isAssignableFrom(clazz)
                    || name.startsWith("tech.seexun.jknow.web.knowledge.")
                    || name.startsWith("tech.seexun.jknow.store.")
                    || name.startsWith("tech.seexun.jknow.search.")
                    || name.startsWith("tech.seexun.jknow.extract.")
                    || name.startsWith("tech.seexun.jknow.reason.")
                    || name.startsWith("tech.seexun.jknow.common.");
        }

        @Override
        protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
                throws HttpMessageNotReadableException, IOException {
            try {
                return mapper.readValue(inputMessage.getBody(), clazz);
            } catch (Exception e) {
                // cause 必须传：AppExceptionHandler 的 422 分支从 cause 链识别
                // MismatchedInputException/JsonParseException，译出 serde 对等文案
                throw new HttpMessageNotReadableException("业务请求体反序列化失败: " + e.getMessage(),
                        e, inputMessage);
            }
        }

        @Override
        protected void writeInternal(Object o, HttpOutputMessage outputMessage)
                throws HttpMessageNotWritableException, IOException {
            try {
                mapper.writeValue(outputMessage.getBody(), o);
            } catch (Exception e) {
                throw new HttpMessageNotWritableException("业务响应序列化失败: " + e.getMessage(), e);
            }
        }
    }
}
