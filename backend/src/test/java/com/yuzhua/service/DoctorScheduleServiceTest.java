package com.yuzhua.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuzhua.common.BizException;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DoctorScheduleService 的时段边界、编辑契约护栏、引用冻结与号源下限、历史编辑不写 status、
 * 停诊医生维护与恢复限制、批量去重与天数钳位的单元测试。
 *
 * <p>两套替身并存：Mockito mock 断言写入参数与拒绝路径；内存替身（InMemoryService）
 * 断言 ±1 秒边界与状态写入次数。真实落库链路由 *IT 与 tools/schedule_status_check.mjs 覆盖，
 * 这里只保留复杂不变量，不做 VO 组装与空查询的逐字段断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DoctorScheduleServiceTest {

    @Mock
    private DoctorScheduleMapper scheduleMapper;
    @Mock
    private DoctorMapper doctorMapper;
    @Mock
    private AppointmentMapper appointmentMapper;
    @Mock
    private DepartmentMapper departmentMapper;

    private DoctorScheduleService service;

    // 内存替身共享状态（InMemoryService 是非静态内部类，捕获当前测试实例的字段）
    private DoctorSchedule current;
    private DoctorSchedule saved;
    private long refs;
    private int doctorStatus;
    private int statusWrites;
    private int saveCalls;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(DoctorSchedule.class, Appointment.class, Doctor.class, Department.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "test"), type);
        }
    }

    @BeforeEach
    void setUp() {
        service = spy(new DoctorScheduleService(doctorMapper, appointmentMapper, departmentMapper));
        ReflectionTestUtils.setField(service, "baseMapper", scheduleMapper);

        when(scheduleMapper.insert(any(DoctorSchedule.class))).thenReturn(1);
        when(scheduleMapper.update(any(), any())).thenReturn(1);
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(doctorMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(departmentMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        current = null;
        saved = null;
        refs = 0;
        doctorStatus = 1;
        statusWrites = 0;
        saveCalls = 0;
    }

    // ---------------------------------------------------------------- 用户端选号

    @Test
    @SuppressWarnings("unchecked")
    void listBookableUsesDefaultsAndClampsDays() {
        LocalDate today = LocalDate.now();
        when(scheduleMapper.selectList(any())).thenReturn(List.of(schedule(1L, 10L, today.plusDays(2))));
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));

        assertThat(service.listBookable(10L, null, null)).hasSize(1);
        service.listBookable(10L, today, 0);
        service.listBookable(10L, today, 100);

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper, times(3)).selectList(captor.capture());
        assertThat(values(captor.getAllValues().get(0)))
                .contains(10L, 1);
        assertThat(values(captor.getAllValues().get(1)))
                .contains(10L, 1, today, today);
        assertThat(values(captor.getAllValues().get(2)))
                .contains(10L, 1, today, today.plusDays(29));
    }

    // ---------------------------------------------------------------- 新建校验

    @Test
    void createValidatesDateAndTimeOrder() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));

        assertThat(biz(() -> service.create(form(10L, null, 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, null))).getMessage())
                .contains("请选择排班日期");
        assertThat(biz(() -> service.create(form(10L, LocalDate.now().minusDays(1), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, null))).getMessage())
                .contains("不能给过去的日期排班");
        assertThat(biz(() -> service.create(form(10L, LocalDate.now().plusDays(1), 1,
                LocalTime.of(10, 0), LocalTime.of(10, 0), 10, null))).getMessage())
                .contains("结束时间必须晚于开始时间");
        assertThat(biz(() -> service.create(form(10L, LocalDate.now().plusDays(1), 1,
                null, LocalTime.of(11, 0), 10, null))).getMessage())
                .contains("结束时间必须晚于开始时间");
        assertThat(biz(() -> service.create(form(10L, LocalDate.now().plusDays(1), 1,
                LocalTime.of(9, 0), null, 10, null))).getMessage())
                .contains("结束时间必须晚于开始时间");
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    @Test
    void createValidatesPeriodAndPeriodWindowAndDuplicate() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        LocalDate date = LocalDate.now().plusDays(1);

        assertThat(biz(() -> service.create(form(10L, date, 0,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, null))).getMessage())
                .contains("时段只能是");
        assertThat(biz(() -> service.create(form(10L, date, null,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, null))).getMessage())
                .contains("时段只能是");

        assertThat(biz(() -> service.create(form(10L, date, 1,
                LocalTime.of(7, 0), LocalTime.of(9, 0), 10, null))).getMessage())
                .contains("上午排班时间须在");
        assertThat(biz(() -> service.create(form(10L, date, 2,
                LocalTime.of(12, 0), LocalTime.of(14, 0), 10, null))).getMessage())
                .contains("下午排班时间须在");
        assertThat(biz(() -> service.create(form(10L, date, 3,
                LocalTime.of(17, 0), LocalTime.of(19, 0), 10, null))).getMessage())
                .contains("夜间排班时间须在");

        when(scheduleMapper.exists(any())).thenReturn(true);
        BizException duplicate = biz(() -> service.create(form(10L, date, 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, null)));
        assertThat(duplicate.getMessage()).contains("已经排过班");
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    // ---------------------------------------------------------------- 编辑：引用冻结 / 号源下限 / 归属

    @Test
    void updateRejectsMissingSchedule() {
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(null);

        BizException e = biz(() -> service.update(1L, edit(10L, LocalDate.now().plusDays(1), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(e.getMessage()).contains("排班不存在");
    }

    @Test
    void updateValidatesChangedDateAndSlotFields() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);

        BizException emptyDate = biz(() -> service.update(1L, edit(10L, null, 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));
        assertThat(emptyDate.getMessage()).contains("请选择排班日期");

        BizException pastDate = biz(() -> service.update(1L, edit(10L, LocalDate.now().minusDays(1), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));
        assertThat(pastDate.getMessage()).contains("不能给过去的日期排班");

        BizException badTime = biz(() -> service.update(1L, edit(10L, current.getWorkDate(), 1,
                LocalTime.of(11, 0), LocalTime.of(11, 0), 10)));
        assertThat(badTime.getMessage()).contains("结束时间必须晚于开始时间");

        BizException badPeriod = biz(() -> service.update(1L, edit(10L, current.getWorkDate(), 4,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));
        assertThat(badPeriod.getMessage()).contains("时段只能是");
        verify(scheduleMapper, never()).updateById(any(DoctorSchedule.class));
    }

    @Test
    void updateRejectsDuplicateCrossDoctorSlot() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(scheduleMapper.exists(any())).thenReturn(true);

        BizException e = biz(() -> service.update(1L, edit(11L, current.getWorkDate(), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("已经排过班");
        verify(doctorMapper, never()).selectById(any());
        verify(scheduleMapper, never()).updateById(any(DoctorSchedule.class));
    }

    @Test
    void updateFreezesAttributionWhenReferenced() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(appointmentMapper.selectCount(any())).thenReturn(3L);

        BizException e = biz(() -> service.update(1L, edit(11L, current.getWorkDate(), 2,
                LocalTime.of(13, 0), LocalTime.of(15, 0), 10)));

        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("3 条预约记录").contains("不能改医生、日期或时段");
        verify(doctorMapper, never()).selectById(any());
        verify(scheduleMapper, never()).updateById(any(DoctorSchedule.class));
    }

    @Test
    void updateAllowsCapacityChangeWithReferencesButRejectsBelowBooked() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        current.setRemainNum(7);
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        when(appointmentMapper.selectCount(any())).thenReturn(3L, 1L);

        service.update(1L, edit(10L, current.getWorkDate(), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10));
        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRemainNum()).isEqualTo(9);
        assertThat(captor.getValue().getTotalNum()).isEqualTo(10);

        when(appointmentMapper.selectCount(any())).thenReturn(0L, 5L);
        BizException e = biz(() -> service.update(1L, edit(10L, current.getWorkDate(), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 4)));
        assertThat(e.getCode()).isEqualTo(400);
        assertThat(e.getMessage()).contains("5 个有效预约");
    }

    @Test
    void updateRequiresSchedulableDoctorOnlyWhenAttributionMoves() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(appointmentMapper.selectCount(any())).thenReturn(0L, 0L);
        when(doctorMapper.selectById(11L)).thenReturn(doctor(11L, 5L, 0));

        BizException stopped = biz(() -> service.update(1L, edit(11L, current.getWorkDate(), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));
        assertThat(stopped.getCode()).isEqualTo(400);
        assertThat(stopped.getMessage()).contains("停诊状态");

        when(doctorMapper.selectById(10L)).thenReturn(null);
        BizException missing = biz(() -> service.update(1L, edit(10L, current.getWorkDate(), 1,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10)));
        assertThat(missing.getCode()).isEqualTo(404);
        assertThat(missing.getMessage()).contains("医生不存在");
    }

    @Test
    void updateAllowsChangingPeriodOnSameDay() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        when(appointmentMapper.selectCount(any())).thenReturn(0L, 0L);

        service.update(1L, edit(10L, current.getWorkDate(), 2,
                LocalTime.of(13, 0), LocalTime.of(15, 0), 10));

        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPeriod()).isEqualTo(2);
        assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(captor.getValue().getRemainNum()).isEqualTo(10);
    }

    @Test
    void updateWritesNewAttributionAndValidatesNewWindow() {
        DoctorSchedule current = schedule(1L, 10L, LocalDate.now().plusDays(1));
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(current);
        when(doctorMapper.selectById(11L)).thenReturn(doctor(11L, 5L, 1));
        when(appointmentMapper.selectCount(any())).thenReturn(0L, 0L);
        LocalDate newDate = LocalDate.now().plusDays(2);

        service.update(1L, edit(11L, newDate, 2,
                LocalTime.of(13, 0), LocalTime.of(15, 0), 12));

        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        DoctorSchedule written = captor.getValue();
        assertThat(written.getDoctorId()).isEqualTo(11L);
        assertThat(written.getWorkDate()).isEqualTo(newDate);
        assertThat(written.getPeriod()).isEqualTo(2);
        assertThat(written.getStartTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(written.getEndTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(written.getTotalNum()).isEqualTo(12);
        assertThat(written.getRemainNum()).isEqualTo(12);
        assertThat(written.getStatus()).isNull();

        BizException badWindow = biz(() -> service.update(1L, edit(11L, newDate, 2,
                LocalTime.of(12, 0), LocalTime.of(14, 0), 12)));
        assertThat(badWindow.getMessage()).contains("下午排班时间须在");
    }

    // ---------------------------------------------------------------- 批量排班

    @Test
    void batchCreateRejectsUnschedulableDoctorAndBadRange() {
        when(doctorMapper.selectById(10L)).thenReturn(null);
        assertThat(biz(() -> service.batchCreate(batch(10L, LocalDate.now(), LocalDate.now().plusDays(1),
                List.of(1), List.of(template(1, 9, 11, 10)), null))).getCode()).isEqualTo(404);

        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 0));
        assertThat(biz(() -> service.batchCreate(batch(10L, LocalDate.now(), LocalDate.now().plusDays(1),
                List.of(1), List.of(template(1, 9, 11, 10)), null))).getMessage()).contains("停诊状态");

        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        assertThat(biz(() -> service.batchCreate(batch(10L, LocalDate.now(), LocalDate.now().minusDays(1),
                List.of(1), List.of(template(1, 9, 11, 10)), null))).getMessage()).contains("开始日期不能晚于结束日期");
        assertThat(biz(() -> service.batchCreate(batch(10L, LocalDate.now(), LocalDate.now().plusDays(90),
                List.of(1), List.of(template(1, 9, 11, 10)), null))).getMessage()).contains("最多批量排 90 天");
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    @Test
    void batchCreateRejectsDuplicateAndInvalidTemplates() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        LocalDate date = LocalDate.now().plusDays(1);

        BizException duplicate = biz(() -> service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(1, 9, 11, 10), template(1, 9, 11, 10)), null)));
        assertThat(duplicate.getMessage()).contains("每个时段只能配置一次");

        BizException badPeriod = biz(() -> service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(4, 9, 11, 10)), null)));
        assertThat(badPeriod.getMessage()).contains("时段只能是");

        BizException badWindow = biz(() -> service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(1, 7, 9, 10)), null)));
        assertThat(badWindow.getMessage()).contains("上午排班时间须在");
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    @Test
    void batchCreateSkipsPastAndUnmatchedWeekdays() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));

        DoctorScheduleService.BatchSummary past = service.batchCreate(batch(10L,
                LocalDate.now().minusDays(5), LocalDate.now().minusDays(1),
                List.of(1, 2, 3, 4, 5, 6, 7),
                List.of(template(1, 9, 11, 10)), null));
        assertThat(past.created()).isZero();
        assertThat(past.skipped()).isZero();

        LocalDate future = LocalDate.now().plusDays(1);
        int otherWeekday = future.getDayOfWeek().getValue() % 7 + 1;
        DoctorScheduleService.BatchSummary unmatched = service.batchCreate(batch(10L, future, future,
                List.of(otherWeekday), List.of(template(1, 9, 11, 10)), null));
        assertThat(unmatched.created()).isZero();
        assertThat(unmatched.skipped()).isZero();
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    @Test
    void batchCreateCountsExistingSlotsAsSkipped() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        LocalDate date = LocalDate.now().plusDays(1);
        when(scheduleMapper.exists(any())).thenReturn(true);

        DoctorScheduleService.BatchSummary summary = service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(1, 9, 11, 10)), null));

        assertThat(summary.created()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        verify(scheduleMapper, never()).insert(any(DoctorSchedule.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchCreateSavesNewSlotsAndCountsSkipped() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        LocalDate date = LocalDate.now().plusDays(1);
        when(scheduleMapper.exists(any())).thenReturn(false, true);
        List<DoctorSchedule> saved = new ArrayList<>();
        doAnswer(invocation -> {
            saved.addAll((Collection<DoctorSchedule>) invocation.getArgument(0));
            return true;
        }).when(service).saveBatch(anyCollection(), anyInt());

        DoctorScheduleService.BatchSummary summary = service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(1, 9, 11, 10), template(2, 13, 15, 8)), null));

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(saved).singleElement().satisfies(slot -> {
            assertThat(slot.getDoctorId()).isEqualTo(10L);
            assertThat(slot.getWorkDate()).isEqualTo(date);
            assertThat(slot.getPeriod()).isEqualTo(1);
            assertThat(slot.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(slot.getTotalNum()).isEqualTo(10);
            assertThat(slot.getRemainNum()).isEqualTo(10);
            assertThat(slot.getStatus()).isEqualTo(1);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchCreateAppliesOverrideTotalNumber() {
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        LocalDate date = LocalDate.now().plusDays(1);
        List<DoctorSchedule> saved = new ArrayList<>();
        doAnswer(invocation -> {
            saved.addAll((Collection<DoctorSchedule>) invocation.getArgument(0));
            return true;
        }).when(service).saveBatch(anyCollection(), anyInt());

        DoctorScheduleService.BatchSummary summary = service.batchCreate(batch(10L, date, date,
                List.of(date.getDayOfWeek().getValue()),
                List.of(template(1, 9, 11, 10)), 20));

        assertThat(summary.created()).isEqualTo(1);
        assertThat(saved).singleElement().satisfies(slot -> {
            assertThat(slot.getTotalNum()).isEqualTo(20);
            assertThat(slot.getRemainNum()).isEqualTo(20);
        });
    }

    // ---------------------------------------------------------------- 删除 / 停诊 / 恢复

    @Test
    void removeRejectsMissingOrReferencedSchedule() {
        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(null);
        assertThat(biz(() -> service.remove(1L)).getCode()).isEqualTo(404);

        when(scheduleMapper.selectByIdForUpdate(1L)).thenReturn(schedule(1L, 10L, LocalDate.now().plusDays(1)));
        when(appointmentMapper.selectCount(any())).thenReturn(2L);
        BizException referenced = biz(() -> service.remove(1L));
        assertThat(referenced.getCode()).isEqualTo(400);
        assertThat(referenced.getMessage()).contains("2 条预约记录").contains("不能删除");
        verify(scheduleMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void stopClinicRequiresExistingRowAndAffectedUpdate() {
        when(scheduleMapper.selectById(1L)).thenReturn(null);
        assertThat(biz(() -> service.stopClinic(1L)).getCode()).isEqualTo(404);

        when(scheduleMapper.selectById(1L)).thenReturn(schedule(1L, 10L, LocalDate.now().plusDays(1)));
        when(scheduleMapper.update(any(), any())).thenReturn(0);
        BizException missed = biz(() -> service.stopClinic(1L));
        assertThat(missed.getCode()).isEqualTo(404);
        assertThat(missed.getMessage()).contains("排班不存在");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopClinicWritesOffStatus() {
        when(scheduleMapper.selectById(1L)).thenReturn(schedule(1L, 10L, LocalDate.now().plusDays(1)));

        service.stopClinic(1L);

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).update(isNull(), captor.capture());
        assertThat(values(captor.getValue())).contains(0, 1L);
    }

    @Test
    void resumeClinicChecksDoctorAndAffectedRows() {
        when(scheduleMapper.selectById(1L)).thenReturn(null);
        assertThat(biz(() -> service.resumeClinic(1L)).getCode()).isEqualTo(404);

        DoctorSchedule schedule = schedule(1L, 10L, LocalDate.now().plusDays(1));
        schedule.setStatus(0);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 0));
        BizException stopped = biz(() -> service.resumeClinic(1L));
        assertThat(stopped.getCode()).isEqualTo(400);
        assertThat(stopped.getMessage()).contains("停诊状态");

        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));
        when(scheduleMapper.update(any(), any())).thenReturn(0);
        BizException missed = biz(() -> service.resumeClinic(1L));
        assertThat(missed.getCode()).isEqualTo(404);
        assertThat(missed.getMessage()).contains("排班不存在");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeClinicWritesOnStatus() {
        DoctorSchedule schedule = schedule(1L, 10L, LocalDate.now().plusDays(1));
        schedule.setStatus(0);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);
        when(doctorMapper.selectById(10L)).thenReturn(doctor(10L, 5L, 1));

        service.resumeClinic(1L);

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).update(isNull(), captor.capture());
        assertThat(values(captor.getValue())).contains(1, 1L);
    }

    // ---------------------------------------------------------------- 内存替身：时段边界与状态写入

    /** 三个时段的边界时间本身合法，越界 1 秒即拒绝；批量路径与单条一致 */
    @Test
    void acceptBoundaryTimesForEachPeriod() {
        InMemoryService s = new InMemoryService();
        String[][] bounds = {{"08:00", "12:00"}, {"13:00", "18:00"}, {"18:00", "22:00"}};
        for (int i = 0; i < bounds.length; i++) {
            ScheduleForm f = form(i + 1, bounds[i][0], bounds[i][1]);
            int before = saveCalls;
            s.create(f);
            if (saveCalls != before + 1) {
                throw new AssertionError("create 应恰好落库一次");
            }
            if (!saved.getStartTime().equals(f.getStartTime())) {
                throw new AssertionError("边界时间未保存");
            }
            if (s.batchCreate(batch(f)).created() != 1) {
                throw new AssertionError("批量边界失败");
            }
            f.setStartTime(f.getStartTime().minusSeconds(1));
            reject(() -> s.create(f), "排班时间须在");
            reject(() -> s.batchCreate(batch(f)), "排班时间须在");
            f.setStartTime(LocalTime.parse(bounds[i][0]));
            f.setEndTime(f.getEndTime().plusSeconds(1));
            reject(() -> s.create(f), "排班时间须在");
            reject(() -> s.batchCreate(batch(f)), "排班时间须在");
        }
    }

    /**
     * 编辑契约的形状：<strong>ScheduleEditForm 不能有 status 字段</strong>。
     *
     * <p>这是契约层的护栏，和 {@code preserveLegacyScheduleOnEdit} 的分工要说清：
     * 那个用例断言的是"实现不发 status"（出参实体上为 null），而真正拦住越权写入的是
     * <em>请求体结构上不存在这个字段</em>。如果将来有人把 status 加回 DTO、并写成
     * "只在非 null 时写入"，出参断言依然全绿，但旧客户端又能改停诊位了。
     * 所以这里直接查反射，字段一旦回来就红。
     */
    @Test
    void editFormHasNoStatusField() {
        for (var field : ScheduleEditForm.class.getDeclaredFields()) {
            if ("status".equals(field.getName())) {
                throw new AssertionError("ScheduleEditForm 不应有 status 字段："
                        + "停诊/恢复只能由 /stop 与 /resume 写入");
            }
        }
        // 六个业务字段必须都在，防止为了"没有 status"把别的字段也删了；
        // 用包含关系而非精确集合，允许后续无害新增字段，避免误报。
        Set<String> names = Arrays.stream(ScheduleEditForm.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertThat(names).as("ScheduleEditForm 的六个业务字段必须都在")
                .containsAll(Set.of("doctorId", "workDate", "period", "startTime", "endTime", "totalNum"));
    }

    /**
     * 历史排班可以改号源，但在已有预约时不能改时间；调整时段同样受限。
     *
     * <p>同时锁住实现侧不变量：编辑路径<strong>不写 status</strong>。
     *
     * <p>注意这个断言依赖 MyBatis-Plus 默认的 {@code updateById} 跳过 null 列
     * （{@code FieldStrategy.NOT_NULL}）；真正的落库保证由
     * {@code tools/schedule_status_check.mjs} 端到端覆盖，这里只保证"实现没有主动设值"。
     * 契约形状由 {@link #editFormHasNoStatusField} 锁住。
     */
    @Test
    void preserveLegacyScheduleOnEdit() {
        InMemoryService s = new InMemoryService();
        current = new DoctorSchedule();
        current.setId(9L);
        current.setDoctorId(1L);
        current.setPeriod(1);
        current.setWorkDate(LocalDate.now().minusDays(1));
        current.setStartTime(LocalTime.of(14, 0));
        current.setEndTime(LocalTime.of(16, 0));
        current.setStatus(0);

        ScheduleForm base = form(1, "14:00", "16:00");
        base.setWorkDate(current.getWorkDate());
        base.setTotalNum(25);
        ScheduleEditForm old = edit(base);
        for (long count : new long[]{0, 2}) {
            refs = count;
            s.update(9L, old);
            if (saved.getStatus() != null) {
                throw new AssertionError("编辑路径不应写 status，实际写了 " + saved.getStatus());
            }
            if (saved.getRemainNum() != 25 - count
                    || !saved.getStartTime().equals(current.getStartTime())) {
                throw new AssertionError("旧排班保存失败");
            }
        }
        old.setStartTime(LocalTime.of(9, 0));
        old.setEndTime(LocalTime.of(11, 0));
        reject(() -> s.update(9L, old), "条预约记录");
        refs = 0;
        s.update(9L, old);
        if (!saved.getStartTime().equals(LocalTime.of(9, 0))) {
            throw new AssertionError("修正时间失败");
        }
        old.setEndTime(LocalTime.of(13, 0));
        reject(() -> s.update(9L, old), "排班时间须在");
        old.setPeriod(2);
        old.setStartTime(LocalTime.of(9, 0));
        old.setEndTime(LocalTime.of(11, 0));
        reject(() -> s.update(9L, old), "排班时间须在");

        ScheduleBatchForm duplicate = batch(form(1, "09:00", "11:00"));
        duplicate.setPeriods(List.of(duplicate.getPeriods().get(0), duplicate.getPeriods().get(0)));
        reject(() -> s.batchCreate(duplicate), "每个时段只能配置一次");
    }

    /** 医生停诊期间仍可维护号源，但不能新开排班或恢复出诊；状态切换只走行内两个专用入口 */
    @Test
    void maintainStoppedDoctorSchedule() {
        InMemoryService s = new InMemoryService();
        current = new DoctorSchedule();
        current.setId(9L);
        current.setDoctorId(1L);
        current.setPeriod(1);
        current.setWorkDate(LocalDate.now().minusDays(1));
        current.setStartTime(LocalTime.of(14, 0));
        current.setEndTime(LocalTime.of(16, 0));
        current.setStatus(1);

        doctorStatus = 0;
        ScheduleForm base = form(1, "14:00", "16:00");
        base.setWorkDate(current.getWorkDate());
        base.setTotalNum(25);
        ScheduleEditForm maintenance = edit(base);
        s.update(9L, maintenance);
        if (saved.getTotalNum() != 25 || saved.getStatus() != null) {
            throw new AssertionError("停诊医生调整号源失败");
        }
        // 状态只能由专用入口翻：行内停诊写一次，编辑路径沿路不碰这一列
        s.stopClinic(9L);
        if (statusWrites != 1) {
            throw new AssertionError("行内停诊失败");
        }
        current.setStatus(0);
        s.update(9L, maintenance);
        if (saved.getStatus() != null) {
            throw new AssertionError("编辑路径不应写 status");
        }

        // 停诊中改容量可以，但恢复出诊只能走 resumeClinic，且要求医生仍在诊
        ScheduleForm restoreBase = form(1, "14:00", "16:00");
        restoreBase.setWorkDate(current.getWorkDate());
        ScheduleEditForm restore = edit(restoreBase);
        reject(() -> s.resumeClinic(9L), "该医生处于停诊状态");
        reject(() -> s.create(form(1, "09:00", "11:00")), "该医生处于停诊状态");
        reject(() -> s.batchCreate(batch(form(1, "09:00", "11:00"))), "该医生处于停诊状态");
        if (statusWrites != 1) {
            throw new AssertionError("停诊医生被恢复排班");
        }

        doctorStatus = 1;
        s.update(9L, restore);
        if (saved.getStatus() != null) {
            throw new AssertionError("编辑路径不应写 status");
        }
        s.resumeClinic(9L);
        if (statusWrites != 2) {
            throw new AssertionError("行内恢复失败");
        }
    }

    // ---------------------------------------------------------------- 内存替身 harness

    @SuppressWarnings("unchecked")
    static <T> T mapper(Class<T> type, Function<String, Object> result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> result.apply(method.getName()));
    }

    class InMemoryService extends DoctorScheduleService {
        InMemoryService() {
            super(mapper(DoctorMapper.class, name -> {
                Doctor d = new Doctor();
                d.setId(1L);
                d.setStatus(doctorStatus);
                return d;
            }), mapper(AppointmentMapper.class, name -> refs), null);
            baseMapper = mapper(DoctorScheduleMapper.class, name -> current);
        }

        @Override
        public boolean exists(Wrapper<DoctorSchedule> query) {
            return false;
        }

        @Override
        public boolean save(DoctorSchedule row) {
            saveCalls++;
            saved = row;
            return true;
        }

        @Override
        public boolean updateById(DoctorSchedule row) {
            saved = row;
            return true;
        }

        @Override
        public boolean saveBatch(Collection<DoctorSchedule> rows) {
            saved = rows.iterator().next();
            return true;
        }

        @Override
        public DoctorSchedule require(Long id) {
            return current;
        }

        @Override
        public boolean update(Wrapper<DoctorSchedule> query) {
            statusWrites++;
            return true;
        }
    }

    /** 断言被拒绝，且拒绝路径上没有发生任何写入 */
    void reject(Runnable action, String message) {
        saved = null;
        try {
            action.run();
            throw new AssertionError("应拒绝：" + message);
        } catch (BizException e) {
            if (!e.getMessage().contains(message)) {
                throw new AssertionError(e);
            }
        }
        if (saved != null) {
            throw new AssertionError("拒绝前发生了写入");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Doctor doctor(long id, long departmentId, int status) {
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setDepartmentId(departmentId);
        doctor.setStatus(status);
        doctor.setName("陈明远");
        doctor.setTitle("主任医师");
        doctor.setAvatar("doctor.png");
        doctor.setConsultationFee(new BigDecimal("50"));
        return doctor;
    }

    private static DoctorSchedule schedule(long id, long doctorId, LocalDate workDate) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(id);
        schedule.setDoctorId(doctorId);
        schedule.setWorkDate(workDate);
        schedule.setPeriod(1);
        schedule.setStartTime(LocalTime.of(9, 0));
        schedule.setEndTime(LocalTime.of(11, 0));
        schedule.setTotalNum(10);
        schedule.setRemainNum(10);
        schedule.setStatus(1);
        return schedule;
    }

    private static ScheduleForm form(Long doctorId, LocalDate date, Integer period,
                                     LocalTime start, LocalTime end, Integer total, Integer status) {
        ScheduleForm form = new ScheduleForm();
        form.setDoctorId(doctorId);
        form.setWorkDate(date);
        form.setPeriod(period);
        form.setStartTime(start);
        form.setEndTime(end);
        form.setTotalNum(total);
        form.setStatus(status);
        return form;
    }

    /** 内存替身用的边界入参：日期固定为明天，号源 20 */
    static ScheduleForm form(int period, String start, String end) {
        ScheduleForm f = new ScheduleForm();
        f.setDoctorId(1L);
        f.setWorkDate(LocalDate.now().plusDays(1));
        f.setPeriod(period);
        f.setStartTime(LocalTime.parse(start));
        f.setEndTime(LocalTime.parse(end));
        f.setTotalNum(20);
        f.setStatus(1);
        return f;
    }

    private static ScheduleEditForm edit(Long doctorId, LocalDate date, Integer period,
                                         LocalTime start, LocalTime end, Integer total) {
        ScheduleEditForm form = new ScheduleEditForm();
        form.setDoctorId(doctorId);
        form.setWorkDate(date);
        form.setPeriod(period);
        form.setStartTime(start);
        form.setEndTime(end);
        form.setTotalNum(total);
        return form;
    }

    /** 编辑入参：字段与 ScheduleForm 相同，唯独没有 status——状态不在这条写入路径上 */
    static ScheduleEditForm edit(ScheduleForm f) {
        ScheduleEditForm e = new ScheduleEditForm();
        e.setDoctorId(f.getDoctorId());
        e.setWorkDate(f.getWorkDate());
        e.setPeriod(f.getPeriod());
        e.setStartTime(f.getStartTime());
        e.setEndTime(f.getEndTime());
        e.setTotalNum(f.getTotalNum());
        return e;
    }

    private static ScheduleBatchForm batch(Long doctorId, LocalDate start, LocalDate end,
                                           List<Integer> weekDays,
                                           List<ScheduleBatchForm.PeriodTemplate> periods,
                                           Integer overrideTotalNum) {
        ScheduleBatchForm form = new ScheduleBatchForm();
        form.setDoctorId(doctorId);
        form.setStartDate(start);
        form.setEndDate(end);
        form.setWeekDays(weekDays);
        form.setPeriods(periods);
        form.setOverrideTotalNum(overrideTotalNum);
        return form;
    }

    static ScheduleBatchForm batch(ScheduleForm f) {
        ScheduleBatchForm b = new ScheduleBatchForm();
        b.setDoctorId(f.getDoctorId());
        b.setStartDate(f.getWorkDate());
        b.setEndDate(f.getWorkDate());
        b.setWeekDays(List.of(f.getWorkDate().getDayOfWeek().getValue()));
        ScheduleBatchForm.PeriodTemplate p = new ScheduleBatchForm.PeriodTemplate();
        p.setPeriod(f.getPeriod());
        p.setStartTime(f.getStartTime());
        p.setEndTime(f.getEndTime());
        p.setTotalNum(20);
        b.setPeriods(List.of(p));
        return b;
    }

    private static ScheduleBatchForm.PeriodTemplate template(Integer period,
                                                             int startHour, int endHour, int total) {
        ScheduleBatchForm.PeriodTemplate template = new ScheduleBatchForm.PeriodTemplate();
        template.setPeriod(period);
        template.setStartTime(LocalTime.of(startHour, 0));
        template.setEndTime(LocalTime.of(endHour, 0));
        template.setTotalNum(total);
        return template;
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