package com.tripplanner.plan.dto.response;

import java.util.List;

/**
 * 路线规划响应
 */
public class RouteResponse {

    private String origin;
    private String destination;
    private String mode; // walk, transit, drive, bike
    private boolean success;
    private Double distance; // 米
    private Integer duration; // 秒
    private Double cost; // 预估费用(元)
    private String polyline; // 编码后的坐标串
    private List<RouteStep> steps;
    private String errorMessage;
    private String source; // amap, baidu, osrm

    public RouteResponse() {}

    // Getters and Setters
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }

    public Double getCost() { return cost; }
    public void setCost(Double cost) { this.cost = cost; }

    public String getPolyline() { return polyline; }
    public void setPolyline(String polyline) { this.polyline = polyline; }

    public List<RouteStep> getSteps() { return steps; }
    public void setSteps(List<RouteStep> steps) { this.steps = steps; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    // Static factory method
    public static RouteResponse of(String origin, String destination, String mode,
            boolean success, Double distance, Integer duration, Double cost,
            String polyline, List<RouteStep> steps, String errorMessage, String source) {
        RouteResponse resp = new RouteResponse();
        resp.origin = origin;
        resp.destination = destination;
        resp.mode = mode;
        resp.success = success;
        resp.distance = distance;
        resp.duration = duration;
        resp.cost = cost;
        resp.polyline = polyline;
        resp.steps = steps;
        resp.errorMessage = errorMessage;
        resp.source = source;
        return resp;
    }

    // Builder pattern (manual)
    public static RouteResponseBuilder builder() {
        return new RouteResponseBuilder();
    }

    public static class RouteResponseBuilder {
        private String origin;
        private String destination;
        private String mode;
        private boolean success;
        private Double distance;
        private Integer duration;
        private Double cost;
        private String polyline;
        private List<RouteStep> steps;
        private String errorMessage;
        private String source;

        public RouteResponseBuilder origin(String origin) { this.origin = origin; return this; }
        public RouteResponseBuilder destination(String destination) { this.destination = destination; return this; }
        public RouteResponseBuilder mode(String mode) { this.mode = mode; return this; }
        public RouteResponseBuilder success(boolean success) { this.success = success; return this; }
        public RouteResponseBuilder distance(Double distance) { this.distance = distance; return this; }
        public RouteResponseBuilder duration(Integer duration) { this.duration = duration; return this; }
        public RouteResponseBuilder cost(Double cost) { this.cost = cost; return this; }
        public RouteResponseBuilder polyline(String polyline) { this.polyline = polyline; return this; }
        public RouteResponseBuilder steps(List<RouteStep> steps) { this.steps = steps; return this; }
        public RouteResponseBuilder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public RouteResponseBuilder source(String source) { this.source = source; return this; }

        public RouteResponse build() {
            RouteResponse resp = new RouteResponse();
            resp.origin = this.origin;
            resp.destination = this.destination;
            resp.mode = this.mode;
            resp.success = this.success;
            resp.distance = this.distance;
            resp.duration = this.duration;
            resp.cost = this.cost;
            resp.polyline = this.polyline;
            resp.steps = this.steps;
            resp.errorMessage = this.errorMessage;
            resp.source = this.source;
            return resp;
        }
    }

    public static class RouteStep {
        private String instruction;
        private Double distance;
        private Integer duration;
        private String polyline;
        private String roadName;
        private String transportMode; // 步行/公交/地铁/驾车
        private String transitInfo; // 公交线路信息

        public RouteStep() {}

        public String getInstruction() { return instruction; }
        public void setInstruction(String instruction) { this.instruction = instruction; }

        public Double getDistance() { return distance; }
        public void setDistance(Double distance) { this.distance = distance; }

        public Integer getDuration() { return duration; }
        public void setDuration(Integer duration) { this.duration = duration; }

        public String getPolyline() { return polyline; }
        public void setPolyline(String polyline) { this.polyline = polyline; }

        public String getRoadName() { return roadName; }
        public void setRoadName(String roadName) { this.roadName = roadName; }

        public String getTransportMode() { return transportMode; }
        public void setTransportMode(String transportMode) { this.transportMode = transportMode; }

        public String getTransitInfo() { return transitInfo; }
        public void setTransitInfo(String transitInfo) { this.transitInfo = transitInfo; }

        public static RouteStep of(String instruction, Double distance, Integer duration,
                String polyline, String roadName, String transportMode, String transitInfo) {
            RouteStep step = new RouteStep();
            step.instruction = instruction;
            step.distance = distance;
            step.duration = duration;
            step.polyline = polyline;
            step.roadName = roadName;
            step.transportMode = transportMode;
            step.transitInfo = transitInfo;
            return step;
        }

        // Builder pattern (manual)
        public static RouteStepBuilder builder() {
            return new RouteStepBuilder();
        }

        public static class RouteStepBuilder {
            private String instruction;
            private Double distance;
            private Integer duration;
            private String polyline;
            private String roadName;
            private String transportMode;
            private String transitInfo;

            public RouteStepBuilder instruction(String instruction) { this.instruction = instruction; return this; }
            public RouteStepBuilder distance(Double distance) { this.distance = distance; return this; }
            public RouteStepBuilder duration(Integer duration) { this.duration = duration; return this; }
            public RouteStepBuilder polyline(String polyline) { this.polyline = polyline; return this; }
            public RouteStepBuilder roadName(String roadName) { this.roadName = roadName; return this; }
            public RouteStepBuilder transportMode(String transportMode) { this.transportMode = transportMode; return this; }
            public RouteStepBuilder transitInfo(String transitInfo) { this.transitInfo = transitInfo; return this; }

            public RouteStep build() {
                RouteStep step = new RouteStep();
                step.instruction = this.instruction;
                step.distance = this.distance;
                step.duration = this.duration;
                step.polyline = this.polyline;
                step.roadName = this.roadName;
                step.transportMode = this.transportMode;
                step.transitInfo = this.transitInfo;
                return step;
            }
        }
    }
}
