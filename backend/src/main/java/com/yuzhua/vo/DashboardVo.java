package com.yuzhua.vo;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class DashboardVo {

    private int days;

    private long todayOrders;
    private long todayPending;
    private long todayAccepted;
    private long todayFinished;
    private @Nullable BigDecimal todayIncome = BigDecimal.ZERO;

    private long totalUsers;
    private long totalPets;
    private long totalDoctors;
    private long onlineDoctors;
    private long reviewPendingReply;
    private long unpaidTimeoutRisk;

    private @Nullable List<Map<String, Object>> trend;
    private @Nullable List<Map<String, Object>> statusDist;
    private @Nullable List<Map<String, Object>> deptDist;
    private @Nullable List<Map<String, Object>> topDoctors;
}
