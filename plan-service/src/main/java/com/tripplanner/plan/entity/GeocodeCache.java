package com.tripplanner.plan.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 地理编码缓存实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("geocode_cache")
public class GeocodeCache {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("query_hash")
    private String queryHash; // SHA256(query + source)

    @TableField("query_text")
    private String queryText;

    @TableField("source")
    private String source; // amap, baidu

    @TableField("formatted_address")
    private String formattedAddress;

    @TableField("location")
    private String location; // WKT POINT

    @TableField("province")
    private String province;

    @TableField("city")
    private String city;

    @TableField("district")
    private String district;

    @TableField("poi_type")
    private String poiType;

    @TableField("confidence")
    private Double confidence;

    @TableField("raw_response")
    private String rawResponse; // JSON

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    @TableField("version")
    private Long version;

    // Explicit getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getQueryHash() { return queryHash; }
    public void setQueryHash(String queryHash) { this.queryHash = queryHash; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFormattedAddress() { return formattedAddress; }
    public void setFormattedAddress(String formattedAddress) { this.formattedAddress = formattedAddress; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getPoiType() { return poiType; }
    public void setPoiType(String poiType) { this.poiType = poiType; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}