package com.yuzhua.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    public DashboardVo dashboard(Integer days) {
        LoginUser u = UserContext.require();
        // 原因: 仅医生账号按本人 doctorId 收窄统计口径，管理员即便绑定 doctorId 也看全院
        Long doctorId = u.isDoctor() ? UserContext.requireDoctorId() : null;
        int win = days == null ? DEFAULT_DAYS : Math.min(Math.max(days, 1), MAX_DAYS);

        DashboardVo vo = new DashboardVo();
        vo.setDays(win);
        AppointmentMapper.TodayCard today = appointmentMapper.todayCard(doctorId);
        vo.setTodayOrders(today.getOrders());
        vo.setTodayPending(today.getPending());
        vo.setTodayAccepted(today.getAccepted());
        vo.setTodayFinished(today.getFinished());
        vo.setTodayIncome(today.getIncome() == null ? BigDecimal.ZERO : today.getIncome());

        AppointmentMapper.ServedCount served = appointmentMapper.countServed(doctorId);
        if (doctorId == null) {
            vo.setTotalUsers(count(sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery())));
            vo.setTotalPets(count(petMapper.selectCount(Wrappers.<Pet>lambdaQuery())));
        } else {
            vo.setTotalUsers(served.getUsers());
            vo.setTotalPets(served.getPets());
        }
        vo.setTotalDoctors(count(doctorMapper.selectCount(Wrappers.<Doctor>lambdaQuery())));
        vo.setOnlineDoctors(count(doctorMapper.selectCount(
                Wrappers.<Doctor>lambdaQuery().eq(Doctor::getOnlineStatus, 1))));

        vo.setReviewPendingReply(count(reviewMapper.selectCount(Wrappers.<DoctorReview>lambdaQuery()
                .eq(doctorId != null, DoctorReview::getDoctorId, doctorId)
                .eq(DoctorReview::getStatus, 1)
                .and(w -> w.isNull(DoctorReview::getReply).or().eq(DoctorReview::getReply, "")))));

        vo.setUnpaidTimeoutRisk(count(appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery()
                .eq(doctorId != null, Appointment::getDoctorId, doctorId)
                .eq(Appointment::getStatus, Appointment.ST_PENDING)
                .eq(Appointment::getPayStatus, Appointment.PAY_NONE))));

        vo.setTrend(appointmentMapper.statDaily(win, doctorId));
        vo.setStatusDist(appointmentMapper.statStatus(win, doctorId));
        vo.setDeptDist(appointmentMapper.statDepartment(win, doctorId));
        // 原因: 接诊排行用于全院横向比较，医生视角保持全院口径
        vo.setTopDoctors(appointmentMapper.statTopDoctors(win, TOP_DOCTORS));
        return vo;
    }

    private long count(Long n) {
        return n == null ? 0L : n;
    }
}
