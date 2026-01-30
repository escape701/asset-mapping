-- 插入初始数据

USE intelligent_asset_mapping;

-- 插入默认管理员账号
INSERT INTO users (username, password, real_name, email, phone, status) VALUES
('admin', '123456', '管理员', 'admin@example.com', '13800138000', 1);

-- 插入测试用户
INSERT INTO users (username, password, real_name, email, phone, status) VALUES
('zhangsan', '123456', '张三', 'zhangsan@example.com', '13800138001', 1),
('lisi', '123456', '李四', 'lisi@example.com', '13800138002', 1),
('wangwu', '123456', '王五', 'wangwu@example.com', '13800138003', 0);

