package com.chatchat.mcpserver.python;

import jakarta.persistence.*;

/** A permanent operation tombstone, serialized with dispatch by the existing task fence. */
@Entity
@Table(name="mcp_python_managed_cancellation")
class PythonManagedCancellationRecord {
    @Id @Column(length=64) String id;
    @Column(name="operation_id",length=128,nullable=false) String operationId;
    @Column(name="fencing_token",nullable=false) long fencingToken;
    @Column(name="receipt_json",columnDefinition="text",nullable=false) String receiptJson;
    @Column(name="manifest_json",columnDefinition="text",nullable=false) String manifestJson;
}
