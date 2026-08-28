package com.vnsearch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {
    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    public void record(String subject, String action, String resource, String outcome, String detail) {
        log.info("audit subject={} action={} resource={} outcome={} detail={}",
                subject, action, resource, outcome, detail);
    }
}
