package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.dto.DoctorForm;
import com.yuzhua.dto.DoctorQuery;
import com.yuzhua.entity.AdminUser;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.ConsultSession;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.DoctorReview;
import com.yuzhua.entity.DoctorSchedule;
import com.yuzhua.entity.MedicalRecord;
import com.yuzhua.mapper.AdminUserMapper;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.ConsultSessionMapper;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.mapper.DoctorScheduleMapper;
import com.yuzhua.mapper.MedicalRecordMapper;
import com.yuzhua.consult.ws.DoctorPresenceGateway;
import com.yuzhua.vo.DoctorVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DoctorService extends ServiceImpl<DoctorMapper, Doctor> {

    private static final int MAX_TOP_LIMIT = 50;
    private static final int DEFAULT_SORT = 99;
    private static final BigDecimal MAX_CONSULTATION_FEE = new BigDecimal("9999");

    private final DepartmentMapper departmentMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final AppointmentMapper appointmentMapper;
    private final MedicalRecordMapper recordMapper;
    private final ConsultSessionMapper sessionMapper;
    private final DoctorReviewMapper reviewMapper;
    private final AdminUserMapper adminUserMapper;
    private final DoctorPresenceGateway socketRegistry;

    public PageResult<DoctorVo> pageVo(DoctorQuery q, boolean visibleOnly) {
        LambdaQueryWrapper<Doctor> qw = Wrappers.<Doctor>lambdaQuery()
                .eq(q.getDepartmentId() != null, Doctor::getDepartmentId, q.getDepartmentId())
                .eq(q.getStatus() != null, Doctor::getStatus, q.getStatus())
                .and(q.getKeyword() != null && !q.getKeyword().isBlank(), w -> w
                        .like(Doctor::getName, q.getKeyword())
                        .or().like(Doctor::getSpecialty, q.getKeyword())
                        .or().like(Doctor::getTitle, q.getKeyword()));
        if (visibleOnly) {
            qw.eq(Doctor::getStatus, EnableStatus.ENABLED);
        }
        applySort(qw, q.getSort());

        Page<Doctor> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        Map<Long, Department> dict = departmentNames(page.getRecords());
        List<DoctorVo> rows = page.getRecords().stream()
                .map(d -> toVo(d, dict))
                .toList();
        return PageResult.of(page, rows);
    }

    public DoctorVo detail(Long id) {
        Doctor d = require(id);
        return toVo(d, departmentNames(List.of(d)));
    }

    public List<DoctorVo> listTopVisible(int limit) {
        List<Doctor> list = list(Wrappers.<Doctor>lambdaQuery()
                .eq(Doctor::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Doctor::getSort)
                .orderByDesc(Doctor::getRating)
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX_TOP_LIMIT))));
        Map<Long, Department> dict = departmentNames(list);
        return list.stream().map(d -> toVo(d, dict)).toList();
    }

    public Doctor require(Long id) {
        Doctor d = getById(id);
        if (d == null) {
            throw BizException.notFound("医生");
        }
        return d;
    }

    /**
     * 在 READ_COMMITTED 下经 {@code requireDepartment}（持 {@code selectByIdForUpdate} 科室行锁）校验科室存在后创建医生，防止与删科并发形成悬空归属；
     * 排序默认 {@code DEFAULT_SORT}，{@code status} 默认 {@code EnableStatus.ENABLED}。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor create(DoctorForm form) {
        requireName(form.getName());
        requireDepartment(form.getDepartmentId());
        requireFee(form.getConsultationFee());
        if (form.getStatus() != null) {
            requireStatus(form.getStatus());
        }
        Doctor d = new Doctor();
        d.setName(form.getName());
        d.setAvatar(form.getAvatar());
        d.setGender(form.getGender());
        d.setPhone(form.getPhone());
        d.setTitle(form.getTitle());
        d.setDepartmentId(form.getDepartmentId());
        d.setSpecialty(form.getSpecialty());
        d.setIntroduction(form.getIntroduction());
        d.setWorkYears(form.getWorkYears());
        d.setConsultationFee(form.getConsultationFee());
        d.setRating(new BigDecimal("5.0"));
        d.setReviewCount(0);
        d.setAppointmentCount(0);
        d.setOnlineStatus(0);
        d.setStatus(form.getStatus() == null ? EnableStatus.ENABLED : form.getStatus());
        d.setSort(form.getSort() == null ? DEFAULT_SORT : form.getSort());
        save(d);
        return d;
    }

    /**
     * 评分、接诊量、在线状态由评价、完成预约和 WebSocket 维护，请求体里也没有这些字段；
     * 状态不在通用更新里写，停诊/恢复还要同步在线标志，统一走 applyStatus。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void update(Long id, DoctorForm form) {
        require(id);
        requireName(form.getName());
        requireDepartment(form.getDepartmentId());
        requireFee(form.getConsultationFee());
        EnableStatus status = form.getStatus();
        if (status != null) {
            requireStatus(status);
        }
        Doctor row = new Doctor();
        row.setId(id);
        row.setName(form.getName());
        row.setAvatar(form.getAvatar());
        row.setGender(form.getGender());
        row.setPhone(form.getPhone());
        row.setTitle(form.getTitle());
        row.setDepartmentId(form.getDepartmentId());
        row.setSpecialty(form.getSpecialty());
        row.setIntroduction(form.getIntroduction());
        row.setWorkYears(form.getWorkYears());
        row.setConsultationFee(form.getConsultationFee());
        row.setSort(form.getSort());
        updateById(row);
        if (status != null) {
            applyStatus(id, status);
        }
    }

    /**
     * 停诊 / 恢复出诊。停诊只挡住将来的号，
     * 已经约出去的单子和排班行保持原样，由前台电话联系改约。
     */
    @Transactional
    public void changeStatus(Long id, @Nullable EnableStatus status) {
        require(id);
        applyStatus(id, status);
    }

    private void applyStatus(Long id, @Nullable EnableStatus status) {
        requireStatus(status);
        update(Wrappers.<Doctor>lambdaUpdate()
                .set(Doctor::getStatus, status)
                .set(Doctor::getOnlineStatus, status == EnableStatus.ENABLED && socketRegistry.doctorConnected(id) ? 1 : 0)
                .eq(Doctor::getId, id));
    }

    private static void requireStatus(@Nullable EnableStatus status) {
        if (status == null) {
            throw new BizException("状态只能是停诊或出诊");
        }
    }

    /** 校验医生不存在关联排班、业务记录与后台账号后才允许删除。 */
    @Transactional
    public void remove(Long id) {
        require(id);
        if (scheduleMapper.selectCount(Wrappers.<DoctorSchedule>lambdaQuery().eq(DoctorSchedule::getDoctorId, id)) > 0
                || appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery().eq(Appointment::getDoctorId, id)) > 0
                || recordMapper.selectCount(Wrappers.<MedicalRecord>lambdaQuery().eq(MedicalRecord::getDoctorId, id)) > 0
                || sessionMapper.selectCount(Wrappers.<ConsultSession>lambdaQuery().eq(ConsultSession::getDoctorId, id)) > 0
                || reviewMapper.selectCount(Wrappers.<DoctorReview>lambdaQuery().eq(DoctorReview::getDoctorId, id)) > 0
                || adminUserMapper.selectCount(Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getDoctorId, id)) > 0) {
            throw new BizException("医生有关联排班、业务记录或后台账号，请使用停诊保留档案");
        }
        removeById(id);
    }

    /** 事务内重算医生评分、评价数与已完成接诊数。 */
    @Transactional
    public void refreshStats(Long doctorId) {
        baseMapper.refreshStats(doctorId);
        baseMapper.refreshAppointmentCount(doctorId);
    }

    private void applySort(LambdaQueryWrapper<Doctor> qw, @Nullable String sort) {
        switch (sort == null ? "" : sort) {
            case "rating" -> qw.orderByDesc(Doctor::getRating).orderByDesc(Doctor::getReviewCount);
            case "appointment" -> qw.orderByDesc(Doctor::getAppointmentCount);
            default -> qw.orderByAsc(Doctor::getSort).orderByAsc(Doctor::getId);
        }
    }

    private void requireName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("请填写医生姓名");
        }
    }

    static void requireFee(@Nullable BigDecimal fee) {
        if (fee == null || fee.signum() < 0 || fee.compareTo(MAX_CONSULTATION_FEE) > 0
                || fee.stripTrailingZeros().scale() > 2) {
            throw new BizException("问诊费须为 0～9999 元，最多两位小数");
        }
    }

    // 原因: 与删科共用科室行锁串行化校验与写入；若将来需锁多科室须按 ID 升序加锁
    private void requireDepartment(@Nullable Long departmentId) {
        if (departmentId == null) {
            throw new BizException("请选择所属科室");
        }
        if (departmentMapper.selectByIdForUpdate(departmentId) == null) {
            throw BizException.notFound("科室");
        }
    }

    private Map<Long, Department> departmentNames(List<Doctor> doctors) {
        return BatchLookup.mapByIds(departmentMapper::selectBatchIds,
                BatchLookup.ids(doctors, Doctor::getDepartmentId), Department::getId);
    }

    private DoctorVo toVo(Doctor d, Map<Long, Department> dict) {
        DoctorVo vo = new DoctorVo();
        BeanUtils.copyProperties(d, vo);
        Department dept = dict.get(d.getDepartmentId());
        if (dept != null) {
            vo.setDepartmentName(dept.getName());
            vo.setDepartmentIcon(dept.getIcon());
        }
        return vo;
    }
}
