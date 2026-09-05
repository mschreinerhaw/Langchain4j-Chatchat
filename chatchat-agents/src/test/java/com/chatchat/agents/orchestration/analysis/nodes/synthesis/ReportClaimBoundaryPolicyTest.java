package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportClaimBoundaryPolicyTest {
    private final ReportClaimBoundaryPolicy policy = new ReportClaimBoundaryPolicy();
    @Test void twoClosedPositionsDoNotEstablishHabitualTrading() {
        var basis = List.of("抽样两条清仓记录，持仓分别为1天和2天");
        assertThat(policy.violations("持仓周期通常仅为1至2天，习惯追求短期收益", basis)).isNotEmpty();
        assertThat(policy.violations("样本中两条清仓记录持仓分别为1天和2天，无法判断通常的持仓周期", basis)).isEmpty();
    }
    @Test void partialHoldingsCannotEstablishTotalPopulation() {
        assertThat(policy.violations("客户共持有20只证券", List.of("返回持仓采样20条")))
            .contains("POPULATION_SCOPE_EXPANSION");
        assertThat(policy.violations("持仓样本涉及20只证券", List.of("返回持仓采样20条"))).isEmpty();
        assertThat(policy.violations("客户共持有20只证券。末尾说明：当前仅为样本。", List.of("返回持仓采样20条")))
            .contains("POPULATION_SCOPE_EXPANSION");
    }
    @Test void numericEqualityDoesNotAuthorizeMetricRenaming() {
        assertThat(policy.violations("当日浮盈42263.81", List.of("当日盈亏42263.81")))
            .contains("UNSUPPORTED_METRIC_MEANING:浮盈");
        assertThat(policy.violations("累计总资产收益804910.44", List.of("ZZC_SR返回804910.44，业务定义未知")))
            .contains("UNSUPPORTED_METRIC_MEANING:累计总资产收益");
        assertThat(policy.violations("当日盈亏42263.81，不能判断浮盈", List.of("当日盈亏42263.81"))).isEmpty();
        assertThat(policy.violations("当日浮盈42263.81", List.of("已确认当日浮盈42263.81"))).isEmpty();
    }
    @Test void snapshotCannotEstablishMotivationAndCaveatDoesNotUndoAbsoluteClaim() {
        assertThat(policy.violations("客户具有极强的进攻意愿。当前仅为样本。", List.of("证券市值占总资产99.89%"))).isNotEmpty();
        assertThat(policy.violations("不能判断其习惯。客户通常满仓。", List.of("一个资产快照"))).isNotEmpty();
    }
}
