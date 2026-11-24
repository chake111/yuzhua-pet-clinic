package com.yuzhua.task;

import com.yuzhua.props.AppProperties;
import com.yuzhua.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清扫任务的租约语义：抢不到就整轮跳过，抢到才扫描，异常不能阻止释放。
 * 纯 Mockito，不启 Spring，也不碰真实数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppointmentSweepTaskTest {

    @Mock
    private AppointmentService appointmentService;
    @Mock
    private SchedulerLease schedulerLease;
    @Mock
    private AppProperties props;
    @Mock
    private AppProperties.Scheduler schedulerConfig;

    private AppointmentSweepTask task;

    @BeforeEach
    void setUp() {
        task = new AppointmentSweepTask(appointmentService, schedulerLease, props);
        when(props.getScheduler()).thenReturn(schedulerConfig);
        when(schedulerConfig.getLeaseSeconds()).thenReturn(300L);
    }

    @Test
    void expireUnpaidSkipsWholeRoundWhenLeaseNotAcquired() {
        when(schedulerLease.tryAcquire("appointment.expire-unpaid", 300L)).thenReturn(false);

        task.expireUnpaid();

        verify(appointmentService, never()).unpaidOverdueIds();
        verify(appointmentService, never()).expireOne(anyLong());
        verify(schedulerLease, never()).release(anyString());
    }

    @Test
    void expireUnpaidProcessesCandidatesAndReleasesLease() {
        when(schedulerLease.tryAcquire("appointment.expire-unpaid", 300L)).thenReturn(true);
        when(appointmentService.unpaidOverdueIds()).thenReturn(List.of(11L, 12L));
        when(appointmentService.expireOne(anyLong())).thenReturn(true);

        task.expireUnpaid();

        verify(appointmentService).expireOne(11L);
        verify(appointmentService).expireOne(12L);
        verify(schedulerLease, times(1)).release("appointment.expire-unpaid");
    }

    @Test
    void expireUnpaidContinuesAfterSingleOrderFailureAndStillReleases() {
        when(schedulerLease.tryAcquire("appointment.expire-unpaid", 300L)).thenReturn(true);
        when(appointmentService.unpaidOverdueIds()).thenReturn(List.of(21L, 22L));
        when(appointmentService.expireOne(21L)).thenThrow(new RuntimeException("boom"));
        when(appointmentService.expireOne(22L)).thenReturn(true);

        assertThatCode(task::expireUnpaid).doesNotThrowAnyException();

        verify(appointmentService).expireOne(21L);
        verify(appointmentService).expireOne(22L);
        verify(schedulerLease, times(1)).release("appointment.expire-unpaid");
    }

    @Test
    void markMissedUsesItsOwnLeaseAndReleases() {
        when(schedulerLease.tryAcquire("appointment.mark-missed", 300L)).thenReturn(true);
        when(appointmentService.staleVisitIds()).thenReturn(List.of(31L));
        when(appointmentService.markMissedOne(31L)).thenReturn(true);

        task.markMissed();

        verify(appointmentService).markMissedOne(31L);
        verify(schedulerLease, times(1)).release("appointment.mark-missed");
    }

    @Test
    void markMissedSkipsWhenLeaseNotAcquired() {
        when(schedulerLease.tryAcquire("appointment.mark-missed", 300L)).thenReturn(false);

        task.markMissed();

        verify(appointmentService, never()).staleVisitIds();
        verify(appointmentService, never()).markMissedOne(anyLong());
        verify(schedulerLease, never()).release(anyString());
    }
}