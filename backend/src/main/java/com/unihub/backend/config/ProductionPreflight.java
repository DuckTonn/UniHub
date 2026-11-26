package com.unihub.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionPreflight {
    @Bean
    @DependsOn("flywayInitializer")
    SmartInitializingSingleton verifyProductionReadiness(JdbcTemplate jdbc,
                                               @Value("${spring.application.frontend-url}") String origin,
                                               @Value("${jwt.access-secret}") String accessSecret,
                                               @Value("${jwt.refresh-secret}") String refreshSecret) {
        return () -> {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("FRONTEND_URL must be one HTTPS origin");
            }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || !uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("FRONTEND_URL must be one HTTPS origin");
            }
            if (accessSecret.equals(refreshSecret)) {
                throw new IllegalStateException("Production JWT keys must be distinct");
            }

            // These identities were provisioned with published example passwords by V2, V10 or V11.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users WHERE upper(status) = 'ACTIVE' AND (
                        lower(email) IN ('admin@unihub.edu.vn','staff@unihub.edu.vn',
                            '21127001@student.unihub.edu.vn','21127002@student.unihub.edu.vn')
                        OR lower(email) ~ '^221270(0[1-9]|10)@student[.]unihub[.]edu[.]vn$'
                    ))
                    """, Boolean.class))) {
                throw new IllegalStateException("Disable seeded demo users before serving production traffic");
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN' AND upper(status) = 'ACTIVE')
                    """, Boolean.class))) {
                throw new IllegalStateException("Provision an active production administrator before serving traffic");
            }
        };
    }
}
