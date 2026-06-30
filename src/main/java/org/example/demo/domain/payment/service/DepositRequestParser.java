package org.example.demo.domain.payment.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.infrastructure.exception.InvalidDepositRequestException;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DepositRequestParser {
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();

    public ParsedDepositRequest parse(String requestBody) {
        Map<String, Object> request;
        try {
            request = jsonParser.parseMap(requestBody);
        } catch (JsonParseException e) {
            throw new InvalidDepositRequestException("requestBody must be valid JSON.");
        }

        return new ParsedDepositRequest(
                readRequiredText(request, "channel_id", "channelId"),
                readRequiredText(request, "channel_order_id", "channelOrderId"),
                readRequiredLong(request, "amount"),
                requestBody
        );
    }

    private String readRequiredText(Map<String, Object> request, String primaryField, String fallbackField) {
        String primary = readOptionalText(request.get(primaryField));
        if (primary != null) {
            return primary;
        }

        String fallback = readOptionalText(request.get(fallbackField));
        if (fallback != null) {
            return fallback;
        }

        throw new InvalidDepositRequestException("Missing required field: " + primaryField);
    }

    private String readOptionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Long readRequiredLong(Map<String, Object> request, String field) {
        Object valueObject = request.get(field);
        if (valueObject == null) {
            throw new InvalidDepositRequestException("Missing required field: " + field);
        }

        long value;
        if (valueObject instanceof Number number) {
            value = number.longValue();
        } else {
            try {
                value = Long.parseLong(String.valueOf(valueObject).trim());
            } catch (NumberFormatException e) {
                throw new InvalidDepositRequestException(field + " must be a valid number.");
            }
        }

        if (value <= 0) {
            throw new InvalidDepositRequestException(field + " must be > 0.");
        }
        return value;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ParsedDepositRequest {
        private final String channelId;
        private final String channelOrderId;
        private final Long amount;
        private final String requestBody;
    }
}
