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

package com.github.oogasawa.coderagent.command;

import com.github.oogasawa.coderagent.claude.ClaudeConfig;
import com.github.oogasawa.coderagent.claude.ClaudeProcess;
import com.github.oogasawa.coderagent.rest.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandHandlerTest {

    private ClaudeProcess claudeProcess;
    private SlashCommandHandler handler;
    private List<ChatEvent> messages;

    @BeforeEach
    void setUp() {
        claudeProcess = new ClaudeProcess(ClaudeConfig.defaults());
        handler = new SlashCommandHandler(claudeProcess);
        messages = new ArrayList<>();
    }

    @Test
    void isCommand_slashPrefix_returnsTrue() {
        assertTrue(handler.isCommand("/model"));
        assertTrue(handler.isCommand("/help"));
        assertTrue(handler.isCommand("/clear"));
    }

    @Test
    void isCommand_noSlash_returnsFalse() {
        assertFalse(handler.isCommand("hello"));
        assertFalse(handler.isCommand("model opus"));
    }

    @Test
    void isCommand_null_returnsFalse() {
        assertFalse(handler.isCommand(null));
    }

    @Test
    void handle_modelWithoutArgs_showsCurrent() {
        handler.handle("/model", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("opus"));
    }

    @Test
    void handle_modelWithArgs_changesModel() {
        handler.handle("/model opus", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("opus"));
        assertEquals("opus", claudeProcess.getConfig().model());
    }

    @Test
    void handle_clear_clearsSession() {
        // Set a session first
        claudeProcess.setConfig(claudeProcess.getConfig().withSessionId("sess-123"));

        handler.handle("/clear", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("cleared"));
        assertNull(claudeProcess.getConfig().sessionId());
        assertFalse(claudeProcess.getConfig().continueSession());
    }

    @Test
    void handle_sessionWithoutArgs_showsNoSession() {
        handler.handle("/session", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("No active session"));
    }

    @Test
    void handle_sessionWithArgs_setsSessionId() {
        handler.handle("/session sess-xyz", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("sess-xyz"));
        assertEquals("sess-xyz", claudeProcess.getConfig().sessionId());
    }

    @Test
    void handle_help_showsHelp() {
        handler.handle("/help", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("/model"));
        assertTrue(messages.get(0).content().contains("/clear"));
        assertTrue(messages.get(0).content().contains("/session"));
    }

    @Test
    void handle_questionMark_showsHelp() {
        handler.handle("/?", messages::add);

        assertEquals(1, messages.size());
        assertEquals("info", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("/help"));
    }

    @Test
    void handle_unknownCommand_sendsError() {
        handler.handle("/unknown", messages::add);

        assertEquals(1, messages.size());
        assertEquals("error", messages.get(0).type());
        assertTrue(messages.get(0).content().contains("Unknown command"));
    }

    @Test
    void handle_modelPreservesOtherConfig() {
        ClaudeConfig config = ClaudeConfig.defaults()
            .withSystemPrompt("Be concise")
            .withMaxTurns(5);
        claudeProcess.setConfig(config);

        handler.handle("/model opus", messages::add);

        assertEquals("opus", claudeProcess.getConfig().model());
        assertEquals("Be concise", claudeProcess.getConfig().systemPrompt());
        assertEquals(5, claudeProcess.getConfig().maxTurns());
    }

    @Test
    void handle_clearPreservesModel() {
        claudeProcess.setConfig(ClaudeConfig.defaults()
            .withModel("opus")
            .withSessionId("sess-123"));

        handler.handle("/clear", messages::add);

        assertEquals("opus", claudeProcess.getConfig().model());
        assertNull(claudeProcess.getConfig().sessionId());
    }
}
