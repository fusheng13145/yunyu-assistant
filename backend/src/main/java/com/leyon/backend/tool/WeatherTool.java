package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class WeatherTool {

    @Value("${app.weather.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ToolCallback getWeatherFunction() {
        return FunctionToolCallback.builder("get_weather", (Function<WeatherRequest, String>) this::getWeather)
                .description("Get weather information for a location")
                .inputType(WeatherRequest.class)
                .build();
    }

    public String getWeather(WeatherRequest request) {
        try {
            String geocodeUrl = "https://restapi.amap.com/v3/geocode/geo?address="
                    + request.getLocation() + "&key=" + apiKey + "&city=" + request.getCity();
            String geocodeResponse = restTemplate.getForObject(geocodeUrl, String.class);
            JsonNode geocodeNode = objectMapper.readTree(geocodeResponse);
            String adcode = geocodeNode.path("geocodes").path(0).path("adcode").asText();

            String extensions = request.getExtensions() != null ? request.getExtensions() : "base";
            String weatherUrl = "https://restapi.amap.com/v3/weather/weatherInfo?city="
                    + adcode + "&extensions=" + extensions + "&key=" + apiKey;
            String weatherResponse = restTemplate.getForObject(weatherUrl, String.class);
            return weatherResponse;
        } catch (Exception e) {
            return "Error getting weather: " + e.getMessage();
        }
    }

    public static class WeatherRequest {
        private String location;
        private String extensions;
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
