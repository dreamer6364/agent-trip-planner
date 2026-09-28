package com.tripplanner.plan.dto.response;

import java.util.List;

/**
 * 地理编码响应
 */
public class GeocodeResponse {

    private String query;
    private boolean success;
    private String formattedAddress;
    private Double lat;
    private Double lng;
    private String location; // WKT POINT
    private String province;
    private String city;
    private String district;
    private String poiType;
    private Double confidence;
    private String source; // amap, baidu
    private String errorMessage;

    private List<GeocodeResponse> results;

    public GeocodeResponse() {}

    // Getters and Setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFormattedAddress() { return formattedAddress; }
    public void setFormattedAddress(String formattedAddress) { this.formattedAddress = formattedAddress; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

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

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public List<GeocodeResponse> getResults() { return results; }
    public void setResults(List<GeocodeResponse> results) { this.results = results; }

    // === Static factory methods ===

    /** 13 params - full fields */
    public static GeocodeResponse of(String query, boolean success, String formattedAddress,
            Double lat, Double lng, String location, String province, String city,
            String district, String poiType, Double confidence, String source, String errorMessage) {
        GeocodeResponse resp = new GeocodeResponse();
        resp.query = query;
        resp.success = success;
        resp.formattedAddress = formattedAddress;
        resp.lat = lat;
        resp.lng = lng;
        resp.location = location;
        resp.province = province;
        resp.city = city;
        resp.district = district;
        resp.poiType = poiType;
        resp.confidence = confidence;
        resp.source = source;
        resp.errorMessage = errorMessage;
        return resp;
    }

    /** 12 params - with confidence, used for DB cache restore */
    public static GeocodeResponse of(String query, boolean success, String formattedAddress,
            double lat, double lng, String location, String province, String city,
            String district, String poiType, Double confidence, String source) {
        return of(query, success, formattedAddress, lat, lng, location,
                province, city, district, poiType, confidence, source, null);
    }

    /** 11 params - amap geocode (Double lat/lng, with location WKT) */
    public static GeocodeResponse of(String query, boolean success, String formattedAddress,
            Double lat, Double lng, String location, String province, String city,
            String district, String poiType, String source) {
        return of(query, success, formattedAddress, lat, lng, location,
                province, city, district, poiType, null, source, null);
    }

    /** 10 params - baidu/regeocode (double lat/lng, no location WKT) */
    public static GeocodeResponse of(String query, boolean success, String formattedAddress,
            double lat, double lng, String province, String city, String district,
            String poiType, String source) {
        return of(query, success, formattedAddress, lat, lng, "",
                province, city, district, poiType, null, source, null);
    }

    public static GeocodeResponse failure(String query, String errorMessage) {
        GeocodeResponse resp = new GeocodeResponse();
        resp.query = query;
        resp.success = false;
        resp.errorMessage = errorMessage;
        return resp;
    }

    public static GeocodeResponse failure(String errorMessage) {
        GeocodeResponse resp = new GeocodeResponse();
        resp.success = false;
        resp.errorMessage = errorMessage;
        return resp;
    }
}
