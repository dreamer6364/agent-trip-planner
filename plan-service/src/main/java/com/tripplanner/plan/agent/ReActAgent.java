package com.tripplanner.plan.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.plan.service.GeocodeService;
import com.tripplanner.plan.dto.response.GeocodeResponse;
import com.tripplanner.plan.dto.response.RouteResponse;
import com.tripplanner.plan.service.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ReAct Agent with tools
 * Implements: Thought -> Action -> Observation -> ... -> Final Answer
 */
@Slf4j
@Component
public class ReActAgent {

    private final GeocodeService geocodeService;
    private final RouteService routeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_ITERATIONS = 5;
    private static final String SYSTEM_PROMPT = """
            You are a travel planning assistant. Analyze the user's trip description and produce a structured itinerary.
            
            You have access to these tools:
            - geocode(query, city): Get coordinates for a place. Returns {lat, lng, formattedAddress}.
            - distance(origin_query, dest_query, city): Get distance between two places.
            
            RULES:
            1. First, identify all places and meals from the user's description.
            2. Call geocode for each place to get coordinates.
            3. After gathering info, IMMEDIATELY output your final answer.
            4. Do NOT call tools for meal names (e.g. 海底捞, 全聚德) - they are restaurants, not waypoints.
            
            Output format for tool calls:
            {"thought": "...", "action": {"tool": "geocode", "params": {"query": "...", "city": "..."}}}
            
            Output format for final answer (MUST include both places and meals):
            {"thought": "...", "finalAnswer": {"places": [{"name": "...", "type": "scenic|shopping|museum|park|temple|restaurant|other", "priority": "must|recommended|optional", "preferredDurationMin": 120, "lat": 0.0, "lng": 0.0}], "meals": [{"type": "breakfast|lunch|dinner", "durationMin": 90}]}}
            
            CRITICAL: On the LAST iteration, you MUST output a finalAnswer, even if you haven't gathered all info. Use best estimates for missing data.
            Output ONLY valid JSON. No markdown, no extra text.
            """;

    public ReActAgent(GeocodeService geocodeService, RouteService routeService) {
        this.geocodeService = geocodeService;
        this.routeService = routeService;
    }

    /**
     * Execute the ReAct loop with tool calls
     */
    public Map<String, Object> executeReActLoop(
            dev.langchain4j.model.chat.ChatLanguageModel chatModel,
            String userRequest,
            String timeStart,
            String timeEnd,
            String transportMode,
            String city) {

        log.info("Starting ReAct loop for: {}", userRequest);

        StringBuilder conversationHistory = new StringBuilder();
        conversationHistory.append(SYSTEM_PROMPT).append("\n\n");
        conversationHistory.append("User: Plan a trip: ").append(userRequest)
                .append("\nTime: ").append(timeStart).append(" to ").append(timeEnd)
                .append("\nTransport: ").append(transportMode)
                .append("\nCity: ").append(city != null ? city : "auto-detect").append("\n");

        Map<String, Object> finalResult = new HashMap<>();
        Map<String, Object> toolResults = new HashMap<>();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("ReAct iteration {}/{}", i + 1, MAX_ITERATIONS);

            // On last iteration, force final answer
            String prompt = conversationHistory.toString();
            if (i == MAX_ITERATIONS - 1) {
                prompt += "\n\n[SYSTEM] This is the LAST iteration. You MUST output your finalAnswer now. "
                        + "Output a JSON with 'finalAnswer' containing all places and meals you've identified. "
                        + "Use best estimates for any missing coordinates.\n";
            }

            // Call LLM with full conversation
            String llmResponse = null;
            for (int retry = 0; retry < 3; retry++) {
                try {
                    llmResponse = chatModel.generate(prompt);
                    break;
                } catch (Exception le) {
                    boolean isRateLimit = le.getMessage() != null && le.getMessage().contains("429");
                    if (isRateLimit && retry < 2) {
                        long waitMs = (retry + 1) * 5000L;
                        log.warn("LLM 速率限制，重试 {}/3，等待 {}ms", retry + 1, waitMs);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    } else {
                        throw le;
                    }
                }
            }
            log.debug("LLM response: {}", llmResponse);

            // Parse the response
            try {
                String cleaned = llmResponse.trim()
                        .replaceAll("```json\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
                
                // Handle null or empty response
                if (cleaned == null || cleaned.isBlank()) {
                    log.warn("Empty LLM response at iteration {}", i + 1);
                    conversationHistory.append("Assistant: (empty)\n");
                    conversationHistory.append("[SYSTEM] Your response was empty. Please output valid JSON.\n");
                    continue;
                }
                
                JsonNode root = objectMapper.readTree(cleaned);

                if (root.has("finalAnswer")) {
                    JsonNode answer = root.get("finalAnswer");
                    List<Map<String, Object>> places = extractPlaces(answer);
                    List<Map<String, Object>> meals = extractMeals(answer);
                    
                    // Validate: if no places or meals, build from tool results
                    if (places.isEmpty() && !toolResults.isEmpty()) {
                        places = buildPlacesFromToolResults(toolResults);
                    }
                    if (meals.isEmpty()) {
                        meals = inferMealsFromRequest(userRequest);
                    }
                    
                    finalResult.put("places", places);
                    finalResult.put("meals", meals);
                    finalResult.put("toolResults", toolResults);
                    log.info("ReAct completed with final answer after {} iterations: places={}, meals={}", 
                            i + 1, places.size(), meals.size());
                    return finalResult;
                }

                if (root.has("action")) {
                    JsonNode action = root.get("action");
                    String toolName = action.get("tool").asText();
                    JsonNode params = action.get("params");

                    // Execute tool
                    String observation = executeTool(toolName, params, city);
                    toolResults.put(toolName + "_" + i, observation);

                    // Append to conversation history
                    conversationHistory.append("Assistant: ").append(cleaned).append("\n");
                    conversationHistory.append("User: Tool observation: ").append(observation).append("\n");
                } else {
                    log.warn("No action or finalAnswer in response at iteration {}", i + 1);
                    conversationHistory.append("Assistant: ").append(cleaned).append("\n");
                    conversationHistory.append("[SYSTEM] Please either call a tool with 'action' or provide your final answer with 'finalAnswer'.\n");
                }
            } catch (Exception e) {
                log.error("Failed to parse LLM response at iteration {}: {}", i + 1, e.getMessage());
                conversationHistory.append("Assistant: (parse error)\n");
                conversationHistory.append("[SYSTEM] Invalid JSON. Please output valid JSON with 'action' or 'finalAnswer'.\n");
            }
        }

        // Fallback: build result from what we have
        log.warn("ReAct loop exhausted {} iterations, building fallback result", MAX_ITERATIONS);
        List<Map<String, Object>> places = buildPlacesFromToolResults(toolResults);
        List<Map<String, Object>> meals = inferMealsFromRequest(userRequest);
        finalResult.put("places", places);
        finalResult.put("meals", meals);
        finalResult.put("toolResults", toolResults);
        return finalResult;
    }

    private String executeTool(String toolName, JsonNode params, String defaultCity) {
        try {
            return switch (toolName) {
                case "geocode" -> executeGeocode(params, defaultCity);
                case "distance" -> executeDistance(params, defaultCity);
                case "search_restaurants" -> executeSearchRestaurants(params, defaultCity);
                default -> "Error: Unknown tool '" + toolName + "'";
            };
        } catch (Exception e) {
            return "Error executing tool " + toolName + ": " + e.getMessage();
        }
    }

    private String executeGeocode(JsonNode params, String defaultCity) {
        String query = params.has("query") ? params.get("query").asText() : "";
        String city = params.has("city") ? params.get("city").asText() : defaultCity;

        GeocodeResponse response = geocodeService.geocode(query, city, "amap");

        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("success", response.isSuccess());
        if (response.isSuccess()) {
            result.put("lat", response.getLat());
            result.put("lng", response.getLng());
            result.put("formattedAddress", response.getFormattedAddress());
            result.put("city", response.getCity());
        } else {
            result.put("error", response.getErrorMessage());
        }

        return serialize(result);
    }

    private String executeDistance(JsonNode params, String defaultCity) {
        String origin = params.has("origin_query") ? params.get("origin_query").asText() : "";
        String dest = params.has("dest_query") ? params.get("dest_query").asText() : "";

        GeocodeResponse originGeo = geocodeService.geocode(origin, defaultCity, "amap");
        GeocodeResponse destGeo = geocodeService.geocode(dest, defaultCity, "amap");

        Map<String, Object> result = new HashMap<>();
        result.put("origin", origin);
        result.put("destination", dest);

        if (originGeo.isSuccess() && destGeo.isSuccess()) {
            RouteResponse route =
                    routeService.route(originGeo.getLat(), originGeo.getLng(),
                            destGeo.getLat(), destGeo.getLng(), "drive");
                result.put("success", route.isSuccess());
                if (route.isSuccess()) {
                    result.put("distance", route.getDistance());
                    result.put("duration", route.getDuration());
                }
        } else {
            result.put("success", false);
            result.put("error", "Geocoding failed for one or both locations");
        }

        return serialize(result);
    }

    private String executeSearchRestaurants(JsonNode params, String defaultCity) {
        String nearQuery = params.has("near_query") ? params.get("near_query").asText() : "";
        String city = params.has("city") ? params.get("city").asText() : defaultCity;

        GeocodeResponse area = geocodeService.geocode(nearQuery, city, "amap");

        Map<String, Object> result = new HashMap<>();
        result.put("near", nearQuery);
        if (area.isSuccess()) {
            result.put("area_lat", area.getLat());
            result.put("area_lng", area.getLng());
            result.put("area_name", area.getFormattedAddress());
            result.put("restaurants", List.of(
                    Map.of("name", "附近餐厅", "type", "restaurant", "priority", "recommended", "preferredDurationMin", 90)
            ));
        } else {
            result.put("error", "Could not find location: " + nearQuery);
            result.put("restaurants", List.of());
        }

        return serialize(result);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    /**
     * Build places from accumulated tool results when LLM fails to produce finalAnswer
     */
    private List<Map<String, Object>> buildPlacesFromToolResults(Map<String, Object> toolResults) {
        List<Map<String, Object>> places = new ArrayList<>();
        for (Map.Entry<String, Object> entry : toolResults.entrySet()) {
            try {
                String json = entry.getValue().toString();
                if (json.contains("\"lat\"") && json.contains("\"lng\"")) {
                    JsonNode node = objectMapper.readTree(json);
                    if (node.has("success") && node.get("success").asBoolean() && node.has("lat")) {
                        Map<String, Object> place = new HashMap<>();
                        place.put("name", node.has("query") ? node.get("query").asText() : "unknown");
                        place.put("type", "scenic");
                        place.put("priority", "recommended");
                        place.put("preferredDurationMin", 120);
                        place.put("lat", node.get("lat").asDouble());
                        place.put("lng", node.get("lng").asDouble());
                        places.add(place);
                    }
                }
            } catch (Exception e) {
                // skip malformed results
            }
        }
        return places;
    }

    /**
     * Infer meals from the raw user request
     */
    private List<Map<String, Object>> inferMealsFromRequest(String userRequest) {
        List<Map<String, Object>> meals = new ArrayList<>();
        String lower = userRequest.toLowerCase();
        if (lower.contains("早餐") || lower.contains("breakfast")) {
            meals.add(Map.of("type", "breakfast", "durationMin", 60));
        }
        if (lower.contains("午餐") || lower.contains("中饭") || lower.contains("lunch")) {
            meals.add(Map.of("type", "lunch", "durationMin", 90));
        }
        if (lower.contains("晚餐") || lower.contains("晚饭") || lower.contains("dinner")
                || lower.contains("海底捞") || lower.contains("火锅") || lower.contains("吃一顿")
                || lower.contains("吃饭")) {
            meals.add(Map.of("type", "dinner", "durationMin", 90));
        }
        if (meals.isEmpty()) {
            meals.add(Map.of("type", "dinner", "durationMin", 90));
        }
        return meals;
    }

    private List<Map<String, Object>> extractPlaces(JsonNode answer) {
        List<Map<String, Object>> places = new ArrayList<>();
        if (answer.has("places")) {
            for (JsonNode p : answer.get("places")) {
                Map<String, Object> place = new HashMap<>();
                place.put("name", p.has("name") ? p.get("name").asText() : "");
                place.put("type", p.has("type") ? p.get("type").asText() : "scenic");
                place.put("priority", p.has("priority") ? p.get("priority").asText() : "recommended");
                place.put("preferredDurationMin", p.has("preferredDurationMin") ? p.get("preferredDurationMin").asInt() : 120);
                if (p.has("lat")) place.put("lat", p.get("lat").asDouble());
                if (p.has("lng")) place.put("lng", p.get("lng").asDouble());
                places.add(place);
            }
        }
        return places;
    }

    private List<Map<String, Object>> extractMeals(JsonNode answer) {
        List<Map<String, Object>> meals = new ArrayList<>();
        if (answer.has("meals")) {
            for (JsonNode m : answer.get("meals")) {
                Map<String, Object> meal = new HashMap<>();
                meal.put("type", m.has("type") ? m.get("type").asText() : "lunch");
                meal.put("durationMin", m.has("durationMin") ? m.get("durationMin").asInt() : 90);
                meals.add(meal);
            }
        }
        return meals;
    }
}
