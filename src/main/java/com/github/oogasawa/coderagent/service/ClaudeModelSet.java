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

import java.util.ArrayList;
import java.util.List;

/**
 * Provides the fixed list of Claude model names (sonnet, opus, haiku).
 */
public class ClaudeModelSet {

    /** A model entry with name, type, and optional server host. */
    public record ModelEntry(String name, String type, String server) {}

    private static final List<String> DEFAULT_MODELS = List.of("sonnet", "opus", "haiku");

    private final List<String> models;

    public ClaudeModelSet() {
        this(DEFAULT_MODELS);
    }

    public ClaudeModelSet(List<String> models) {
        this.models = List.copyOf(models);
    }

    public List<ModelEntry> getAvailableModels() {
        List<ModelEntry> entries = new ArrayList<>();
        for (String name : models) {
            entries.add(new ModelEntry(name, "claude", null));
        }
        return entries;
    }
}
