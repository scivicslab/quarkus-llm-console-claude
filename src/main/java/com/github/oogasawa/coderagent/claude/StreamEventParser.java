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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses stream-json output lines from Claude CLI into {@link StreamEvent} objects.
 *
 * <p>Claude Code {@code --output-format stream-json} emits these event types:</p>
 * <ul>
 *   <li>{@code system} — init event with session_id, tools, model</li>
 *   <li>{@code assistant} — assistant message with content blocks (text, thinking, tool_use)</li>
 *   <li>{@code user} — tool results returned by Claude Code</li>
 *   <li>{@code result} — turn completion with cost and duration</li>
 *   <li>{@code rate_limit_event} — rate limit status</li>
 *   <li>{@code error} — error event</li>
 * </ul>
 *
 * @author Osamu Ogasawara
 */
public class StreamEventParser {

    /**
     * Parses a single JSON line into a StreamEvent.
     *
     * @param jsonLine a single line of stream-json output
     * @return the parsed StreamEvent, or null for blank/irrelevant input
     */
    public StreamEvent parse(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(jsonLine);
            String type = json.optString("type", "unknown");

            return switch (type) {
                case "system" -> parseSystem(json, jsonLine);
                case "assistant" -> parseAssistant(json, jsonLine);
                case "user" -> parseUser(json, jsonLine);
                case "result" -> parseResult(json, jsonLine);
                case "error" -> parseError(json, jsonLine);
                case "rate_limit_event" -> new StreamEvent("rate_limit_event", null, null, -1, -1, false, jsonLine);
                default -> new StreamEvent(type, null, null, -1, -1, false, jsonLine);
            };
        } catch (Exception e) {
            return StreamEvent.error("Failed to parse JSON: " + e.getMessage());
        }
    }

    /**
     * Parses a system init event.
     *
     * <p>Format: {@code {"type":"system","subtype":"init","session_id":"...","model":"...","tools":[...]}}</p>
     */
    private StreamEvent parseSystem(JSONObject json, String rawJson) {
        String subtype = json.optString("subtype", "");
        String sessionId = json.optString("session_id", null);
        String model = json.optString("model", "");

        String content;
        if ("init".equals(subtype)) {
            content = "Session initialized" + (model.isEmpty() ? "" : " (model: " + model + ")");
        } else {
            content = json.optString("message", json.optString("content", subtype));
        }

        return new StreamEvent("system", content, sessionId, -1, -1, false, rawJson);
    }

    /**
     * Parses an assistant message event.
     *
     * <p>Format: {@code {"type":"assistant","message":{"content":[{"type":"text","text":"..."},
     * {"type":"thinking","thinking":"..."},{"type":"tool_use","name":"...","input":{...}}]}}}</p>
     *
     * <p>Content blocks are processed in order. Text blocks are concatenated.
     * Tool use blocks are reported as activity. AskUserQuestion tool calls
     * are converted to prompt events.</p>
     */
    private StreamEvent parseAssistant(JSONObject json, String rawJson) {
        JSONObject message = json.optJSONObject("message");
        if (message == null) {
            // Fallback: direct content field
            String content = json.optString("content", "");
            return new StreamEvent("assistant", content, null, -1, -1, false, rawJson);
        }

        JSONArray contentArray = message.optJSONArray("content");
        if (contentArray == null || contentArray.length() == 0) {
            return new StreamEvent("assistant", "", null, -1, -1, false, rawJson);
        }

        StringBuilder textBuilder = new StringBuilder();
        boolean hasThinking = false;
        List<String> toolNames = new ArrayList<>();

        for (int i = 0; i < contentArray.length(); i++) {
            JSONObject block = contentArray.optJSONObject(i);
            if (block == null) continue;

            String blockType = block.optString("type", "");
            switch (blockType) {
                case "text" -> textBuilder.append(block.optString("text", ""));
                case "thinking" -> hasThinking = true;
                case "tool_use" -> {
                    String toolName = block.optString("name", "");
                    if ("AskUserQuestion".equals(toolName)) {
                        return parseEmbeddedAskUser(block, rawJson);
                    }
                    toolNames.add(toolName);
                }
            }
        }

        String text = textBuilder.toString();

        // If we have text content, return it
        if (!text.isEmpty()) {
            return new StreamEvent("assistant", text, null, -1, -1, false, rawJson);
        }

        // No text: emit thinking or tool activity indicator
        if (hasThinking) {
            return new StreamEvent("thinking", null, null, -1, -1, false, rawJson);
        }
        if (!toolNames.isEmpty()) {
            return new StreamEvent("tool_activity",
                String.join(", ", toolNames), null, -1, -1, false, rawJson);
        }

        return new StreamEvent("assistant", "", null, -1, -1, false, rawJson);
    }

    /**
     * Parses a user event (tool result from Claude Code).
     *
     * <p>Format: {@code {"type":"user","message":{"content":[{"type":"tool_result",...}]},
     * "tool_use_result":{...}}}</p>
     *
     * <p>These events indicate that Claude Code has executed a tool and received a result.
     * The UI may optionally display a brief summary.</p>
     */
    private StreamEvent parseUser(JSONObject json, String rawJson) {
        // Extract tool result summary for optional display
        JSONObject toolResult = json.optJSONObject("tool_use_result");
        String summary = null;
        if (toolResult != null) {
            summary = toolResult.toString();
            if (summary.length() > 200) {
                summary = summary.substring(0, 200) + "...";
            }
        } else {
            // Try to extract from message.content[0].content (raw tool output)
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArray = message.optJSONArray("content");
                if (contentArray != null && contentArray.length() > 0) {
                    JSONObject first = contentArray.optJSONObject(0);
                    if (first != null) {
                        String textContent = first.optString("content", "");
                        if (!textContent.isEmpty()) {
                            summary = textContent.length() > 200
                                ? textContent.substring(0, 200) + "..." : textContent;
                        }
                    }
                }
            }
        }

        return new StreamEvent("tool_result", summary, null, -1, -1, false, rawJson);
    }

    /**
     * Parses a result event.
     *
     * <p>Format: {@code {"type":"result","subtype":"success","session_id":"...",
     * "total_cost_usd":0.05,"duration_ms":1234,"duration_api_ms":1000,
     * "num_turns":1,"result":"...","is_error":false}}</p>
     */
    private StreamEvent parseResult(JSONObject json, String rawJson) {
        String sessionId = json.optString("session_id", null);
        double costUsd = json.optDouble("total_cost_usd", -1);
        long durationMs = json.optLong("duration_ms", -1);
        boolean isError = json.optBoolean("is_error", false);

        if (isError) {
            String errorResult = json.optString("result", "Unknown error");
            return new StreamEvent("error", errorResult, sessionId, costUsd, durationMs, true, rawJson);
        }

        return new StreamEvent("result", null, sessionId, costUsd, durationMs, false, rawJson);
    }

    /**
     * Parses an error event.
     */
    private StreamEvent parseError(JSONObject json, String rawJson) {
        // Check for object format: {"error": {"message": "..."}}
        JSONObject errorObj = json.optJSONObject("error");
        if (errorObj != null) {
            String message = errorObj.optString("message", "Unknown error");
            return new StreamEvent("error", message, null, -1, -1, true, rawJson);
        }
        // Fallback: string format or message field
        String message = json.optString("error",
            json.optString("message", "Unknown error"));
        return new StreamEvent("error", message, null, -1, -1, true, rawJson);
    }

    // -------------------------------------------------------------------------
    // AskUserQuestion handling
    // -------------------------------------------------------------------------

    private StreamEvent parseEmbeddedAskUser(JSONObject toolUseBlock, String rawJson) {
        String id = toolUseBlock.optString("id", UUID.randomUUID().toString());
        JSONObject input = toolUseBlock.optJSONObject("input");
        if (input == null) {
            return StreamEvent.prompt(id, "Question from Claude", "ask_user",
                new ArrayList<>(), rawJson);
        }
        return parseAskUserQuestion(id, input, rawJson);
    }

    private StreamEvent parseAskUserQuestion(String id, JSONObject input, String rawJson) {
        // Try "questions" array first (actual Claude Code format)
        JSONArray questionsArray = input.optJSONArray("questions");
        if (questionsArray != null && questionsArray.length() > 0) {
            JSONObject firstQ = questionsArray.optJSONObject(0);
            if (firstQ != null) {
                return parseSingleQuestion(id, firstQ, rawJson);
            }
        }
        // Fallback: direct question/options fields
        return parseSingleQuestion(id, input, rawJson);
    }

    private StreamEvent parseSingleQuestion(String id, JSONObject qObj, String rawJson) {
        String question = qObj.optString("question",
            qObj.optString("message", "Question from Claude"));

        List<String> options = new ArrayList<>();
        JSONArray optionsArray = qObj.optJSONArray("options");
        if (optionsArray != null) {
            for (int i = 0; i < optionsArray.length(); i++) {
                Object opt = optionsArray.opt(i);
                if (opt instanceof JSONObject optObj) {
                    options.add(optObj.optString("label", optObj.toString()));
                } else if (opt instanceof String optStr) {
                    options.add(optStr);
                } else {
                    options.add(String.valueOf(opt));
                }
            }
        }

        return StreamEvent.prompt(id, question, "ask_user", options, rawJson);
    }
}
