package com.tripplanner.plan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 地理编码请求
 */
public class GeocodeRequest {

    private String query;

    private String city; // 可选：限定城市

    private String source = "amap"; // amap, baidu

    // 批量查询
    private List<String> queries;

    // Getters and Setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public List<String> getQueries() { return queries; }
    public void setQueries(List<String> queries) { this.queries = queries; }
}