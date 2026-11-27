package com.yuzhua.vo;

import java.math.BigDecimal;
import java.util.List;

public record DashboardVo(
        int days,
        long todayOrders,
        long todayPending,
        long todayAccepted,
        long todayFinished,
        BigDecimal todayIncome,
        long totalUsers,
        long totalPets,
        long totalDoctors,
        long onlineDoctors,
        long reviewPendingReply,
        long unpaidTimeoutRisk,
        List<DailyTrendItemVo> trend,
        List<StatusCountVo> statusDist,
        List<NamedCountVo> deptDist,
        List<NamedCountVo> topDoctors
) {

    public DashboardVo {
        todayIncome = todayIncome == null ? BigDecimal.ZERO : todayIncome;
        trend = trend == null ? List.of() : List.copyOf(trend);
        statusDist = statusDist == null ? List.of() : List.copyOf(statusDist);
        deptDist = deptDist == null ? List.of() : List.copyOf(deptDist);
        topDoctors = topDoctors == null ? List.of() : List.copyOf(topDoctors);
    }
}
