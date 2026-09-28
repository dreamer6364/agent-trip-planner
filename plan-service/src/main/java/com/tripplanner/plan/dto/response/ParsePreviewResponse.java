package com.tripplanner.plan.dto.response;

import com.tripplanner.common.model.ParsedInput;

/**
 * 解析预览响应
 */
public class ParsePreviewResponse {

    private ParsedInput parsedInput;
    private boolean valid;
    private String warning; // 警告信息（如模糊地点）

    public ParsePreviewResponse() {}

    public ParsePreviewResponse(ParsedInput parsedInput, boolean valid, String warning) {
        this.parsedInput = parsedInput;
        this.valid = valid;
        this.warning = warning;
    }

    public static ParsePreviewResponse of(ParsedInput parsedInput, boolean valid, String warning) {
        return new ParsePreviewResponse(parsedInput, valid, warning);
    }

    public ParsedInput getParsedInput() { return parsedInput; }
    public void setParsedInput(ParsedInput parsedInput) { this.parsedInput = parsedInput; }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public String getWarning() { return warning; }
    public void setWarning(String warning) { this.warning = warning; }
}