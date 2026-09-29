package com.tripplanner.plan.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * 智谱 ChatLanguageModel 轻量直连实现。
 *
 * <p>替代 langchain4j OpenAiChatModel 的原因（性能优化 1.24.0）：
 * <ul>
 *   <li>glm-4.5/4.6/5 系列为推理模型，默认开思考会耗尽 max_tokens 并输出空内容
 *       （实测 4096 token 全部被思考消耗、135s 返回空），必须注入
 *       {@code thinking={"type":"disabled"}}；langchain4j 0.35 的 OpenAiChatModel
 *       不支持追加自定义请求体字段，故自建实现</li>
 *   <li>关闭思考 + 瘦身后行程生成由 96s 降至约 28s（实测）</li>
 *   <li>仅记录调用摘要（模型/耗时/token），不再落全量请求响应报文</li>
 * </ul>
 *
 * <p>仅支持本项目用到的纯文本单轮调用（无工具/流式/多模态）；工具调用走
 * {@code AgentController#reactPlan} 的 OpenAiChatModel 路径。
 */
@Slf4j
public class ZhipuChatModel implements ChatLanguageModel {

    /** 瞬时故障（网络/5xx/限流）最多尝试 2 次；参数/模型类错误不重试 */
    private static final int MAX_ATTEMPTS = 2;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final boolean thinkingDisabled;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ZhipuChatModel(String baseUrl, String apiKey, String model,
                          double temperature, int maxTokens, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        this.thinkingDisabled = m.startsWith("glm-4.5") || m.startsWith("glm-4.6") || m.startsWith("glm-5");
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        if (thinkingDisabled) {
            body.putObject("thinking").put("type", "disabled");
        }
        ArrayNode msgs = body.putArray("messages");
        int promptChars = 0;
        for (ChatMessage m : messages) {
            ObjectNode jm = msgs.addObject();
            jm.put("role", switch (m.type()) {
                case SYSTEM -> "system";
                case AI -> "assistant";
                case TOOL_EXECUTION_RESULT -> "tool";
                default -> "user";
            });
            String text = switch (m.type()) {
                case SYSTEM -> ((SystemMessage) m).text();
                case AI -> ((AiMessage) m).text();
                default -> ((UserMessage) m).text();
            };
            jm.put("content", text == null ? "" : text);
            promptChars += text == null ? 0 : text.length();
        }

        String payload = body.toString();
        long start = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doPost(payload, promptChars, start);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                last = new RuntimeException("LLM调用网络异常: " + e.getMessage(), e);
            } catch (TransientLlmException e) {
                last = new RuntimeException("LLM调用瞬时失败: " + e.getMessage(), e);
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("LLM调用第{}次失败，500ms后重试: {}", attempt, last.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        Response<AiMessage> response = generate(chatRequest.messages());
        return ChatResponse.builder()
                .aiMessage(response.content())
                .tokenUsage(response.tokenUsage())
                .build();
    }

    private Response<AiMessage> doPost(String payload, int promptChars, long start)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        String respBody = resp.body() == null ? "" : resp.body();
        if (status == 429 || status >= 500) {
            throw new TransientLlmException("HTTP " + status + ": " + abbreviate(respBody));
        }
        if (status != 200) {
            throw new IllegalStateException("LLM调用失败(HTTP " + status + "): " + abbreviate(respBody));
        }

        JsonNode root;
        try {
            root = mapper.readTree(respBody);
        } catch (Exception e) {
            throw new TransientLlmException("响应非JSON: " + abbreviate(respBody));
        }
        if (root.has("error") && !root.get("error").isNull()) {
            JsonNode err = root.get("error");
            String code = err.hasNonNull("code") ? err.get("code").asText() : "";
            String msg = err.hasNonNull("message") ? err.get("message").asText() : err.toString();
            // 1113/1211 等业务错误码为参数/模型问题，重试无意义
            throw new IllegalStateException("LLM返回错误(" + code + "): " + abbreviate(msg));
        }
        JsonNode msg = root.path("choices").path(0).path("message");
        String content = msg.hasNonNull("content") ? msg.get("content").asText() : null;
        if (content == null || content.isBlank()) {
            throw new TransientLlmException("LLM返回空内容: " + abbreviate(respBody));
        }
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        Integer totalTokens = usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asInt() : null;
        log.info("LLM调用完成: model={}, thinkingDisabled={}, promptChars={}, 耗时{}ms, tokens={}/{}",
                model, thinkingDisabled, promptChars, System.currentTimeMillis() - start,
                promptTokens, completionTokens);
        if (promptTokens == null && completionTokens == null) {
            return Response.from(AiMessage.from(content));
        }
        if (totalTokens == null) {
            totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        }
        return Response.from(AiMessage.from(content), new TokenUsage(promptTokens, completionTokens, totalTokens));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    /** 瞬时故障标记：网络异常/5xx/429/空内容，允许重试 */
    private static final class TransientLlmException extends RuntimeException {
        TransientLlmException(String message) {
            super(message);
        }
    }
}
