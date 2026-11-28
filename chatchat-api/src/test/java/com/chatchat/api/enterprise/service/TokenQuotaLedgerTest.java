package com.chatchat.api.enterprise.service;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.enterprise.entity.identity.SysRole;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.identity.SysUserRole;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static com.chatchat.api.enterprise.service.TokenQuotaLedger.Decision.*;
import static com.chatchat.api.enterprise.service.TokenQuotaLedger.PoolType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenQuotaLedgerTest {
    private static final String TENANT = "tenant";
    private static final String ROLE_ID = "analysts";
    private final KernelDataScope first = scope("one");
    private final KernelDataScope second = scope("two");
    private TokenQuotaLedger ledger;
    private TransactionTemplate transaction;
    private SysUserRepository users;
    private JdbcTemplate postgres;
    private String postgresSchema;

    @BeforeEach
    void initialize() {
        var ds = testDataSource();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table sys_tenant(id varchar(64) primary key)");
        jdbc.update("insert into sys_tenant values (?)", TENANT);
        users = mock(SysUserRepository.class);
        var roles = mock(SysRoleRepository.class);
        var memberships = mock(SysUserRoleRepository.class);
        var role = new SysRole();
        role.setId(ROLE_ID);
        role.setTenantId(TENANT);
        role.setStatus("enabled");
        when(roles.findById(ROLE_ID)).thenReturn(Optional.of(role));
        for (var scope : List.of(first, second)) {
            var user = new SysUser();
            user.setId(scope.userId());
            user.setTenantId(TENANT);
            user.setTokenBillingRoleId(ROLE_ID);
            when(users.findById(scope.userId())).thenReturn(Optional.of(user));
            var membership = new SysUserRole();
            membership.setTenantId(TENANT);
            membership.setRoleId(ROLE_ID);
            when(memberships.findByUserId(scope.userId())).thenReturn(List.of(membership));
        }
        ledger = new TokenQuotaLedger(jdbc, users, roles, memberships);
        ledger.initialize();
    }

    private DriverManagerDataSource testDataSource() {
        String url = System.getProperty("quota.test.postgres.url", "");
        if (url.isBlank()) return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var root = new DriverManagerDataSource(url, System.getenv("QUOTA_TEST_POSTGRES_USER"), System.getenv("QUOTA_TEST_POSTGRES_PASSWORD"));
        postgres = new JdbcTemplate(root);
        postgresSchema = "quota_test_" + UUID.randomUUID().toString().replace("-", "");
        postgres.execute("create schema " + postgresSchema);
        return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + postgresSchema,
            root.getUsername(), root.getPassword());
    }

    @AfterEach
    void removeTestSchema() {
        if (postgres != null) postgres.execute("drop schema " + postgresSchema + " cascade");
    }

    @Test
    void concurrentUsersCannotOverspendTheSameRoleAndUnknownUsageRemainsReserved() throws Exception {
        inTransaction(() -> ledger.configure(TENANT, ROLE, ROLE_ID, 1000L, false));
        inTransaction(() -> ledger.configure(TENANT, USER, first.userId(), 900L, false));
        var start = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var one = threads.submit(() -> { start.await(); return reserve(first, "one"); });
            var two = threads.submit(() -> { start.await(); return reserve(second, "two"); });
            start.countDown();
            var oneDecision = one.get(5, TimeUnit.SECONDS);
            assertThat(List.of(oneDecision, two.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(ADMITTED, QUOTA_EXHAUSTED);
            var winner = oneDecision == ADMITTED ? first : second;
            String id = winner.userId();
            assertThat(reserve(winner, id)).isEqualTo(DUPLICATE);
            settle(winner, id, null, false);
            assertThat(ledger.pool(TENANT, ROLE, ROLE_ID)).isEqualTo(new TokenQuotaLedger.Pool(1000L, 0, 0, 800));
            settle(winner, id, 125L, false);
            settle(winner, id, 125L, false);
            assertThat(ledger.pool(TENANT, ROLE, ROLE_ID)).isEqualTo(new TokenQuotaLedger.Pool(1000L, 0, 125, 0));
            assertThat(ledger.pool(TENANT, USER, winner.userId()).spent()).isEqualTo(125);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void directInvocationAndNativeAdmissionShareRolePoolAndOnlyProviderUsageSettles() throws Exception {
        var request = ChatRequest.builder().messages(UserMessage.from("hello")).build();
        long roleLimit = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 656L;
        inTransaction(() -> ledger.configure(TENANT, ROLE, ROLE_ID, Math.max(800L, roleLimit), false));
        assertThat(reserve(first, "native")).isEqualTo(ADMITTED);
        var identities = mock(EnterpriseAdminService.class);
        when(identities.isActiveIdentity(TENANT, second.userId())).thenReturn(true);
        var direct = new QuotaGovernedDirectModelInvocation(ledger, users, identities, mock(AgentChatModelResolver.class));
        var calls = new AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", response -> {
            calls.incrementAndGet();
            byte[] body = """
                {"id":"response","object":"chat.completion","created":1,"model":"fixture",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":21,"completion_tokens":4,"total_tokens":25}}
                """.getBytes(StandardCharsets.UTF_8);
            try (response) {
                response.getResponseHeaders().set("Content-Type", "application/json");
                response.sendResponseHeaders(200, body.length);
                response.getResponseBody().write(body);
            }
        });
        upstream.start();
        try {
            var provider = OpenAiChatModel.builder().baseUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                .apiKey("fixture-key").modelName("fixture").maxTokens(200).maxRetries(0).build();
            assertThatThrownBy(() -> inTransaction(() -> direct.chat(second, "test", provider, request, 200)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("429");
            assertThat(calls).hasValue(0);
            settle(first, "native", 125L, false);
            assertThat(inTransaction(() -> direct.chat(second, "test", provider, request, 200)).aiMessage().text()).isEqualTo("done");
            assertThat(ledger.pool(TENANT, ROLE, ROLE_ID)).isEqualTo(new TokenQuotaLedger.Pool(Math.max(800L, roleLimit), 0, 150, 0));
            assertThat(ledger.pool(TENANT, USER, second.userId()).spent()).isEqualTo(25);
            when(identities.isActiveIdentity(TENANT, second.userId())).thenReturn(false);
            assertThatThrownBy(() -> inTransaction(() -> direct.chat(second, "test", provider, request, 200)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
            assertThat(calls).hasValue(1);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void aRunKeepsItsBillingRoleAcrossDifferentModelPaths() {
        inTransaction(() -> ledger.configure(TENANT, ROLE, ROLE_ID, 2000L, false));
        assertThat(reserve(first, "classify")).isEqualTo(ADMITTED);
        users.findById(first.userId()).orElseThrow().setTokenBillingRoleId("changed-role");
        assertThat(inTransaction(() -> ledger.reserve(first, "native-task", "native-turn", 200, 200L))).isEqualTo(ADMITTED);
        assertThat(ledger.pool(TENANT, ROLE, ROLE_ID).reserved()).isEqualTo(1200);
        var next = new KernelDataScope(TENANT, first.userId(), null, null, "next-run", null, Map.of());
        assertThat(reserve(next, "next-request")).isEqualTo(BILLING_ROLE_REQUIRED);
    }

    @Test
    void nativeTaskReservationsFollowTheTaskAcrossConcurrentAndLaterWorkerRequests() throws Exception {
        String task = "same-native-task";
        var threads = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var firstWorker = threads.submit(() -> {
                start.await();
                return inTransaction(() -> ledger.reserve(first, task, "worker-one", 300, 500L, 1, 1000));
            });
            var nextWorker = threads.submit(() -> {
                start.await();
                return inTransaction(() -> ledger.reserve(first, task, "worker-two", 300, 500L, 1, 1000));
            });
            start.countDown();
            var firstChoice = firstWorker.get(5, TimeUnit.SECONDS);
            var secondChoice = nextWorker.get(5, TimeUnit.SECONDS);
            assertThat(List.of(firstChoice, secondChoice)).containsExactlyInAnyOrder(ADMITTED, TASK_BUDGET_BUSY);
            String winner = firstChoice == ADMITTED ? "worker-one" : "worker-two";
            transaction.executeWithoutResult(tx -> ledger.settle(first, task, winner, null, false));
            assertThat(inTransaction(() -> ledger.reserve(first, task, "replacement-worker", 200, 300L, 1, 1000)))
                .isEqualTo(TASK_BUDGET_EXHAUSTED);
            transaction.executeWithoutResult(tx -> ledger.settle(first, task, winner, 120L, false));
            transaction.executeWithoutResult(tx -> ledger.settle(first, task, winner, 120L, false));
            assertThat(inTransaction(() -> ledger.reserve(first, task, winner, 300, 500L, 1, 1000))).isEqualTo(DUPLICATE);
            assertThat(inTransaction(() -> ledger.reserve(first, "other-native-task", "other-worker", 300, 500L, 1, 1000)))
                .isEqualTo(ADMITTED);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void aLateProviderTokenReceiptReplacesTheUnknownReservationWithoutResettingTheTask() {
        String task = "same-native-task";
        assertThat(inTransaction(() -> ledger.reserve(first, task, "unknown", 300, 500L, -1, 1000))).isEqualTo(ADMITTED);
        transaction.executeWithoutResult(tx -> ledger.settle(first, task, "unknown", null, false));
        assertThat(inTransaction(() -> ledger.reserve(first, task, "next-session", 200, 400L, -1, 1000)))
            .isEqualTo(TASK_BUDGET_EXHAUSTED);
        transaction.executeWithoutResult(tx -> ledger.settle(first, task, "unknown", 120L, false));
        assertThat(inTransaction(() -> ledger.reserve(first, task, "next-session", 200, 400L, -1, 1000))).isEqualTo(ADMITTED);
        transaction.executeWithoutResult(tx -> ledger.settle(first, task, "next-session", null, true));
        assertThat(inTransaction(() -> ledger.reserve(first, task, "resumed-session", 200, 400L, -1, 1000))).isEqualTo(ADMITTED);
    }

    @Test
    void missingOutputBoundAndPersonalExhaustionDoNotCreateReservationsAndResetKeepsOldReceiptsSeparate() {
        inTransaction(() -> ledger.configure(TENANT, USER, first.userId(), 700L, false));
        inTransaction(() -> ledger.configure(TENANT, ROLE, ROLE_ID, 1000L, false));
        assertThat(reserve(first, "personal")).isEqualTo(QUOTA_EXHAUSTED);
        assertThat(inTransaction(() -> ledger.reserve(second, second.runId(), "unbounded", 400, null))).isEqualTo(OUTPUT_BOUND_REQUIRED);
        assertThat(reserve(second, "actual")).isEqualTo(ADMITTED);
        inTransaction(() -> ledger.configure(TENANT, ROLE, ROLE_ID, 1000L, true));
        settle(second, "actual", 225L, false);
        assertThat(ledger.pool(TENANT, ROLE, ROLE_ID)).isEqualTo(new TokenQuotaLedger.Pool(1000L, 1, 0, 0));
        assertThat(ledger.pool(TENANT, USER, second.userId()).spent()).isEqualTo(225);
        assertThat(reserve(second, "local-stop")).isEqualTo(ADMITTED);
        settle(second, "local-stop", null, true);
        assertThat(ledger.pool(TENANT, ROLE, ROLE_ID).reserved()).isZero();
        assertThatThrownBy(() -> settle(first, "actual", 225L, false)).isInstanceOf(SecurityException.class);
    }

    private TokenQuotaLedger.Decision reserve(KernelDataScope scope, String id) {
        return inTransaction(() -> ledger.reserve(scope, scope.runId(), id, 400, 400L));
    }

    private void settle(KernelDataScope scope, String id, Long used, boolean neverDispatched) {
        transaction.executeWithoutResult(status -> ledger.settle(scope, scope.runId(), id, used, neverDispatched));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private static KernelDataScope scope(String user) {
        return new KernelDataScope(TENANT, user, null, null, "run-" + user, null, Map.of());
    }
}
