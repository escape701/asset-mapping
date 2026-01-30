package com.example.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
     * 首页
     */
    @GetMapping("/index")
    public String indexPage() {
        return "index";
    }
    
    /**
     * 用户管理页面
     */
    @GetMapping("/users")
    public String usersPage() {
        return "users";
    }
}

