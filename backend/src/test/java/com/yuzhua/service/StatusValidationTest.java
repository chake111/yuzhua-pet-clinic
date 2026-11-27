package com.yuzhua.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuzhua.common.BizException;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.dto.DepartmentForm;
import com.yuzhua.dto.DoctorForm;
import com.yuzhua.dto.NoticeForm;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.entity.Notice;
import com.yuzhua.mapper.AdminUserMapper;
import com.yuzhua.mapper.AppointmentMapper;
import com.yuzhua.mapper.ConsultSessionMapper;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.mapper.DoctorReviewMapper;
import com.yuzhua.mapper.DoctorScheduleMapper;
import com.yuzhua.mapper.MedicalRecordMapper;
import com.yuzhua.security.LoginUser;
import com.yuzhua.websocket.ConsultSocketRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 科室与医生写入入口的状态/编码校验。
 *
 * <p>拦截点必须落在任何写入之前：非法值不能先进库再靠事务回滚兜底，
 * 否则并发读与失败提示都会看到中间态。医生编辑与停诊/恢复共用同一把状态写入出口，
 * 这里一并验证在线标志的联动。
 */
class StatusValidationTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Department.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Doctor.class);
    }

    @SuppressWarnings("unchecked")
    static <T> T mapper(Class<T> type, Function<String, Object> result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> result.apply(method.getName()));
    }

    static <T> List<Object> values(Wrapper<T> wrapper) {
        return new ArrayList<>(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values());
    }

    // ---------------------------------------------------------------- 科室

    static class DeptService extends DepartmentService {
        Department current;
        Department saved;
        Wrapper<Department> updated;
        int writes;

        DeptService() {
            super(mapper(DoctorMapper.class, name -> null),
                    mapper(AppointmentMapper.class, name -> 0L),
                    mapper(MedicalRecordMapper.class, name -> 0L),
                    mapper(DoctorReviewMapper.class, name -> 0L));
        }

        @Override
        public boolean exists(Wrapper<Department> query) {
            return false;
        }

        @Override
        public boolean save(Department row) {
            saved = row;
            return true;
        }

        @Override
        public boolean updateById(Department row) {
            saved = row;
            return true;
        }

        @Override
        public boolean update(Wrapper<Department> query) {
            updated = query;
            writes++;
            return true;
        }

        @Override
        public Department require(Long id) {
            return current;
        }
    }

    static DepartmentForm department(String name, String code, EnableStatus status) {
        DepartmentForm d = new DepartmentForm();
        d.setName(name);
        d.setCode(code);
        d.setStatus(status);
        return d;
    }

    void reject(Runnable action, String message) {
        BizException e = assertThrows(BizException.class, action::run);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    void assertNoWrite(DeptService s) {
        assertNull(s.saved);
        assertEquals(0, s.writes);
    }

    @Test
    void departmentRejectsBlankCodeBeforeWrite() {
        DeptService s = new DeptService();
        reject(() -> s.create(department("内科", null, EnableStatus.ENABLED)), "请填写科室编码");
        reject(() -> s.create(department("内科", "   ", EnableStatus.ENABLED)), "请填写科室编码");
        assertNoWrite(s);
    }

    @Test
    void departmentDefaultsStatusOnCreateAndPreservesItOnEdit() {
        DeptService s = new DeptService();
        s.create(department("内科", "NK", null));
        assertEquals(EnableStatus.ENABLED, s.saved.getStatus());
        assertEquals(99, s.saved.getSort());

        s.saved = null;
        s.current = new Department();
        s.current.setId(1L);
        s.current.setName("内科");
        s.current.setCode("NK");
        s.current.setStatus(EnableStatus.DISABLED);
        DepartmentForm edit = department("内科", "NK", null);
        s.update(1L, edit);
        assertEquals(1L, s.saved.getId());
        assertNull(s.saved.getStatus(), "编辑省略状态时应保持未提交，由 updateById 保留原值");
    }

    @Test
    void departmentStatusSwitchRejectsNullAndWritesEnum() {
        DeptService s = new DeptService();
        s.current = new Department();
        s.current.setId(1L);
        s.current.setStatus(EnableStatus.ENABLED);
        reject(() -> s.changeStatus(1L, null), "状态只能是");
        assertEquals(0, s.writes);

        s.changeStatus(1L, EnableStatus.DISABLED);
        assertTrue(values(s.updated).contains(EnableStatus.DISABLED));
        s.changeStatus(1L, EnableStatus.ENABLED);
        assertTrue(values(s.updated).contains(EnableStatus.ENABLED));
    }

    // ---------------------------------------------------------------- 医生

    static class DocService extends DoctorService {
        Doctor current;
        Doctor saved;
        Wrapper<Doctor> updated;
        int writes;

        DocService(ConsultSocketRegistry registry) {
            super(mapper(DepartmentMapper.class, name -> {
                        Department d = new Department();
                        d.setId(1L);
                        return d;
                    }),
                    mapper(DoctorScheduleMapper.class, name -> null),
                    mapper(AppointmentMapper.class, name -> 0L),
                    mapper(MedicalRecordMapper.class, name -> 0L),
                    mapper(ConsultSessionMapper.class, name -> 0L),
                    mapper(DoctorReviewMapper.class, name -> 0L),
                    mapper(AdminUserMapper.class, name -> 0L),
                    registry);
        }

        @Override
        public Doctor require(Long id) {
            return current;
        }

        @Override
        public boolean save(Doctor row) {
            saved = row;
            return true;
        }

        @Override
        public boolean updateById(Doctor row) {
            saved = row;
            return true;
        }

        @Override
        public boolean update(Wrapper<Doctor> query) {
            updated = query;
            writes++;
            return true;
        }
    }

    static DoctorForm doctor(EnableStatus status) {
        DoctorForm d = new DoctorForm();
        d.setName("陈明远");
        d.setDepartmentId(1L);
        d.setConsultationFee(new BigDecimal("50"));
        d.setStatus(status);
        return d;
    }

    static Doctor doctorEntity(EnableStatus status) {
        Doctor d = new Doctor();
        d.setId(1L);
        d.setName("陈明远");
        d.setDepartmentId(1L);
        d.setStatus(status);
        return d;
    }

    static LoginUser doctorUser(long doctorId) {
        LoginUser u = new LoginUser();
        u.setId(99L);
        u.setTyp(TokenType.ADMIN);
        u.setRole(UserRole.DOCTOR);
        u.setDoctorId(doctorId);
        return u;
    }

    void assertNoWrite(DocService s) {
        assertNull(s.saved);
        assertEquals(0, s.writes);
    }

    @Test
    void doctorCreateDefaultsStatusAndOnlineFlag() {
        DocService s = new DocService(new ConsultSocketRegistry());
        s.create(doctor(null));
        assertEquals(EnableStatus.ENABLED, s.saved.getStatus());
        assertEquals(0, s.saved.getOnlineStatus());

        s.saved = null;
        s.create(doctor(EnableStatus.DISABLED));
        assertEquals(EnableStatus.DISABLED, s.saved.getStatus());
        assertEquals(0, s.saved.getOnlineStatus());
    }

    @Test
    void doctorEditAndStatusSwitchShareOneStatusWritePath() {
        ConsultSocketRegistry registry = new ConsultSocketRegistry();
        DocService s = new DocService(registry);
        s.current = doctorEntity(EnableStatus.ENABLED);

        // 编辑里停诊：通用字段写入时不能带 status，状态与在线标志由 applyStatus 一次写清
        s.update(1L, doctor(EnableStatus.DISABLED));
        assertNull(s.saved.getStatus(), "updateById 不应写 status");
        assertEquals(1, s.writes);
        assertTrue(values(s.updated).contains(EnableStatus.DISABLED));

        // 恢复出诊且医生仍有活动连接：onlineStatus 跟随为 1
        registry.open(new ConsultSocketRegistry.Conn(
                ConsultSocketRegistry.keyOf(doctorUser(1L)), doctorUser(1L), null));
        s.changeStatus(1L, EnableStatus.ENABLED);
        assertTrue(values(s.updated).contains(EnableStatus.ENABLED), "status 应为 ENABLED");
        assertTrue(values(s.updated).contains(1), "onlineStatus 应为 1");
        assertEquals(2, s.writes);

        // 没有活动连接时恢复出诊不能误报在线
        ConsultSocketRegistry offline = new ConsultSocketRegistry();
        DocService s2 = new DocService(offline);
        s2.current = doctorEntity(EnableStatus.DISABLED);
        s2.changeStatus(1L, EnableStatus.ENABLED);
        assertTrue(values(s2.updated).contains(0), "无连接时 onlineStatus 应为 0");
    }

    @Test
    void doctorStatusSwitchRejectsNull() {
        DocService s = new DocService(new ConsultSocketRegistry());
        s.current = doctorEntity(EnableStatus.ENABLED);
        reject(() -> s.changeStatus(1L, null), "状态只能是");
        assertNoWrite(s);
    }

    // ---------------------------------------------------------------- 公告

    static class NoticeSvc extends NoticeService {
        Notice current;
        Notice saved;

        @Override
        public boolean save(Notice row) {
            saved = row;
            return true;
        }

        @Override
        public boolean updateById(Notice row) {
            saved = row;
            return true;
        }

        @Override
        public Notice require(Long id) {
            return current;
        }
    }

    static NoticeForm notice(String title, NoticeType type, Integer topFlag) {
        NoticeForm f = new NoticeForm();
        f.setTitle(title);
        f.setType(type);
        f.setTopFlag(topFlag);
        f.setStatus(NoticeStatus.DRAFT);
        return f;
    }

    void assertNoWrite(NoticeSvc s) {
        assertNull(s.saved);
    }

    @Test
    void noticeRejectsInvalidTopFlagBeforeWrite() {
        NoticeSvc s = new NoticeSvc();
        reject(() -> s.create(notice("公告", NoticeType.NOTICE, 2)), "置顶标志只能是");
        reject(() -> s.create(notice("公告", NoticeType.NOTICE, -1)), "置顶标志只能是");
        assertNoWrite(s);

        s.current = new Notice();
        s.current.setId(1L);
        s.current.setType(NoticeType.NOTICE);
        s.current.setTopFlag(0);
        s.current.setStatus(NoticeStatus.DRAFT);
        reject(() -> s.update(1L, notice("公告", NoticeType.NOTICE, 2)), "置顶标志只能是");
        assertNoWrite(s);
    }
}
