package com.tripplanner.plan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.plan.config.LlmConfig;
import com.tripplanner.common.model.ParsedInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 输入解析服务
 * 使用 LLM 将自由文本解析为结构化的 JSON 数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputParserService {

    private final LlmConfig llmConfig;
    private final WebClient llmWebClient;
    private final ObjectMapper objectMapper;
    private final JsonUtils jsonUtils;

    private static final String SYSTEM_PROMPT = """
        你是一个专业的行程规划助手。请将用户的自由文本描述解析为结构化的 JSON 数据。
        
        【输出格式要求】必须严格遵循以下 JSON Schema：
        {
          "places": [
            {"name": "地点名称", "priority": "must|recommended|optional|excluded", "type": "scenic|hotel|shopping|transport|park|temple|museum", "preferredDurationMin": 120, "minDurationMin": 60, "maxDurationMin": 240, "notes": "备注"}
          ],
          "meals": [
            {"type": "breakfast|lunch|dinner", "durationMin": 90}
          ],
          "timeRange": {"start": "2026-09-21T08:00:00", "end": "2026-09-22T20:00:00", "timezone": "Asia/Shanghai"},
          "transportMode": "walk|transit|drive|bike|mixed",
          "preferences": {
            "visitDurationDefaults": {"scenic": 120, "museum": 180, "park": 90, "temple": 60},
            "mealDurations": {"breakfast": 60, "lunch": 90, "dinner": 120},
            "activeWindow": {"start": "08:00", "end": "23:00"},
            "blockedPeriods": [{"start": "13:00", "end": "14:00"}],
            "intensity": "relaxed|standard|packed",
            "dietaryTags": ["vegetarian"],
            "accessibility": false,
            "preferredTransportModes": ["transit", "walk"]
          }
        }
        
        【解析规则】：
        1. priority 识别："必须去/必去/一定要去" -> must；"想去/推荐/建议" -> recommended；"如果有时间/可选/顺便" -> optional；"不去/不要/避开" -> excluded
        2. type 识别：景点/公园/博物馆/寺庙 -> scenic/park/museum/temple；酒店/住宿 -> hotel；购物/商场/街区 -> shopping；车站/机场 -> transport
        3. 时间偏好："早上/上午" -> breakfast 时间段；"中午/午餐" -> lunch；"晚上/晚餐/夜宵" -> dinner
        4. 如果用户未指定时间范围，使用默认值
        5. 如果用户未指定交通方式，默认 mixed
        6. 只输出 JSON，不要任何额外解释
        7. **餐厅禁止作为独立 place：** 餐厅写入 meals 的 restaurant 字段，并按用餐时段写入 type（breakfast/lunch/dinner）
        8. **禁止占位文案：** 不要输出「无则省略」「具体餐厅名」「偏好描述」等占位说明；用户没提到口味/餐厅就省略对应字段
        """;

    private static final String FEW_SHOT_EXAMPLES = """
        示例 1：
        输入："计划周末两天去杭州，想去西湖、灵隐寺、河坊街，早餐吃包子，午餐尝尝杭帮菜楼外楼，晚上逛逛河坊街"
        输出：
        {
          "places": [
            {"name": "西湖", "priority": "must", "type": "scenic", "preferredDurationMin": 180},
            {"name": "灵隐寺", "priority": "recommended", "type": "temple", "preferredDurationMin": 120},
            {"name": "河坊街", "priority": "optional", "type": "shopping", "preferredDurationMin": 90}
          ],
          "meals": [
            {"type": "breakfast", "preference": "包子", "durationMin": 60},
            {"type": "lunch", "preference": "杭帮菜", "restaurant": "楼外楼", "durationMin": 90},
            {"type": "dinner", "preference": "河坊街小吃", "durationMin": 90}
          ],
          "timeRange": {"start": "2026-09-21T08:00:00", "end": "2026-09-22T20:00:00", "timezone": "Asia/Shanghai"},
          "transportMode": "mixed",
          "preferences": {"intensity": "standard"}
        }
        
        示例 2：
        输入："明天自驾去苏州，必去拙政园、虎丘，中午吃松鹤楼，下午如果有时间去平江路，不想去博物馆"
        输出：
        {
          "places": [
            {"name": "拙政园", "priority": "must", "type": "scenic", "preferredDurationMin": 120},
            {"name": "虎丘", "priority": "must", "type": "scenic", "preferredDurationMin": 90},
            {"name": "平江路", "priority": "optional", "type": "shopping", "preferredDurationMin": 120},
            {"name": "苏州博物馆", "priority": "excluded", "type": "museum"}
          ],
          "meals": [
            {"type": "lunch", "preference": "苏帮菜", "restaurant": "松鹤楼", "durationMin": 90}
          ],
          "timeRange": {"start": "2026-09-22T08:00:00", "end": "2026-09-22T20:00:00", "timezone": "Asia/Shanghai"},
          "transportMode": "drive",
          "preferences": {"intensity": "standard"}
        }
        """;

    /**
     * 解析预览 (同步调用，不触发规划)
     */
    public ParsedInput parsePreview(String rawInput, LocalDateTime timeStart, LocalDateTime timeEnd,
                                     String transportMode, Map<String, Object> preferences) {
        String prompt = buildPrompt(rawInput, timeStart, timeEnd, transportMode, preferences);
        String response = callLlm(prompt);
        return parseResponse(response, timeStart, timeEnd, transportMode, preferences);
    }

    private String buildPrompt(String rawInput, LocalDateTime timeStart, LocalDateTime timeEnd,
                               String transportMode, Map<String, Object> preferences) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append("\n\n");
        sb.append(FEW_SHOT_EXAMPLES).append("\n\n");
        sb.append("当前任务：\n");
        sb.append("用户输入：").append(rawInput).append("\n");
        sb.append("时间范围：").append(timeStart).append(" 至 ").append(timeEnd).append("\n");
        sb.append("交通方式：").append(transportMode).append("\n");
        if (preferences != null && !preferences.isEmpty()) {
            sb.append("偏好配置：").append(jsonUtils.toJson(preferences)).append("\n");
        }
        sb.append("\n请输出 JSON：");
        return sb.toString();
    }

    private String callLlm(String prompt) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Map<String, Object> requestBody = Map.of(
                        "model", llmConfig.getModel(),
                        "messages", List.of(
                                Map.of("role", "system", "content", SYSTEM_PROMPT + "\n\n" + FEW_SHOT_EXAMPLES),
                                Map.of("role", "user", "content", prompt)
                        ),
                        "temperature", llmConfig.getTemperature(),
                        "max_tokens", llmConfig.getMaxTokens(),
                        "response_format", Map.of("type", "json_object")
                );

                String response = llmWebClient.post()
                        .uri("/chat/completions")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                // 解析 OpenAI 格式响应
                JsonNode root = objectMapper.readTree(response);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    return message.path("content").asText();
                }
                throw new BizException("LLM_RESPONSE_ERROR", "LLM 响应格式错误: " + response);
            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");
                if (isRateLimit && attempt < maxRetries) {
                    long waitMs = attempt * 5000L;
                    log.warn("LLM 速率限制，第{}次重试，等待{}ms...", attempt, waitMs);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.error("LLM 调用失败 (attempt {}/{}): {}", attempt, maxRetries, e.getMessage(), e);
                throw new BizException("INPUT_PARSE_FAILED", "输入解析失败: " + e.getMessage());
            }
        }
        throw new BizException("INPUT_PARSE_FAILED", "输入解析失败: 超过最大重试次数");
    }

    private ParsedInput parseResponse(String json, LocalDateTime timeStart, LocalDateTime timeEnd,
                                       String transportMode, Map<String, Object> preferences) {
        try {
            JsonNode root = objectMapper.readTree(json);
            
            ParsedInput.ParsedInputBuilder builder = ParsedInput.builder()
                    .rawInput(json)
                    .timeRange(ParsedInput.TimeRange.builder()
                            .start(timeStart)
                            .end(timeEnd)
                            .timezone("Asia/Shanghai")
                            .build())
                    .transportMode(transportMode);

            // 解析 places
            if (root.has("places")) {
                List<ParsedInput.PlaceInfo> places = objectMapper.convertValue(
                        root.get("places"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedInput.PlaceInfo.class)
                );
                builder.places(places);
            }

            // 解析 meals
            if (root.has("meals")) {
                List<ParsedInput.MealInfo> meals = objectMapper.convertValue(
                        root.get("meals"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedInput.MealInfo.class)
                );
                builder.meals(meals);
            }

            // 解析 preferences
            if (root.has("preferences")) {
                JsonNode prefNode = root.get("preferences");
                ParsedInput.UserPreferences.UserPreferencesBuilder prefBuilder = ParsedInput.UserPreferences.builder();
                
                if (prefNode.has("visitDurationDefaults")) {
                    prefBuilder.visitDurationDefaults(objectMapper.convertValue(
                            prefNode.get("visitDurationDefaults"), Map.class));
                }
                if (prefNode.has("mealDurations")) {
                    prefBuilder.mealDurations(objectMapper.convertValue(
                            prefNode.get("mealDurations"), Map.class));
                }
                if (prefNode.has("activeWindow")) {
                    JsonNode aw = prefNode.get("activeWindow");
                    prefBuilder.activeWindow(ParsedInput.ActiveWindow.builder()
                            .start(aw.path("start").asText())
                            .end(aw.path("end").asText())
                            .build());
                }
                if (prefNode.has("blockedPeriods")) {
                    prefBuilder.blockedPeriods(objectMapper.convertValue(
                            prefNode.get("blockedPeriods"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, ParsedInput.BlockedPeriod.class)
                    ));
                }
                if (prefNode.has("intensity")) {
                    prefBuilder.intensity(prefNode.path("intensity").asText());
                }
                if (prefNode.has("dietaryTags")) {
                    prefBuilder.dietaryTags(objectMapper.convertValue(
                            prefNode.get("dietaryTags"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ));
                }
                if (prefNode.has("accessibility")) {
                    prefBuilder.accessibility(prefNode.path("accessibility").asBoolean());
                }
                if (prefNode.has("preferredTransportModes")) {
                    prefBuilder.preferredTransportModes(objectMapper.convertValue(
                            prefNode.get("preferredTransportModes"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ));
                }
                builder.preferences(prefBuilder.build());
            } else if (preferences != null) {
                // 使用传入的偏好
                builder.preferences(objectMapper.convertValue(preferences, ParsedInput.UserPreferences.class));
            }

            return builder.build();
        } catch (JsonProcessingException e) {
            log.error("解析 LLM 响应失败: {}", e.getMessage(), e);
            throw new BizException("PARSE_RESPONSE_FAILED", "解析结果格式错误: " + e.getMessage(), 500);
        }
    }
}