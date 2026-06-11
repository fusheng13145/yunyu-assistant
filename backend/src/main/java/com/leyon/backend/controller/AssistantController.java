package com.leyon.backend.controller;

import com.leyon.backend.entity.Assistant;
import com.leyon.backend.model.ApiResponse;
import com.leyon.backend.service.AssistantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistants")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping
    public ApiResponse<Assistant> create(@RequestBody Assistant assistant, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        assistant.setUserId(userId);
        Assistant created = assistantService.create(assistant);
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<Assistant>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(assistantService.listByUserId(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Assistant> getById(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        Assistant assistant = assistantService.getById(id);
        if (assistant == null) {
            return ApiResponse.paramError("Assistant not found");
        }
        if (!assistant.getUserId().equals(userId)) {
            return ApiResponse.paramError("无权访问此助手");
        }
        return ApiResponse.success(assistant);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        Assistant assistant = assistantService.getById(id);
        if (assistant == null || !assistant.getUserId().equals(userId)) {
            return ApiResponse.paramError("Assistant not found or no permission");
        }
        boolean deleted = assistantService.delete(id);
        if (!deleted) {
            return ApiResponse.paramError("Delete failed");
        }
        return ApiResponse.success();
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Assistant assistant, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        assistant.setUserId(userId);
        boolean updated = assistantService.update(assistant);
        if (!updated) {
            return ApiResponse.paramError("Update failed");
        }
        return ApiResponse.success();
    }
}
