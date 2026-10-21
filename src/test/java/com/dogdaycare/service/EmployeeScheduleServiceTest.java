package com.dogdaycare.service;

import com.dogdaycare.model.EmployeeSchedule;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EmployeeScheduleRepository;
import com.dogdaycare.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeScheduleServiceTest {

    @Mock
    private EmployeeScheduleRepository scheduleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private EmployeeScheduleService scheduleService;

    private User employee;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        employee = new User();
        employee.setId(1L);
        employee.setRole("EMPLOYEE");
        employee.setEnabled(true);

        date = LocalDate.of(2026, 10, 21);
    }

    private EmployeeSchedule existingSchedule(int startHour, int endHour) {
        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployee(employee);
        schedule.setScheduleDate(date);
        schedule.setStartTime(LocalTime.of(startHour, 0));
        schedule.setEndTime(LocalTime.of(endHour, 0));
        return schedule;
    }

    @Test
    void createsFirstScheduledPeriod() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(1L, date))
                .thenReturn(List.of());
        when(scheduleRepository.save(any(EmployeeSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSchedule result = scheduleService.createSchedule(
                1L, date, LocalTime.of(8, 0), LocalTime.of(12, 0));

        assertEquals(LocalTime.of(8, 0), result.getStartTime());
        assertEquals(LocalTime.of(12, 0), result.getEndTime());
        verify(scheduleRepository).save(any(EmployeeSchedule.class));
    }

    @Test
    void allowsSecondNonOverlappingPeriod() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(1L, date))
                .thenReturn(List.of(existingSchedule(8, 12)));
        when(scheduleRepository.save(any(EmployeeSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSchedule result = scheduleService.createSchedule(
                1L, date, LocalTime.of(13, 0), LocalTime.of(17, 0));

        assertNotNull(result);
        verify(scheduleRepository).save(any(EmployeeSchedule.class));
    }

    @Test
    void rejectsThirdScheduledPeriod() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(1L, date))
                .thenReturn(List.of(
                        existingSchedule(8, 10),
                        existingSchedule(12, 14)
                ));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        1L, date, LocalTime.of(15, 0), LocalTime.of(17, 0)));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void rejectsOverlappingPeriod() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));
        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(1L, date))
                .thenReturn(List.of(existingSchedule(8, 12)));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        1L, date, LocalTime.of(11, 0), LocalTime.of(15, 0)));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void rejectsInvalidTimeRange() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        1L, date, LocalTime.of(16, 0), LocalTime.of(8, 0)));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void rejectsDisabledEmployee() {
        employee.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        1L, date, LocalTime.of(8, 0), LocalTime.of(16, 0)));

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void rejectsNonEmployeeAccount() {
        employee.setRole("CUSTOMER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        1L, date, LocalTime.of(8, 0), LocalTime.of(16, 0)));

        verify(scheduleRepository, never()).save(any());
    }


    @Test
    void createsSchedulesForMultipleDates() {
        LocalDate secondDate = date.plusDays(1);
        LocalDate thirdDate = date.plusDays(3);

        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                eq(1L), any(LocalDate.class)))
                .thenReturn(List.of());

        when(scheduleRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<EmployeeSchedule> result = scheduleService.createSchedules(
                1L,
                List.of(date, secondDate, thirdDate),
                LocalTime.of(8, 0),
                LocalTime.of(16, 0)
        );

        assertEquals(3, result.size());
        assertEquals(date, result.get(0).getScheduleDate());
        assertEquals(secondDate, result.get(1).getScheduleDate());
        assertEquals(thirdDate, result.get(2).getScheduleDate());

        verify(scheduleRepository).saveAll(anyList());
    }

    @Test
    void rejectsBulkScheduleWhenOneDateAlreadyHasTwoPeriods() {
        LocalDate secondDate = date.plusDays(1);

        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, date))
                .thenReturn(List.of());

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, secondDate))
                .thenReturn(List.of(
                        existingSchedule(8, 10),
                        existingSchedule(12, 14)
                ));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedules(
                        1L,
                        List.of(date, secondDate),
                        LocalTime.of(15, 0),
                        LocalTime.of(17, 0)
                )
        );

        // Nothing should be saved if any selected date is invalid.
        verify(scheduleRepository, never()).saveAll(anyList());
    }

    @Test
    void rejectsBulkScheduleWhenOneDateOverlaps() {
        LocalDate secondDate = date.plusDays(1);

        when(userRepository.findById(1L)).thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, date))
                .thenReturn(List.of());

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, secondDate))
                .thenReturn(List.of(existingSchedule(8, 12)));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedules(
                        1L,
                        List.of(date, secondDate),
                        LocalTime.of(11, 0),
                        LocalTime.of(15, 0)
                )
        );

        verify(scheduleRepository, never()).saveAll(anyList());
    }


    @Test
    void updatesExistingScheduleWithoutCreatingNewRecord() {
        EmployeeSchedule schedule = existingSchedule(8, 12);
        schedule.setId(10L);

        when(scheduleRepository.findById(10L))
                .thenReturn(Optional.of(schedule));

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, date))
                .thenReturn(List.of(schedule));

        when(scheduleRepository.save(any(EmployeeSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSchedule result = scheduleService.updateSchedule(
                10L,
                1L,
                date,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0)
        );

        assertEquals(10L, result.getId());
        assertEquals(LocalTime.of(9, 0), result.getStartTime());
        assertEquals(LocalTime.of(13, 0), result.getEndTime());

        verify(scheduleRepository).save(schedule);
        verify(scheduleRepository, never()).saveAll(anyList());
    }

    @Test
    void allowsEditingScheduleWithoutOverlappingItself() {
        EmployeeSchedule schedule = existingSchedule(8, 12);
        schedule.setId(10L);

        when(scheduleRepository.findById(10L))
                .thenReturn(Optional.of(schedule));

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, date))
                .thenReturn(List.of(schedule));

        when(scheduleRepository.save(any(EmployeeSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSchedule result = scheduleService.updateSchedule(
                10L,
                1L,
                date,
                LocalTime.of(8, 0),
                LocalTime.of(12, 0)
        );

        assertNotNull(result);
        assertEquals(10L, result.getId());

        verify(scheduleRepository).save(schedule);
    }

    @Test
    void rejectsEditingScheduleWhenTimesOverlapAnotherPeriod() {
        EmployeeSchedule schedule = existingSchedule(8, 12);
        schedule.setId(10L);

        EmployeeSchedule otherSchedule = existingSchedule(13, 17);
        otherSchedule.setId(11L);

        when(scheduleRepository.findById(10L))
                .thenReturn(Optional.of(schedule));

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, date))
                .thenReturn(List.of(schedule, otherSchedule));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.updateSchedule(
                        10L,
                        1L,
                        date,
                        LocalTime.of(12, 0),
                        LocalTime.of(15, 0)
                )
        );

        // The rejected edit must not modify the original period.
        assertEquals(LocalTime.of(8, 0), schedule.getStartTime());
        assertEquals(LocalTime.of(12, 0), schedule.getEndTime());

        verify(scheduleRepository, never()).save(any(EmployeeSchedule.class));
    }

    @Test
    void rejectsMovingScheduleToDateWithTwoExistingPeriods() {
        EmployeeSchedule schedule = existingSchedule(8, 12);
        schedule.setId(10L);

        LocalDate newDate = date.plusDays(1);

        EmployeeSchedule firstPeriod = existingSchedule(8, 10);
        firstPeriod.setId(11L);

        EmployeeSchedule secondPeriod = existingSchedule(12, 14);
        secondPeriod.setId(12L);

        when(scheduleRepository.findById(10L))
                .thenReturn(Optional.of(schedule));

        when(userRepository.findById(1L))
                .thenReturn(Optional.of(employee));

        when(scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                1L, newDate))
                .thenReturn(List.of(firstPeriod, secondPeriod));

        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.updateSchedule(
                        10L,
                        1L,
                        newDate,
                        LocalTime.of(15, 0),
                        LocalTime.of(17, 0)
                )
        );

        assertEquals(date, schedule.getScheduleDate());

        verify(scheduleRepository, never()).save(any(EmployeeSchedule.class));
    }
}
