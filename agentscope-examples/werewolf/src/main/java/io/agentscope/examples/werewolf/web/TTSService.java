/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.werewolf.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for Text-to-Speech generation using OpenAI-compatible API.
 */
public class TTSService {
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TTSService(String apiKey, String baseUrl, String modelName) {
        this.apiKey = apiKey;
        // Normalize baseUrl: remove trailing slash
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generates speech audio for the given text and voice.
     *
     * @param text The text to speak.
     * @param voice The voice ID (alloy, echo, fable, onyx, nova, shimmer).
     * @return A CompletableFuture containing the Base64 encoded MP3 audio data, or null if failed.
     */
    public CompletableFuture<String> generateAudio(String text, String voice) {
        if (text == null || text.trim().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("input", text);
            requestBody.put("voice", voice);
            requestBody.put("response_format", "mp3");

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/audio/speech"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            logger.info("TTS generation successful for text length: {}", text.length());
                            return Base64.getEncoder().encodeToString(response.body());
                        } else {
                            String errorBody = new String(response.body());
                            String errorMsg = "TTS API failed with status: " + response.statusCode() + " - Body: " + errorBody;
                            logger.error(errorMsg);
                            throw new RuntimeException(errorMsg);
                        }
                    });
        } catch (Exception e) {
            logger.error("Error preparing TTS request", e);
            return CompletableFuture.completedFuture(null);
        }
    }
}
