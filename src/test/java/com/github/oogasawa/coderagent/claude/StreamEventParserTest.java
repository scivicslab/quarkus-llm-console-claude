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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventParserTest {

    private final StreamEventParser parser = new StreamEventParser();

    // -------------------------------------------------------------------------
    // Null / blank input
    // -------------------------------------------------------------------------

    @Test
    void parse_nullInput_returnsNull() {
        assertNull(parser.parse(null));
    }

    @Test
    void parse_blankInput_returnsNull() {
        assertNull(parser.parse("   "));
    }

    @Test
    void parse_invalidJson_returnsError() {
        StreamEvent event = parser.parse("not json");
        assertNotNull(event);
        assertEquals("error", event.type());
        assertTrue(event.isError());
    }

    // -------------------------------------------------------------------------
    // system events (actual Claude Code format)
    // -------------------------------------------------------------------------

    @Test
    void parse_systemInit() {
        String json = """
            {"type":"system","subtype":"init","cwd":"/home/user","session_id":"abc-123","model":"claude-sonnet-4-6","tools":["Bash","Read"]}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("system", event.type());
        assertEquals("abc-123", event.sessionId());
        assertTrue(event.content().contains("claude-sonnet-4-6"));
        assertFalse(event.isError());
    }

    @Test
    void parse_systemNonInit() {
        String json = """
            {"type":"system","message":"Claude CLI initialized"}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("system", event.type());
        assertEquals("Claude CLI initialized", event.content());
    }

    // -------------------------------------------------------------------------
    // assistant events (actual Claude Code format)
    // -------------------------------------------------------------------------

    @Test
    void parse_assistantWithText() {
        String json = """
            {"type":"assistant","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"Hello!"}],"role":"assistant"}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("assistant", event.type());
        assertEquals("Hello!", event.content());
        assertFalse(event.isError());
    }

    @Test
    void parse_assistantWithMultipleTextBlocks() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"world!"}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("assistant", event.type());
        assertEquals("Hello world!", event.content());
    }

    @Test
    void parse_assistantWithThinkingOnly() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Let me think..."}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("thinking", event.type());
        assertFalse(event.hasContent());
    }

    @Test
    void parse_assistantWithToolUse() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_abc","name":"Bash","input":{"command":"ls"}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("tool_activity", event.type());
        assertEquals("Bash", event.content());
    }

    @Test
    void parse_assistantWithMultipleToolUse() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Read","input":{}},{"type":"tool_use","id":"t2","name":"Bash","input":{}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("tool_activity", event.type());
        assertEquals("Read, Bash", event.content());
    }

    @Test
    void parse_assistantWithTextAndToolUse() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"text","text":"Let me check that."},{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("assistant", event.type());
        assertEquals("Let me check that.", event.content());
    }

    @Test
    void parse_assistantWithDirectContent() {
        String json = """
            {"type":"assistant","content":"Direct content"}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("assistant", event.type());
        assertEquals("Direct content", event.content());
    }

    @Test
    void parse_assistantEmptyContent() {
        String json = """
            {"type":"assistant","message":{"content":[]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("assistant", event.type());
        assertEquals("", event.content());
    }

    // -------------------------------------------------------------------------
    // user events (tool results)
    // -------------------------------------------------------------------------

    @Test
    void parse_userToolResult() {
        String json = """
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1"}]},"tool_use_result":{"matches":["Bash"],"query":"select:Bash"}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("tool_result", event.type());
        assertNotNull(event.content());
        assertFalse(event.isError());
    }

    @Test
    void parse_userToolResultWithContent() {
        String json = """
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"file1.txt\\nfile2.txt"}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("tool_result", event.type());
        assertTrue(event.content().contains("file1.txt"));
    }

    // -------------------------------------------------------------------------
    // result events (actual Claude Code format)
    // -------------------------------------------------------------------------

    @Test
    void parse_resultSuccess() {
        String json = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1234,"duration_api_ms":1200,"num_turns":1,"result":"Hello!","session_id":"sess-abc","total_cost_usd":0.045}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("result", event.type());
        assertEquals("sess-abc", event.sessionId());
        assertEquals(0.045, event.costUsd(), 0.001);
        assertEquals(1234, event.durationMs());
        assertFalse(event.isError());
    }

    @Test
    void parse_resultError() {
        String json = """
            {"type":"result","subtype":"error","is_error":true,"result":"Something went wrong","session_id":"sess-err","total_cost_usd":0.01,"duration_ms":500}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("error", event.type());
        assertEquals("Something went wrong", event.content());
        assertEquals("sess-err", event.sessionId());
        assertTrue(event.isError());
    }

    // -------------------------------------------------------------------------
    // error events
    // -------------------------------------------------------------------------

    @Test
    void parse_errorStringFormat() {
        String json = """
            {"type":"error","error":"Rate limit exceeded"}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("error", event.type());
        assertEquals("Rate limit exceeded", event.content());
        assertTrue(event.isError());
    }

    @Test
    void parse_errorObjectFormat() {
        String json = """
            {"type":"error","error":{"message":"Authentication failed"}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("error", event.type());
        assertEquals("Authentication failed", event.content());
        assertTrue(event.isError());
    }

    @Test
    void parse_errorMessageField() {
        String json = """
            {"type":"error","message":"Connection timeout"}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("error", event.type());
        assertEquals("Connection timeout", event.content());
    }

    // -------------------------------------------------------------------------
    // rate_limit_event
    // -------------------------------------------------------------------------

    @Test
    void parse_rateLimitEvent() {
        String json = """
            {"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("rate_limit_event", event.type());
        assertFalse(event.isError());
    }

    // -------------------------------------------------------------------------
    // unknown types
    // -------------------------------------------------------------------------

    @Test
    void parse_unknownType() {
        String json = """
            {"type":"ping","data":"keepalive"}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("ping", event.type());
        assertFalse(event.isError());
    }

    // -------------------------------------------------------------------------
    // AskUserQuestion
    // -------------------------------------------------------------------------

    @Test
    void parse_askUserQuestion_realClaudeCodeFormat() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_abc","name":"AskUserQuestion","input":{"questions":[{"question":"Do you want to continue?","header":"Continue?","options":[{"label":"Yes","description":"Continue with the task."},{"label":"No","description":"Stop."}],"multiSelect":false}]}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("prompt", event.type());
        assertTrue(event.isPrompt());
        assertEquals("toolu_abc", event.promptId());
        assertEquals("ask_user", event.promptType());
        assertEquals("Do you want to continue?", event.content());
        assertEquals(2, event.options().size());
        assertEquals("Yes", event.options().get(0));
        assertEquals("No", event.options().get(1));
    }

    @Test
    void parse_askUserQuestion_withStringOptions() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"ask-2","name":"AskUserQuestion","input":{"question":"Pick one","options":["A","B","C"]}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("prompt", event.type());
        assertEquals("ask_user", event.promptType());
        assertEquals("Pick one", event.content());
        assertEquals(3, event.options().size());
    }

    @Test
    void parse_askUserQuestion_noOptions() {
        String json = """
            {"type":"assistant","message":{"content":[{"type":"tool_use","id":"ask-3","name":"AskUserQuestion","input":{"question":"What do you think?"}}]}}
            """;

        StreamEvent event = parser.parse(json);

        assertEquals("prompt", event.type());
        assertEquals("ask_user", event.promptType());
        assertEquals("What do you think?", event.content());
        assertTrue(event.options().isEmpty());
    }

    // -------------------------------------------------------------------------
    // StreamEvent record factories
    // -------------------------------------------------------------------------

    @Test
    void streamEvent_text_factory() {
        StreamEvent event = StreamEvent.text("assistant", "hello");

        assertEquals("assistant", event.type());
        assertEquals("hello", event.content());
        assertTrue(event.hasContent());
    }

    @Test
    void streamEvent_result_factory() {
        StreamEvent event = StreamEvent.result("sess-1", 0.02, 3000);

        assertEquals("result", event.type());
        assertEquals("sess-1", event.sessionId());
        assertEquals(0.02, event.costUsd(), 0.0001);
        assertEquals(3000, event.durationMs());
    }

    @Test
    void streamEvent_error_factory() {
        StreamEvent event = StreamEvent.error("something went wrong");

        assertEquals("error", event.type());
        assertEquals("something went wrong", event.content());
        assertTrue(event.isError());
    }

    @Test
    void streamEvent_prompt_factory() {
        StreamEvent event = StreamEvent.prompt("p-1", "Allow?", "permission",
            java.util.List.of("Yes", "No"), null);

        assertEquals("prompt", event.type());
        assertTrue(event.isPrompt());
        assertEquals("p-1", event.promptId());
    }

    @Test
    void streamEvent_hasContent_falseForNull() {
        StreamEvent event = new StreamEvent("test", null, null, -1, -1, false, null);
        assertFalse(event.hasContent());
    }

    @Test
    void streamEvent_hasContent_falseForEmpty() {
        StreamEvent event = new StreamEvent("test", "", null, -1, -1, false, null);
        assertFalse(event.hasContent());
    }
}
