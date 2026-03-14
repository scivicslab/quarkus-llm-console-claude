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

package com.github.oogasawa.coderagent.service;

import com.github.oogasawa.coderagent.claude.ClaudeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTest {

    // --- session file persistence ---

    @Test
    void restoreSession_fromFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve(".coder-agent-session-7777");
        Files.writeString(file, "sessionId=abc-123\nmodel=opus\n");

        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 7777);

        assertEquals("opus", service.getModel());
    }

    @Test
    void restoreSession_noFile(@TempDir Path tempDir) {
        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 7777);

        assertEquals("opus", service.getModel());
    }

    @Test
    void saveSession_writesFile(@TempDir Path tempDir) throws Exception {
        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 7777);

        service.saveSession("sess-xyz", "haiku");

        Path file = tempDir.resolve(".coder-agent-session-7777");
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("sessionId=sess-xyz"));
        assertTrue(content.contains("model=haiku"));
    }

    @Test
    void deleteSessionFile_removesFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve(".coder-agent-session-7777");
        Files.writeString(file, "sessionId=abc\nmodel=sonnet\n");

        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 7777);

        service.deleteSessionFile();
        assertFalse(Files.exists(file));
    }

    @Test
    void restoreSession_corruptFile_fallsBackToDefaults(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve(".coder-agent-session-7777");
        Files.writeString(file, "garbage content\nno equals sign\n");

        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 7777);

        assertEquals("opus", service.getModel());
    }

    @Test
    void cancel_whenNotBusy_doesNotThrow(@TempDir Path tempDir) {
        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 9999);
        assertDoesNotThrow(service::cancel);
    }

    @Test
    void getAvailableModels_returnsClaudeModels(@TempDir Path tempDir) {
        ChatService service = new ChatService(
                Optional.empty(), Optional.empty(),
                tempDir.resolve(".coder-agent-session").toString(), 9999);

        var models = service.getAvailableModels();
        assertEquals(3, models.size());
        assertEquals("sonnet", models.get(0).name());
        assertEquals("claude", models.get(0).type());
    }
}
