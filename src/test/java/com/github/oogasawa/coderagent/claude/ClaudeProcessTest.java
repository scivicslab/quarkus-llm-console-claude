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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeProcess command building (no subprocess execution).
 */
class ClaudeProcessTest {

    @Test
    void buildCommand_defaultConfig() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());
        List<String> cmd = cp.buildCommand();

        assertEquals("claude", cmd.get(0));
        // --print is intentionally omitted to enable interactive tool permissions
        assertFalse(cmd.contains("--print"), "Should not contain --print");
        assertEquals("--output-format", cmd.get(1));
        assertEquals("stream-json", cmd.get(2));
        assertTrue(cmd.contains("--input-format"));
        int inputIdx = cmd.indexOf("--input-format");
        assertEquals("stream-json", cmd.get(inputIdx + 1));
        assertTrue(cmd.contains("--verbose"));
        assertTrue(cmd.contains("--model"));
        int modelIdx = cmd.indexOf("--model");
        assertEquals("opus", cmd.get(modelIdx + 1));
        // No prompt argument — prompt is sent via stdin in v2
        // The command should not contain any free-text prompt argument
        for (String arg : cmd) {
            assertFalse(arg.equals("Hello") || arg.equals("test"),
                "Command should not contain a prompt argument");
        }
    }

    @Test
    void buildCommand_hasInputFormatStreamJson() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--input-format"));
        int idx = cmd.indexOf("--input-format");
        assertEquals("stream-json", cmd.get(idx + 1));
    }

    @Test
    void buildCommand_withModel() {
        ClaudeConfig config = ClaudeConfig.defaults().withModel("opus");
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--model"));
        int idx = cmd.indexOf("--model");
        assertEquals("opus", cmd.get(idx + 1));
    }

    @Test
    void buildCommand_withSystemPrompt() {
        ClaudeConfig config = ClaudeConfig.defaults().withSystemPrompt("Be concise");
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--system-prompt"));
        int idx = cmd.indexOf("--system-prompt");
        assertEquals("Be concise", cmd.get(idx + 1));
    }

    @Test
    void buildCommand_withMaxTurns() {
        ClaudeConfig config = ClaudeConfig.defaults().withMaxTurns(3);
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--max-turns"));
        int idx = cmd.indexOf("--max-turns");
        assertEquals("3", cmd.get(idx + 1));
    }

    @Test
    void buildCommand_withSessionId() {
        ClaudeConfig config = ClaudeConfig.defaults().withSessionId("sess-abc-123");
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--resume"));
        int idx = cmd.indexOf("--resume");
        assertEquals("sess-abc-123", cmd.get(idx + 1));
    }

    @Test
    void buildCommand_withContinueSession() {
        ClaudeConfig config = ClaudeConfig.defaults().withContinueSession();
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("-c"));
    }

    @Test
    void buildCommand_withAllowedTools() {
        ClaudeConfig config = ClaudeConfig.defaults().withAllowedTools("Read", "Bash");
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--allowedTools"));
        // Should have two --allowedTools entries
        int count = 0;
        for (String s : cmd) {
            if ("--allowedTools".equals(s)) count++;
        }
        assertEquals(2, count);
    }

    @Test
    void buildCommand_withoutMaxTurns_noFlag() {
        ClaudeConfig config = ClaudeConfig.defaults();
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertFalse(cmd.contains("--max-turns"));
    }

    @Test
    void buildCommand_withoutSessionId_noFlag() {
        ClaudeConfig config = ClaudeConfig.defaults();
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertFalse(cmd.contains("--resume"));
    }

    @Test
    void buildCommand_withoutContinue_noFlag() {
        ClaudeConfig config = ClaudeConfig.defaults();
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertFalse(cmd.contains("-c"));
    }

    @Test
    void buildCommand_noPromptArg() {
        // In v2, prompt is sent via stdin, not as command-line argument
        ClaudeConfig config = ClaudeConfig.defaults()
            .withModel("opus")
            .withSystemPrompt("test prompt")
            .withMaxTurns(5);
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        // Last argument should not be a prompt string
        String last = cmd.get(cmd.size() - 1);
        // It should be a flag value (e.g., "5" for max-turns or a tool name)
        assertFalse(last.contains("question"));
    }

    @Test
    void buildCommand_fullConfig() {
        ClaudeConfig config = ClaudeConfig.defaults()
            .withModel("opus")
            .withSystemPrompt("Be helpful")
            .withMaxTurns(10)
            .withSessionId("sess-1")
            .withAllowedTools("Read");
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertTrue(cmd.contains("--model"));
        assertTrue(cmd.contains("--system-prompt"));
        assertTrue(cmd.contains("--max-turns"));
        assertTrue(cmd.contains("--resume"));
        assertTrue(cmd.contains("--allowedTools"));
        assertTrue(cmd.contains("--input-format"));
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        ClaudeConfig config = ClaudeConfig.defaults();
        ClaudeProcess cp = new ClaudeProcess(config);

        assertEquals(config, cp.getConfig());
    }

    @Test
    void setConfig_updatesConfig() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());
        ClaudeConfig newConfig = ClaudeConfig.defaults().withModel("haiku");
        cp.setConfig(newConfig);

        assertEquals("haiku", cp.getConfig().model());
    }

    @Test
    void getLastSessionId_initiallyNull() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());

        assertNull(cp.getLastSessionId());
    }

    @Test
    void isAlive_initiallyFalse() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());

        assertFalse(cp.isAlive());
    }

    @Test
    void buildCommand_nullModel_noModelFlag() {
        ClaudeConfig config = new ClaudeConfig(null, null, 0, null, null, false, null);
        ClaudeProcess cp = new ClaudeProcess(config);
        List<String> cmd = cp.buildCommand();

        assertFalse(cmd.contains("--model"));
    }

    @Test
    void wrapWithPty_wrapsWithScript() {
        ClaudeProcess cp = new ClaudeProcess(ClaudeConfig.defaults());
        List<String> claudeCmd = cp.buildCommand();
        List<String> wrapped = cp.wrapWithPty(claudeCmd);

        assertEquals("script", wrapped.get(0));
        assertEquals("-q", wrapped.get(1));
        assertEquals("-e", wrapped.get(2));
        assertEquals("-f", wrapped.get(3));
        assertEquals("-c", wrapped.get(4));
        // The inner command is a shell-escaped string
        assertTrue(wrapped.get(5).contains("claude"));
        assertEquals("/dev/null", wrapped.get(wrapped.size() - 1));
    }

    @Test
    void stripAnsi_removesEscapeSequences() {
        String dirty = "\u001b[?1004l\u001b[?2004l{\"type\":\"result\"}";
        String clean = ClaudeProcess.stripAnsi(dirty);
        assertEquals("{\"type\":\"result\"}", clean);
    }

    @Test
    void stripAnsi_preservesCleanString() {
        String clean = "{\"type\":\"assistant\",\"content\":\"Hello!\"}";
        assertEquals(clean, ClaudeProcess.stripAnsi(clean));
    }

    @Test
    void stripAnsi_handlesEmptyString() {
        assertEquals("", ClaudeProcess.stripAnsi(""));
    }

    @Test
    void escapeJsonString_basicString() {
        assertEquals("\"hello\"", ClaudeProcess.escapeJsonString("hello"));
    }

    @Test
    void escapeJsonString_withQuotes() {
        assertEquals("\"say \\\"hi\\\"\"", ClaudeProcess.escapeJsonString("say \"hi\""));
    }

    @Test
    void escapeJsonString_withBackslash() {
        assertEquals("\"path\\\\to\\\\file\"", ClaudeProcess.escapeJsonString("path\\to\\file"));
    }

    @Test
    void escapeJsonString_withNewlines() {
        assertEquals("\"line1\\nline2\"", ClaudeProcess.escapeJsonString("line1\nline2"));
    }

    @Test
    void escapeJsonString_withTab() {
        assertEquals("\"col1\\tcol2\"", ClaudeProcess.escapeJsonString("col1\tcol2"));
    }

    @Test
    void escapeJsonString_withControlChar() {
        String input = "before\u0003after";
        String result = ClaudeProcess.escapeJsonString(input);
        assertEquals("\"before\\u0003after\"", result);
    }
}
