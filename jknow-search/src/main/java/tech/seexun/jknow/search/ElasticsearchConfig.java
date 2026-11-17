package tech.seexun.jknow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端装配（M1 任务 6）：低层 RestClient（elasticsearch-rest-client）+
 * JacksonJsonpMapper（独立 ObjectMapper——不共享 Boot 全局 SNAKE_CASE 实例，
 * 索引文档字段名由 ChunkDoc 的 @JsonProperty 钉死）。
 *
 * <p>地址经 {@code jknow.elasticsearch.url}（缺省 http://localhost:9200，
 * 环境变量 JKNOW_ES_URL 覆盖——docker run -p 9200:9200 的本机单节点）。
 * ES 不可达不阻止上下文启动（客户端懒连接）；用到它的检索路径译 500、启动对账告警吞掉。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    RestClient restClient(@Value("${jknow.elasticsearch.url:http://localhost:9200}") String url) {
        return RestClient.builder(HttpHost.create(url)).build();
    }

    @Bean
    ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper(new ObjectMapper())));
    }

    @Bean
    EsChunkIndex esChunkIndex(ElasticsearchClient client) {
        return new EsChunkIndex(client);
    }
}
