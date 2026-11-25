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
import com.yuzhua.domain.enums.SchedulePeriod;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.dto.ScheduleBatchForm;
import com.yuzhua.dto.ScheduleEditForm;
import com.yuzhua.dto.ScheduleForm;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorSchedule;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorScheduleMapper;
import com.yuzhua.vo.ScheduleVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DoctorScheduleService extends ServiceImpl<DoctorScheduleMapper, DoctorSchedule> {

    private static final int DEFAULT_BOOKABLE_DAYS = 7;
    private static final int MAX_BOOKABLE_DAYS = 30;
    private static final int MAX_BATCH_DAYS = 90;
    private static final int MORNING_START_HOUR = 8;
    private static final int MORNING_END_HOUR = 12;
    private static final int AFTERNOON_START_HOUR = 13;
    private static final int AFTERNOON_END_HOUR = 18;
    private static final int EVENING_START_HOUR = 18;
    private static final int EVENING_END_HOUR = 22;

    private final DoctorMapper doctorMapper;
    private final AppointmentMapper appointmentMapper;
    private final DepartmentMapper departmentMapper;

    public List<ScheduleVo> listBookable(Long doctorId, @Nullable LocalDate from, @Nullable Integer days) {
        LocalDate start = from == null ? LocalDate.now() : from;
        int span = days == null ? DEFAULT_BOOKABLE_DAYS : Math.max(1, Math.min(days, MAX_BOOKABLE_DAYS));
        List<DoctorSchedule> rows = list(Wrappers.<DoctorSchedule>lambdaQuery()
                .eq(DoctorSchedule::getDoctorId, doctorId)
                .eq(DoctorSchedule::getStatus, EnableStatus.ENABLED)
                .ge(DoctorSchedule::getWorkDate, start)
                .le(DoctorSchedule::getWorkDate, start.plusDays(span - 1L))
                .orderByAsc(DoctorSchedule::getWorkDate)
                .orderByAsc(DoctorSchedule::getPeriod));
        Doctor doctor = doctorMapper.selectById(doctorId);
        Map<Long, Doctor> doctorDict = doctor == null ? Map.of() : Map.of(Objects.requireNonNull(doctor.getId()), doctor);
        Map<Long, Department> deptDict = departmentsOf(doctorDict.values());
        return rows.stream().map(s -> toVo(s, doctorDict, deptDict)).collect(Collectors.toList());
    }

    public PageResult<ScheduleVo> pageAdmin(@Nullable Long doctorId, @Nullable LocalDate start, @Nullable LocalDate end,
                                            @Nullable EnableStatus status, PageQuery query) {
        LambdaQueryWrapper<DoctorSchedule> qw = Wrappers.<DoctorSchedule>lambdaQuery()
                .eq(doctorId != null, DoctorSchedule::getDoctorId, doctorId)
                .ge(start != null, DoctorSchedule::getWorkDate, start)
                .le(end != null, DoctorSchedule::getWorkDate, end)
                .eq(status != null, DoctorSchedule::getStatus, status)
                .orderByDesc(DoctorSchedule::getWorkDate)
                .orderByAsc(DoctorSchedule::getPeriod)
                .orderByAsc(DoctorSchedule::getDoctorId);
        Page<DoctorSchedule> page = page(new Page<>(query.getPageNum(), query.getPageSize()), qw);
        Map<Long, Doctor> doctors = doctorsOf(page.getRecords());
        Map<Long, Department> depts = departmentsOf(doctors.values());
        Map<Long, Long> refs = refCounts(page.getRecords());
        return PageResult.of(page, s -> {
            ScheduleVo vo = toVo(s, doctors, depts);
            vo.setRefNum(refs.getOrDefault(s.getId(), 0L));
            return vo;
        });
    }

    /** 校验医生在诊、时段范围与唯一性后新建排班，初始余量等于总量。 */
    @Transactional
    public DoctorSchedule create(ScheduleForm req) {
        requireSchedulable(req.getDoctorId());
        checkSlotSanity(req.getWorkDate(), req.getStartTime(), req.getEndTime());
        checkPeriodTime(req.getPeriod(), req.getStartTime(), req.getEndTime());
        if (exists(Wrappers.<DoctorSchedule>lambdaQuery()
                .eq(DoctorSchedule::getDoctorId, req.getDoctorId())
                .eq(DoctorSchedule::getWorkDate, req.getWorkDate())
                .eq(DoctorSchedule::getPeriod, req.getPeriod()))) {
            throw new BizException("该医生在这一天的这个时段已经排过班了");
        }
        DoctorSchedule s = new DoctorSchedule();
        s.setDoctorId(req.getDoctorId());
        s.setWorkDate(req.getWorkDate());
        s.setPeriod(req.getPeriod());
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setTotalNum(req.getTotalNum());
        s.setRemainNum(req.getTotalNum());
        s.setStatus(req.getStatus() == null ? EnableStatus.ENABLED : req.getStatus());
        save(s);
        return s;
    }

    /**
     * 在排班行锁内更新排班（不修改 status，仅停诊/恢复入口写状态）；
     * 已存在任何预约引用时禁止改动医生、日期、时段或起止时间，且新总量不得小于有效预约数。
     */
    @Transactional
    public void update(Long id, ScheduleEditForm req) {
        DoctorSchedule cur = baseMapper.selectByIdForUpdate(id);
        if (cur == null) {
            throw BizException.notFound("排班");
        }
        validateUpdateFields(id, cur, req);
        int booked = appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getScheduleId, id)
                .ne(Appointment::getStatus, AppointmentStatus.CANCELLED)).intValue();
        Integer totalNum = req.getTotalNum();
        if (totalNum == null || totalNum < booked) {
            throw new BizException("该时段已有 " + booked + " 个有效预约，总量不能改到 " + booked + " 以下");
        }
        DoctorSchedule s = new DoctorSchedule();
        s.setId(id);
        s.setDoctorId(req.getDoctorId());
        s.setWorkDate(req.getWorkDate());
        s.setPeriod(req.getPeriod());
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setTotalNum(totalNum);
        s.setRemainNum(totalNum - booked);
        // 原因: status 仅由 stopClinic/resumeClinic 写入，避免旧编辑弹窗提交覆盖停诊状态
        updateById(s);
    }

    private void validateUpdateFields(Long id, DoctorSchedule cur, ScheduleEditForm req) {
        // 原因: 仅在更换日期时校验不可早于今天，历史排班仍需支持调整容量
        if (!Objects.equals(cur.getWorkDate(), req.getWorkDate())) {
            if (req.getWorkDate() == null) {
                throw new BizException("请选择排班日期");
            }
            checkDateNotPast(req.getWorkDate());
        }
        checkTimeOrder(req.getStartTime(), req.getEndTime());
        checkPeriod(req.getPeriod());
        boolean sameSlot = isSameSlot(cur, req);
        if (!sameSlot && existsSlot(id, req)) {
            throw new BizException("该医生在这一天的这个时段已经排过班了");
        }
        boolean movesAttribution = !sameSlot || isTimeChanged(cur, req);
        validateAttributionAndDoctor(id, req.getDoctorId(), movesAttribution);
        if (!Objects.equals(cur.getPeriod(), req.getPeriod()) || isTimeChanged(cur, req)) {
            checkPeriodTime(req.getPeriod(), req.getStartTime(), req.getEndTime());
        }
    }

    private void validateAttributionAndDoctor(Long id, @Nullable Long doctorId, boolean movesAttribution) {
        long refs = referencedById(id);
        if (refs > 0 && movesAttribution) {
            throw new BizException("该时段已有 " + refs + " 条预约记录（含已取消），不能改医生、日期或时段，"
                    + "请另建时段；号源总数仍可调整");
        }
        // 原因: 仅转移排班归属时要求医生在诊；改容量只需医生档案存在
        if (movesAttribution) {
            requireSchedulable(doctorId);
        } else {
            requireDoctor(doctorId);
        }
    }

    private boolean existsSlot(Long excludeId, ScheduleEditForm req) {
        return exists(Wrappers.<DoctorSchedule>lambdaQuery()
                .eq(DoctorSchedule::getDoctorId, req.getDoctorId())
                .eq(DoctorSchedule::getWorkDate, req.getWorkDate())
                .eq(DoctorSchedule::getPeriod, req.getPeriod())
                .ne(DoctorSchedule::getId, excludeId));
    }

    private static boolean isSameSlot(DoctorSchedule cur, ScheduleEditForm req) {
        return Objects.equals(cur.getDoctorId(), req.getDoctorId())
                && Objects.equals(cur.getWorkDate(), req.getWorkDate())
                && Objects.equals(cur.getPeriod(), req.getPeriod());
    }

    private static boolean isTimeChanged(DoctorSchedule cur, ScheduleEditForm req) {
        return !Objects.equals(cur.getStartTime(), req.getStartTime())
                || !Objects.equals(cur.getEndTime(), req.getEndTime());
    }

    /**
     * 按星期模板批量生成排班，保留已有排班及其号源。
     *
     * @param req 批量模板；仅处理日期范围内今天及以后、且匹配 weekDays 的日期，
     *            已存在的医生、日期、时段组合计入 skipped
     * @return 新建条数与已有组合的跳过次数；日期筛选未命中的日期不计入 skipped
     */
    @Transactional
    public BatchSummary batchCreate(ScheduleBatchForm req) {
        requireSchedulable(req.getDoctorId());
        validateBatchRequest(req);
        LocalDate startDate = Objects.requireNonNull(req.getStartDate());
        LocalDate endDate = Objects.requireNonNull(req.getEndDate());
        List<ScheduleBatchForm.PeriodTemplate> templates = Objects.requireNonNull(req.getPeriods());
        Set<Integer> weekDays = Set.copyOf(Objects.requireNonNull(req.getWeekDays()));
        List<DoctorSchedule> pending = new ArrayList<>();
        int skipped = 0;
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            if (d.isBefore(LocalDate.now()) || !weekDays.contains(d.getDayOfWeek().getValue())) {
                continue;
            }
            for (ScheduleBatchForm.PeriodTemplate t : templates) {
                if (exists(Wrappers.<DoctorSchedule>lambdaQuery()
                        .eq(DoctorSchedule::getDoctorId, req.getDoctorId())
                        .eq(DoctorSchedule::getWorkDate, d)
                        .eq(DoctorSchedule::getPeriod, t.getPeriod()))) {
                    skipped++;
                    continue;
                }
                pending.add(buildBatchSchedule(req, d, t));
            }
        }
        if (!pending.isEmpty()) {
            saveBatch(pending);
        }
        return new BatchSummary(pending.size(), skipped);
    }

    private void validateBatchRequest(ScheduleBatchForm req) {
        LocalDate startDate = req.getStartDate();
        LocalDate endDate = req.getEndDate();
        if (startDate == null || endDate == null) {
            throw new BizException("请选择开始和结束日期");
        }
        if (startDate.isAfter(endDate)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        if (endDate.toEpochDay() - startDate.toEpochDay() + 1 > MAX_BATCH_DAYS) {
            throw new BizException("一次最多批量排 90 天");
        }
        List<ScheduleBatchForm.PeriodTemplate> templates = req.getPeriods();
        List<Integer> weekDays = req.getWeekDays();
        if (templates == null || templates.isEmpty() || weekDays == null || weekDays.isEmpty()) {
            throw new BizException("请配置排班星期与时段");
        }
        Set<SchedulePeriod> periods = new HashSet<>();
        for (ScheduleBatchForm.PeriodTemplate t : templates) {
            checkPeriodTime(t.getPeriod(), t.getStartTime(), t.getEndTime());
            if (t.getPeriod() != null && !periods.add(t.getPeriod())) {
                throw new BizException("每个时段只能配置一次");
            }
        }
    }

    private static DoctorSchedule buildBatchSchedule(ScheduleBatchForm req, LocalDate date,
                                                     ScheduleBatchForm.PeriodTemplate t) {
        DoctorSchedule s = new DoctorSchedule();
        s.setDoctorId(req.getDoctorId());
        s.setWorkDate(date);
        s.setPeriod(t.getPeriod());
        s.setStartTime(t.getStartTime());
        s.setEndTime(t.getEndTime());
        int total = Objects.requireNonNull(
                req.getOverrideTotalNum() != null ? req.getOverrideTotalNum() : t.getTotalNum());
        s.setTotalNum(total);
        s.setRemainNum(total);
        s.setStatus(EnableStatus.ENABLED);
        return s;
    }

    /**
     * 删除排班前，在排班行锁内统计未软删预约引用（含已取消预约）；存在引用时拒绝删除，可改为停诊。
     *
     * @param id 排班 ID；仅统计 deleted=0 的预约
     */
    @Transactional
    public void remove(Long id) {
        if (baseMapper.selectByIdForUpdate(id) == null) {
            throw BizException.notFound("排班");
        }
        long refs = referencedById(id);
        if (refs > 0) {
            throw new BizException("该时段已有 " + refs + " 条预约记录（含已取消），不能删除，请改为停诊保留历史");
        }
        removeById(id);
    }

    private long referencedById(Long scheduleId) {
        return appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getScheduleId, scheduleId));
    }

    /**
     * 停诊（R6）：只关可预约状态，不动余量。
     * 已成交的订单保持原样，由前台线下联系改约——这是单店场景下最简单的收敛方式。
     * 之前这里会把 remain_num 一并清零、恢复时再按存活订单重算，页面就分不出
     * "真余量"和"停诊清零"（跟踪表 I07/D2）；余量是事实，不该被一个状态开关改写。
     *
     * <p>状态列<strong>只</strong>在这里和 {@link #resumeClinic} 写入：编辑接口
     * {@link #update} 的入参不含 status，避免旧的编辑表单把停诊覆盖回去。
     *
     * <p>这里用 <code>require</code>（普通读）确认存在，互斥靠 UPDATE 自身的行锁——
     * 不是 <code>selectByIdForUpdate</code>。窄窗口下若记录刚被并发删除，
     * UPDATE 影响 0 行，此时抛 404，不把"没改到"报成"已停诊"。
     */
    @Transactional
    public void stopClinic(Long id) {
        require(id);
        boolean changed = update(Wrappers.<DoctorSchedule>lambdaUpdate()
                .set(DoctorSchedule::getStatus, EnableStatus.DISABLED)
                .eq(DoctorSchedule::getId, id));
        if (!changed) {
            throw BizException.notFound("排班");
        }
    }

    /**
     * 恢复出诊（R6）：只把状态打开，余量原样保留。
     * 停诊期间发生的取消会照常走 release 退回余量，所以这里不需要、也不允许重算。
     *
     * <p>同样靠 UPDATE 自身的行锁做互斥，改了 0 行（被并发删除）时抛 404。
     */
    @Transactional
    public void resumeClinic(Long id) {
        DoctorSchedule s = require(id);
        requireSchedulable(s.getDoctorId());
        boolean changed = update(Wrappers.<DoctorSchedule>lambdaUpdate()
                .set(DoctorSchedule::getStatus, EnableStatus.ENABLED)
                .eq(DoctorSchedule::getId, id));
        if (!changed) {
            throw BizException.notFound("排班");
        }
    }

    public DoctorSchedule require(Long id) {
        DoctorSchedule s = getById(id);
        if (s == null) {
            throw BizException.notFound("排班");
        }
        return s;
    }

    private Doctor requireDoctor(@Nullable Long doctorId) {
        if (doctorId == null) {
            throw BizException.notFound("医生");
        }
        Doctor d = doctorMapper.selectById(doctorId);
        if (d == null) {
            throw BizException.notFound("医生");
        }
        return d;
    }

    private void requireSchedulable(@Nullable Long doctorId) {
        if (requireDoctor(doctorId).getStatus() != EnableStatus.ENABLED) {
            throw new BizException("该医生处于停诊状态，请先在医生管理里恢复出诊再排班");
        }
    }

    private void checkPeriod(@Nullable SchedulePeriod period) {
        if (period == null) {
            throw new BizException("时段只能是 1 上午 / 2 下午 / 3 夜间");
        }
    }

    private void checkSlotSanity(@Nullable LocalDate workDate, @Nullable LocalTime start, @Nullable LocalTime end) {
        if (workDate == null) {
            throw new BizException("请选择排班日期");
        }
        checkDateNotPast(workDate);
        checkTimeOrder(start, end);
    }

    private void checkPeriodTime(@Nullable SchedulePeriod period, @Nullable LocalTime start, @Nullable LocalTime end) {
        checkPeriod(period);
        checkTimeOrder(start, end);
        SchedulePeriod p = Objects.requireNonNull(period);
        LocalTime s = Objects.requireNonNull(start);
        LocalTime e = Objects.requireNonNull(end);
        LocalTime min = LocalTime.of(switch (p) {
            case MORNING -> MORNING_START_HOUR;
            case AFTERNOON -> AFTERNOON_START_HOUR;
            case EVENING -> EVENING_START_HOUR;
        }, 0);
        LocalTime max = LocalTime.of(switch (p) {
            case MORNING -> MORNING_END_HOUR;
            case AFTERNOON -> AFTERNOON_END_HOUR;
            case EVENING -> EVENING_END_HOUR;
        }, 0);
        if (s.isBefore(min) || e.isAfter(max)) {
            String label = switch (p) {
                case MORNING -> "上午";
                case AFTERNOON -> "下午";
                case EVENING -> "夜间";
            };
            throw new BizException(label + "排班时间须在 " + min + "–" + max + " 内");
        }
    }

    private void checkDateNotPast(LocalDate workDate) {
        if (workDate.isBefore(LocalDate.now())) {
            throw new BizException("不能给过去的日期排班");
        }
    }

    private void checkTimeOrder(@Nullable LocalTime start, @Nullable LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BizException("结束时间必须晚于开始时间");
        }
    }

    private Map<Long, Long> refCounts(List<DoctorSchedule> rows) {
        List<Long> ids = rows.stream().map(DoctorSchedule::getId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return appointmentMapper.selectMaps(Wrappers.<Appointment>query()
                        .select("schedule_id AS scheduleId", "COUNT(*) AS num")
                        .in("schedule_id", ids)
                        .groupBy("schedule_id"))
                .stream().filter(m -> m.get("scheduleId") != null)
                .collect(Collectors.toMap(
                        m -> ((Number) Objects.requireNonNull(m.get("scheduleId"))).longValue(),
                        m -> {
                            Number num = (Number) m.get("num");
                            return num == null ? 0L : num.longValue();
                        }));
    }

    private Map<Long, Doctor> doctorsOf(List<DoctorSchedule> rows) {
        return BatchLookup.mapByIds(doctorMapper::selectBatchIds,
                BatchLookup.ids(rows, DoctorSchedule::getDoctorId), Doctor::getId);
    }

    private ScheduleVo toVo(DoctorSchedule s, Map<Long, Doctor> doctorDict, Map<Long, Department> deptDict) {
        ScheduleVo vo = new ScheduleVo();
        BeanUtils.copyProperties(s, vo);
        Doctor d = doctorDict.get(s.getDoctorId());
        if (d == null) {
            vo.setDoctorAvailable(false);
            return vo;
        }
        vo.setDoctorName(d.getName());
        vo.setDoctorTitle(d.getTitle());
        vo.setDoctorAvatar(d.getAvatar());
        vo.setDepartmentId(d.getDepartmentId());
        vo.setConsultationFee(d.getConsultationFee());
        vo.setDoctorAvailable(d.getStatus() == EnableStatus.ENABLED);
        Department dept = deptDict.get(d.getDepartmentId());
        if (dept != null) {
            vo.setDepartmentName(dept.getName());
        }
        return vo;
    }

    private Map<Long, Department> departmentsOf(Collection<Doctor> doctors) {
        return BatchLookup.mapByIds(departmentMapper::selectBatchIds,
                BatchLookup.ids(doctors, Doctor::getDepartmentId), Department::getId);
    }

    public record BatchSummary(int created, int skipped) {
    }
}
