package com.chatchat.mcpserver.ops.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandTemplateServiceTest {

    private final CommandTemplateConfigRepository repository = mock(CommandTemplateConfigRepository.class);

    @Test
    void onlyRequiredManagedTemplatesSeedWhenBulkDefaultsAreDisabled() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.times(10)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(CommandTemplateConfig::getCode)
            .containsExactlyInAnyOrder(
                "CHECK_DOCKER_PS_ALL", "CHECK_DOCKER_IMAGES", "CHECK_DOCKER_STATS",
                "CHECK_DOCKER_INSPECT", "CHECK_DOCKER_LOGS", "CHECK_DOCKER_TOP",
                "CHECK_DOCKER_PORTS", "CHECK_DOCKER_SYSTEM_DF", "CHECK_DOCKER_NETWORKS",
                "CHECK_DOCKER_VOLUMES");
    }

    @Test
    void enabledDefaultSeedDoesNotIncludeRuntimeSpecificOverviewCommands() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<CommandTemplateConfig> saved = captor.getAllValues();
        assertThat(saved).extracting(CommandTemplateConfig::getCode)
            .contains(
                "CHECK_SYSTEM_OVERVIEW",
                "CHECK_SERVICE_INFO",
                "CHECK_PROCESS_INFO",
                "CHECK_PROCESS_NETWORK",
                "CHECK_JAVA_PROCESS",
                "CHECK_JVM_DETAIL",
                "CHECK_JVM_RUNTIME",
                "CHECK_JVM_GC",
                "CHECK_JVM_THREADS",
                "CHECK_JAVA_NETWORK",
                "CHECK_JAVA_LOG_ERRORS",
                "CHECK_IO_STATUS",
                "CHECK_PORT_BINDING",
                "CHECK_SYSTEM_LOAD",
                "CHECK_MIDDLEWARE_PROCESS_OVERVIEW",
                "CHECK_REDIS_OVERVIEW",
                "CHECK_ZOOKEEPER_OVERVIEW",
                "CHECK_NGINX_OVERVIEW",
                "CHECK_DOCKER_OVERVIEW",
                "CHECK_DOCKER_CONTAINERS",
                "CHECK_DOCKER_PS_ALL",
                "CHECK_DOCKER_IMAGES",
                "CHECK_DOCKER_STATS",
                "CHECK_DOCKER_INSPECT",
                "CHECK_DOCKER_LOGS",
                "CHECK_DOCKER_TOP",
                "CHECK_DOCKER_PORTS",
                "CHECK_DOCKER_SYSTEM_DF",
                "CHECK_DOCKER_NETWORKS",
                "CHECK_DOCKER_VOLUMES",
                "CHECK_CONTAINER_RUNTIME_OVERVIEW",
                "CHECK_K8S_NODE_OVERVIEW",
                "CHECK_K8S_NODE_DETAIL",
                "CHECK_K8S_WORKLOAD_OVERVIEW",
                "CHECK_K8S_EVENTS",
                "CHECK_K8S_POD_DETAIL",
                "CHECK_K8S_RESOURCES",
                "CHECK_K8S_NETWORK",
                "CHECK_K8S_ROLLOUT",
                "CHECK_MOUNT_DISK_USAGE"
            );
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_PS_ALL".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate()).isEqualTo("docker ps -a"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_IMAGES".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate()).isEqualTo("docker images"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("CHECK_DOCKER_"))
            .allSatisfy(template -> {
                assertThat(template.getRiskLevel()).isEqualTo("LOW");
                assertThat(template.getCategory()).isEqualTo("container_diagnostic");
                assertThat(template.getRuntimeAction()).isEqualTo("confirm_required");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_STATS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .isEqualTo("docker stats --no-stream"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_LOGS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .isEqualTo("docker logs --timestamps --tail {{lines}} {{container}}");
                assertThat(template.getParameterSchemaJson()).contains("container", "lines", "maximum");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_INSPECT".equals(template.getCode())
                || "CHECK_DOCKER_TOP".equals(template.getCode())
                || "CHECK_DOCKER_PORTS".equals(template.getCode()))
            .allSatisfy(template -> assertThat(template.getParameterSchemaJson()).contains("container", "pattern"));
        assertThat(saved)
            .allSatisfy(template -> assertThat(template.getCommandTemplate().length())
                .as("command template length for %s", template.getCode())
                .isLessThanOrEqualTo(2000));
        assertThat(saved)
            .filteredOn(template -> "CHECK_PROCESS_INFO".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("process resource details: {{processName}}")
                    .contains("ps -eo pid,ppid,user,comm,%cpu,%mem,rss,vsz,etime,cmd")
                    .contains("process json lines: {{processName}}")
                    .contains("ps -eo pid,comm,%cpu,%mem,rss,vsz,cmd")
                    .contains("{{processName}}")
                    .contains("index(tolower($0), q)>0")
                    .contains("vsz");
                assertThat(template.getParameterSchemaJson()).contains("processName");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_PROCESS_NETWORK".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("lsof -i -P -n")
                    .contains("ss -ptn")
                    .contains("{{processName}}");
                assertThat(template.getParameterSchemaJson()).contains("processName");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_JAVA_PROCESS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .contains("jps -lv")
                .contains("ps -eo pid,ppid,user,stat,pcpu,pmem,etime,args")
                .contains("ps -eo pid,%cpu,%mem,rss,vsz,cmd --no-headers | grep java | grep -v grep")
                .contains("ps -eo pid,comm,%cpu,%mem,rss,vsz --no-headers | grep -i java")
                .contains("pid")
                .contains("rss"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_JVM_DETAIL".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("jstat -gc {{pid}} 1s 3")
                    .contains("jmap -heap {{pid}}")
                    .contains("jstack {{pid}}")
                    .contains("jps -lv");
                assertThat(template.getParameterSchemaJson()).contains("pid");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_JVM_GC".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("jstat -gcutil {{pid}} 1s 5")
                    .contains("jstat -gccause {{pid}}")
                    .contains("jcmd {{pid}} GC.heap_info");
                assertThat(template.getIntentSignalsJson()).contains("频繁 GC").contains("Java 内存高");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_JVM_THREADS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .contains("top -H -b -n 1 -p {{pid}}")
                .contains("Thread.print -l")
                .contains("head -500"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_JAVA_LOG_ERRORS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("tail -n {{lines}} {{path}}")
                    .contains("outofmemory")
                    .contains("too many open files");
                assertThat(template.getParameterSchemaJson()).contains("lines").contains("path");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_IO_STATUS".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .contains("iostat -x 1 3")
                .contains("pidstat -d 1 3"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_PORT_BINDING".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .contains("ss -tulnp")
                .contains("lsof -iTCP -sTCP:LISTEN"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_SYSTEM_LOAD".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> assertThat(template.getCommandTemplate())
                .contains("/proc/loadavg")
                .contains("mpstat 1 1")
                .contains("vmstat 1 3"));
        assertThat(saved)
            .filteredOn(template -> "CHECK_SERVICE_INFO".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("systemctl show {{serviceName}}")
                    .contains("systemctl status {{serviceName}}")
                    .contains("pgrep -af {{serviceName}}")
                    .contains("ss -tulnp");
                assertThat(template.getParameterSchemaJson()).contains("serviceName");
                assertThat(template.getCategory()).isEqualTo("service_diagnostic");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_MIDDLEWARE_PROCESS_OVERVIEW".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("redis")
                    .contains("zookeeper")
                    .contains("nginx")
                    .contains("[j]ava")
                    .contains("[d]ockerd")
                    .contains("[k]ubelet");
                assertThat(template.getCategory()).isEqualTo("middleware_diagnostic");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_DOCKER_OVERVIEW".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("docker info")
                    .contains("docker system df")
                    .contains("image_count");
                assertThat(template.getCategory()).isEqualTo("container_diagnostic");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_K8S_NODE_OVERVIEW".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("kubectl get nodes")
                    .contains("kubectl get pods -A");
                assertThat(template.getCategory()).isEqualTo("k8s_diagnostic");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_K8S_POD_DETAIL".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("kubectl describe pod {{pod}} -n {{namespace}}")
                    .contains("--all-containers=true")
                    .contains("--previous");
                assertThat(template.getParameterSchemaJson()).contains("namespace").contains("pod");
                assertThat(template.getIntentSignalsJson()).contains("CrashLoopBackOff").contains("OOMKilled");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_K8S_NODE_DETAIL".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("kubectl describe node {{node}}")
                    .contains("kubectl top node {{node}}")
                    .contains("involvedObject.name={{node}}");
                assertThat(template.getIntentSignalsJson()).contains("NotReady").contains("DiskPressure");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_K8S_ROLLOUT".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("kubectl rollout status {{kind}}/{{name}}")
                    .contains("kubectl rollout history {{kind}}/{{name}}")
                    .contains("--timeout=10s");
                assertThat(template.getParameterSchemaJson())
                    .contains("deployment")
                    .contains("statefulset")
                    .contains("daemonset");
            });
        assertThat(saved)
            .filteredOn(template -> "CHECK_MOUNT_DISK_USAGE".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getCommandTemplate())
                    .contains("df -hT")
                    .contains("findmnt")
                    .contains("mount summary");
                assertThat(template.getCategory()).isEqualTo("storage_diagnostic");
            });
    }

    @Test
    void repairsLegacyJavaProcessTemplateToUsePsFallback() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateConfig legacy = new CommandTemplateConfig();
        legacy.setCode("CHECK_JAVA_PROCESS");
        legacy.setTitle("Java process");
        legacy.setDescription("Read Java processes.");
        legacy.setCommandTemplate("jps -lv");
        legacy.setParameterSchemaJson("{}");
        legacy.setIntentSignalsJson("[]");
        legacy.setEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode("CHECK_JAVA_PROCESS")).thenReturn(Optional.of(legacy));
        when(repository.findByCode(org.mockito.ArgumentMatchers.argThat(code -> !"CHECK_JAVA_PROCESS".equals(code))))
            .thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(legacy));

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("CHECK_JAVA_PROCESS".equals(saved.getCode())) {
                assertThat(saved.getCommandTemplate())
                    .contains("jps -lv")
                    .contains("ps -eo")
                    .contains("awk")
                    .contains("ps -eo pid,%cpu,%mem,rss,vsz,cmd --no-headers | grep java | grep -v grep")
                    .contains("ps -eo pid,comm,%cpu,%mem,rss,vsz --no-headers | grep -i java")
                    .contains("name");
            }
        });
    }

    @Test
    void repairsExistingDefaultJavaProcessTemplateWhenResourceCommandIsMissing() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateConfig existing = new CommandTemplateConfig();
        existing.setCode("CHECK_JAVA_PROCESS");
        existing.setTitle("Java process");
        existing.setDescription("Read Java processes with jps when available and ps as a fallback.");
        existing.setCommandTemplate("[\"echo '=== jps -lv ==='; jps -lv\",\"echo '=== ps java processes ==='; ps -eo pid,ppid,user,stat,pcpu,pmem,etime,args | awk 'NR==1 || /[j]ava/'\"]");
        existing.setParameterSchemaJson("{}");
        existing.setIntentSignalsJson("[]");
        existing.setEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode("CHECK_JAVA_PROCESS")).thenReturn(Optional.of(existing));
        when(repository.findByCode(org.mockito.ArgumentMatchers.argThat(code -> !"CHECK_JAVA_PROCESS".equals(code))))
            .thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(existing));

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("CHECK_JAVA_PROCESS".equals(saved.getCode())) {
                assertThat(saved.getCommandTemplate())
                    .contains("ps -eo pid,%cpu,%mem,rss,vsz,cmd --no-headers | grep java | grep -v grep")
                    .contains("ps -eo pid,comm,%cpu,%mem,rss,vsz --no-headers | grep -i java")
                    .contains("cpu");
            }
        });
    }

    @Test
    void refreshesExistingDefaultTemplateDefinitionButPreservesDisabledState() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        CommandTemplateConfig existing = new CommandTemplateConfig();
        existing.setCode("CHECK_SYSTEM_LOAD");
        existing.setTitle("Old load title");
        existing.setDescription("Old load description");
        existing.setCommandTemplate("uptime");
        existing.setParameterSchemaJson("{}");
        existing.setRiskLevel("HIGH");
        existing.setCategory("custom");
        existing.setIntentSignalsJson("[]");
        existing.setRuntimeAction("forbidden");
        existing.setEnabled(false);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("CHECK_SYSTEM_LOAD")).thenReturn(Optional.of(existing));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("CHECK_SYSTEM_LOAD".equals(saved.getCode())) {
                assertThat(saved.getTitle()).isEqualTo("System load pressure");
                assertThat(saved.getDescription()).contains("load average");
                assertThat(saved.getCommandTemplate()).contains("/proc/loadavg").contains("vmstat 1 3");
                assertThat(saved.getRiskLevel()).isEqualTo("LOW");
                assertThat(saved.getCategory()).isEqualTo("system_diagnostic");
                assertThat(saved.isEnabled()).isFalse();
            }
        });
    }

    @Test
    void refreshesExistingDefaultTemplateEvenWhenDefaultSeedIsDisabled() {
        CommandTemplateSeedProperties properties = new CommandTemplateSeedProperties();
        CommandTemplateConfig existing = new CommandTemplateConfig();
        existing.setCode("CHECK_BLOCK");
        existing.setTitle("块设备");
        existing.setDescription("查询块设备。");
        existing.setCommandTemplate("old lsblk");
        existing.setParameterSchemaJson("{}");
        existing.setRiskLevel("HIGH");
        existing.setCategory("custom");
        existing.setIntentSignalsJson("[]");
        existing.setRuntimeAction("forbidden");
        existing.setEnabled(true);
        CommandTemplateService service = new CommandTemplateService(repository, new ObjectMapper(), properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("CHECK_BLOCK")).thenReturn(Optional.of(existing));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(existing));

        service.listEnabled();

        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        CommandTemplateConfig saved = captor.getAllValues().stream()
            .filter(template -> "CHECK_BLOCK".equals(template.getCode()))
            .findFirst()
            .orElseThrow();
        assertThat(saved.getCode()).isEqualTo("CHECK_BLOCK");
        assertThat(saved.getTitle()).isEqualTo("Block devices");
        assertThat(saved.getDescription()).isEqualTo("Read block devices.");
        assertThat(saved.getCommandTemplate()).isEqualTo("lsblk");
        assertThat(saved.getRiskLevel()).isEqualTo("LOW");
        assertThat(saved.getCategory()).isEqualTo("storage_diagnostic");
        assertThat(saved.isEnabled()).isTrue();
    }
}
