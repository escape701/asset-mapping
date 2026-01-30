package com.example.controller;

import com.example.dto.ApiResponse;
import com.example.dto.LoginRequest;
import com.example.entity.User;
import com.example.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;

/**
 * 用户API控制器
 */
@RestController
@RequestMapping("/api")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<User> login(@RequestBody LoginRequest request, HttpSession session) {
        try {
            User user = userService.login(request);
            if (user != null) {
                // 将用户信息存入session
                session.setAttribute("currentUser", user);
                // 返回前不要暴露密码
                user.setPassword(null);
                return ApiResponse.success("登录成功", user);
            } else {
                return ApiResponse.error(401, "用户名或密码错误");
            }
        } catch (Exception e) {
            return ApiResponse.error("登录失败：" + e.getMessage());
        }
    }
    
    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpSession session) {
        session.removeAttribute("currentUser");
        session.invalidate();
        return ApiResponse.success("退出成功", null);
    }
    
    /**
     * 获取当前登录用户
     */
    @GetMapping("/currentUser")
    public ApiResponse<User> getCurrentUser(HttpSession session) {
        User user = (User) session.getAttribute("currentUser");
        if (user != null) {
            user.setPassword(null);
            return ApiResponse.success(user);
        }
        return ApiResponse.error(401, "未登录");
    }
    
    /**
     * 获取所有用户
     */
    @GetMapping("/users")
    public ApiResponse<List<User>> getAllUsers() {
        try {
            List<User> users = userService.getAllUsers();
            // 不返回密码信息
            users.forEach(user -> user.setPassword(null));
            return ApiResponse.success(users);
        } catch (Exception e) {
            return ApiResponse.error("获取用户列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 根据ID获取用户
     */
    @GetMapping("/users/{userId}")
    public ApiResponse<User> getUserById(@PathVariable Long userId) {
        try {
            User user = userService.getUserById(userId);
            if (user != null) {
                user.setPassword(null);
                return ApiResponse.success(user);
            }
            return ApiResponse.error("用户不存在");
        } catch (Exception e) {
            return ApiResponse.error("获取用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 创建用户
     */
    @PostMapping("/users")
    public ApiResponse<User> createUser(@RequestBody User user) {
        try {
            User newUser = userService.createUser(user);
            newUser.setPassword(null);
            return ApiResponse.success("用户创建成功", newUser);
        } catch (Exception e) {
            return ApiResponse.error("创建用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 更新用户
     */
    @PutMapping("/users/{userId}")
    public ApiResponse<User> updateUser(@PathVariable Long userId, @RequestBody User user) {
        try {
            User updatedUser = userService.updateUser(userId, user);
            updatedUser.setPassword(null);
            return ApiResponse.success("用户更新成功", updatedUser);
        } catch (Exception e) {
            return ApiResponse.error("更新用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 删除用户
     */
    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        try {
            userService.deleteUser(userId);
            return ApiResponse.success("用户删除成功", null);
        } catch (Exception e) {
            return ApiResponse.error("删除用户失败：" + e.getMessage());
        }
    }
}

