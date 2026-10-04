package com.progresstracker.progresstracker.outbox;

import com.progresstracker.progresstracker.service.CompletionQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay's bookkeeping, with the queue's answers scripted. A real broker accepts every
 * well-formed message, so partial rejections can only be tested this way.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private CompletionQueueService queue;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, queue, transactionManager);
        ReflectionTestUtils.setField(relay, "enabled", true);
    }

    @Test
    void doesNothingOnItsTimerWhenTheQueueIsDisabled() {
        ReflectionTestUtils.setField(relay, "enabled", false);

        relay.publishPending();
        relay.purgePublished();

        verifyNoInteractions(repository, queue);
    }

    private static List<OutboxEvent> events(int count) {
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new OutboxEvent(UUID.randomUUID(), "habit.completed", "{}", OffsetDateTime.now()));
        }
        return events;
    }

    private static Set<UUID> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).collect(Collectors.toSet());
    }

    @Test
    void marksOnlyTheEventsTheQueueAcceptedAndCommits() {
        List<OutboxEvent> batch = events(3);
        when(repository.lockNextUnpublished(10)).thenReturn(batch);
        // The queue rejects the middle one.
        when(queue.publish(batch)).thenReturn(Set.of(batch.get(0).getId(), batch.get(2).getId()));

        int accepted = relay.publishNextBatch();

        assertThat(accepted).isEqualTo(2);
        assertThat(batch.get(0).getPublishedAt()).isNotNull();
        assertThat(batch.get(1).getPublishedAt()).as("rejected event must stay unpublished").isNull();
        assertThat(batch.get(2).getPublishedAt()).isNotNull();
        // A partial result still commits, so the accepted ones are not sent again.
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void aFailedSendMarksNothingAndRollsBack() {
        List<OutboxEvent> batch = events(2);
        when(repository.lockNextUnpublished(10)).thenReturn(batch);
        when(queue.publish(batch)).thenThrow(new IllegalStateException("queue unreachable"));

        assertThatThrownBy(relay::publishNextBatch).isInstanceOf(IllegalStateException.class);

        assertThat(batch).allSatisfy(event -> assertThat(event.getPublishedAt()).isNull());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aFailingRunDoesNotThrowSoTheTimerKeepsGoing() {
        when(repository.lockNextUnpublished(10)).thenReturn(events(1));
        when(queue.publish(anyList())).thenThrow(new IllegalStateException("queue unreachable"));

        assertThatCode(relay::publishPending).doesNotThrowAnyException();
    }

    @Test
    void keepsDrainingWhileFullBatchesAreFullyAccepted() {
        List<OutboxEvent> first = events(10);
        List<OutboxEvent> second = events(10);
        List<OutboxEvent> last = events(4);
        when(repository.lockNextUnpublished(10)).thenReturn(first, second, last);
        when(queue.publish(first)).thenReturn(ids(first));
        when(queue.publish(second)).thenReturn(ids(second));
        when(queue.publish(last)).thenReturn(ids(last));

        relay.publishPending();

        verify(repository, times(3)).lockNextUnpublished(10);
    }

    @Test
    void stopsTheRunWhenTheQueueRejectsPartOfABatch() {
        List<OutboxEvent> batch = events(10);
        when(repository.lockNextUnpublished(10)).thenReturn(batch);
        // One rejected. Looping now would claim and re-send the same rejected row at once.
        when(queue.publish(batch)).thenReturn(ids(batch.subList(0, 9)));

        relay.publishPending();

        verify(repository, times(1)).lockNextUnpublished(10);
    }

    @Test
    void doesNotCallTheQueueWhenThereIsNothingToSend() {
        when(repository.lockNextUnpublished(10)).thenReturn(List.of());

        assertThat(relay.publishNextBatch()).isZero();

        verify(queue, never()).publish(anyList());
    }
}
