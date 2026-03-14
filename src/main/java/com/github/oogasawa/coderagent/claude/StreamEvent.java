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

package com.github.oogasawa.coderagent.claude;

import java.util.List;

/**
 * Represents a parsed event from Claude CLI stream-json output.
 *
 * @param type the event type
 * @param content the text content (for assistant/delta events)
 * @param sessionId the session ID (for result events)
 * @param costUsd the cost in USD (for result events, -1 if not available)
 * @param durationMs the duration in milliseconds (for result events, -1 if not available)
 * @param isError whether this is an error event
 * @param rawJson the raw JSON line
 * @param promptId unique identifier for a prompt event (for tool_use/permission events)
 * @param promptType the type of prompt (e.g., "permission", "tool_use")
 * @param options list of options for the prompt (e.g., ["Allow", "Deny"])
 * @author Osamu Ogasawara
 */
public record StreamEvent(
    String type,
    String content,
    String sessionId,
    double costUsd,
    long durationMs,
    boolean isError,
    String rawJson,
    String promptId,
    String promptType,
    List<String> options
) {

    /**
     * Compatibility constructor for events without prompt fields.
     */
    public StreamEvent(String type, String content, String sessionId,
                       double costUsd, long durationMs, boolean isError, String rawJson) {
        this(type, content, sessionId, costUsd, durationMs, isError, rawJson, null, null, null);
    }

    /**
     * Creates a text content event.
     *
     * @param type the event type
     * @param content the text content
     * @return a StreamEvent for text content
     */
    public static StreamEvent text(String type, String content) {
        return new StreamEvent(type, content, null, -1, -1, false, null);
    }

    /**
     * Creates a result event.
     *
     * @param sessionId the session ID
     * @param costUsd the cost in USD
     * @param durationMs the duration in milliseconds
     * @return a StreamEvent for a result
     */
    public static StreamEvent result(String sessionId, double costUsd, long durationMs) {
        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, null);
    }

    /**
     * Creates an error event.
     *
     * @param message the error message
     * @return a StreamEvent for an error
     */
    public static StreamEvent error(String message) {
        return new StreamEvent("error", message, null, -1, -1, true, null);
    }

    /**
     * Creates a prompt event for interactive prompts (tool use permission, etc.).
     *
     * @param promptId unique identifier for this prompt
     * @param content the prompt text
     * @param promptType the type of prompt
     * @param options the available options
     * @param rawJson the raw JSON
     * @return a StreamEvent for a prompt
     */
    public static StreamEvent prompt(String promptId, String content,
                                      String promptType, List<String> options, String rawJson) {
        return new StreamEvent("prompt", content, null, -1, -1, false, rawJson,
                               promptId, promptType, options);
    }

    /**
     * Checks if this event contains displayable text content.
     *
     * @return true if this event has text to display
     */
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    /**
     * Checks if this event is a prompt requiring user interaction.
     *
     * @return true if this is a prompt event
     */
    public boolean isPrompt() {
        return "prompt".equals(type);
    }
}
