/*
 * Copyright 2025 Osamu Ogasawara
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.github.oogasawa.coderagent.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oogasawa.coderagent.service.ChatService;
import com.github.oogasawa.coderagent.service.ClaudeModelSet;
import com.github.oogasawa.coderagent.service.LogStreamHandler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST + SSE endpoint for chat interaction with Claude CLI.
 */
@Path("/api")
@Blocking
public class ChatResource {

    private static final Logger logger = Logger.getLogger(ChatResource.class.getName());

    @Inject
    ChatService chatService;

    @Inject
    LogStreamHandler logStreamHandler;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "coder-agent.title", defaultValue = "quarkus-coder-agent-claude")
    String appTitle;

    /** Raw Vert.x response for SSE streaming (bypasses RESTEasy buffering). */
    private volatile HttpServerResponse sseResponse;
    private volatile Long heartbeatTimerId;

    void registerSseRoute(@Observes Router router) {
        router.get("/api/chat/stream").handler(this::handleSseConnect);
    }

    private void handleSseConnect(RoutingContext rc) {
        var prev = sseResponse;
        if (prev != null && !prev.ended()) {
            try { prev.end(); } catch (Exception ignored) {}
        }
        if (heartbeatTimerId != null) {
            vertx.cancelTimer(heartbeatTimerId);
        }

        var response = rc.response();
        response.setChunked(true);
        response.putHeader("Content-Type", "text/event-stream");
        response.putHeader("Cache-Control", "no-cache");
        response.putHeader("X-Accel-Buffering", "no");

        sseResponse = response;
        logStreamHandler.setSseEmitter(this::emitSse);

        writeSse(response, ChatEvent.status(
            chatService.getModel(),
            chatService.getSessionId(),
            chatService.isBusy()
        ));

        heartbeatTimerId = vertx.setPeriodic(15_000, id -> {
            var r = sseResponse;
            if (r != null && !r.ended()) {
                writeSse(r, ChatEvent.heartbeat());
            } else {
                vertx.cancelTimer(id);
            }
        });

        response.closeHandler(v -> {
            logStreamHandler.clearSseEmitter();
            if (heartbeatTimerId != null) {
                vertx.cancelTimer(heartbeatTimerId);
                heartbeatTimerId = null;
            }
            if (sseResponse == response) {
                sseResponse = null;
            }
        });
    }

    private void writeSse(HttpServerResponse response, ChatEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            response.write("data: " + json + "\n\n");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to write SSE event: type=" + event.type(), e);
        }
    }

    private void emitSse(ChatEvent event) {
        var resp = sseResponse;
        if (resp != null && !resp.ended()) {
            vertx.runOnContext(v -> writeSse(resp, event));
        } else {
            logger.warning("SSE event DROPPED (no connection): type=" + event.type()
                    + " content=" + (event.content() != null
                        ? event.content().substring(0, Math.min(event.content().length(), 60))
                        : "null"));
        }
    }

    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent chat(PromptRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            return ChatEvent.error("Empty prompt");
        }

        if (sseResponse == null) {
            return ChatEvent.error("No SSE connection. Please refresh the page.");
        }

        String model = (request.model != null && !request.model.isBlank())
                ? request.model : chatService.getModel();

        Thread.startVirtualThread(() -> {
            try {
                chatService.sendPrompt(request.text, model, this::emitSse);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Chat prompt failed", e);
                emitSse(ChatEvent.error("Internal error: " + e.getMessage()));
            }
        });

        return ChatEvent.info("Processing");
    }

    @POST
    @Path("/respond")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent respond(RespondRequest request) {
        if (request == null || request.response == null || request.response.isBlank()) {
            return ChatEvent.error("Empty response");
        }
        try {
            chatService.respond(request.promptId, request.response);
            return ChatEvent.info("Response sent");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to send response to Claude", e);
            return ChatEvent.error("Failed to send response: " + e.getMessage());
        }
    }

    @POST
    @Path("/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent cancel() {
        chatService.cancel();
        return ChatEvent.info("Cancelled");
    }

    @POST
    @Path("/command")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatEvent> command(CommandRequest request) {
        List<ChatEvent> responses = new ArrayList<>();
        if (request != null && request.text != null && chatService.isCommand(request.text)) {
            chatService.handleCommand(request.text, responses::add);
        } else {
            responses.add(ChatEvent.error("Invalid command"));
        }
        return responses;
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent status() {
        return ChatEvent.status(
            chatService.getModel(),
            chatService.getSessionId(),
            chatService.isBusy()
        );
    }

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ModelInfo> models() {
        return chatService.getAvailableModels().stream()
                .map(e -> new ModelInfo(e.name(), e.type(), e.server()))
                .toList();
    }

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatEvent> logs() {
        return logStreamHandler.getRecentLogs();
    }

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public AppConfig config() {
        return new AppConfig(appTitle, chatService.isAuthenticated(),
                chatService.getAuthMode().name());
    }

    /** Sets the API key from the Web UI. */
    @POST
    @Path("/auth")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatEvent auth(AuthRequest request) {
        if (request == null || request.apiKey == null || request.apiKey.isBlank()) {
            return ChatEvent.error("API key is required");
        }
        chatService.setApiKey(request.apiKey);
        return ChatEvent.info("API key configured");
    }

    public record AppConfig(String title, boolean authenticated, String authMode) {}

    public record ModelInfo(String name, String type, String server) {}

    public static class PromptRequest {
        public String text;
        public String model;
    }

    public static class CommandRequest {
        public String text;
    }

    public static class RespondRequest {
        public String promptId;
        public String response;
    }

    public static class AuthRequest {
        public String apiKey;
    }
}
