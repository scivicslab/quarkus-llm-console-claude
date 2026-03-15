package com.scivicslab.llmconsole.claude.mcp;

import com.scivicslab.llmconsole.claude.rest.ChatEvent;
import com.scivicslab.llmconsole.claude.service.ChatService;
import com.scivicslab.llmconsole.claude.rest.ChatResource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MCP tools for the LLM Console.
 *
 * <p>Allows external MCP clients (e.g., workflow-editor) to send prompts
 * to Claude and retrieve results programmatically.</p>
 */
public class LlmConsoleTools {

    @Inject
    ChatService chatService;

    @Inject
    ChatResource chatResource;

    @Tool(description = "Send a prompt to Claude AI and return the response. "
            + "This is a synchronous call that waits for the full response.")
    String sendPrompt(
            @ToolArg(description = "The prompt text to send to Claude") String prompt,
            @ToolArg(description = "The model to use (e.g., sonnet, opus, haiku). Leave empty for current model.") String model,
            @ToolArg(description = "Caller info injected by MCP Gateway (optional)") String _caller
    ) {
        if (chatService.isBusy()) {
            return "Error: Claude is currently processing another prompt. Try again later.";
        }

        String effectiveModel = (model == null || model.isBlank()) ? chatService.getModel() : model;

        StringBuilder response = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        // Show the incoming MCP prompt on the browser UI with caller info
        String callerLabel = formatCaller(_caller);
        chatResource.emitSse(ChatEvent.info("[MCP" + callerLabel + "] " + prompt));

        Thread.startVirtualThread(() -> {
            try {
                chatService.sendPrompt(prompt, effectiveModel, event -> {
                    // Collect response for MCP client
                    if ("delta".equals(event.type())) {
                        response.append(event.content());
                    } else if ("error".equals(event.type())) {
                        response.append("[ERROR] ").append(event.content());
                    }
                    // Also forward to browser UI via SSE
                    chatResource.emitSse(event);
                });
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                return "Error: Prompt timed out after 5 minutes. Partial response: " + response;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted. Partial response: " + response;
        }

        return response.toString();
    }

    @Tool(description = "Get the current status of the LLM Console (model, session, busy state)")
    String getStatus() {
        return String.format("model=%s, session=%s, busy=%s",
                chatService.getModel(),
                chatService.getSessionId(),
                chatService.isBusy());
    }

    @Tool(description = "List available Claude models")
    String listModels() {
        List<String> models = new ArrayList<>();
        for (var entry : chatService.getAvailableModels()) {
            models.add(entry.name());
        }
        return String.join(", ", models);
    }

    @Tool(description = "Cancel the currently running Claude request")
    String cancelRequest() {
        if (!chatService.isBusy()) {
            return "No request is currently running.";
        }
        chatService.cancel();
        return "Cancel requested.";
    }

    /**
     * Formats _caller into a readable label.
     * HATEOAS: _caller is a URL like "http://localhost:8888/api/sessions/abc123"
     * Fetches the URL to get caller metadata (name, remoteAddress, etc.)
     * If fetch fails or _caller is not a URL, returns it as-is.
     */
    private String formatCaller(String callerUrl) {
        if (callerUrl == null || callerUrl.isBlank()) {
            return "";
        }
        if (callerUrl.startsWith("http")) {
            try {
                var client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(2)).build();
                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(callerUrl))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .GET().build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var obj = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
                    String name = obj.has("caller") ? obj.get("caller").asText() : "unknown";
                    boolean registered = obj.has("registered") && obj.get("registered").asBoolean();
                    if (registered && obj.has("callerUrl")) {
                        // Use registered server URL instead of ephemeral remote address
                        return " from " + name + " (" + obj.get("callerUrl").asText() + ")";
                    }
                    String addr = obj.has("remoteAddress") ? obj.get("remoteAddress").asText() : "";
                    return " from " + name + (addr.isEmpty() ? "" : "@" + addr);
                }
            } catch (Exception e) {
                // Fall through
            }
        }
        return " from " + callerUrl;
    }
}
