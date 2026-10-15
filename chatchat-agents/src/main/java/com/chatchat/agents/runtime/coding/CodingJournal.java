package com.chatchat.agents.runtime.coding;

import com.chatchat.common.runtime.coding.*;
import java.time.Instant;
import java.util.*;

/** Internal checkpoint format, not a new wire protocol or Evidence Store. One owner partition per journal. */
public final class CodingJournal {
    public int storageVersion = 1;
    public Map<String, Task> tasks = new LinkedHashMap<>();
    public Map<String, CodingTaskContract.Budget> budgets = new LinkedHashMap<>();

    public static final class Task {
        public CodingTaskContract contract;
        public CodingTaskOutcome outcome;
        public Lease lease = new Lease(null, Instant.EPOCH, 0);
        public Map<String, Operation> operations = new LinkedHashMap<>();
        public List<CodingExecutionEvent> events = new ArrayList<>();
    }
    public record Lease(String owner, Instant leaseUntil, long fencingToken) { }
    public enum OperationKind { MODEL, TOOL, CODE, ACCOUNTING }
    public static final class Operation {
        public OperationKind kind;
        public String intentSha256;
        public CodingTaskOutcome.Counters reservation;
        public CodingTaskOutcome.Counters actual = CodingBudgetMath.zero();
        public boolean meteringComplete;
        public boolean startIntent;
        public String executionHandle;
        public long startingFencingToken;
        public String stopProofRef;
    }
}
