package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.BatchLookup;
import com.yuzhua.common.PageResult;
import com.yuzhua.dto.MedicalRecordForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.dto.RecordQuery;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.MedicalRecord;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.MedicalRecordMapper;
import com.yuzhua.mapper.PetMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.MedicalRecordVo;
import com.yuzhua.vo.PetDisplayVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class MedicalRecordService extends ServiceImpl<MedicalRecordMapper, MedicalRecord> {

    // 原因: 并发首次建档会撞 uk_record_no，秒级时间戳精度不足须到毫秒加随机
    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final AppointmentService appointmentService;
    private final DoctorMapper doctorMapper;
    private final PetMapper petMapper;
    private final DepartmentMapper departmentMapper;

    /** 先锁预约行再查改病历，仅接诊医生可写且仅允许已接诊/已完成状态（R7），防止并发双写或未到院开方。 */
    @Transactional
    public MedicalRecord saveOrUpdateForAppointment(Long appointmentId, MedicalRecordForm req) {
        // 原因: 查空后插入必须在预约行锁内串行化，避免并发首次保存写出同预约双份病历
        Appointment a = requireStaffAppointmentLocked(appointmentId);
        // 原因: R7 状态用白名单仅放行已接诊与已完成，避免黑名单漏态导致未就诊开处方
        if (!Integer.valueOf(Appointment.ST_ACCEPTED).equals(a.getStatus())
                && !Integer.valueOf(Appointment.ST_FINISHED).equals(a.getStatus())) {
            throw new BizException("仅已接诊或已完成的预约可填写病历");
        }
        if (req.getDiagnosis() == null || req.getDiagnosis().isBlank()) {
            throw new BizException("请填写诊断结论");
        }

        MedicalRecord exist = getOne(Wrappers.<MedicalRecord>lambdaQuery()
                .eq(MedicalRecord::getAppointmentId, appointmentId), false);
        MedicalRecord r = new MedicalRecord();
        r.setAppointmentId(appointmentId);
        r.setChiefComplaint(req.getChiefComplaint());
        r.setPhysicalExam(req.getPhysicalExam());
        r.setDiagnosis(req.getDiagnosis().trim());
        r.setPrescription(req.getPrescription());
        r.setTreatment(req.getTreatment());
        r.setAdvice(req.getAdvice());
        r.setNextVisitDate(req.getNextVisitDate());
        r.setImages(req.getImages());
        r.setVisitDate(a.getAppointmentDate() == null ? LocalDate.now() : a.getAppointmentDate());
        if (exist == null) {
            r.setRecordNo("MR" + LocalDateTime.now().format(NO_STAMP)
                    + String.format(Locale.ROOT, "%04d", ThreadLocalRandom.current().nextInt(10000)));
            r.setUserId(a.getUserId());
            r.setPetId(a.getPetId());
            r.setDoctorId(a.getDoctorId());
            r.setDepartmentId(a.getDepartmentId());
            save(r);
        } else {
            r.setId(exist.getId());
            update(r, Wrappers.<MedicalRecord>lambdaUpdate().eq(MedicalRecord::getId, exist.getId())
                    .set(req.isNextVisitDateSubmitted(), MedicalRecord::getNextVisitDate, req.getNextVisitDate()));
        }
        return getById(r.getId());
    }

    public MedicalRecord require(Long id) {
        MedicalRecord r = getById(id);
        if (r == null) {
            throw BizException.notFound("病历");
        }
        return r;
    }

    public PageResult<MedicalRecordVo> pageMine(PageQuery q) {
        LambdaQueryWrapper<MedicalRecord> qw = Wrappers.<MedicalRecord>lambdaQuery()
                .eq(MedicalRecord::getUserId, UserContext.requireId())
                .orderByDesc(MedicalRecord::getVisitDate)
                .orderByDesc(MedicalRecord::getId);
        IPage<MedicalRecord> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, fillNames(page.getRecords()));
    }

    public PageResult<MedicalRecordVo> pageForStaff(RecordQuery q) {
        LoginUser u = UserContext.require();
        Long doctorId = u.isDoctor() ? UserContext.requireDoctorId() : q.getDoctorId();
        String keyword = q.getKeyword();
        LambdaQueryWrapper<MedicalRecord> qw = Wrappers.<MedicalRecord>lambdaQuery()
                .eq(doctorId != null, MedicalRecord::getDoctorId, doctorId)
                .eq(q.getPetId() != null, MedicalRecord::getPetId, q.getPetId())
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(MedicalRecord::getDiagnosis, keyword)
                        .or().like(MedicalRecord::getChiefComplaint, keyword)
                        .or().like(MedicalRecord::getRecordNo, keyword))
                .orderByDesc(MedicalRecord::getId);
        IPage<MedicalRecord> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, fillNames(page.getRecords()));
    }

    private List<MedicalRecordVo> fillNames(List<MedicalRecord> rows) {
        // 原因: 宠物档案逻辑删除后历史病历仍须展示宠物信息，故走历史查询而非普通 selectBatchIds
        Map<Long, PetDisplayVo> pets = BatchLookup.mapByIds(petMapper::selectHistoryDisplay,
                BatchLookup.ids(rows, MedicalRecord::getPetId), PetDisplayVo::getId);
        Map<Long, Doctor> doctors = BatchLookup.mapByIds(doctorMapper::selectBatchIds,
                BatchLookup.ids(rows, MedicalRecord::getDoctorId), Doctor::getId);
        Map<Long, Department> depts = BatchLookup.mapByIds(departmentMapper::selectBatchIds,
                BatchLookup.ids(rows, MedicalRecord::getDepartmentId), Department::getId);
        return rows.stream().map(r -> {
            MedicalRecordVo vo = new MedicalRecordVo();
            BeanUtils.copyProperties(r, vo);
            PetDisplayVo pet = pets.get(r.getPetId());
            if (pet != null) {
                vo.setPetName(pet.getName());
                vo.setPetSpecies(pet.getSpecies());
                vo.setPetBreed(pet.getBreed());
                vo.setPetAvatar(pet.getAvatar());
            }
            Doctor d = doctors.get(r.getDoctorId());
            if (d != null) {
                vo.setDoctorName(d.getName());
                vo.setDoctorTitle(d.getTitle());
            }
            Department dept = depts.get(r.getDepartmentId());
            vo.setDepartmentName(dept == null ? null : dept.getName());
            return vo;
        }).toList();
    }

    public MedicalRecordVo detail(Long id) {
        MedicalRecord r = require(id);
        LoginUser u = UserContext.require();
        if (u.isClient() && !Objects.equals(r.getUserId(), u.getId())) {
            throw BizException.notFound("病历");
        }
        if (u.isDoctor() && !Objects.equals(r.getDoctorId(), u.getDoctorId())) {
            throw BizException.forbidden("看不到别人的病历");
        }
        MedicalRecordVo vo = new MedicalRecordVo();
        BeanUtils.copyProperties(r, vo);
        // 原因: 详情与列表同走含已删档案的历史查询口径，避免已删宠物档案无法展示
        PetDisplayVo pet = BatchLookup.mapByIds(petMapper::selectHistoryDisplay,
                BatchLookup.ids(List.of(r), MedicalRecord::getPetId), PetDisplayVo::getId).get(r.getPetId());
        if (pet != null) {
            vo.setPetName(pet.getName());
            vo.setPetSpecies(pet.getSpecies());
            vo.setPetBreed(pet.getBreed());
            vo.setPetAvatar(pet.getAvatar());
        }
        Doctor d = doctorMapper.selectById(r.getDoctorId());
        if (d != null) {
            vo.setDoctorName(d.getName());
            vo.setDoctorTitle(d.getTitle());
        }
        Department dept = r.getDepartmentId() == null ? null : departmentMapper.selectById(r.getDepartmentId());
        vo.setDepartmentName(dept == null ? null : dept.getName());
        return vo;
    }

    public @Nullable MedicalRecord ofAppointment(Long appointmentId) {
        return getOne(Wrappers.<MedicalRecord>lambdaQuery()
                .eq(MedicalRecord::getAppointmentId, appointmentId), false);
    }

    public @Nullable MedicalRecord ofAppointmentForStaff(Long appointmentId) {
        requireStaffAppointment(appointmentId);
        return ofAppointment(appointmentId);
    }

    public @Nullable MedicalRecord ofAppointmentForOwner(Long appointmentId) {
        appointmentService.requireMine(appointmentId);
        return ofAppointment(appointmentId);
    }

    private Appointment requireStaffAppointment(Long appointmentId) {
        Appointment a = appointmentService.requireExists(appointmentId);
        requireMayTouch(a);
        return a;
    }

    private Appointment requireStaffAppointmentLocked(Long appointmentId) {
        Appointment a = appointmentService.lockForWrite(appointmentId);
        LoginUser u = UserContext.require();
        if (!u.isDoctor()) {
            throw BizException.forbidden("病历只能由医生账号书写，管理员为只读");
        }
        requireMayTouch(a);
        return a;
    }

    private void requireMayTouch(Appointment a) {
        LoginUser u = UserContext.require();
        if (u.isDoctor() && !Objects.equals(a.getDoctorId(), u.getDoctorId())) {
            throw BizException.forbidden("只能处理自己接诊的单子");
        }
    }

}
