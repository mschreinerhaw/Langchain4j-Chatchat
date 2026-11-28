package com.chatchat.api.enterprise.service;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class TokenQuotaLedger {
    public enum PoolType { USER, ROLE }
    public enum Decision { ADMITTED, DUPLICATE, QUOTA_EXHAUSTED, TASK_BUDGET_EXHAUSTED, TASK_BUDGET_BUSY, BILLING_ROLE_REQUIRED, OUTPUT_BOUND_REQUIRED }
    public record Pool(Long limit, long period, long spent, long reserved) {}
    private record Binding(String user, String role) {}
    private record Charge(String user, String run, String task, String state, Long actual) {}
    private final JdbcTemplate jdbc;
    private final SysUserRepository users;
    private final SysRoleRepository roles;
    private final SysUserRoleRepository memberships;

    public static String nativeReceiptId(String sessionId, String requestId) {
        return "native-" + com.chatchat.api.assets.UserAssetService.hash(
            (sessionId+"\0"+requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public TokenQuotaLedger(JdbcTemplate jdbc, SysUserRepository users, SysRoleRepository roles,
                            SysUserRoleRepository memberships) {
        this.jdbc = jdbc;
        this.users = users;
        this.roles = roles;
        this.memberships = memberships;
    }

    @PostConstruct
    void initialize() {
        String product = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        boolean mysql = "MySQL".equalsIgnoreCase(product) || "MariaDB".equalsIgnoreCase(product);
        jdbc.execute("""
            create table if not exists enterprise_token_pool (
              tenant_id varchar(64) not null, pool_type varchar(8) not null, principal_id varchar(64) not null,
              token_limit bigint, period_id bigint not null, primary key (tenant_id,pool_type,principal_id)
            )
            """);
        jdbc.execute("""
            create table if not exists enterprise_token_binding (
              tenant_id varchar(64) not null, run_id varchar(256) not null, user_id varchar(64) not null,
              role_id varchar(64) not null, primary key(tenant_id,run_id)
            )
            """);
        jdbc.execute("""
            create table if not exists enterprise_token_charge (
              tenant_id varchar(64) not null, request_id varchar(128) not null, task_id varchar(256) not null,
              run_id varchar(256) not null, user_id varchar(64) not null, role_id varchar(64) not null,
              user_period bigint not null, role_period bigint not null, held_tokens bigint not null,
              actual_tokens bigint, state varchar(12) not null, created_ms bigint not null,
              primary key(tenant_id,request_id)%s
            )
            """.formatted(mysql ? ", key idx_token_charge_user (tenant_id,user_id,user_period),"
                + " key idx_token_charge_role (tenant_id,role_id,role_period)" : ""));
        if (!mysql) {
            jdbc.execute("create index if not exists idx_token_charge_user on enterprise_token_charge(tenant_id,user_id,user_period)");
            jdbc.execute("create index if not exists idx_token_charge_role on enterprise_token_charge(tenant_id,role_id,role_period)");
            jdbc.execute("""
                create index if not exists idx_token_charge_native_task
                on enterprise_token_charge(tenant_id,run_id,task_id,user_id,state)
                """);
        } else {
            var exists = jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema=database() and table_name='enterprise_token_charge'
                  and index_name='idx_token_charge_native_task'
                """, Integer.class);
            if (exists != null && exists == 0) {
                try {
                    jdbc.execute("""
                        create index idx_token_charge_native_task
                        on enterprise_token_charge(tenant_id,run_id,task_id,user_id,state)
                        """);
                } catch (org.springframework.dao.DataAccessException concurrent) {
                    if (!(concurrent.getMostSpecificCause() instanceof java.sql.SQLException sql)
                            || sql.getErrorCode() != 1061) throw concurrent;
                }
            }
        }
    }

    @Transactional
    public void bind(KernelDataScope scope) {
        lockTenant(scope.tenantId());
        binding(scope);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision reserve(KernelDataScope scope, String taskId, String requestId, long inputBound, Long outputBound) {
        return reserve(scope, taskId, requestId, inputBound, outputBound, -1, -1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision reserve(KernelDataScope scope, String taskId, String requestId, long inputBound, Long outputBound,
                            long maxTaskCalls, long maxTaskTokens) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,128}") || inputBound < 0
                || inputBound > Integer.MAX_VALUE || outputBound != null && (outputBound < 1 || outputBound > Integer.MAX_VALUE))
            throw new IllegalArgumentException("Invalid model reservation");
        lockTenant(scope.tenantId());
        var prior = charge(scope.tenantId(), requestId);
        if (prior != null) {
            requireChargeScope(prior, scope, taskId);
            return Decision.DUPLICATE;
        }
        var binding = binding(scope);
        if (binding.role().isEmpty() ? hasRoleLimits(scope.tenantId()) : !activeRole(scope, binding.role()))
            return Decision.BILLING_ROLE_REQUIRED;
        var user = admissionPool(scope.tenantId(), PoolType.USER, scope.userId());
        var role = binding.role().isEmpty() ? new Pool(null, 0, 0, 0) : admissionPool(scope.tenantId(), PoolType.ROLE, binding.role());
        if (outputBound == null && (user.limit() != null || role.limit() != null || maxTaskTokens >= 0)) return Decision.OUTPUT_BOUND_REQUIRED;
        long hold = inputBound + (outputBound == null ? 0 : outputBound);
        if (!fits(user, hold) || !fits(role, hold)) return Decision.QUOTA_EXHAUSTED;
        if (maxTaskCalls >= 0 || maxTaskTokens >= 0) {
            var denied = taskLimit(scope, taskId, hold, maxTaskCalls, maxTaskTokens);
            if (denied != null) return denied;
        }
        jdbc.update("""
            insert into enterprise_token_charge(tenant_id,request_id,task_id,run_id,user_id,role_id,
            user_period,role_period,held_tokens,actual_tokens,state,created_ms) values (?,?,?,?,?,?,?,?,?,null,'HELD',?)
            """, scope.tenantId(), requestId, taskId, scope.runId(), scope.userId(), binding.role(), user.period(),
            role.period(), hold, System.currentTimeMillis());
        return Decision.ADMITTED;
    }

    private Decision taskLimit(KernelDataScope scope, String taskId, long hold, long maxCalls, long maxTokens) {
        // The same transaction and tenant lock protect quota and task reservations.
        return jdbc.queryForObject("""
            select count(*),
                   coalesce(sum(case when state='HELD' then 1 else 0 end),0),
                   coalesce(sum(case when state='HELD' then held_tokens else 0 end),0),
                   coalesce(sum(case when state='UNKNOWN' then held_tokens else coalesce(actual_tokens,0) end),0)
            from enterprise_token_charge
            where tenant_id=? and run_id=? and task_id=? and user_id=? and state<>'RELEASED'
            """, (row, index) -> {
                long calls = row.getLong(1), pendingCalls = row.getLong(2);
                long pendingTokens = row.getLong(3), spentOrUnknown = row.getLong(4);
                var limit = new Pool(maxTokens >= 0 ? maxTokens : null, 0, spentOrUnknown, pendingTokens);
                if (maxCalls >= 0 && calls - pendingCalls >= maxCalls ||
                        !fits(new Pool(limit.limit(), 0, limit.spent(), 0), hold)) return Decision.TASK_BUDGET_EXHAUSTED;
                if (maxCalls >= 0 && calls >= maxCalls || !fits(limit, hold)) return Decision.TASK_BUDGET_BUSY;
                return null;
            }, scope.tenantId(), scope.runId(), taskId, scope.userId());
    }

    /** Call using the durable model receipt. A transport failure alone does not prove zero use. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(KernelDataScope scope, String taskId, String requestId, Long providerTokens, boolean neverDispatched) {
        settleCharge(scope, taskId, requestId, providerTokens, neverDispatched, false);
    }

    /** The signed broker can close a pre-dispatch request while admission is still in flight. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleNative(KernelDataScope scope, String taskId, String requestId, Long providerTokens, boolean neverDispatched) {
        settleCharge(scope, taskId, requestId, providerTokens, neverDispatched, true);
    }

    private void settleCharge(KernelDataScope scope, String taskId, String requestId, Long providerTokens,
                              boolean neverDispatched, boolean fenceLateAdmission) {
        if (providerTokens != null && providerTokens < 0) throw new IllegalArgumentException("Invalid model usage");
        lockTenant(scope.tenantId());
        var prior = charge(scope.tenantId(), requestId);
        if (prior == null) {
            if (fenceLateAdmission && neverDispatched && providerTokens == null) {
                jdbc.update("""
                    insert into enterprise_token_charge(tenant_id,request_id,task_id,run_id,user_id,role_id,
                    user_period,role_period,held_tokens,actual_tokens,state,created_ms)
                    values (?,?,?,?,?,'',0,0,0,null,'RELEASED',?)
                    """, scope.tenantId(), requestId, taskId, scope.runId(), scope.userId(), System.currentTimeMillis());
            }
            return;
        }
        requireChargeScope(prior, scope, taskId);
        if ("SETTLED".equals(prior.state())) {
            if (providerTokens != null && !providerTokens.equals(prior.actual()))
                throw new IllegalStateException("Provider observation differs from settled charge");
            return;
        }
        if ("RELEASED".equals(prior.state())) return;
        String state = providerTokens != null ? "SETTLED" : neverDispatched ? "RELEASED" : "UNKNOWN";
        if (state.equals(prior.state())) return;
        jdbc.update("update enterprise_token_charge set actual_tokens=?,state=? where tenant_id=? and request_id=?",
            providerTokens, state, scope.tenantId(), requestId);
    }

    @Transactional
    public Pool configure(String tenant, PoolType type, String principal, Long limit, boolean reset) {
        if (limit != null && limit < 0) throw new IllegalArgumentException("Invalid token limit");
        lockTenant(tenant);
        boolean owned = type == PoolType.USER
            ? users.findById(principal).filter(user -> tenant.equals(user.getTenantId())).isPresent()
            : roles.findById(principal).filter(role -> tenant.equals(role.getTenantId())).isPresent();
        if (!owned) throw new IllegalArgumentException("Pool owner is outside tenant");
        var previous = configuredPool(tenant, type, principal);
        long period = reset ? Math.addExact(previous.period(), 1) : previous.period();
        if (jdbc.update("update enterprise_token_pool set token_limit=?,period_id=? where tenant_id=? and pool_type=? and principal_id=?",
                limit, period, tenant, type.name(), principal) == 0) {
            jdbc.update("insert into enterprise_token_pool(tenant_id,pool_type,principal_id,token_limit,period_id) values(?,?,?,?,?)",
                tenant, type.name(), principal, limit, period);
        }
        return pool(tenant, type, principal);
    }

    public Pool pool(String tenant, PoolType type, String principal) {
        return withConsumption(tenant, type, principal, configuredPool(tenant, type, principal));
    }

    private Pool admissionPool(String tenant, PoolType type, String principal) {
        var config = configuredPool(tenant, type, principal);
        return config.limit() == null ? config : withConsumption(tenant, type, principal, config);
    }

    private Pool configuredPool(String tenant, PoolType type, String principal) {
        return jdbc.query("select token_limit,period_id from enterprise_token_pool where tenant_id=? and pool_type=? and principal_id=?",
            (row, index) -> new Pool(row.getObject(1, Long.class), row.getLong(2), 0, 0), tenant, type.name(), principal)
            .stream().findFirst().orElse(new Pool(null, 0, 0, 0));
    }

    private Pool withConsumption(String tenant, PoolType type, String principal, Pool config) {
        String owner = type == PoolType.USER ? "user_id" : "role_id";
        String period = type == PoolType.USER ? "user_period" : "role_period";
        return jdbc.queryForObject("""
            select coalesce(sum(actual_tokens),0),coalesce(sum(case when state in ('HELD','UNKNOWN') then held_tokens else 0 end),0)
            from enterprise_token_charge where tenant_id=? and %s=? and %s=?
            """.formatted(owner, period), (row, index) -> new Pool(config.limit(), config.period(), row.getLong(1), row.getLong(2)),
            tenant, principal, config.period());
    }

    private Binding binding(KernelDataScope scope) {
        var current = jdbc.query("select user_id,role_id from enterprise_token_binding where tenant_id=? and run_id=?",
            (row, index) -> new Binding(row.getString(1), row.getString(2)), scope.tenantId(), scope.runId())
            .stream().findFirst();
        if (current.isPresent()) {
            var binding = current.get();
            if (!binding.user().equals(scope.userId()))
                throw new SecurityException("Billing run is outside identity scope");
            return binding;
        }
        String role = users.findById(scope.userId()).filter(user -> scope.tenantId().equals(user.getTenantId()))
            .orElseThrow(() -> new SecurityException("Billing user is outside tenant")).getTokenBillingRoleId();
        role = role == null ? "" : role;
        jdbc.update("insert into enterprise_token_binding(tenant_id,run_id,user_id,role_id) values(?,?,?,?)",
            scope.tenantId(), scope.runId(), scope.userId(), role);
        return new Binding(scope.userId(), role);
    }

    private boolean activeRole(KernelDataScope scope, String role) {
        Instant now = Instant.now();
        return roles.findById(role).filter(value -> scope.tenantId().equals(value.getTenantId()) && value.activeAt(now)).isPresent()
            && memberships.findByUserId(scope.userId()).stream()
                .anyMatch(value -> scope.tenantId().equals(value.getTenantId()) && role.equals(value.getRoleId()));
    }

    private boolean hasRoleLimits(String tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select case when count(*)>0 then true else false end from enterprise_token_pool where tenant_id=? and pool_type='ROLE' and token_limit is not null",
            Boolean.class, tenant));
    }

    private Charge charge(String tenant, String request) {
        return jdbc.query("select user_id,run_id,task_id,state,actual_tokens from enterprise_token_charge where tenant_id=? and request_id=?",
            (row, index) -> new Charge(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getObject(5, Long.class)),
            tenant, request).stream().findFirst().orElse(null);
    }

    private static boolean fits(Pool pool, long hold) {
        return pool.limit() == null || pool.spent() <= pool.limit() && pool.reserved() <= pool.limit() - pool.spent()
            && hold <= pool.limit() - pool.spent() - pool.reserved();
    }

    private static void requireChargeScope(Charge charge, KernelDataScope scope, String task) {
        if (!Objects.equals(charge.user(), scope.userId()) || !Objects.equals(charge.run(), scope.runId()) || !Objects.equals(charge.task(), task))
            throw new SecurityException("Billing request is outside task scope");
    }

    private void lockTenant(String tenant) {
        jdbc.queryForObject("select id from sys_tenant where id=? for update", String.class, tenant);
    }
}
