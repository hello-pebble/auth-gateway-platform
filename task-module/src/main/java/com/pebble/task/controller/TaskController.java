package com.pebble.task.controller;

import com.pebble.task.domain.Task;
import com.pebble.task.domain.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<Task> createTask(@RequestBody Task task, @AuthenticationPrincipal Jwt jwt) {
        task.setUserId(jwt.getSubject());
        return ResponseEntity.ok(taskService.createTask(task));
    }

    @GetMapping
    public ResponseEntity<List<Task>> getMyTasks(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(taskService.getMyTasks(jwt.getSubject()));
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<Task> updateTaskStatus(
            @PathVariable Long taskId,
            @RequestParam boolean completed,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(taskService.updateTaskStatus(taskId, jwt.getSubject(), completed));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long taskId, @AuthenticationPrincipal Jwt jwt) {
        taskService.deleteTask(taskId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/access-info")
    public ResponseEntity<java.util.Map<String, Object>> getAccessInfo(
            @RequestHeader java.util.Map<String, String> headers,
            @AuthenticationPrincipal Jwt jwt) {
        
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("service", "Task Service");
        
        java.util.Map<String, Object> accessInfo = new java.util.HashMap<>();
        accessInfo.put("viaGateway", headers.containsKey("x-forwarded-host"));
        accessInfo.put("gatewayHeaders", headers.entrySet().stream()
                .filter(e -> e.getKey().startsWith("x-"))
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)));
        
        java.util.Map<String, Object> authInfo = new java.util.HashMap<>();
        if (jwt != null) {
            authInfo.put("authenticated", true);
            authInfo.put("subject", jwt.getSubject());
            authInfo.put("claims", jwt.getClaims());
            authInfo.put("token_preview", jwt.getTokenValue().substring(0, 15) + "...");
        } else {
            authInfo.put("authenticated", false);
        }
        
        response.put("accessInfo", accessInfo);
        response.put("authInfo", authInfo);
        response.put("tasks", taskService.getMyTasks(jwt != null ? jwt.getSubject() : "anonymous"));
        
        return ResponseEntity.ok(response);
    }
}
