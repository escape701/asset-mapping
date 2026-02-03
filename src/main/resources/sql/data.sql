-- 插入初始数据

USE intelligent_asset_mapping;

-- 插入默认管理员账号
-- 密码已使用BCrypt加密，原始密码为: 123456
-- BCrypt特点：即使原始密码相同，每次加密结果也不同（因为盐值随机）
INSERT INTO users (username, password, real_name, email, phone, status) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员', 'admin@example.com', '13800138000', 1);

-- 插入测试用户（密码均为: 123456）
INSERT INTO users (username, password, real_name, email, phone, status) VALUES
('zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '张三', 'zhangsan@example.com', '13800138001', 1),
('lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '李四', 'lisi@example.com', '13800138002', 1),
('wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '王五', 'wangwu@example.com', '13800138003', 0);

