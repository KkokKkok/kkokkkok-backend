package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.client.KakaoLocalClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Converts user-entered addresses or place names into WGS84 latitude/longitude for the map UI.
 * It only resolves what the user typed or pinned; it never infers a photo's location.
 */
@Service
public class GeocodingService {
    static final int MAX_QUERY_LENGTH = 100;
    private static final int ADDRESS_RESULTS = 10;
    private static final int KEYWORD_RESULTS = 10;
    private final KakaoLocalClient kakao;

    public GeocodingService(KakaoLocalClient kakao) { this.kakao = kakao; }

    public Map<String, Object> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.isEmpty()) throw new ApiFailure(400, "INVALID_QUERY", "검색어를 입력해 주세요.");
        if (query.length() > MAX_QUERY_LENGTH)
            throw new ApiFailure(400, "INVALID_QUERY", "검색어는 " + MAX_QUERY_LENGTH + "자 이하로 입력해 주세요.");

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var document : kakao.searchAddress(query, ADDRESS_RESULTS)) {
            String road = nestedText(document, "road_address", "address_name");
            String jibun = nestedText(document, "address", "address_name");
            String name = firstNonBlank(road, text(document, "address_name"), jibun);
            add(results, seen, "address", name, firstNonBlank(road, jibun, name), document);
        }
        for (var document : kakao.searchKeyword(query, KEYWORD_RESULTS)) {
            String address = firstNonBlank(text(document, "road_address_name"), text(document, "address_name"));
            add(results, seen, "place", text(document, "place_name"), address, document);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("query", query);
        response.put("coordinateSystem", "WGS84");
        response.put("results", results);
        return response;
    }

    public Map<String, Object> reverse(double latitude, double longitude) {
        validateCoordinate(latitude, longitude);
        var response = new LinkedHashMap<String, Object>();
        response.put("latitude", latitude);
        response.put("longitude", longitude);
        response.put("coordinateSystem", "WGS84");
        var documents = kakao.coordinateToAddress(latitude, longitude);
        if (documents.isEmpty()) {
            response.put("address", null);
            response.put("roadAddress", null);
            return response;
        }
        var first = documents.get(0);
        response.put("address", nestedText(first, "address", "address_name"));
        response.put("roadAddress", nestedText(first, "road_address", "address_name"));
        return response;
    }

    static void validateCoordinate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180)
            throw new ApiFailure(400, "INVALID_COORDINATE", "위도·경도 범위를 확인해 주세요.");
    }

    private static void add(List<Map<String, Object>> results, Set<String> seen, String type, String name,
                            String address, Map<String, Object> document) {
        Double longitude = number(document.get("x"));
        Double latitude = number(document.get("y"));
        if (name == null || latitude == null || longitude == null) return;
        try { validateCoordinate(latitude, longitude); } catch (ApiFailure invalid) { return; }
        if (!seen.add(name + "|" + latitude + "|" + longitude)) return;
        var item = new LinkedHashMap<String, Object>();
        item.put("type", type);
        item.put("name", name);
        item.put("address", address);
        item.put("latitude", latitude);
        item.put("longitude", longitude);
        results.add(item);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static String nestedText(Map<String, Object> map, String objectKey, String key) {
        Object nested = map.get(objectKey);
        return nested instanceof Map<?, ?> inner ? text((Map<String, Object>) inner, key) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
