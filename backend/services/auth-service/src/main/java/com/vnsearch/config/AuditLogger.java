package com.vnsearch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final JdbcClient jdbc;

    public AuditLogger(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbc = jdbcProvider.getIfAvailable();
    }

    public void record(String subject, String action, String resource, String outcome, String detail) {
        log.info("audit subject={} action={} resource={} outcome={}", subject, action, resource, outcome);
        if (jdbc == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO auth.audit_log (subject, action, resource, outcome, detail)
                VALUES (:subject, :action, :resource, :outcome, :detail)
                """)
                .param("subject", subject)
                .param("action", action)
                .param("resource", resource)
                .param("outcome", outcome)
                .param("detail", detail)
                .update();
    }
}
