package com.example.controller;

import com.example.dto.ApiResponse;
import com.example.dto.ChatRequest;
import com.example.dto.ChatResponse;
import com.example.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI对话控制器
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    
    private final ChatService chatService;
    
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }
    
    /**
     * 发送对话消息
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            // 验证请求
            if (request.getTaskId() == null || request.getTaskId().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("任务ID不能为空"));
            }
            if (request.getDomain() == null || request.getDomain().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("域名不能为空"));
            }
            if (request.getMessage() == null || request.getMessage().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("消息不能为空"));
            }
            
            ChatResponse response = chatService.chat(request);
            return ResponseEntity.ok(ApiResponse.success(response));
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }
}
