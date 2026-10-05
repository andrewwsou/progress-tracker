package com.progresstracker.progressworker.worker;

import com.progresstracker.progressworker.events.HabitEventNotifier;
import com.progresstracker.progressworker.service.CompletionProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The poller on its own, against a fake queue: backpressure, failures, and a graceful stop. */
class SqsPollerTest {

    private final SqsClient sqs = mock(SqsClient.class);
    private final CompletionProcessor processor = mock(CompletionProcessor.class);
    private final HabitEventNotifier notifier = mock(HabitEventNotifier.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Queue<Message> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger deleted = new AtomicInteger();
    /** The most messages held at once: received and not yet deleted. */
    private final AtomicInteger mostHeld = new AtomicInteger();
    private SqsPoller poller;

    @BeforeEach
    void fakeQueue() {
        // Hands out up to the requested number of queued messages, like a receive call.
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(inv -> {
            ReceiveMessageRequest request = inv.getArgument(0);
            List<Message> batch = new ArrayList<>();
            Message next;
            while (batch.size() < request.maxNumberOfMessages() && (next = queue.poll()) != null) {
                batch.add(next);
            }
            if (batch.isEmpty()) {
                Thread.sleep(5); // an empty long poll
            }
            mostHeld.accumulateAndGet(received.addAndGet(batch.size()) - deleted.get(), Math::max);
            return ReceiveMessageResponse.builder().messages(batch).build();
        });
        when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(inv -> {
            deleted.incrementAndGet();
            return null;
        });
    }

    @AfterEach
    void stopPoller() {
        if (poller != null) {
            poller.stop();
        }
    }

    private void startPoller(int concurrency, int shutdownTimeoutSeconds) {
        poller = new SqsPoller(new JsonMapper(), processor, notifier, metrics, sqs);
        ReflectionTestUtils.setField(poller, "workerEnabled", true);
        ReflectionTestUtils.setField(poller, "queueEnabled", true);
        ReflectionTestUtils.setField(poller, "sqsUrl", "http://sqs.test/000000000000/completions");
        ReflectionTestUtils.setField(poller, "concurrency", concurrency);
        ReflectionTestUtils.setField(poller, "shutdownTimeoutSeconds", shutdownTimeoutSeconds);
        poller.pollErrorBackoffMs = 10;
        poller.start();
    }

    private void enqueue(int count) {
        for (int i = 0; i < count; i++) {
            String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"userId\":1,\"habitId\":" + i + ",\"date\":\"2026-10-01\"}";
            queue.add(Message.builder().messageId("m" + i).receiptHandle("r" + i).body(body).build());
        }
    }

    private double events(String outcome) {
        return metrics.get("worker.events").tag("outcome", outcome).counter().count();
    }

    @Test
    void neverTakesMoreMessagesThanItHasIdleWorkers() throws Exception {
        AtomicInteger processing = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        when(processor.process(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            mostAtOnce.accumulateAndGet(processing.incrementAndGet(), Math::max);
            Thread.sleep(10);
            processing.decrementAndGet();
            return true;
        });
        enqueue(30);

        startPoller(3, 5);

        await().atMost(Duration.ofSeconds(10)).until(() -> deleted.get() == 30);
        assertThat(mostAtOnce.get()).isEqualTo(3);
        assertThat(mostHeld.get()).isLessThanOrEqualTo(3);
        verify(sqs, never()).receiveMessage(argThat((ReceiveMessageRequest r) -> r.maxNumberOfMessages() > 3));
        assertThat(events("applied")).isEqualTo(30);
    }

    @Test
    void stoppingLetsTheMessagesInFlightFinishBeforeClosingTheClient() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(processor.process(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            bothStarted.countDown();
            release.await();
            return true;
        });
        enqueue(2);
        startPoller(2, 10);
        assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();

        Thread stopping = new Thread(poller::stop);
        stopping.start();
        stopping.join(300);

        // Still waiting for the two messages: nothing deleted, client still open.
        assertThat(stopping.isAlive()).isTrue();
        assertThat(poller.isRunning()).isFalse();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).close();

        release.countDown();
        stopping.join(5_000);

        assertThat(stopping.isAlive()).isFalse();
        InOrder order = inOrder(sqs);
        order.verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        order.verify(sqs).close();
    }

    @Test
    void aMessageStillRunningAtTheDeadlineIsLeftForRedelivery() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(processor.process(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            new CountDownLatch(1).await(); // hangs until interrupted
            return true;
        });
        enqueue(1);
        startPoller(1, 1);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        poller.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(3));
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs).close();
        poller = null;
    }

    @Test
    void aFailedMessageIsLeftOnTheQueueAndTheNextOneStillRuns() throws Exception {
        when(processor.process(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(true);
        enqueue(2);

        startPoller(1, 5);

        await().atMost(Duration.ofSeconds(5)).until(() -> events("applied") == 1);
        assertThat(events("failed")).isEqualTo(1);
        verify(sqs, times(1)).deleteMessage(any(DeleteMessageRequest.class)); // only the one that worked
        // Only the reward that committed is announced to the API.
        verify(notifier, times(1)).rewardApplied(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void anUnreadableMessageIsDeletedAndCounted() {
        queue.add(Message.builder().messageId("bad").receiptHandle("r-bad").body("not json").build());

        startPoller(1, 5);

        await().atMost(Duration.ofSeconds(5)).until(() -> deleted.get() == 1);
        assertThat(events("invalid")).isEqualTo(1);
        verify(processor, never()).process(any(), any(), any(), any(), any());
    }

    @Test
    void afterFailedPollsItBacksOffAndRecovers() throws Exception {
        enqueue(1);
        Message waiting = queue.poll();
        List<Long> callTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Two failures, then the message, then empty polls.
        doAnswer(inv -> {
            callTimes.add(System.nanoTime());
            int call = callTimes.size();
            if (call <= 2) {
                throw SdkClientException.create("queue unreachable");
            }
            if (call == 3) {
                return ReceiveMessageResponse.builder().messages(waiting).build();
            }
            Thread.sleep(5);
            return ReceiveMessageResponse.builder().build();
        }).when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        when(processor.process(any(), any(), any(), any(), any())).thenReturn(true);

        startPoller(1, 5);

        await().atMost(Duration.ofSeconds(5)).until(() -> deleted.get() == 1);
        assertThat(metrics.get("worker.poll.errors").counter().count()).isEqualTo(2);
        // It waited 10 ms after the first failure and twice that after the second.
        assertThat(Duration.ofNanos(callTimes.get(1) - callTimes.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(10));
        assertThat(Duration.ofNanos(callTimes.get(2) - callTimes.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }
}
