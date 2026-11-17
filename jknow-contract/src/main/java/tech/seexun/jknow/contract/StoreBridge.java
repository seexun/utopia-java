package tech.seexun.jknow.contract;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tech.seexun.jknow.store.ResolutionService;

/**
 * store 桥（M3 任务 11，{@code store:} 步的 Java 侧通道）：以最小 Spring 上下文直调
 * utopia-store 的心脏 bean——M4 之前 Java 侧的裁决写路径（如 decide_review 合并）只有
 * store 层（m3-plan 前置 3 的 M3/M4 边界），HTTP 端点属 M4，剧本经这里才能在
 * {@code --writes java} 轮驱动 Java 自己的合并代码（切流预演口径）。
 *
 * <p><b>装配面</b>（plain {@link AnnotationConfigApplicationContext}，不起 Boot）：
 * DataSource（与 {@link Snapshot#openConnection} 同一套环境变量/缺省）+ MyBatis
 * （mapper XML 从 utopia-store 的 classpath:mapper/ 装载——utopia-contract 依赖
 * utopia-store，资源随 jar 可见）+ 事务管理器（store 的 @Transactional/@Transactional
 * REQUIRES_NEW 都走它）+ 组件扫描 tech.seexun.jknow.store（@Service 全家）。
 * 凭据封印（SecretsConfiguration→SecretsBox）随扫描装配：data_dir 与双栈共享
 * （UTOPIA_DATA_DIR，缺省对齐 dev 形态指 Rust 参照实例数据目录）。
 *
 * <p><b>惰性单例</b>：首个 {@code store:} 步才起上下文（一次 2-4s）；连接池用
 * {@link DriverManagerDataSource}（无池化）——剧本的 store 调用是低频一次性操作，
 * 不值得为它养一个 Hikari 池。
 */
public final class StoreBridge {

    private static volatile ApplicationContext context;

    private StoreBridge() {
    }

    /** 桥的最小配置：数据源 + MyBatis + 事务 + store 组件扫描（见类注）。 */
    @Configuration
    @ComponentScan("tech.seexun.jknow.store")
    @MapperScan("tech.seexun.jknow.store.mapper")
    @EnableTransactionManagement
    static class BridgeConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DataSource dataSource() {
            // 与 Snapshot.openConnection 同源的环境变量与缺省（共享栈 1517/utopia/utopia）
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(env("UTOPIA_JDBC_URL", "jdbc:postgresql://localhost:1517/utopia"));
            ds.setUsername(env("UTOPIA_DB_USERNAME", "utopia"));
            ds.setPassword(env("UTOPIA_DB_PASSWORD", "utopia"));
            ds.setDriverClassName("org.postgresql.Driver");
            return ds;
        }

        @Bean
        PlatformTransactionManager txManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*.xml"));
            // 与 server 的 application.yml mybatis 段同款配置：下划线折驼峰 + store 的
            // 类型处理器包（mapper XML 里的 UUID/jsonb/vector 列全靠它——漏了连 XML 都解析不过）
            org.apache.ibatis.session.Configuration cfg =
                    new org.apache.ibatis.session.Configuration();
            cfg.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(cfg);
            factory.setTypeHandlersPackage("tech.seexun.jknow.store.typehandler");
            return factory.getObject();
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 首次调用装载上下文（惰性单例）。 */
    private static ApplicationContext context() {
        ApplicationContext ctx = context;
        if (ctx == null) {
            synchronized (StoreBridge.class) {
                ctx = context;
                if (ctx == null) {
                    // utopia.data-dir：SecretsConfiguration 读它找 secret.key——与双栈共享
                    //（缺省对齐 application-dev.yml 的 dev 形态；Recorder 约定从仓库根启动）
                    System.setProperty("utopia.data-dir",
                            System.getProperty("utopia.data-dir", env("UTOPIA_DATA_DIR", "../utopia-0.1.0-rc9/data")));
                    ctx = new AnnotationConfigApplicationContext(BridgeConfig.class);
                    context = ctx;
                }
            }
        }
        return ctx;
    }

    /**
     * 操作分发（{@code store:} 步的 op）。异常原样上抛——调用方（ScenarioRunner）按
     * ScenarioFailure 包装中止剧本。
     */
    public static void call(String op, Map<String, String> args) {
        switch (op) {
            case "decide_review" -> {
                // ResolutionService.decideReview：人工定夺（merge=合并方向+十步事务；keep=保留）
                UUID kb = UUID.fromString(args.get("kb_id"));
                UUID review = UUID.fromString(args.get("review_id"));
                UUID user = UUID.fromString(args.get("user_id"));
                String rationale = args.get("rationale");
                context().getBean(ResolutionService.class)
                        .decideReview(kb, review, args.getOrDefault("action", "merge"), user, rationale);
            }
            default -> throw new IllegalArgumentException("不支持的 store 操作: " + op
                    + "（当前支持 decide_review）");
        }
    }
}
