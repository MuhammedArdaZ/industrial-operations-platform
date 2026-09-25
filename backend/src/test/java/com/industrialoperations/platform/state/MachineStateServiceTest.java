package com.industrialoperations.platform.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.industrialoperations.platform.common.Measurements;

@ExtendWith(MockitoExtension.class)
class MachineStateServiceTest {

    private static final UUID MACHINE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant EXISTING_OCCURRED_AT = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private MachineLatestStateRepository machineLatestStateRepository;

    @InjectMocks
    private MachineStateService machineStateService;

    @Test
    void shouldCreateStateWhenNoneExists() {
        when(machineLatestStateRepository.findById(MACHINE_ID)).thenReturn(Optional.empty());
        when(machineLatestStateRepository.save(any(MachineLatestState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MachineLatestState result = machineStateService.updateLatestState(
                update(EXISTING_OCCURRED_AT, new BigDecimal("70.00")));

        assertThat(result.getMachineId()).isEqualTo(MACHINE_ID);
        assertThat(result.getOccurredAt()).isEqualTo(EXISTING_OCCURRED_AT);
        assertThat(result.getTemperature()).isEqualByComparingTo("70.00");
    }

    @Test
    void shouldApplyUpdateWhenObservationIsNewer() {
        MachineLatestState existing = existingState();
        Instant newer = EXISTING_OCCURRED_AT.plusSeconds(60);
        when(machineLatestStateRepository.findById(MACHINE_ID)).thenReturn(Optional.of(existing));
        when(machineLatestStateRepository.save(existing)).thenReturn(existing);

        MachineLatestState result = machineStateService.updateLatestState(
                update(newer, new BigDecimal("91.00")));

        assertThat(result.getOccurredAt()).isEqualTo(newer);
        assertThat(result.getTemperature()).isEqualByComparingTo("91.00");
    }

    @Test
    void shouldIgnoreUpdateWhenObservationIsOlder() {
        MachineLatestState existing = existingState();
        when(machineLatestStateRepository.findById(MACHINE_ID)).thenReturn(Optional.of(existing));

        MachineLatestState result = machineStateService.updateLatestState(
                update(EXISTING_OCCURRED_AT.minusSeconds(60), new BigDecimal("50.00")));

        assertThat(result.getOccurredAt()).isEqualTo(EXISTING_OCCURRED_AT);
        assertThat(result.getTemperature()).isEqualByComparingTo("68.40");
        verify(machineLatestStateRepository, never()).save(any(MachineLatestState.class));
    }

    @Test
    void shouldIgnoreUpdateWhenObservationTimeIsEqual() {
        MachineLatestState existing = existingState();
        when(machineLatestStateRepository.findById(MACHINE_ID)).thenReturn(Optional.of(existing));

        MachineLatestState result = machineStateService.updateLatestState(
                update(EXISTING_OCCURRED_AT, new BigDecimal("50.00")));

        assertThat(result.getTemperature()).isEqualByComparingTo("68.40");
        verify(machineLatestStateRepository, never()).save(any(MachineLatestState.class));
    }

    private MachineLatestState existingState() {
        return new MachineLatestState(
                MACHINE_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "msg-existing",
                "sensor-1",
                EXISTING_OCCURRED_AT,
                EXISTING_OCCURRED_AT.plusSeconds(1),
                new Measurements(new BigDecimal("68.40"), new BigDecimal("0.021")));
    }

    private LatestStateUpdate update(Instant occurredAt, BigDecimal temperature) {
        return new LatestStateUpdate(
                MACHINE_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "msg-incoming",
                "sensor-1",
                occurredAt,
                occurredAt.plusSeconds(1),
                new Measurements(temperature, new BigDecimal("0.030")));
    }
}
