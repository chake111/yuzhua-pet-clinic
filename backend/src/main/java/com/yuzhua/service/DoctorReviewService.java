package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.dto.ReviewForm;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorReview;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.PetDisplayVo;
import com.yuzhua.vo.ReviewVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DoctorReviewService extends ServiceImpl<DoctorReviewMapper, DoctorReview> {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    private final SysUserMapper sysUserMapper;
    private final AppointmentService appointmentService;
    private final DoctorMapper doctorMapper;
    private final AppointmentMapper appointmentMapper;
    private final PetMapper petMapper;

    /** 同一事务内落库评价、标记已评并刷新医生均分；仅已完成且未评价的本人预约可提交。 */
    @Transactional
    public DoctorReview submit(ReviewForm req) {
        Appointment a = validateSubmitEligibility(req);
        DoctorReview r = buildReview(req, a);
        save(r);
        appointmentService.markReviewed(Objects.requireNonNull(a.getId()));
        doctorMapper.refreshStats(Objects.requireNonNull(a.getDoctorId()));
        return r;
    }

    private Appointment validateSubmitEligibility(ReviewForm req) {
        if (req.getRating() == null || req.getRating() < MIN_RATING || req.getRating() > MAX_RATING) {
            throw new BizException("评分为 1-5 星");
        }
        if (req.getRating() < MAX_RATING && (req.getContent() == null || req.getContent().isBlank())) {
            throw new BizException("低于 5 星请填写评价说明");
        }
        if (req.getAppointmentId() == null) {
            throw new BizException("缺少预约单");
        }
        Appointment a = appointmentService.requireMine(req.getAppointmentId());
        if (a.getStatus() != AppointmentStatus.FINISHED) {
            throw new BizException("问诊完成后才能评价");
        }
        if (Integer.valueOf(1).equals(a.getIsReviewed())) {
            throw new BizException("这个预约已经评价过了");
        }
        if (exists(Wrappers.<DoctorReview>lambdaQuery().eq(DoctorReview::getAppointmentId, a.getId()))) {
            // 原因: 兜底 is_reviewed 被手工改写时仍受唯一键语义保护
            throw new BizException("这个预约已经评价过了");
        }
        return a;
    }

    private DoctorReview buildReview(ReviewForm req, Appointment a) {
        DoctorReview r = new DoctorReview();
        r.setAppointmentId(a.getId());
        r.setUserId(a.getUserId());
        r.setDoctorId(a.getDoctorId());
        r.setDepartmentId(a.getDepartmentId());
        r.setRating(req.getRating());
        r.setAttitudeScore(req.getAttitudeScore() == null ? req.getRating() : req.getAttitudeScore());
        r.setEffectScore(req.getEffectScore() == null ? req.getRating() : req.getEffectScore());
        r.setContent(req.getContent() == null || req.getContent().isBlank() ? null : req.getContent().trim());
        r.setImages(req.getImages() == null || req.getImages().isBlank() ? null : req.getImages());
        r.setIsAnonymous(Integer.valueOf(1).equals(req.getIsAnonymous()) ? 1 : 0);
        r.setStatus(EnableStatus.ENABLED);
        return r;
    }

    /**
     * 医生或管理员回复评价：要求已登录，校验非空白且原串长度不超过 500 字（{@code trim()} 后落库）；
     * 医生身份仅可回复归属本人 {@code doctorId} 的评价（越权抛 403），管理员可代回复。
     * 直接覆写 {@code reply} 与 {@code replyTime}，不校验评价显示状态（{@code status}）或是否已有旧回复。
     */
    @Transactional
    public void reply(Long id, @Nullable String reply) {
        if (reply == null || reply.isBlank()) {
            throw new BizException("请填写回复内容");
        }
        if (reply.length() > 500) {
            throw new BizException("回复不超过 500 字");
        }
        DoctorReview r = require(id);
        LoginUser u = UserContext.require();
        if (u.isDoctor() && !Objects.equals(r.getDoctorId(), u.getDoctorId())) {
            throw BizException.forbidden("只能回复自己收到的评价");
        }
        update(Wrappers.<DoctorReview>lambdaUpdate()
                .set(DoctorReview::getReply, reply.trim())
                .set(DoctorReview::getReplyTime, LocalDateTime.now())
                .eq(DoctorReview::getId, id));
    }

    /**
     * 显示 / 隐藏评价。评分聚合只统计启用态（{@code EnableStatus.ENABLED}，库值 {@code status = 1}）的行，
     * 所以隐藏一条就要重算一次，否则医生卡片上的均分会和实际看到的评价对不上。
     */
    @Transactional
    public void changeStatus(Long id, @Nullable EnableStatus status) {
        DoctorReview r = require(id);
        if (status == null) {
            throw new BizException("状态只能是 0 隐藏 或 1 显示");
        }
        update(Wrappers.<DoctorReview>lambdaUpdate()
                .set(DoctorReview::getStatus, status)
                .eq(DoctorReview::getId, id));
        Long doctorId = r.getDoctorId();
        if (doctorId != null) {
            doctorMapper.refreshStats(doctorId);
        }
    }

    public DoctorReview require(Long id) {
        DoctorReview r = getById(id);
        if (r == null) {
            throw BizException.notFound("评价");
        }
        return r;
    }

    public PageResult<ReviewVo> pageForDoctor(Long doctorId, PageQuery q) {
        Page<DoctorReview> page = page(new Page<>(q.getPageNum(), q.getPageSize()),
                Wrappers.<DoctorReview>lambdaQuery()
                        .eq(DoctorReview::getDoctorId, doctorId)
                        .eq(DoctorReview::getStatus, EnableStatus.ENABLED)
                        .orderByDesc(DoctorReview::getCreateTime));
        return toVoPage(page, true);
    }

    public PageResult<ReviewVo> pageForStaff(@Nullable Long doctorId, @Nullable EnableStatus status, PageQuery q) {
        LoginUser u = UserContext.require();
        Long scoped = u.isDoctor() ? UserContext.requireDoctorId() : doctorId;
        LambdaQueryWrapper<DoctorReview> qw = Wrappers.<DoctorReview>lambdaQuery()
                .eq(scoped != null, DoctorReview::getDoctorId, scoped)
                .eq(status != null, DoctorReview::getStatus, status)
                .orderByDesc(DoctorReview::getCreateTime);
        return toVoPage(page(new Page<>(q.getPageNum(), q.getPageSize()), qw), false);
    }

    private PageResult<ReviewVo> toVoPage(Page<DoctorReview> page, boolean anonymousMasked) {
        Map<Long, SysUser> users = usersOf(page.getRecords());
        // 原因: 医生归属与预约上下文仅后台需要，公开展示以医生主页为上下文不冗余查询
        Map<Long, Doctor> doctors = anonymousMasked ? Map.of() : doctorsOf(page.getRecords());
        Map<Long, Appointment> appointments = anonymousMasked ? Map.of() : appointmentsOf(page.getRecords());
        Map<Long, PetDisplayVo> pets = petsOf(appointments.values());
        List<ReviewVo> rows = page.getRecords().stream().map(r -> {
            ReviewVo vo = new ReviewVo();
            BeanUtils.copyProperties(r, vo);
            SysUser u = users.get(r.getUserId());
            if (u != null) {
                vo.setUserNickname(u.getNickname());
                vo.setUserAvatar(u.getAvatar());
            }
            Doctor d = doctors.get(r.getDoctorId());
            if (d != null) {
                vo.setDoctorName(d.getName());
                vo.setDoctorTitle(d.getTitle());
            }
            Appointment a = appointments.get(r.getAppointmentId());
            if (a != null) {
                vo.setOrderNo(a.getOrderNo());
                vo.setAppointmentDate(a.getAppointmentDate());
                PetDisplayVo p = pets.get(a.getPetId());
                if (p != null) {
                    vo.setPetName(p.getName());
                    vo.setPetSpecies(p.getSpecies());
                }
            }
            if (anonymousMasked) {
                vo.maskAnonymous();
            }
            return vo;
        }).toList();
        return PageResult.of(page, rows);
    }

    private Map<Long, SysUser> usersOf(List<DoctorReview> rows) {
        return BatchLookup.mapByIds(sysUserMapper::selectBatchIds,
                BatchLookup.ids(rows, DoctorReview::getUserId), SysUser::getId);
    }

    private Map<Long, Doctor> doctorsOf(List<DoctorReview> rows) {
        return BatchLookup.mapByIds(doctorMapper::selectBatchIds,
                BatchLookup.ids(rows, DoctorReview::getDoctorId), Doctor::getId);
    }

    private Map<Long, Appointment> appointmentsOf(List<DoctorReview> rows) {
        return BatchLookup.mapByIds(appointmentMapper::selectBatchIds,
                BatchLookup.ids(rows, DoctorReview::getAppointmentId), Appointment::getId);
    }

    private Map<Long, PetDisplayVo> petsOf(Collection<Appointment> appointments) {
        return BatchLookup.mapByIds(petMapper::selectHistoryDisplay,
                BatchLookup.ids(appointments, Appointment::getPetId), PetDisplayVo::getId);
    }
}
