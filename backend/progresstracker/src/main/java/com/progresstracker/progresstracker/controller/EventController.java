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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "http://localhost:5173")
@Tag(name = "Events")
public class EventController {

    private final UserEventStreams streams;

    public EventController(UserEventStreams streams) {
        this.streams = streams;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live updates for the caller",
            description = "A server-sent event stream. The first event is `ready`; after that, `reward` when the worker "
                    + "has applied a completion's reward and `summary` when a weekly summary is written. Each event's "
                    + "data is a small JSON object; read the new state through the other endpoints.")
    @ApiResponse(responseCode = "200", description = "The stream, open until the client disconnects or it times out",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    public SseEmitter events(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return streams.subscribe(user.getId());
    }
}
