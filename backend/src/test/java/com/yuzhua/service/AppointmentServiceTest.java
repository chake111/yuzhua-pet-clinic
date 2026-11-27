package com.yuzhua.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.domain.enums.CancelSource;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.PayStatus;
import com.yuzhua.domain.enums.SchedulePeriod;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppointmentService 的角色与归属越权隔离、接诊/完成/未到诊状态机、前置拒绝
 * 与非幂等唯一键透传的单元测试。
 *
 * <p>下单主路径、支付、取消、幂等重放与超时清扫的真实链路由 *IT（Testcontainers）覆盖，
 * 这里只保留 IT 未覆盖的复杂不变量；每个用例都断言业务结果（code/message、状态或写入参数），
 * 而不是只跑一遍代码。Mapper 是 Mockito mock，baseMapper 通过 ReflectionTestUtils 注入父类字段；
 * UserContext 是 ThreadLocal，用例结束必须清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentServiceTest {

    @Mock
    private AppointmentMapper appointmentMapper;
    @Mock
    private PetService petService;
    @Mock
    private PetMapper petMapper;
    @Mock
    private DoctorMapper doctorMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private DoctorScheduleMapper scheduleMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ConsultSessionMapper sessionMapper;
    @Mock
    private MedicalRecordMapper recordMapper;
    @Mock
    private AppProperties props;
    @Mock
    private PlatformTransactionManager txManager;

    private AppointmentService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(Appointment.class, DoctorSchedule.class, Doctor.class,
                Department.class, SysUser.class, ConsultSession.class, MedicalRecord.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "test"), type);
        }
    }

    @BeforeEach
    void setUp() {
        service = new AppointmentService(petService, petMapper, doctorMapper, departmentMapper,
                scheduleMapper, sysUserMapper, sessionMapper, recordMapper, props, txManager);
        ReflectionTestUtils.setField(service, "baseMapper", appointmentMapper);

        // TransactionTemplate 只要求事务管理器返回一个状态对象，提交/回滚在 mock 上是空操作
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(props.getPayTimeoutMinutes()).thenReturn(30);
        when(petService.requireOwned(any())).thenReturn(pet(1L, 7L));
        when(appointmentMapper.insert(any(Appointment.class))).thenReturn(1);
        when(appointmentMapper.update(any(), any())).thenReturn(1);
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(scheduleMapper.tryOccupy(any())).thenReturn(1);
        when(scheduleMapper.selectLastQueueNoForUpdate(any())).thenReturn(1);
        when(petMapper.selectHistoryDisplay(anyCollection())).thenReturn(List.of());
        when(sysUserMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(recordMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------------------------------------------------------- 前置拒绝

    @Test
    void createRejectsUnknownDoctor() {
        when(doctorMapper.selectById(2L)).thenReturn(null);

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("医生不存在");
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    void createRejectsStoppedDoctor() {
        Doctor doctor = doctor(2L, 3L, 0, new BigDecimal("50"));
        when(doctorMapper.selectById(2L)).thenReturn(doctor);

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("该医生已停诊");
        verify(scheduleMapper, never()).tryOccupy(any());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    void createRejectsMissingSymptom() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));

        assertThat(biz(() -> service.create(form(1L, 2L, 4L, null))).getMessage())
                .contains("请描述症状");
        assertThat(biz(() -> service.create(form(1L, 2L, 4L, "   "))).getMessage())
                .contains("请描述症状");
        verify(scheduleMapper, never()).tryOccupy(any());
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    void createRejectsUnknownSchedule() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(null);

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("排班时段不存在");
    }

    @Test
    void createRejectsScheduleOfAnotherDoctor() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule(4L, 99L, LocalDate.now().plusDays(1)));

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("所选时段不属于该医生");
    }

    @Test
    void createRejectsStoppedSchedule() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        DoctorSchedule schedule = schedule(4L, 2L, LocalDate.now().plusDays(1));
        schedule.setStatus(EnableStatus.DISABLED);
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule);

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("该时段已停诊");
    }

    @Test
    void createRejectsPastOrAlreadyEndedSchedule() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));

        DoctorSchedule past = schedule(4L, 2L, LocalDate.now().minusDays(1));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(past);
        BizException pastError = biz(() -> service.create(form(1L, 2L, 4L, "发热")));
        assertThat(pastError.getCode()).isEqualTo(400);
        assertThat(pastError.getMessage()).contains("该时段已过期");

        DoctorSchedule ended = schedule(4L, 2L, LocalDate.now());
        ended.setStartTime(LocalTime.MIN);
        ended.setEndTime(LocalTime.MIN);
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(ended);
        BizException endedError = biz(() -> service.create(form(1L, 2L, 4L, "发热")));
        assertThat(endedError.getCode()).isEqualTo(400);
        assertThat(endedError.getMessage()).contains("门诊已经结束");
    }

    @Test
    void createAcceptsTodayBeforeSlotEnds() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        DoctorSchedule today = schedule(4L, 2L, LocalDate.now());
        today.setStartTime(LocalTime.MIN);
        today.setEndTime(LocalTime.MAX);
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(today);
        when(scheduleMapper.selectLastQueueNoForUpdate(4L)).thenReturn(5);

        AppointmentVo vo = service.create(form(1L, 2L, 4L, "发热"));

        assertThat(vo.getAppointmentDate()).isEqualTo(LocalDate.now());
        assertThat(vo.getQueueNo()).isEqualTo(5);
        assertThat(vo.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        verify(scheduleMapper).tryOccupy(4L);
    }

    @Test
    void createRejectsDuplicatePetAndFullSchedule() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule(4L, 2L, LocalDate.now().plusDays(1)));

        when(appointmentMapper.selectCount(any())).thenReturn(1L);
        BizException duplicate = biz(() -> service.create(form(1L, 2L, 4L, "发热")));
        assertThat(duplicate.getCode()).isEqualTo(400);
        assertThat(duplicate.getMessage()).contains("该宠物已预约此时段");
        verify(scheduleMapper, never()).tryOccupy(any());

        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(scheduleMapper.tryOccupy(4L)).thenReturn(0);
        BizException full = biz(() -> service.create(form(1L, 2L, 4L, "发热")));
        assertThat(full.getCode()).isEqualTo(400);
        assertThat(full.getMessage()).contains("号源已约满");
    }

    @Test
    void createRejectsMissingQueueCounter() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule(4L, 2L, LocalDate.now().plusDays(1)));
        when(scheduleMapper.selectLastQueueNoForUpdate(4L)).thenReturn(null);

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("号源状态异常");
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    // ---------------------------------------------------------------- 非幂等唯一键透传

    @Test
    void createRethrowsDuplicateKeyWhenNoIdemRowCanBeFound() {
        UserContext.set(client(7L));
        String key = "concurrent-key-02";
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule(4L, 2L, LocalDate.now().plusDays(1)));
        when(appointmentMapper.selectByUserAndIdemKey(7L, key)).thenReturn(null);
        when(appointmentMapper.insert(any(Appointment.class)))
                .thenThrow(new DuplicateKeyException("uk_order_no"));

        Throwable thrown = catchThrowable(() -> service.create(form(1L, 2L, 4L, "发热"), key));

        // 撞的是单号唯一键，与幂等键无关，必须原样抛出而不是伪装成重放
        assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void createWithoutIdemKeyRethrowsDuplicateKey() {
        when(doctorMapper.selectById(2L)).thenReturn(doctor(2L, 3L, 1, new BigDecimal("50")));
        when(scheduleMapper.selectByIdForUpdate(4L)).thenReturn(schedule(4L, 2L, LocalDate.now().plusDays(1)));
        when(appointmentMapper.insert(any(Appointment.class)))
                .thenThrow(new DuplicateKeyException("uk_order_no"));

        Throwable thrown = catchThrowable(() -> service.create(form(1L, 2L, 4L, "发热")));

        assertThat(thrown).isInstanceOf(DuplicateKeyException.class);
        verify(appointmentMapper, never()).selectByUserAndIdemKey(any(), any());
    }

    // ---------------------------------------------------------------- 角色与归属越权隔离

    @Test
    void payRejectsMissingOrForeignOrder() {
        UserContext.set(client(7L));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(null);
        assertThat(biz(() -> service.pay(1L)).getCode()).isEqualTo(404);

        Appointment foreign = pendingAppointment(1L, 8L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(foreign);
        BizException e = biz(() -> service.pay(1L));
        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("预约记录不存在");
        verify(appointmentMapper, never()).update(any(), any());
    }

    @Test
    void createReplayWithForeignOwnerIsTreatedAsNotFound() {
        UserContext.set(client(7L));
        String key = "foreign-owner-01";
        // 回读查询本身带 user_id=7；这里模拟将来有人复用到别的入口后拿到他人的行，防御性断言必须挡住。
        when(appointmentMapper.selectByUserAndIdemKey(7L, key)).thenReturn(pendingAppointment(88L, 8L));

        BizException e = biz(() -> service.create(form(1L, 2L, 4L, "发热"), key));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("预约记录不存在");
        verify(appointmentMapper, never()).insert(any(Appointment.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelByStaffUsesOperatorRole() {
        Appointment appointment = pendingAppointment(1L, 7L);
        appointment.setScheduleId(null);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(appointment);

        UserContext.set(doctor(2L));
        service.cancelByStaff(1L, "临时停诊");
        ArgumentCaptor<Wrapper<Appointment>> doctorCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper, times(1)).update(isNull(), doctorCaptor.capture());
        assertThat(values(doctorCaptor.getValue())).contains("临时停诊", CancelSource.DOCTOR);

        UserContext.set(admin());
        service.cancelByStaff(1L, "前台改约");
        ArgumentCaptor<Wrapper<Appointment>> adminCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper, times(2)).update(isNull(), adminCaptor.capture());
        assertThat(values(adminCaptor.getValue())).contains("前台改约", CancelSource.ADMIN);
    }

    @Test
    void cancelByStaffRejectsOtherDoctorAndMissingOrder() {
        Appointment appointment = pendingAppointment(1L, 7L);
        appointment.setDoctorId(2L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(appointment);

        UserContext.set(doctor(99L));
        BizException forbidden = biz(() -> service.cancelByStaff(1L, null));
        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(forbidden.getMessage()).contains("只能处理自己的接诊单");

        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(null);
        BizException missing = biz(() -> service.cancelByStaff(1L, null));
        assertThat(missing.getCode()).isEqualTo(404);
        verify(appointmentMapper, never()).update(any(), any());
    }

    @Test
    void pageMineFiltersByCurrentUserAndOptionalStatus() {
        UserContext.set(client(7L));
        Appointment row = pendingAppointment(1L, 7L);
        row.setId(10L);
        doAnswer(invocation -> {
            Page<Appointment> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        }).when(appointmentMapper).selectPage(any(), any());

        PageResult<AppointmentVo> all = service.pageMine(null, new PageQuery());
        assertThat(all.getTotal()).isEqualTo(1);
        assertThat(all.getRecords()).singleElement().satisfies(vo -> {
            assertThat(vo.getId()).isEqualTo(10L);
            assertThat(vo.getUserNickname()).isNull();
        });

        PageResult<AppointmentVo> filtered = service.pageMine(AppointmentStatus.ACCEPTED, new PageQuery());
        assertThat(filtered.getRecords()).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Appointment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper, times(2)).selectPage(any(), captor.capture());
        assertThat(values(captor.getAllValues().get(0))).contains(7L).doesNotContain(AppointmentStatus.ACCEPTED);
        assertThat(values(captor.getAllValues().get(1))).contains(7L, AppointmentStatus.ACCEPTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageForStaffForcesDoctorToOwnOrders() {
        UserContext.set(doctor(77L));
        AppointmentQuery query = new AppointmentQuery();
        query.setDoctorId(999L);
        query.setDay(LocalDate.now().plusDays(2));
        doAnswer(invocation -> {
            Page<Appointment> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        }).when(appointmentMapper).selectPage(any(), any());

        PageResult<AppointmentVo> result = service.pageForStaff(query);

        assertThat(result.getRecords()).isEmpty();
        ArgumentCaptor<Wrapper<Appointment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper).selectPage(any(), captor.capture());
        assertThat(values(captor.getValue())).contains(77L).doesNotContain(999L);
    }

    @Test
    void detailAppliesClientOwnership() {
        Appointment mine = pendingAppointment(1L, 7L);
        when(appointmentMapper.selectById(1L)).thenReturn(mine);
        UserContext.set(client(7L));

        AppointmentVo vo = service.detail(1L);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getUserNickname()).isNull();

        when(appointmentMapper.selectById(2L)).thenReturn(pendingAppointment(2L, 8L));
        BizException foreign = biz(() -> service.detail(2L));
        assertThat(foreign.getCode()).isEqualTo(404);
        assertThat(foreign.getMessage()).contains("预约记录不存在");
    }

    @Test
    void detailEnforcesDoctorScopeAndAllowsAdmin() {
        Appointment appointment = pendingAppointment(1L, 7L);
        appointment.setDoctorId(2L);
        when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        SysUser owner = new SysUser();
        owner.setId(7L);
        owner.setNickname("小王");
        owner.setPhone("13800000000");
        when(sysUserMapper.selectBatchIds(anyCollection())).thenReturn(List.of(owner));

        UserContext.set(doctor(2L));
        AppointmentVo own = service.detail(1L);
        assertThat(own.getUserNickname()).isEqualTo("小王");
        assertThat(own.getUserPhone()).isEqualTo("13800000000");

        UserContext.set(doctor(3L));
        BizException forbidden = biz(() -> service.detail(1L));
        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(forbidden.getMessage()).contains("只能查看自己接诊的单子");

        UserContext.set(admin());
        assertThat(service.detail(1L).getId()).isEqualTo(1L);
    }

    @Test
    void detailRejectsMissingAppointment() {
        when(appointmentMapper.selectById(1L)).thenReturn(null);
        UserContext.set(admin());

        BizException e = biz(() -> service.detail(1L));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("预约记录不存在");
    }

    @Test
    void requireMineChecksOwnershipAndExistence() {
        UserContext.set(client(7L));
        when(appointmentMapper.selectById(1L)).thenReturn(pendingAppointment(1L, 7L));
        assertThat(service.requireMine(1L).getUserId()).isEqualTo(7L);

        when(appointmentMapper.selectById(2L)).thenReturn(pendingAppointment(2L, 8L));
        assertThat(biz(() -> service.requireMine(2L)).getCode()).isEqualTo(404);

        when(appointmentMapper.selectById(3L)).thenReturn(null);
        assertThat(biz(() -> service.requireMine(3L)).getCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- 接诊 / 完成 / 未到诊状态机

    @Test
    void cancelByUserMarksOverduePaidOrderAsMissed() {
        UserContext.set(client(7L));
        Appointment overdue = pendingAppointment(1L, 7L);
        overdue.setPayStatus(PayStatus.PAID);
        overdue.setAppointmentDate(LocalDate.now().minusDays(1));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(overdue);

        Throwable thrown = catchThrowable(() -> service.cancelByUser(1L, null));

        assertThat(thrown).isInstanceOf(AppointmentService.VisitOverdueException.class);
        assertThat(thrown.getMessage()).contains("已标记未到诊");
        ArgumentCaptor<Wrapper<Appointment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper).update(isNull(), captor.capture());
        assertThat(values(captor.getValue())).contains(AppointmentStatus.MISSED);
    }

    @Test
    void acceptEnforcesDoctorOwnershipAndState() {
        Appointment appointment = pendingAppointment(1L, 7L);
        appointment.setDoctorId(2L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(appointment);

        UserContext.set(admin());
        BizException adminError = biz(() -> service.accept(1L));
        assertThat(adminError.getCode()).isEqualTo(403);
        assertThat(adminError.getMessage()).contains("仅限医生账号");

        UserContext.set(doctor(99L));
        BizException foreignError = biz(() -> service.accept(1L));
        assertThat(foreignError.getCode()).isEqualTo(403);
        assertThat(foreignError.getMessage()).contains("只能处理自己的接诊单");

        UserContext.set(doctor(2L));
        Appointment accepted = pendingAppointment(1L, 7L);
        accepted.setDoctorId(2L);
        accepted.setStatus(AppointmentStatus.ACCEPTED);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(accepted);
        assertThat(biz(() -> service.accept(1L)).getMessage()).contains("只有待接诊");

        Appointment unpaid = pendingAppointment(1L, 7L);
        unpaid.setDoctorId(2L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(unpaid);
        assertThat(biz(() -> service.accept(1L)).getMessage()).contains("尚未支付");

        verify(appointmentMapper, never()).update(any(), any());
    }

    @Test
    void acceptMarksOverdueOrderAsMissed() {
        UserContext.set(doctor(2L));
        Appointment overdue = pendingAppointment(1L, 7L);
        overdue.setDoctorId(2L);
        overdue.setPayStatus(PayStatus.PAID);
        overdue.setAppointmentDate(LocalDate.now().minusDays(1));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(overdue);

        Throwable thrown = catchThrowable(() -> service.accept(1L));

        assertThat(thrown).isInstanceOf(AppointmentService.VisitOverdueException.class);
        verify(appointmentMapper).update(any(), any());
    }

    @Test
    void acceptFailsWhenConditionalUpdateMisses() {
        UserContext.set(doctor(2L));
        Appointment paid = pendingAppointment(1L, 7L);
        paid.setDoctorId(2L);
        paid.setPayStatus(PayStatus.PAID);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(paid);
        when(appointmentMapper.update(any(), any())).thenReturn(0);

        BizException e = biz(() -> service.accept(1L));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("状态已变化");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptMarksOrderAccepted() {
        UserContext.set(doctor(2L));
        Appointment paid = pendingAppointment(1L, 7L);
        paid.setDoctorId(2L);
        paid.setPayStatus(PayStatus.PAID);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(paid);

        service.accept(1L);

        ArgumentCaptor<Wrapper<Appointment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper).update(isNull(), captor.capture());
        assertThat(values(captor.getValue())).contains(AppointmentStatus.ACCEPTED, 1L);
    }

    @Test
    void finishEnforcesDoctorAndAcceptedState() {
        Appointment accepted = pendingAppointment(1L, 7L);
        accepted.setDoctorId(2L);
        accepted.setStatus(AppointmentStatus.ACCEPTED);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(accepted);

        UserContext.set(admin());
        BizException adminError = biz(() -> service.finish(1L));
        assertThat(adminError.getCode()).isEqualTo(403);

        UserContext.set(doctor(2L));
        Appointment pending = pendingAppointment(1L, 7L);
        pending.setDoctorId(2L);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(pending);
        assertThat(biz(() -> service.finish(1L)).getMessage()).contains("请先接诊再结束");

        when(appointmentMapper.update(any(), any())).thenReturn(0);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(accepted);
        BizException missed = biz(() -> service.finish(1L));
        assertThat(missed.getCode()).isEqualTo(400);
        assertThat(missed.getMessage()).contains("状态已变化");
        verify(doctorMapper, never()).incrementAppointmentCount(any());
    }

    @Test
    void finishMarksDoneAndIncrementsDoctorCount() {
        UserContext.set(doctor(2L));
        Appointment accepted = pendingAppointment(1L, 7L);
        accepted.setDoctorId(2L);
        accepted.setStatus(AppointmentStatus.ACCEPTED);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(accepted);

        service.finish(1L);

        verify(appointmentMapper).update(any(), any());
        verify(doctorMapper).incrementAppointmentCount(2L);
    }

    @Test
    void markMissedOneRechecksDateStatusAndPayment() {
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(null);
        assertThat(service.markMissedOne(1L)).isFalse();

        Appointment noDate = pendingAppointment(1L, 7L);
        noDate.setPayStatus(PayStatus.PAID);
        noDate.setAppointmentDate(null);
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(noDate);
        assertThat(service.markMissedOne(1L)).isFalse();

        Appointment today = pendingAppointment(1L, 7L);
        today.setPayStatus(PayStatus.PAID);
        today.setAppointmentDate(LocalDate.now());
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(today);
        assertThat(service.markMissedOne(1L)).isFalse();

        Appointment accepted = pendingAppointment(1L, 7L);
        accepted.setPayStatus(PayStatus.PAID);
        accepted.setStatus(AppointmentStatus.ACCEPTED);
        accepted.setAppointmentDate(LocalDate.now().minusDays(1));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(accepted);
        assertThat(service.markMissedOne(1L)).isFalse();

        Appointment unpaid = pendingAppointment(1L, 7L);
        unpaid.setAppointmentDate(LocalDate.now().minusDays(1));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(unpaid);
        assertThat(service.markMissedOne(1L)).isFalse();
        verify(appointmentMapper, never()).update(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void markMissedOneWritesMissedStatusOnlyWhenConditionalUpdateMatches() {
        Appointment stale = pendingAppointment(1L, 7L);
        stale.setPayStatus(PayStatus.PAID);
        stale.setAppointmentDate(LocalDate.now().minusDays(1));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(stale);

        assertThat(service.markMissedOne(1L)).isTrue();
        ArgumentCaptor<Wrapper<Appointment>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(appointmentMapper).update(isNull(), captor.capture());
        assertThat(values(captor.getValue()))
                .contains(AppointmentStatus.MISSED, AppointmentStatus.PENDING, PayStatus.PAID, 1L);

        when(appointmentMapper.update(any(), any())).thenReturn(0);
        assertThat(service.markMissedOne(1L)).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    private static Pet pet(long id, long userId) {
        Pet pet = new Pet();
        pet.setId(id);
        pet.setUserId(userId);
        pet.setName("团子");
        return pet;
    }

    private static Doctor doctor(long id, long departmentId, int status, BigDecimal fee) {
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setDepartmentId(departmentId);
        doctor.setStatus(status == 1 ? EnableStatus.ENABLED : EnableStatus.DISABLED);
        doctor.setConsultationFee(fee);
        doctor.setName("陈明远");
        return doctor;
    }

    private static DoctorSchedule schedule(long id, long doctorId, LocalDate workDate) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(id);
        schedule.setDoctorId(doctorId);
        schedule.setWorkDate(workDate);
        schedule.setPeriod(SchedulePeriod.MORNING);
        schedule.setStartTime(LocalTime.of(9, 0));
        schedule.setEndTime(LocalTime.of(11, 0));
        schedule.setTotalNum(10);
        schedule.setRemainNum(10);
        schedule.setStatus(EnableStatus.ENABLED);
        return schedule;
    }

    private static Appointment pendingAppointment(long id, long userId) {
        Appointment appointment = new Appointment();
        appointment.setId(id);
        appointment.setUserId(userId);
        appointment.setPetId(1L);
        appointment.setDoctorId(2L);
        appointment.setDepartmentId(3L);
        appointment.setScheduleId(4L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setPeriod(SchedulePeriod.MORNING);
        appointment.setStartTime(LocalTime.of(9, 0));
        appointment.setEndTime(LocalTime.of(11, 0));
        appointment.setStatus(AppointmentStatus.PENDING);
        appointment.setPayStatus(PayStatus.UNPAID);
        appointment.setAmount(new BigDecimal("50"));
        return appointment;
    }

    private static AppointmentForm form(Long petId, Long doctorId, Long scheduleId, String symptom) {
        AppointmentForm form = new AppointmentForm();
        form.setPetId(petId);
        form.setDoctorId(doctorId);
        form.setScheduleId(scheduleId);
        form.setSymptom(symptom);
        form.setImages("   ");
        return form;
    }

    private static LoginUser client(long id) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setTyp(TokenType.CLIENT);
        user.setRole(UserRole.USER);
        user.setUsername("client" + id);
        return user;
    }

    private static LoginUser doctor(long doctorId) {
        LoginUser user = new LoginUser();
        user.setId(900L);
        user.setTyp(TokenType.ADMIN);
        user.setRole(UserRole.DOCTOR);
        user.setDoctorId(doctorId);
        user.setUsername("doctor" + doctorId);
        return user;
    }

    private static LoginUser admin() {
        LoginUser user = new LoginUser();
        user.setId(901L);
        user.setTyp(TokenType.ADMIN);
        user.setRole(UserRole.ADMIN);
        user.setUsername("admin");
        return user;
    }

    private static BizException biz(Runnable action) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).as("expected BizException").isInstanceOf(BizException.class);
        return (BizException) thrown;
    }

    private static List<Object> values(Wrapper<?> wrapper) {
        // eq 的参数是延迟写入 paramNameValuePairs 的 ISqlSegment，先求值 SQL 再读字典。
        ((AbstractWrapper<?, ?, ?>) wrapper).getSqlSegment();
        return new java.util.ArrayList<>(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values());
    }
}