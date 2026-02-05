package com.example.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 页面控制器
 */
@Controller
public class PageController {
    
    /**
     * 登录页面
     */
    @GetMapping({"/", "/login"})
    public String loginPage() {
        return "login";
    }
    
    /**
     * 首页重定向到任务管理
     */
    @GetMapping("/index")
    public String indexPage() {
        return "redirect:/tasks";
    }
    
    /**
     * 任务管理页面
     */
    @GetMapping("/tasks")
    public String tasksPage() {
        return "tasks";
    }
    
    /**
     * 任务详情页面
     */
    @GetMapping("/tasks/{taskId}")
    public String taskDetailPage(@PathVariable String taskId) {
        return "task-detail";
    }
    
    /**
     * 用户管理页面
     */
    @GetMapping("/users")
    public String usersPage() {
        return "users";
    }
    
    /**
     * 系统设置页面
     */
    @GetMapping("/settings")
    public String settingsPage() {
        return "settings";
    }
}

