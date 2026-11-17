package com.ureca.snac.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 즉시 발행의 상태 표시만 쓰는 작은 커넥션 풀
 * <p>
 * defaultCandidate = false : 이 빈 때문에 기본 DataSource 자동 구성이 물러나지 않게 한다. JPA는 기본 풀을 그대로 쓴다
 * 접속 정보는 기본 풀과 같은 DB라 spring.datasource 값을 그대로 쓴다
 */
@Configuration(proxyBeanMethods = false)
public class OutboxPushDataSourceConfig {

    public static final String PUSH_STATUS_DATA_SOURCE = "outboxPushStatusDataSource";

    @Qualifier(PUSH_STATUS_DATA_SOURCE)
    @Bean(name = PUSH_STATUS_DATA_SOURCE, defaultCandidate = false)
    public HikariDataSource outboxPushStatusDataSource(
            DataSourceProperties properties,
            @Value("${outbox.push.status-pool-size}") int maxPoolSize
    ) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setMaximumPoolSize(maxPoolSize);
        dataSource.setPoolName("outbox-push-status");
        return dataSource;
    }
}
