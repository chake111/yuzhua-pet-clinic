package com.yuzhua.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.PayStatus;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorReview;
import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.DashboardVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 90;
    private static final int TOP_DOCTORS = 8;

    private final AppointmentMapper appointmentMapper;
    private final SysUserMapper sysUserMapper;
    private final PetMapper petMapper;
    private final DoctorMapper doctorMapper;
    private final DoctorReviewMapper reviewMapper;

    public DashboardVo dashboard(@Nullable Integer days) {
        LoginUser u = UserContext.require();
        // 原因: 仅医生账号按本人 doctorId 收窄统计口径，管理员即便绑定 doctorId 也看全院
        Long doctorId = u.isDoctor() ? UserContext.requireDoctorId() : null;
        int win = days == null ? DEFAULT_DAYS : Math.min(Math.max(days, 1), MAX_DAYS);

        AppointmentMapper.TodayCard today = appointmentMapper.todayCard(doctorId);
        long todayOrders = today == null ? 0L : today.getOrders();
        long todayPending = today == null ? 0L : today.getPending();
        long todayAccepted = today == null ? 0L : today.getAccepted();
        long todayFinished = today == null ? 0L : today.getFinished();
        BigDecimal todayIncome = today == null || today.getIncome() == null ? BigDecimal.ZERO : today.getIncome();

        AppointmentMapper.ServedCount served = appointmentMapper.countServed(doctorId);
        long totalUsers;
        long totalPets;
        if (doctorId == null) {
            totalUsers = count(sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()));
            totalPets = count(petMapper.selectCount(Wrappers.<Pet>lambdaQuery()));
        } else if (served != null) {
            totalUsers = served.getUsers() == null ? 0L : served.getUsers();
            totalPets = served.getPets() == null ? 0L : served.getPets();
        } else {
            totalUsers = 0L;
            totalPets = 0L;
        }
        long totalDoctors = count(doctorMapper.selectCount(Wrappers.<Doctor>lambdaQuery()));
        long onlineDoctors = count(doctorMapper.selectCount(
                Wrappers.<Doctor>lambdaQuery().eq(Doctor::getOnlineStatus, 1)));

        long reviewPendingReply = count(reviewMapper.selectCount(Wrappers.<DoctorReview>lambdaQuery()
                .eq(doctorId != null, DoctorReview::getDoctorId, doctorId)
                .eq(DoctorReview::getStatus, EnableStatus.ENABLED)
                .and(w -> w.isNull(DoctorReview::getReply).or().eq(DoctorReview::getReply, ""))));

        long unpaidTimeoutRisk = count(appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery()
                .eq(doctorId != null, Appointment::getDoctorId, doctorId)
                .eq(Appointment::getStatus, AppointmentStatus.PENDING)
                .eq(Appointment::getPayStatus, PayStatus.UNPAID)));

        return new DashboardVo(
                win,
                todayOrders,
                todayPending,
                todayAccepted,
                todayFinished,
                todayIncome,
                totalUsers,
                totalPets,
                totalDoctors,
                onlineDoctors,
                reviewPendingReply,
                unpaidTimeoutRisk,
                appointmentMapper.statDaily(win, doctorId),
                appointmentMapper.statStatus(win, doctorId),
                appointmentMapper.statDepartment(win, doctorId),
                // 原因: 接诊排行用于全院横向比较，医生视角保持全院口径
                appointmentMapper.statTopDoctors(win, TOP_DOCTORS)
        );
    }

    private long count(Long n) {
        return n == null ? 0L : n;
    }
}
