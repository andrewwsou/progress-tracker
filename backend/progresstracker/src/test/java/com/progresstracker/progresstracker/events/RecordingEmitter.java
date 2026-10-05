package com.progresstracker.progresstracker.events;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** Records the name of each event sent to it, and its completion, in order. */
final class RecordingEmitter extends SseEmitter {
    final List<String> calls = new CopyOnWriteArrayList<>();

    RecordingEmitter(long timeoutMillis) {
        super(timeoutMillis);
    }

    /** Streams whose every new stream records what it is sent. */
    static UserEventStreams streams(int maxStreamsPerUser) {
        return new UserEventStreams(Duration.ofMinutes(30), 3600, maxStreamsPerUser) {
            @Override
            SseEmitter newEmitter(long timeoutMillis) {
                return new RecordingEmitter(timeoutMillis);
            }
        };
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        Set<DataWithMediaType> parts = builder.build(); // build() only once: it appends as it goes
        String text = parts.stream().map(part -> String.valueOf(part.getData())).collect(Collectors.joining());
        int name = text.indexOf("event:") + "event:".length();
        calls.add(text.substring(name, text.indexOf('\n', name)));
        send(parts);
    }

    @Override
    public void complete() {
        calls.add("complete");
        super.complete();
    }
}
