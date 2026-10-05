package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.events.UserEventStreams;
import com.progresstracker.progresstracker.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events")
public class EventController {

    private final UserEventStreams streams;

    public EventController(UserEventStreams streams) {
        this.streams = streams;
    }

    /**
     * The stream also ends, with no event, when the user signs out everywhere: on this instance at
     * once, and on the others through the internal {@code signedOut} notification (see
     * PostgresEventListener), which is never sent to a browser. A reconnect with the old token gets 401.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live updates for the caller",
            description = "A server-sent event stream. Each event's data is a small JSON object; events only say that "
                    + "something changed, so read the new state through the other endpoints. The events: "
                    + "`ready` first, once the stream is live; "
                    + "`reward` when the worker has applied a completion's reward; "
                    + "`summary` when a weekly summary is written; "
                    + "`resync` when events may have been missed (the server's database listener reconnected), "
                    + "so re-read everything; "
                    + "`evicted` just before the server closes this stream because the user opened more streams "
                    + "than it keeps (it closes the oldest), so do not reconnect until the page is in use again.")
    @ApiResponse(responseCode = "200", description = "The stream, open until the client disconnects or it times out",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    public SseEmitter events(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return streams.subscribe(user.getId());
    }
}
