package com.softropic.payam.config;


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false) //why use `proxyBeanMethods = false`? see https://stackoverflow.com/questions/61266792/when-to-set-proxybeanmethods-to-false-in-springs-configuration
@EnableJpaAuditing
@EnableTransactionManagement
public class DataSourceConfig {

    @Bean
    @ConditionalOnProperty(name="log.database.spy", havingValue="false", matchIfMissing=true)
    DataSource dataSource(HikariConfig hikariConfig) {
        return new HikariDataSource(hikariConfig);
    }


    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @ConditionalOnProperty(name="datasource.container", havingValue="false", matchIfMissing=true)
    HikariConfig hikariConfig(DataSourceProperties dataSourceProperties) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPassword(dataSourceProperties.getPassword());
        hikariConfig.setUsername(dataSourceProperties.getUsername());
        hikariConfig.setJdbcUrl(dataSourceProperties.getUrl());
        return hikariConfig;
    }

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            // do nothing
        };
    }
    
}
