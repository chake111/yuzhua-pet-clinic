package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.PageResult;
import com.yuzhua.props.AppProperties;
import com.yuzhua.dto.AppointmentForm;
import com.yuzhua.dto.AppointmentQuery;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.ConsultSession;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorSchedule;
import com.yuzhua.entity.MedicalRecord;
import com.yuzhua.entity.Pet;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.ConsultSessionMapper;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorScheduleMapper;
import com.yuzhua.mapper.MedicalRecordMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.AppointmentVo;
import com.yuzhua.vo.PetDisplayVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService extends ServiceImpl<AppointmentMapper, Appointment> {

    // 原因: 毫秒时间戳后补 8 位随机，将百并发抢号下撞 uk_order_no 的概率压到可忽略（总长 27 字符）
    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    // 原因: 收紧幂等键为 8-64 位 URL 安全字符，入口即拒脏键避免落库截断或排序规则差异导致异常重放
    private static final Pattern IDEM_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private final PetService petService;
    private final PetMapper petMapper;
    private final DoctorMapper doctorMapper;
    private final DepartmentMapper departmentMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final SysUserMapper sysUserMapper;
    private final ConsultSessionMapper sessionMapper;
    private final MedicalRecordMapper recordMapper;
    private final AppProperties props;
    private final PlatformTransactionManager txManager;

    public AppointmentVo create(AppointmentForm req) {
        return create(req, null);
    }

    public AppointmentVo create(AppointmentForm req, @Nullable String idemKey) {
        String key = normalizeIdemKey(idemKey);
        // 原因: 仅键非空时提前取当前用户，无键路径延续 petService.requireOwned 的 401/404 校验顺序
        Long userId = key == null ? null : UserContext.requireId();
        if (key != null && userId != null) {
            Appointment existing = baseMapper.selectByUserAndIdemKey(userId, key);
            if (existing != null) {
                return replayOf(existing, req);
            }
        }

        // 原因: 须在事务外捕获唯一键冲突并回滚扣号后才能读到并发首单，不能在类内套 @Transactional
        TransactionTemplate tx = new TransactionTemplate(txManager);
        // 原因: READ_COMMITTED 使排班行锁串行化后能读到刚提交的同键预约与同宠物占座记录
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return Objects.requireNonNull(tx.execute(status -> doCreate(req, key, userId)));
        } catch (DuplicateKeyException e) {
            if (key == null || userId == null) {
                throw e;
            }
            // 原因: 事务已回滚扣号与计数，此时重读确认是同键并发冲突还是 uk_order_no 碰撞
            Appointment existing = readIdemRowWithRetry(userId, key);
            if (existing == null) {
                throw e;
            }
            log.warn("下单唯一键冲突命中幂等键，回读原单：user={} key={} appointment={}",
                    userId, key, existing.getId());
            return replayOf(existing, req);
        } catch (BizException e) {
            // 原因: 参数校验失败也可能是并发首单尚未提交，再回读确认后决定重放或抛原异常
            if (key != null && userId != null) {
                Appointment existing = readIdemRowWithRetry(userId, key);
                if (existing != null) {
                    log.warn("下单校验失败但同键原单已存在，按幂等返回原单：user={} key={} appointment={}",
                            userId, key, existing.getId());
                    return replayOf(existing, req);
                }
            }
            throw e;
        }
    }

    private AppointmentVo doCreate(AppointmentForm req, @Nullable String key, @Nullable Long userId) {
        // 原因: scheduleId 非空时先锁排班再查幂等键以收敛同键并发；新建仅插新单不锁已有预约，与取消路径不成环
        DoctorSchedule sch = req.getScheduleId() == null ? null : scheduleMapper.selectByIdForUpdate(req.getScheduleId());
        if (key != null && userId != null) {
            Appointment existing = baseMapper.selectByUserAndIdemKey(userId, key);
            if (existing != null) {
                return replayOf(existing, req);
            }
        }

        if (req.getPetId() == null) {
            throw BizException.notFound("宠物档案");
        }
        Pet pet = petService.requireOwned(req.getPetId());
        Doctor doctor = validateCreateTarget(req, sch, pet);
        DoctorSchedule validSch = Objects.requireNonNull(sch);
        Long schId = Objects.requireNonNull(validSch.getId());
        Appointment a = buildNewAppointment(req, key, pet, doctor, validSch);
        // 原因: 扣号放在写单之前，影响行数为 0 说明最后一个号已被抢走
        if (scheduleMapper.tryOccupy(schId) == 0) {
            throw new BizException("该时段号源已约满，请换个时间");
        }
        // 原因: 队列序号取自占号时递增的当前读，避免 REPEATABLE READ 快照读到旧号
        Integer queueNo = scheduleMapper.selectLastQueueNoForUpdate(schId);
        if (queueNo == null) {
            throw new BizException("号源状态异常，请稍后重试");
        }
        a.setQueueNo(queueNo);
        save(a);
        return detailOf(a, false);
    }

    private Doctor validateCreateTarget(AppointmentForm req, @Nullable DoctorSchedule sch, Pet pet) {
        Doctor doctor = req.getDoctorId() == null ? null : doctorMapper.selectById(req.getDoctorId());
        if (doctor == null) {
            throw BizException.notFound("医生");
        }
        if (!Integer.valueOf(1).equals(doctor.getStatus())) {
            throw new BizException("该医生已停诊，请改约其他医生");
        }
        if (req.getSymptom() == null || req.getSymptom().isBlank()) {
            throw new BizException("请描述症状");
        }
        if (sch == null) {
            throw BizException.notFound("排班时段");
        }
        if (!Objects.equals(sch.getDoctorId(), req.getDoctorId())) {
            throw new BizException("所选时段不属于该医生");
        }
        if (!Integer.valueOf(1).equals(sch.getStatus())) {
            throw new BizException("该时段已停诊，请换个时间");
        }
        requireNotPast(sch);
        if (count(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getPetId, pet.getId())
                .eq(Appointment::getScheduleId, sch.getId())
                .ne(Appointment::getStatus, Appointment.ST_CANCELLED)) > 0) {
            throw new BizException("该宠物已预约此时段，请在我的预约中查看");
        }
        return doctor;
    }

    private Appointment buildNewAppointment(AppointmentForm req, @Nullable String key, Pet pet,
                                            Doctor doctor, DoctorSchedule sch) {
        Appointment a = new Appointment();
        a.setOrderNo(nextOrderNo());
        a.setIdemKey(key);
        a.setUserId(pet.getUserId());
        a.setPetId(pet.getId());
        a.setDoctorId(doctor.getId());
        a.setDepartmentId(doctor.getDepartmentId());
        a.setConsultType(Appointment.CT_VISIT);
        a.setScheduleId(sch.getId());
        a.setAppointmentDate(sch.getWorkDate());
        a.setPeriod(sch.getPeriod());
        a.setStartTime(sch.getStartTime());
        a.setEndTime(sch.getEndTime());
        a.setSymptom(Objects.requireNonNull(req.getSymptom()).trim());
        a.setImages(blankToNull(req.getImages()));
        DoctorService.requireFee(doctor.getConsultationFee());
        a.setAmount(doctor.getConsultationFee());
        a.setPayStatus(Appointment.PAY_NONE);
        a.setStatus(Appointment.ST_PENDING);
        a.setIsReviewed(0);
        return a;
    }

    /** 锁预约行并校验超时与状态后完成模拟支付，条件更新防重复扣款/写时间。 */
    @Transactional
    public AppointmentVo pay(Long id) {
        Appointment a = lockOwned(id);
        if (!Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())) {
            throw new BizException("当前状态不能支付");
        }
        if (Integer.valueOf(Appointment.PAY_PAID).equals(a.getPayStatus())) {
            return detailOf(a, false);
        }
        if (isPayExpired(a)) {
            throw new BizException("订单已超过 " + props.getPayTimeoutMinutes() + " 分钟未支付，请重新预约");
        }
        // 原因: 取一次当前时刻用于落库与响应回填，保持 payTime 一致
        LocalDateTime now = LocalDateTime.now();
        if (baseMapper.update(null, Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getPayStatus, Appointment.PAY_PAID)
                .set(Appointment::getPayTime, now)
                .set(Appointment::getPayType, "MOCK")
                .eq(Appointment::getId, id)
                .eq(Appointment::getStatus, Appointment.ST_PENDING)
                .eq(Appointment::getPayStatus, Appointment.PAY_NONE)) == 0) {
            throw new BizException("订单状态已变化，请刷新后重试");
        }
        a.setPayStatus(Appointment.PAY_PAID);
        a.setPayTime(now);
        a.setPayType("MOCK");
        return detailOf(a, false);
    }

    /** 用户取消本人待接诊预约：先锁预约再条件更新并退号，已支付订单标记已退款（VisitOverdueException 不回滚）。 */
    @Transactional(noRollbackFor = VisitOverdueException.class)
    public void cancelByUser(Long id, @Nullable String reason) {
        cancelLocked(lockOwned(id), blankToNull(reason), "USER");
    }

    /** 医生或管理员取消待接诊预约：先锁预约校验权限再退号退款（VisitOverdueException 不回滚）。 */
    @Transactional(noRollbackFor = VisitOverdueException.class)
    public void cancelByStaff(Long id, @Nullable String reason) {
        Appointment a = lockForUpdate(id);
        requireStaffMayTouch(a);
        cancelLocked(a, blankToNull(reason), UserContext.require().isDoctor() ? "DOCTOR" : "ADMIN");
    }

    private void cancelLocked(Appointment a, @Nullable String reason, String by) {
        requireVisitNotOverdue(a);
        if (Integer.valueOf(Appointment.ST_CANCELLED).equals(a.getStatus())) {
            throw new BizException("该预约已取消");
        }
        if (!Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())) {
            throw new BizException("已进入接诊流程，无法取消，请联系前台处理");
        }
        if (baseMapper.update(null, Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getStatus, Appointment.ST_CANCELLED)
                .set(Appointment::getCancelReason, reason)
                .set(Appointment::getCancelBy, by)
                .set(Appointment::getCancelTime, LocalDateTime.now())
                .set(Integer.valueOf(Appointment.PAY_PAID).equals(a.getPayStatus()),
                        Appointment::getPayStatus, Appointment.PAY_REFUNDED)
                .eq(Appointment::getId, a.getId())
                .eq(Appointment::getStatus, Appointment.ST_PENDING)) == 0) {
            throw new BizException("该预约已取消");
        }
        if (a.getScheduleId() != null) {
            scheduleMapper.release(a.getScheduleId());
        }
    }

    /** 医生接诊：锁行校验归属、未过就诊日、已支付且待接诊后推进至已接诊（VisitOverdueException 不回滚）。 */
    @Transactional(noRollbackFor = VisitOverdueException.class)
    public void accept(Long id) {
        Appointment a = lockForUpdate(id);
        requireDoctorMayTouch(a);
        requireVisitNotOverdue(a);
        if (!Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())) {
            throw new BizException("只有待接诊的预约可以接诊");
        }
        if (!Integer.valueOf(Appointment.PAY_PAID).equals(a.getPayStatus())) {
            throw new BizException("患者尚未支付，不能开始接诊");
        }
        if (baseMapper.update(null, Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getStatus, Appointment.ST_ACCEPTED)
                .set(Appointment::getAcceptTime, LocalDateTime.now())
                .eq(Appointment::getId, id)
                .eq(Appointment::getStatus, Appointment.ST_PENDING)) == 0) {
            throw new BizException("该预约状态已变化，请刷新后重试");
        }
    }

    /** 医生结束就诊并递增接诊量；允许结束后补写病历，不强制病历已存在。 */
    @Transactional
    public void finish(Long id) {
        Appointment a = lockForUpdate(id);
        requireDoctorMayTouch(a);
        if (!Integer.valueOf(Appointment.ST_ACCEPTED).equals(a.getStatus())) {
            throw new BizException("请先接诊再结束");
        }
        if (baseMapper.update(null, Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getStatus, Appointment.ST_FINISHED)
                .set(Appointment::getFinishTime, LocalDateTime.now())
                .eq(Appointment::getId, id)
                .eq(Appointment::getStatus, Appointment.ST_ACCEPTED)) == 0) {
            throw new BizException("该预约状态已变化，请刷新后重试");
        }
        Long doctorId = a.getDoctorId();
        if (doctorId != null) {
            doctorMapper.incrementAppointmentCount(doctorId);
        }
    }

    public PageResult<AppointmentVo> pageMine(@Nullable Integer status, PageQuery q) {
        LambdaQueryWrapper<Appointment> qw = Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getUserId, UserContext.requireId())
                .eq(status != null, Appointment::getStatus, status)
                .orderByDesc(Appointment::getCreateTime);
        return voPage(page(new Page<>(q.getPageNum(), q.getPageSize()), qw), false);
    }

    public List<AppointmentVo> listByUserForAdmin(Long userId, int limit) {
        List<Appointment> rows = list(Wrappers.<Appointment>lambdaQuery()
                .eq(Appointment::getUserId, userId)
                .orderByDesc(Appointment::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        return buildVos(rows, true);
    }

    public PageResult<AppointmentVo> pageForStaff(AppointmentQuery q) {
        LoginUser u = UserContext.require();
        Long doctorId = u.isDoctor() ? UserContext.requireDoctorId() : q.getDoctorId();
        LocalDate start = q.getDay() != null ? q.getDay() : q.getStart();
        LocalDate end = q.getDay() != null ? q.getDay() : q.getEnd();
        LambdaQueryWrapper<Appointment> qw = Wrappers.<Appointment>lambdaQuery()
                .eq(doctorId != null, Appointment::getDoctorId, doctorId)
                .eq(q.getUserId() != null, Appointment::getUserId, q.getUserId())
                .eq(q.getStatus() != null, Appointment::getStatus, q.getStatus())
                .eq(q.getPayStatus() != null, Appointment::getPayStatus, q.getPayStatus())
                .ge(start != null, Appointment::getAppointmentDate, start)
                .le(end != null, Appointment::getAppointmentDate, end)
                .orderByDesc(Appointment::getAppointmentDate)
                .orderByAsc(Appointment::getPeriod)
                .orderByAsc(Appointment::getQueueNo);
        return voPage(page(new Page<>(q.getPageNum(), q.getPageSize()), qw), true);
    }

    public AppointmentVo detail(Long id) {
        Appointment a = requireExists(id);
        LoginUser u = UserContext.require();
        if (u.isClient()) {
            // 原因: 越权查他人预约返回 404 不存在，避免泄露可枚举的订单存在信号
            if (!Objects.equals(a.getUserId(), u.getId())) {
                throw BizException.notFound("预约记录");
            }
            return detailOf(a, false);
        }
        if (u.isDoctor() && !Objects.equals(a.getDoctorId(), u.getDoctorId())) {
            throw BizException.forbidden("只能查看自己接诊的单子");
        }
        return detailOf(a, true);
    }

    public Appointment requireMine(Long id) {
        Appointment a = requireExists(id);
        if (!Objects.equals(a.getUserId(), UserContext.requireId())) {
            throw BizException.notFound("预约记录");
        }
        return a;
    }

    public Appointment requireExists(Long id) {
        Appointment a = getById(id);
        if (a == null) {
            throw BizException.notFound("预约记录");
        }
        return a;
    }

    private Appointment lockForUpdate(Long id) {
        Appointment a = baseMapper.selectByIdForUpdate(id);
        if (a == null) {
            throw BizException.notFound("预约记录");
        }
        return a;
    }

    /** 先锁预约行，供病历首次落库等下游写入在同一临界区内判空与插库（锁顺序：预约在前）。 */
    @Transactional
    public Appointment lockForWrite(Long id) {
        return lockForUpdate(id);
    }

    private Appointment lockOwned(Long id) {
        Appointment a = lockForUpdate(id);
        if (!Objects.equals(a.getUserId(), UserContext.requireId())) {
            throw BizException.notFound("预约记录");
        }
        return a;
    }

    public void markReviewed(Long appointmentId) {
        update(Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getIsReviewed, 1)
                .eq(Appointment::getId, appointmentId));
    }

    public List<Long> unpaidOverdueIds() {
        return baseMapper.selectUnpaidOverdueIds(
                LocalDateTime.now().minusMinutes(props.getPayTimeoutMinutes()));
    }

    /**
     * 逐单独立事务取消超时未支付预约并退号（防整批共事务与用户取消锁顺序成环死锁）；
     * 锁内复核状态与超时线，仅确实执行取消时返回 true。
     */
    @Transactional
    public boolean expireOne(Long id) {
        Appointment a = baseMapper.selectByIdForUpdate(id);
        if (a == null || !Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())
                || !Integer.valueOf(Appointment.PAY_NONE).equals(a.getPayStatus())
                || !isPayExpired(a)) {
            return false;
        }
        cancelLocked(a, "超过 " + props.getPayTimeoutMinutes() + " 分钟未支付，系统自动取消", "SYSTEM");
        return true;
    }

    public List<Long> staleVisitIds() {
        return baseMapper.selectStaleVisitIds();
    }

    /**
     * 锁内复核后将过就诊日仍待接诊的已支付订单记为未到诊（R3，不退号不退款）。
     * 仅本次实际改写状态时返回 true。
     */
    @Transactional
    public boolean markMissedOne(Long id) {
        Appointment a = baseMapper.selectByIdForUpdate(id);
        if (a == null || a.getAppointmentDate() == null
                || !a.getAppointmentDate().isBefore(LocalDate.now())
                || !Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())
                || !Integer.valueOf(Appointment.PAY_PAID).equals(a.getPayStatus())) {
            return false;
        }
        return baseMapper.update(null, Wrappers.<Appointment>lambdaUpdate()
                .set(Appointment::getStatus, Appointment.ST_MISSED)
                .eq(Appointment::getId, id)
                .eq(Appointment::getStatus, Appointment.ST_PENDING)
                .eq(Appointment::getPayStatus, Appointment.PAY_PAID)) > 0;
    }

    private static @Nullable String normalizeIdemKey(@Nullable String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            return null;
        }
        String key = idemKey.trim();
        if (!IDEM_KEY.matcher(key).matches()) {
            throw new BizException("幂等键格式不正确");
        }
        return key;
    }

    private AppointmentVo replayOf(Appointment existing, AppointmentForm req) {
        // 原因: 再校验归属防止未来复用此重放逻辑时越权返回他人订单
        if (!Objects.equals(existing.getUserId(), UserContext.requireId())) {
            throw BizException.notFound("预约记录");
        }
        if (!Objects.equals(existing.getPetId(), req.getPetId())
                || !Objects.equals(existing.getScheduleId(), req.getScheduleId())) {
            log.warn("幂等键重放参数不一致，返回原单：appointment={} 首次 pet={} schedule={} 本次 pet={} schedule={}",
                    existing.getId(), existing.getPetId(), existing.getScheduleId(),
                    req.getPetId(), req.getScheduleId());
        }
        return detailOf(existing, false);
    }

    private @Nullable Appointment readIdemRowWithRetry(Long userId, String key) {
        Appointment existing = baseMapper.selectByUserAndIdemKey(userId, key);
        for (int attempt = 0; existing == null && attempt < 2; attempt++) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            existing = baseMapper.selectByUserAndIdemKey(userId, key);
        }
        return existing;
    }

    public static class VisitOverdueException extends BizException {
        public VisitOverdueException() { super("预约已过就诊日，已标记未到诊"); }
    }

    private void requireVisitNotOverdue(Appointment a) {
        if (Integer.valueOf(Appointment.ST_PENDING).equals(a.getStatus())
                && Integer.valueOf(Appointment.PAY_PAID).equals(a.getPayStatus())
                && a.getAppointmentDate() != null && a.getAppointmentDate().isBefore(LocalDate.now())) {
            Long id = a.getId();
            if (id != null) {
                markMissedOne(id);
            }
            throw new VisitOverdueException();
        }
    }

    private void requireDoctorMayTouch(Appointment a) {
        LoginUser u = UserContext.require();
        if (!u.isDoctor()) {
            throw BizException.forbidden("接诊与结束就诊仅限医生账号，管理员不能代为操作");
        }
        requireStaffMayTouch(a);
    }

    private void requireStaffMayTouch(Appointment a) {
        LoginUser u = UserContext.require();
        if (u.isDoctor() && !Objects.equals(a.getDoctorId(), u.getDoctorId())) {
            throw BizException.forbidden("只能处理自己的接诊单");
        }
    }

    private void requireNotPast(DoctorSchedule sch) {
        LocalDate workDate = sch.getWorkDate();
        LocalTime endTime = sch.getEndTime();
        if (workDate == null || endTime == null) {
            throw new BizException("排班时段数据异常");
        }
        LocalDate today = LocalDate.now();
        if (workDate.isBefore(today)) {
            throw new BizException("该时段已过期");
        }
        if (workDate.isEqual(today) && LocalTime.now().isAfter(endTime)) {
            throw new BizException("该时段的门诊已经结束，请改约其他时间");
        }
    }

    private boolean isPayExpired(Appointment a) {
        if (a.getCreateTime() == null) {
            return false;
        }
        LocalDateTime deadline = a.getCreateTime().plusMinutes(props.getPayTimeoutMinutes());
        return !LocalDateTime.now().isBefore(deadline);
    }

    private String nextOrderNo() {
        return "PC" + LocalDateTime.now().format(NO_STAMP)
                + String.format(Locale.ROOT, "%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private AppointmentVo detailOf(Appointment a, boolean withPatient) {
        return buildVos(List.of(a), withPatient).get(0);
    }

    private PageResult<AppointmentVo> voPage(Page<Appointment> page, boolean withPatient) {
        return PageResult.of(page, buildVos(page.getRecords(), withPatient));
    }

    private List<AppointmentVo> buildVos(List<Appointment> rows, boolean withPatient) {
        if (rows.isEmpty()) {
            return List.of();
        }
        VoLookup lookup = loadVoLookup(rows, withPatient);
        List<AppointmentVo> out = new ArrayList<>(rows.size());
        for (Appointment a : rows) {
            out.add(toVo(a, lookup, withPatient));
        }
        return out;
    }

    private VoLookup loadVoLookup(List<Appointment> rows, boolean withPatient) {
        // 原因: 宠物档案逻辑删除后旧单据仍需展示宠物信息，走历史查询而非普通 selectBatchIds
        Map<Long, PetDisplayVo> pets = BatchLookup.mapByIds(petMapper::selectHistoryDisplay,
                BatchLookup.ids(rows, Appointment::getPetId), PetDisplayVo::getId);
        Map<Long, Doctor> doctors = BatchLookup.mapByIds(doctorMapper::selectBatchIds,
                BatchLookup.ids(rows, Appointment::getDoctorId), Doctor::getId);
        Set<Long> deptIds = new HashSet<>(BatchLookup.ids(doctors.values(), Doctor::getDepartmentId));
        deptIds.addAll(BatchLookup.ids(rows, Appointment::getDepartmentId));
        Map<Long, Department> depts = BatchLookup.mapByIds(departmentMapper::selectBatchIds,
                deptIds, Department::getId);
        Map<Long, SysUser> users = withPatient
                ? BatchLookup.mapByIds(sysUserMapper::selectBatchIds,
                BatchLookup.ids(rows, Appointment::getUserId), SysUser::getId)
                : Map.of();
        Set<Long> orderIds = BatchLookup.ids(rows, Appointment::getId);
        Map<Long, Long> sessionIds = new HashMap<>();
        sessionMapper.selectList(Wrappers.<ConsultSession>lambdaQuery()
                        .in(ConsultSession::getAppointmentId, orderIds))
                .forEach(s -> {
                    if (s.getAppointmentId() != null && s.getId() != null) {
                        sessionIds.putIfAbsent(s.getAppointmentId(), s.getId());
                    }
                });
        Map<Long, Long> recordIds = new HashMap<>();
        recordMapper.selectList(Wrappers.<MedicalRecord>lambdaQuery()
                        .in(MedicalRecord::getAppointmentId, orderIds))
                .forEach(r -> {
                    if (r.getAppointmentId() != null && r.getId() != null) {
                        recordIds.putIfAbsent(r.getAppointmentId(), r.getId());
                    }
                });
        return new VoLookup(pets, doctors, depts, users, sessionIds, recordIds);
    }

    private AppointmentVo toVo(Appointment a, VoLookup lookup, boolean withPatient) {
        AppointmentVo vo = new AppointmentVo();
        BeanUtils.copyProperties(a, vo);
        if (a.getCreateTime() != null) {
            vo.setPayDeadline(a.getCreateTime().plusMinutes(props.getPayTimeoutMinutes()));
        }
        PetDisplayVo pet = lookup.pets().get(a.getPetId());
        if (pet != null) {
            vo.setPetName(pet.getName());
            vo.setPetSpecies(pet.getSpecies());
            vo.setPetBreed(pet.getBreed());
            vo.setPetAvatar(pet.getAvatar());
        }
        Doctor d = lookup.doctors().get(a.getDoctorId());
        if (d != null) {
            vo.setDoctorName(d.getName());
            vo.setDoctorTitle(d.getTitle());
            vo.setDoctorAvatar(d.getAvatar());
        }
        Department dept = lookup.depts().get(a.getDepartmentId());
        vo.setDepartmentName(dept == null ? null : dept.getName());
        if (withPatient) {
            SysUser u = lookup.users().get(a.getUserId());
            if (u != null) {
                vo.setUserNickname(u.getNickname());
                vo.setUserPhone(u.getPhone());
            }
        }
        vo.setSessionId(lookup.sessionIds().get(a.getId()));
        vo.setRecordId(lookup.recordIds().get(a.getId()));
        return vo;
    }

    private record VoLookup(
            Map<Long, PetDisplayVo> pets,
            Map<Long, Doctor> doctors,
            Map<Long, Department> depts,
            Map<Long, SysUser> users,
            Map<Long, Long> sessionIds,
            Map<Long, Long> recordIds) {
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
