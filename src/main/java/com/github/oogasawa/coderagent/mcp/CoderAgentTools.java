package com.github.oogasawa.coderagent.mcp;

import com.github.oogasawa.coderagent.rest.ChatEvent;
import com.github.oogasawa.coderagent.service.ChatService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MCP tools for the Coder Agent.
 *
 * <p>Allows external MCP clients (e.g., workflow-editor) to send prompts
 * to Claude and retrieve results programmatically.</p>
 */
public class CoderAgentTools {

    @Inject
    ChatService chatService;

    @Tool(description = "Send a prompt to Claude AI and return the response. "
            + "This is a synchronous call that waits for the full response.")
    String sendPrompt(
            @ToolArg(description = "The prompt text to send to Claude") String prompt,
            @ToolArg(description = "The model to use (e.g., sonnet, opus, haiku). Leave empty for current model.") String model
    ) {
        if (chatService.isBusy()) {
            return "Error: Claude is currently processing another prompt. Try again later.";
        }

        String effectiveModel = (model == null || model.isBlank()) ? chatService.getModel() : model;

        StringBuilder response = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            try {
                chatService.sendPrompt(prompt, effectiveModel, event -> {
                    if ("delta".equals(event.type())) {
                        response.append(event.content());
                    } else if ("error".equals(event.type())) {
                        response.append("[ERROR] ").append(event.content());
                    }
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

    @Tool(description = "Get the current status of the Coder Agent (model, session, busy state)")
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
}
