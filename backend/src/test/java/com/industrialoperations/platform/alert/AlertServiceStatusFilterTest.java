package com.industrialoperations.platform.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for the status-filter rules of {@link AlertService#getOpenAlerts(List)}.
 * The controller test mocks the service, so these rules are only covered here and end to end.
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceStatusFilterTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private ApplicationEventPublisher publisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AlertService alertService;

    @Test
    void shouldDefaultToActiveWhenFilterIsNull() {
        alertService.getOpenAlerts(null);

        verify(alertRepository).findByStatusInOrderByTriggeredAtDesc(List.of(AlertStatus.ACTIVE));
    }

    @Test
    void shouldDefaultToActiveWhenFilterIsEmpty() {
        alertService.getOpenAlerts(List.of());

        verify(alertRepository).findByStatusInOrderByTriggeredAtDesc(List.of(AlertStatus.ACTIVE));
    }

    @Test
    void shouldQueryAllRequestedOpenStatuses() {
        List<AlertStatus> requested = List.of(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);

        alertService.getOpenAlerts(requested);

        verify(alertRepository).findByStatusInOrderByTriggeredAtDesc(requested);
    }

    @Test
    void shouldRejectResolvedWithDistinctRejectedListAndNotQuery() {
        List<AlertStatus> requested = List.of(AlertStatus.ACTIVE, AlertStatus.RESOLVED, AlertStatus.RESOLVED);

        assertThatThrownBy(() -> alertService.getOpenAlerts(requested))
                .isInstanceOfSatisfying(InvalidAlertStatusFilterException.class, ex -> {
                    assertThat(ex.getRejected()).containsExactly(AlertStatus.RESOLVED);
                    assertThat(ex.getAllowed()).containsExactlyInAnyOrder(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);
                });

        verify(alertRepository, never()).findByStatusInOrderByTriggeredAtDesc(any());
    }

    @Test
    void shouldRejectNullElementAsInvalidFilterInsteadOfThrowingNpe() {
        // "?status=ACTIVE," binds to [ACTIVE, null]: Spring converts the empty segment to a null enum.
        // List.of(...).contains(null) throws NullPointerException, which would surface as a 500.
        List<AlertStatus> requested = Arrays.asList(AlertStatus.ACTIVE, null);

        assertThatThrownBy(() -> alertService.getOpenAlerts(requested))
                .isInstanceOfSatisfying(InvalidAlertStatusFilterException.class, ex -> {
                    // The client sent an empty value, not a named status: nothing to list as rejected,
                    // and the message must say what was actually wrong.
                    assertThat(ex.getRejected()).isEmpty();
                    assertThat(ex.getMessage()).containsIgnoringCase("empty");
                    assertThat(ex.getAllowed()).containsExactlyInAnyOrder(AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED);
                });

        verify(alertRepository, never()).findByStatusInOrderByTriggeredAtDesc(any());
    }
}
