package com.tripplanner.plan.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.plan.agent.ReActAgent;
import com.tripplanner.plan.agent.TripPlanningAgent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 旅行规划Agent控制器
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "旅行规划Agent", description = "基于LangChain的智能旅行规划")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final TripPlanningAgent tripPlanningAgent;
    private final ReActAgent reActAgent;

    @Value("${zhipu.api-key:${llm.api-key:}}")
    private String apiKey;

    @Value("${zhipu.model:${llm.model:glm-4-flash}}")
    private String model;

    @Value("${zhipu.base-url:${llm.base-url:https://open.bigmodel.cn/api/paas/v4}}")
    private String baseUrl;

    @PostMapping("/plan")
    @Operation(summary = "智能旅行规划", description = "根据用户需求自动生成旅行行程")
    public ResponseEntity<ApiResponse<Map<String, Object>>> planTrip(@RequestBody Map<String, String> request) {
        String userRequest = request.get("request");
        if (userRequest == null || userRequest.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "旅行需求描述不能为空"));
        }

        log.info("收到旅行规划请求: {}", userRequest);
        Map<String, Object> result = tripPlanningAgent.planTrip(userRequest);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/parse")
    @Operation(summary = "解析旅行输入", description = "将原始文本解析为结构化的地点、餐饮和时间信息")
    public ResponseEntity<ApiResponse<Map<String, Object>>> parseInput(@RequestBody Map<String, String> request) {
        String rawInput = request.get("rawInput");
        String timeStart = request.get("timeStart");
        String timeEnd = request.get("timeEnd");
        String transportMode = request.get("transportMode");
        String pace = request.get("pace");
        String city = request.get("city");

        if (rawInput == null || rawInput.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "旅行需求描述不能为空"));
        }

        log.info("收到解析请求: rawInput={}, pace={}, city={}", rawInput, pace, city);
        Map<String, Object> result = tripPlanningAgent.parseInput(rawInput, timeStart, timeEnd, transportMode, pace, city);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/react-plan")
    @Operation(summary = "ReAct Agent 规划", description = "使用ReAct循环（推理-行动-观察）进行旅行规划，支持工具调用")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reactPlan(@RequestBody Map<String, String> request) {
        String rawInput = request.get("rawInput");
        String timeStart = request.getOrDefault("timeStart", "");
        String timeEnd = request.getOrDefault("timeEnd", "");
        String transportMode = request.getOrDefault("transportMode", "mixed");
        String city = request.get("city");

        if (rawInput == null || rawInput.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", "旅行需求描述不能为空"));
        }

        log.info("收到ReAct规划请求: rawInput={}", rawInput);

        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(baseUrl)
                .temperature(0.7)
                .timeout(java.time.Duration.ofSeconds(180))
                .maxTokens(4096)
                .logRequests(true)
                .logResponses(true)
                .build();

        Map<String, Object> result = reActAgent.executeReActLoop(
                chatModel, rawInput, timeStart, timeEnd, transportMode, city);
        result.put("success", true);
        result.put("method", "react-loop");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/plan-itinerary")
    @Operation(summary = "LLM生成完整行程", description = "根据景点列表、距离矩阵，由LLM直接生成精确到分钟的完整行程规划")
    public ResponseEntity<ApiResponse<Map<String, Object>>> planItinerary(@RequestBody Map<String, Object> request) {
        String rawInput = (String) request.getOrDefault("rawInput", "");
        String timeStart = (String) request.getOrDefault("timeStart", "");
        String timeEnd = (String) request.getOrDefault("timeEnd", "");
        String distanceMatrix = (String) request.getOrDefault("distanceMatrix", "无");

        List<Map<String, Object>> places = (List<Map<String, Object>>) request.getOrDefault("places", List.of());
        List<Map<String, Object>> meals = (List<Map<String, Object>>) request.getOrDefault("meals", List.of());
        String requestCity = (String) request.getOrDefault("city", "");
        String pace = (String) request.getOrDefault("pace", "moderate");
        // 换版规划：同主题不同内容，排除基准版本已用 POI，并携带基准行程摘要供 LLM 避开相同组合
        boolean variant = "true".equalsIgnoreCase(String.valueOf(request.getOrDefault("variant", "false")));
        java.util.List<String> excludePois = new java.util.ArrayList<>();
        if (request.get("excludePois") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    excludePois.add(String.valueOf(item).trim());
                }
            }
        }
        String basePlanSummary = request.get("basePlanSummary") instanceof String s ? s : null;
        com.tripplanner.plan.agent.VariantSpec variantSpec =
                new com.tripplanner.plan.agent.VariantSpec(variant, excludePois, basePlanSummary);

        log.info("收到行程规划请求: places={}, meals={}, city={}, pace={}, variant={}, exclude={}, summary={}",
                places.size(), meals.size(), requestCity, pace, variant, excludePois.size(),
                basePlanSummary == null ? "无" : basePlanSummary.length() + "字符");
        Map<String, Object> result = tripPlanningAgent.planDetailedItinerary(
                rawInput, places, meals, timeStart, timeEnd, distanceMatrix, requestCity, pace,
                variantSpec);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/health")
    @Operation(summary = "Agent健康检查")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "status", "UP",
            "service", "TripPlanningAgent",
            "model", model
        )));
    }
}
