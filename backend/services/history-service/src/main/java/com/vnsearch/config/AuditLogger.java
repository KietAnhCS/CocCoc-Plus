package com.vnsearch.config;

import com.vnsearch.history.AuditLogEntry;
import com.vnsearch.history.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final AuditLogRepository repository;

    public AuditLogger(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String subject, String action, String resource, String outcome, String detail) {
        log.info("audit subject={} action={} resource={} outcome={}", subject, action, resource, outcome);
        repository.save(new AuditLogEntry(null, Instant.now(), subject, action, resource, outcome, detail));
    }
}
