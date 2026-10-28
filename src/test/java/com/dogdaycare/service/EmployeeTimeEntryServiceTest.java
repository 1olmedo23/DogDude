package com.dogdaycare.service;

import com.dogdaycare.model.EmployeeTimeEntry;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EmployeeTimeEntryRepository;
import com.dogdaycare.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
class EmployeeTimeEntryServiceTest {

    @Mock
    private EmployeeTimeEntryRepository timeEntryRepository;

    @Mock
    private UserRepository userRepository;

    private EmployeeTimeEntryService service;
    private User employee;

    @BeforeEach
    void setUp() {
        service = new EmployeeTimeEntryService(
                timeEntryRepository,
                userRepository,
                "America/Los_Angeles"
        );

        employee = new User();
        employee.setId(113L);
        employee.setRole("EMPLOYEE");
        employee.setEnabled(true);
    }

    @Test
    void clockInCreatesOpenTimeEntry() {
        when(userRepository.findById(113L))
                .thenReturn(Optional.of(employee));

        when(timeEntryRepository.findByEmployeeIdAndClockOutIsNull(113L))
                .thenReturn(Optional.empty());

        when(timeEntryRepository.saveAndFlush(any(EmployeeTimeEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeTimeEntry result = service.clockIn(113L);

        assertEquals(employee, result.getEmployee());
        assertNotNull(result.getClockIn());
        assertNull(result.getClockOut());

        verify(timeEntryRepository).saveAndFlush(any(EmployeeTimeEntry.class));
    }

    @Test
    void clockInRejectsDuplicateActiveEntry() {
        when(userRepository.findById(113L))
                .thenReturn(Optional.of(employee));

        when(timeEntryRepository.findByEmployeeIdAndClockOutIsNull(113L))
                .thenReturn(Optional.of(new EmployeeTimeEntry()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.clockIn(113L)
        );

        assertEquals("You are already clocked in.", exception.getMessage());

        verify(timeEntryRepository, never()).saveAndFlush(any());
    }

    @Test
    void clockOutClosesActiveEntry() {
        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setEmployee(employee);
        entry.setClockIn(java.time.LocalDateTime.now().minusHours(1));

        when(userRepository.findById(113L))
                .thenReturn(Optional.of(employee));

        when(timeEntryRepository.findByEmployeeIdAndClockOutIsNull(113L))
                .thenReturn(Optional.of(entry));

        when(timeEntryRepository.save(any(EmployeeTimeEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeTimeEntry result = service.clockOut(113L);

        assertNotNull(result.getClockOut());
        assertFalse(result.getClockOut().isBefore(result.getClockIn()));

        verify(timeEntryRepository).save(entry);
    }

    @Test
    void clockOutRejectsMissingActiveEntry() {
        when(userRepository.findById(113L))
                .thenReturn(Optional.of(employee));

        when(timeEntryRepository.findByEmployeeIdAndClockOutIsNull(113L))
                .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.clockOut(113L)
        );

        assertEquals(
                "You are not currently clocked in.",
                exception.getMessage()
        );

        verify(timeEntryRepository, never()).save(any());
    }

    @Test
    void disabledEmployeeCannotClockIn() {
        employee.setEnabled(false);

        when(userRepository.findById(113L))
                .thenReturn(Optional.of(employee));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.clockIn(113L)
        );

        assertEquals(
                "Only active employees can clock in.",
                exception.getMessage()
        );

        verifyNoInteractions(timeEntryRepository);
    }

    @Test
    void updateTimeEntryCorrectsExistingTimes() {
        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setId(1L);
        entry.setEmployee(employee);

        LocalDateTime originalClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 12);

        LocalDateTime originalClockOut =
                LocalDateTime.of(2026, 10, 22, 16, 45);

        entry.setClockIn(originalClockIn);
        entry.setClockOut(originalClockOut);

        LocalDateTime correctedClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        LocalDateTime correctedClockOut =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        when(timeEntryRepository.findById(1L))
                .thenReturn(Optional.of(entry));

        when(timeEntryRepository.save(entry))
                .thenReturn(entry);

        EmployeeTimeEntry result = service.updateTimeEntry(
                1L,
                correctedClockIn,
                correctedClockOut
        );

        assertEquals(1L, result.getId());

        // Original timestamps must remain unchanged.
        assertEquals(originalClockIn, result.getClockIn());
        assertEquals(originalClockOut, result.getClockOut());

        // Admin adjustments are stored separately.
        assertEquals(correctedClockIn, result.getAdjustedClockIn());
        assertEquals(correctedClockOut, result.getAdjustedClockOut());

        assertEquals(employee, result.getEmployee());

        verify(timeEntryRepository).save(entry);
    }

    @Test
    void updateTimeEntryRejectsClockOutBeforeClockIn() {
        EmployeeTimeEntry entry = new EmployeeTimeEntry();

        LocalDateTime originalClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        LocalDateTime originalClockOut =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        entry.setClockIn(originalClockIn);
        entry.setClockOut(originalClockOut);

        when(timeEntryRepository.findById(1L))
                .thenReturn(Optional.of(entry));

        LocalDateTime invalidClockIn =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        LocalDateTime invalidClockOut =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateTimeEntry(
                        1L,
                        invalidClockIn,
                        invalidClockOut
                )
        );

        assertEquals(
                "Adjusted clock-out cannot be before adjusted clock-in.",
                exception.getMessage()
        );

        assertEquals(originalClockIn, entry.getClockIn());
        assertEquals(originalClockOut, entry.getClockOut());

        verify(timeEntryRepository, never()).save(any());
    }

    @Test
    void updateTimeEntryRejectsMissingRecord() {
        when(timeEntryRepository.findById(999L))
                .thenReturn(Optional.empty());

        LocalDateTime clockIn =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        LocalDateTime clockOut =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateTimeEntry(
                        999L,
                        clockIn,
                        clockOut
                )
        );

        assertEquals(
                "Time entry not found.",
                exception.getMessage()
        );

        verify(timeEntryRepository, never()).save(any());
    }

    @Test
    void repeatedAdjustmentsPreserveOriginalTimes() {

        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setId(1L);
        entry.setEmployee(employee);

        LocalDateTime originalClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 12);

        LocalDateTime originalClockOut =
                LocalDateTime.of(2026, 10, 22, 16, 45);

        entry.setClockIn(originalClockIn);
        entry.setClockOut(originalClockOut);

        when(timeEntryRepository.findById(1L))
                .thenReturn(Optional.of(entry));

        when(timeEntryRepository.save(entry))
                .thenReturn(entry);

        // First adjustment
        service.updateTimeEntry(
                1L,
                LocalDateTime.of(2026, 10, 22, 8, 0),
                LocalDateTime.of(2026, 10, 22, 17, 0)
        );

        // Second adjustment
        service.updateTimeEntry(
                1L,
                LocalDateTime.of(2026, 10, 22, 8, 5),
                LocalDateTime.of(2026, 10, 22, 17, 15)
        );

        // Original timestamps remain unchanged.
        assertEquals(originalClockIn, entry.getClockIn());
        assertEquals(originalClockOut, entry.getClockOut());

        // Latest adjustments are stored separately.
        assertEquals(
                LocalDateTime.of(2026, 10, 22, 8, 5),
                entry.getAdjustedClockIn()
        );

        assertEquals(
                LocalDateTime.of(2026, 10, 22, 17, 15),
                entry.getAdjustedClockOut()
        );

        verify(timeEntryRepository, times(2)).save(entry);
    }

    @Test
    void adminClockOutRecordsMissingOriginalClockOut() {

        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setId(1L);
        entry.setEmployee(employee);

        LocalDateTime originalClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        LocalDateTime adminClockOut =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        entry.setClockIn(originalClockIn);

        when(timeEntryRepository.findById(1L))
                .thenReturn(Optional.of(entry));

        when(timeEntryRepository.save(entry))
                .thenReturn(entry);

        EmployeeTimeEntry result =
                service.adminClockOut(1L, adminClockOut);

        assertEquals(originalClockIn, result.getClockIn());
        assertEquals(adminClockOut, result.getClockOut());

        assertNull(result.getAdjustedClockIn());
        assertNull(result.getAdjustedClockOut());

        verify(timeEntryRepository).save(entry);
    }

    @Test
    void adminClockOutCannotOverwriteOriginalClockOut() {

        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setId(1L);
        entry.setEmployee(employee);

        LocalDateTime originalClockIn =
                LocalDateTime.of(2026, 10, 22, 8, 0);

        LocalDateTime originalClockOut =
                LocalDateTime.of(2026, 10, 22, 17, 0);

        entry.setClockIn(originalClockIn);
        entry.setClockOut(originalClockOut);

        when(timeEntryRepository.findById(1L))
                .thenReturn(Optional.of(entry));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.adminClockOut(
                        1L,
                        LocalDateTime.of(2026, 10, 22, 18, 0)
                )
        );

        assertEquals(
                "This employee already has a recorded clock-out.",
                exception.getMessage()
        );

        // The original timestamps must remain unchanged.
        assertEquals(originalClockIn, entry.getClockIn());
        assertEquals(originalClockOut, entry.getClockOut());

        verify(timeEntryRepository, never()).save(any());
    }

}