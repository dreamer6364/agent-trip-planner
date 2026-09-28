package com.tripplanner.plan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网页搜索服务
 * 使用 FreeSerp 免费搜索API（无需Key）
 */
@Slf4j
@Service
public class WebSearchService {

    private final WebClient webClient;

    public WebSearchService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * 搜索城市热门景点
     */
    public List<Map<String, Object>> searchAttractions(String city, int count) {
        String query = city + " 必去景点 热门旅游景点推荐 2026";
        return searchAndParse(query, count, "scenic");
    }

    /**
     * 搜索城市特色餐厅
     */
    public List<Map<String, Object>> searchRestaurants(String city, int count) {
        String query = city + " 必吃美食 特色餐厅推荐 当地人推荐";
        return searchAndParse(query, count, "restaurant");
    }

    /**
     * 搜索城市美食街/小吃
     */
    public List<Map<String, Object>> searchFoodStreets(String city, int count) {
        String query = city + " 美食街 小吃街 夜市 当地美食";
        return searchAndParse(query, count, "shopping");
    }

    /**
     * 执行搜索并解析结果
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchAndParse(String query, int count, String defaultType) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String url = "https://freeserp.ai/api.php?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&num=" + count;
            
            Map<String, Object> response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();

            if (response != null && response.containsKey("results")) {
                List<Map<String, Object>> searchResults = (List<Map<String, Object>>) response.get("results");
                if (searchResults != null) {
                    for (Map<String, Object> item : searchResults) {
                        String title = (String) item.getOrDefault("title", "");
                        String snippet = (String) item.getOrDefault("snippet", "");
                        
                        // 从搜索结果中提取景点/餐厅名
                        String name = extractName(title, snippet);
                        if (name != null && !name.isEmpty()) {
                            Map<String, Object> place = new java.util.HashMap<>();
                            place.put("name", name);
                            place.put("type", defaultType);
                            place.put("priority", "recommended");
                            place.put("preferredDurationMin", defaultType.equals("restaurant") ? 90 : 120);
                            place.put("notes", snippet.length() > 100 ? snippet.substring(0, 100) : snippet);
                            results.add(place);
                        }
                    }
                }
            }
            log.info("搜索完成: query={}, 结果数={}", query, results.size());
        } catch (Exception e) {
            log.warn("搜索失败: query={}, error={}", query, e.getMessage());
        }
        return results;
    }

    /**
     * 从搜索结果中提取景点/餐厅名
     */
    private String extractName(String title, String snippet) {
        // 优先使用标题中的关键信息
        if (title != null && !title.isEmpty()) {
            // 去除常见前缀后缀
            String name = title
                    .replaceAll("\\s*[-|—]\\s*.*$", "")
                    .replaceAll("【.*?】", "")
                    .replaceAll("\\(.*?\\)", "")
                    .replaceAll("（.*?）", "")
                    .trim();
            if (name.length() >= 2 && name.length() <= 20) {
                return name;
            }
        }
        return null;
    }

    /**
     * 综合搜索：景点 + 餐厅 + 美食街
     */
    public Map<String, List<Map<String, Object>>> searchCity(String city) {
        List<Map<String, Object>> attractions = searchAttractions(city, 8);
        List<Map<String, Object>> restaurants = searchRestaurants(city, 5);
        List<Map<String, Object>> foodStreets = searchFoodStreets(city, 3);
        
        return Map.of(
                "attractions", attractions,
                "restaurants", restaurants,
                "foodStreets", foodStreets
        );
    }
}
