package com.gamestock;

import java.util.HashMap;
import java.util.Map;

/** 데모 API에서 필요한 단순 문자열 JSON 객체만 처리한다. */
final class JsonFields {
    private JsonFields() { }

    static Map<String, String> parse(String json) {
        if (json == null || !json.trim().startsWith("{")) {
            throw new IllegalArgumentException("JSON 요청 본문이 필요합니다.");
        }
        Map<String, String> fields = new HashMap<>();
        String content = json.trim().replaceFirst("^\\{", "").replaceFirst("\\}$", "");
        for (String pair : content.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length != 2) continue;
            fields.put(unquote(parts[0]), unquote(parts[1]));
        }
        return fields;
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.replaceFirst("^\"", "").replaceFirst("\"$", "").replace("\\\"", "\"");
    }
}
