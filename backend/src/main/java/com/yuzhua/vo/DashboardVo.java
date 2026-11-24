package com.yuzhua.vo;

import lombok.Data;

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
    private BigDecimal todayIncome = BigDecimal.ZERO;

    private long totalUsers;
    private long totalPets;
    private long totalDoctors;
    private long onlineDoctors;
    private long reviewPendingReply;
    private long unpaidTimeoutRisk;

    private List<Map<String, Object>> trend;
    private List<Map<String, Object>> statusDist;
    private List<Map<String, Object>> deptDist;
    private List<Map<String, Object>> topDoctors;
}
