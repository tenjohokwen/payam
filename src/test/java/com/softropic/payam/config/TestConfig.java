package com.softropic.payam.config;

import com.softropic.payam.email.api.MailManager;
import com.softropic.payam.utils.TestMailManager;
import com.softropic.payam.utils.sql.EntityFetchAsserter;
import com.softropic.payam.utils.sql.QueryRecorderListener;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import net.ttddyy.dsproxy.listener.ChainListener;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.logging.SystemOutQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.18"))
                .withDatabaseName(dbName)
                .withPassword("postgres")
                .withUsername("postgres")
                .withInitScript("sql/createSchema.sql");
    }

    @Bean
    @ConditionalOnProperty(name="log.database.spy", havingValue="true")
    DataSource spyDataSource(HikariConfig hikariConfig) {
        // https://jdbc-observations.github.io/datasource-proxy/docs/snapshot/user-guide/index.html
        final DataSource dataSourceSpy = new HikariDataSource(hikariConfig);
        SystemOutQueryLoggingListener listener = new SystemOutQueryLoggingListener();
        return ProxyDataSourceBuilder.create(dataSourceSpy)
                                     .name("DS-Proxy")
                                     .listener(listener)
                                     .multiline()
                                     .countQuery() //metric collection
                                     .logQueryToSysOut()
                                     .retrieveIsolation()
                                     .writeIsolation()
                                     .logSlowQueryToSysOut(1, TimeUnit.SECONDS)
                                     .build();
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @ConditionalOnProperty(name="datasource.container", havingValue="true")
    HikariConfig hikariConfig(JdbcConnectionDetails jdbcConnectionDetails) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(jdbcConnectionDetails.getPassword());
        hikariConfig.setUsername(jdbcConnectionDetails.getUsername());
        hikariConfig.setJdbcUrl(jdbcConnectionDetails.getJdbcUrl());
        return hikariConfig;
    }


    @Bean
    public EntityFetchAsserter createAsserter(EntityManagerFactory emf) {
        return new EntityFetchAsserter(emf);
    }

    private QueryExecutionListener provideListener() {
        final ChainListener chainListener = new ChainListener();
        chainListener.addListener(new DataSourceQueryCountListener());
        chainListener.addListener(new QueryRecorderListener());
        return chainListener;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "enable.test.mail", havingValue = "true")
    public MailManager mailManager() {
        return new TestMailManager();
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Do any additional configuration here
        return builder.build();
    }
}
