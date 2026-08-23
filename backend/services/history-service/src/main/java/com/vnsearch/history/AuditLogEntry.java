package com.vnsearch.history;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "audit_log")
public record AuditLogEntry(
        @Id String id,
        Instant occurredAt,
        String subject,
        String action,
        String resource,
        String outcome,
        String detail) {
}
