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

class ClaudeConfigTest {

    @Test
    void defaults_returnsExpectedValues() {
        ClaudeConfig config = ClaudeConfig.defaults();

        assertEquals("opus", config.model());
        assertNull(config.systemPrompt());
        assertEquals(0, config.maxTurns());
        assertNull(config.workingDir());
        assertNull(config.sessionId());
        assertFalse(config.continueSession());
        assertNull(config.allowedTools());
    }

    @Test
    void withModel_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withModel("opus");

        assertEquals("opus", config.model());
        // Other fields remain default
        assertNull(config.systemPrompt());
        assertEquals(0, config.maxTurns());
    }

    @Test
    void withSystemPrompt_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withSystemPrompt("You are a helpful assistant");

        assertEquals("You are a helpful assistant", config.systemPrompt());
        assertEquals("opus", config.model());
    }

    @Test
    void withSessionId_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withSessionId("abc-123");

        assertEquals("abc-123", config.sessionId());
        assertFalse(config.continueSession());
    }

    @Test
    void withContinueSession_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withContinueSession();

        assertTrue(config.continueSession());
    }

    @Test
    void withMaxTurns_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withMaxTurns(5);

        assertEquals(5, config.maxTurns());
    }

    @Test
    void withWorkingDir_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withWorkingDir("/tmp/work");

        assertEquals("/tmp/work", config.workingDir());
    }

    @Test
    void withAllowedTools_createsNewConfig() {
        ClaudeConfig config = ClaudeConfig.defaults().withAllowedTools("Read", "Write", "Bash");

        assertArrayEquals(new String[]{"Read", "Write", "Bash"}, config.allowedTools());
    }

    @Test
    void chaining_worksCorrectly() {
        ClaudeConfig config = ClaudeConfig.defaults()
            .withModel("opus")
            .withSystemPrompt("Test prompt")
            .withMaxTurns(3)
            .withSessionId("sess-1");

        assertEquals("opus", config.model());
        assertEquals("Test prompt", config.systemPrompt());
        assertEquals(3, config.maxTurns());
        assertEquals("sess-1", config.sessionId());
    }

    @Test
    void record_equality() {
        ClaudeConfig a = ClaudeConfig.defaults();
        ClaudeConfig b = ClaudeConfig.defaults();

        assertEquals(a, b);
    }
}
