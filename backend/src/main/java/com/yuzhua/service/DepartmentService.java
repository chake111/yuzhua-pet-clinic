package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.dto.DepartmentForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.MedicalRecordMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.entity.Appointment;
import com.yuzhua.entity.MedicalRecord;
import com.yuzhua.entity.DoctorReview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService extends ServiceImpl<DepartmentMapper, Department> {

    private final DoctorMapper doctorMapper;
    private final AppointmentMapper appointmentMapper;
    private final MedicalRecordMapper recordMapper;
    private final DoctorReviewMapper reviewMapper;

    public List<Department> listEnabled() {
        return list(Wrappers.<Department>lambdaQuery()
                .eq(Department::getStatus, 1)
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId));
    }

    public PageResult<Department> page(PageQuery query, String keyword) {
        LambdaQueryWrapper<Department> qw = Wrappers.<Department>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), Department::getName, keyword)
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId);
        return PageResult.of(page(new Page<>(query.getPageNum(), query.getPageSize()), qw));
    }

    /** 校验编码唯一后新建科室；默认排序 99、默认状态启用。 */
    @Transactional
    public Department create(DepartmentForm form) {
        requireName(form.getName());
        requireCode(form.getCode());
        if (form.getStatus() != null) {
            requireStatus(form.getStatus());
        }
        if (exists(Wrappers.<Department>lambdaQuery().eq(Department::getCode, form.getCode()))) {
            throw new BizException("科室编码 " + form.getCode() + " 已存在");
        }
        Department d = new Department();
        d.setName(form.getName());
        d.setCode(form.getCode());
        d.setIcon(form.getIcon());
        d.setDescription(form.getDescription());
        d.setSort(form.getSort() == null ? 99 : form.getSort());
        d.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        save(d);
        return d;
    }

    /** 校验科室存在与编码唯一后更新科室配置。 */
    @Transactional
    public void update(Long id, DepartmentForm form) {
        Department d = require(id);
        requireName(form.getName());
        requireCode(form.getCode());
        if (form.getStatus() != null) {
            requireStatus(form.getStatus());
        }
        if (!form.getCode().equals(d.getCode())
                && exists(Wrappers.<Department>lambdaQuery()
                .eq(Department::getCode, form.getCode()).ne(Department::getId, id))) {
            throw new BizException("科室编码 " + form.getCode() + " 已存在");
        }
        Department row = new Department();
        row.setId(id);
        row.setName(form.getName());
        row.setCode(form.getCode());
        row.setIcon(form.getIcon());
        row.setDescription(form.getDescription());
        row.setSort(form.getSort());
        row.setStatus(form.getStatus());
        updateById(row);
    }

    /**
     * 在 READ_COMMITTED 与科室行锁内校验无医生挂靠及历史业务引用后逻辑删除科室。
     * 与 DoctorService 写入共用科室行锁，防止删科与转科并发产生悬空归属。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(Long id) {
        // 原因: 必须走锁定读校验存在并串行化 DoctorService 写入，避免快照读并发产生悬空医生
        if (baseMapper.selectByIdForUpdate(id) == null) {
            throw BizException.notFound("科室");
        }
        long mounted = doctorMapper.selectCount(Wrappers.<Doctor>lambdaQuery()
                .eq(Doctor::getDepartmentId, id));
        if (mounted > 0) {
            throw new BizException("该科室下还有 " + mounted + " 名医生，请先调整医生归属");
        }
        if (appointmentMapper.selectCount(Wrappers.<Appointment>lambdaQuery().eq(Appointment::getDepartmentId, id)) > 0
                || recordMapper.selectCount(Wrappers.<MedicalRecord>lambdaQuery().eq(MedicalRecord::getDepartmentId, id)) > 0
                || reviewMapper.selectCount(Wrappers.<DoctorReview>lambdaQuery().eq(DoctorReview::getDepartmentId, id)) > 0) {
            throw new BizException("科室已有业务记录，请使用停用保留档案");
        }
        removeById(id);
    }

    public void changeStatus(Long id, Integer status) {
        requireStatus(status);
        require(id);
        update(Wrappers.<Department>lambdaUpdate()
                .set(Department::getStatus, status)
                .eq(Department::getId, id));
    }

    public Department require(Long id) {
        Department d = getById(id);
        if (d == null) {
            throw BizException.notFound("科室");
        }
        return d;
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("请填写科室名称");
        }
    }

    private void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("请填写科室编码");
        }
    }

    private void requireStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态只能是停用或启用");
        }
    }
}
