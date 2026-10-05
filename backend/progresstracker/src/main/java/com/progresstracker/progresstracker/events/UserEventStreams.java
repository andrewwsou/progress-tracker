package com.progresstracker.progresstracker.events;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The open event streams (server-sent events), by user. A user can have several (one per tab).
 * Events only tell the browser that something changed; it then reads the new state through the
 * normal endpoints, so a lost event costs a few seconds of staleness, never wrong data.
 *
 * On shutdown every stream is closed first, before the web server's graceful shutdown starts:
 * an open stream never finishes on its own, so graceful shutdown would otherwise wait out its
 * whole timeout. Browsers reconnect, to another instance or to this one once it is back.
 */
@Component
public class UserEventStreams implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UserEventStreams.class);

    // Per user, oldest first. Copy-on-write: sends iterate while other threads add and remove.
    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private volatile boolean running;
    private final Duration timeout;
    private final int maxStreamsPerUser;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "event-stream-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public UserEventStreams(@Value("${events.stream-timeout:30m}") Duration timeout,
                            @Value("${events.heartbeat-seconds:25}") long heartbeatSeconds,
                            @Value("${events.max-streams-per-user:5}") int maxStreamsPerUser) {
        this.timeout = timeout;
        this.maxStreamsPerUser = maxStreamsPerUser;
        // A comment line every so often keeps proxies from closing a quiet stream, and finds
        // streams whose browser has gone away.
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    /**
     * Opens a stream for the user. The first event, {@code ready}, tells the client it is live.
     * A user may hold a few streams (tabs); opening one more closes their oldest, so one account
     * cannot tie up the server's connections.
     */
    public SseEmitter subscribe(long userId) {
        if (!running) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Shutting down");
        }
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        SseEmitter[] evicted = new SseEmitter[1];
        // One atomic step per user, so a concurrent remove cannot unmap the list this joins.
        streams.compute(userId, (id, userStreams) -> {
            List<SseEmitter> list = userStreams == null ? new CopyOnWriteArrayList<>() : userStreams;
            list.add(emitter);
            if (list.size() > maxStreamsPerUser) {
                evicted[0] = list.remove(0);
            }
            return list;
        });
        if (evicted[0] != null) {
            evicted[0].complete(); // outside compute: completion calls back into remove()
        }
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(userId, emitter));
        send(userId, emitter, SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        if (!running) {
            emitter.complete(); // stop() ran meanwhile and did not see this one
        }
        return emitter;
    }

    /** Sends an event to every open stream of the user, if any. */
    public void publish(long userId, String type, String json) {
        List<SseEmitter> userStreams = streams.get(userId);
        if (userStreams == null) {
            return;
        }
        for (SseEmitter emitter : userStreams) {
            send(userId, emitter, SseEmitter.event().name(type).data(json, MediaType.APPLICATION_JSON));
        }
    }

    /** Tells every open stream to re-read its state, after a gap in which events may have been lost. */
    public void publishAll(String type) {
        streams.forEach((userId, userStreams) -> userStreams.forEach(emitter ->
                send(userId, emitter, SseEmitter.event().name(type).data("{}", MediaType.APPLICATION_JSON))));
    }

    int openStreams(long userId) {
        List<SseEmitter> userStreams = streams.get(userId);
        return userStreams == null ? 0 : userStreams.size();
    }

    private void sendHeartbeats() {
        streams.forEach((userId, userStreams) ->
                userStreams.forEach(emitter -> send(userId, emitter, SseEmitter.event().comment("keep-alive"))));
    }

    private void send(long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The browser went away, or the stream already finished. Forget it.
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (id, userStreams) -> {
            userStreams.remove(emitter);
            return userStreams.isEmpty() ? null : userStreams;
        });
    }

    @Override
    public void start() {
        running = true;
    }

    /** Runs in the last phase to start, so the first to stop: before the web server's graceful shutdown. */
    @Override
    public void stop() {
        running = false;
        streams.values().forEach(userStreams -> userStreams.forEach(SseEmitter::complete));
        streams.clear();
        log.info("Event streams closed");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeat.shutdownNow();
    }
}
