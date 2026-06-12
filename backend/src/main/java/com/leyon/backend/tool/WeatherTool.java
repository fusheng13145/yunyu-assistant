package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Function;

/**
 * 天气查询工具
 * 供 Spring AI 调用，基于高德地图接口获取指定城市/地点天气信息
 *
 * @author leyon
 */
@Component
public class WeatherTool {

    /** 高德开放平台密钥 */
    @Value("${app.weather.api-key}")
    private String apiKey;

    /** 默认天气查询模式：base-基础天气 */
    private static final String DEFAULT_EXTENSIONS = "base";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册 AI 工具回调：天气查询
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback getWeatherFunction() {
        return FunctionToolCallback
                .builder("get_weather", (Function<WeatherRequest, String>) this::getWeather)
                .description("根据城市与地点查询天气信息，支持基础/详细天气数据")
                .inputType(WeatherRequest.class)
                .build();
    }

    /**
     * 执行天气查询
     *
     * @param request 查询请求参数
     * @return 原始天气接口响应文本 / 错误提示
     */
    public String getWeather(WeatherRequest request) {
        // 基础参数校验
        if (request == null) {
            return "查询失败：请求参数不能为空";
        }
        if (request.getCity() == null || request.getCity().isBlank()) {
            return "查询失败：城市名称不能为空";
        }

        try {
            // 1. 地理编码查询，获取区域编码 adcode
            String geoUrl = UriComponentsBuilder.fromUriString("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("address", request.getLocation())
                    .queryParam("city", request.getCity())
                    .queryParam("key", apiKey)
                    .toUriString();

            String geoResp = restTemplate.getForObject(geoUrl, String.class);
            if (geoResp == null) {
                return "查询失败：地理编码接口无返回数据";
            }

            JsonNode geoNode = objectMapper.readTree(geoResp);
            JsonNode geoCodes = geoNode.path("geocodes");
            if (geoCodes.isEmpty()) {
                return "查询失败：未匹配到该地理位置";
            }
            String adcode = geoCodes.path(0).path("adcode").asText("");
            if (adcode.isBlank()) {
                return "查询失败：获取区域编码失败";
            }

            // 2. 天气数据查询
            String extensions = request.getExtensions() != null ? request.getExtensions() : DEFAULT_EXTENSIONS;
            String weatherUrl = UriComponentsBuilder.fromUriString("https://restapi.amap.com/v3/weather/weatherInfo")
                    .queryParam("city", adcode)
                    .queryParam("extensions", extensions)
                    .queryParam("key", apiKey)
                    .toUriString();

            String weatherResp = restTemplate.getForObject(weatherUrl, String.class);
            return weatherResp == null ? "查询失败：天气接口无返回数据" : weatherResp;

        } catch (RestClientException e) {
            return "请求异常：调用第三方天气接口失败，" + e.getMessage();
        } catch (Exception e) {
            return "解析异常：" + e.getMessage();
        }
    }

    /**
     * 天气查询请求参数
     */
    public static class WeatherRequest {
        /** 具体地点 */
        private String location;
        /** 天气数据类型：base=基础实况，all=预报+实况 */
        private String extensions;
        /** 所属城市 */
        private String city;

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getExtensions() {
            return extensions;
        }

        public void setExtensions(String extensions) {
            this.extensions = extensions;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }
}