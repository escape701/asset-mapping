-- 创建数据库
CREATE DATABASE IF NOT EXISTS intelligent_asset_mapping
DEFAULT CHARACTER SET utf8mb4
DEFAULT COLLATE utf8mb4_unicode_ci;

USE intelligent_asset_mapping;

-- 创建用户表
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    real_name VARCHAR(50) COMMENT '真实姓名',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    status INT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 创建任务表
CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(36) PRIMARY KEY COMMENT '任务ID (UUID)',
    name VARCHAR(255) COMMENT '任务名称',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, running, completed, failed, stopped',
    domains_input TEXT COMMENT '输入的域名列表 (JSON数组)',
    total_domains INT NOT NULL DEFAULT 0 COMMENT '总域名数',
    completed_domains INT NOT NULL DEFAULT 0 COMMENT '已完成域名数',
    
    -- Python 进程信息
    pid INT COMMENT 'Python进程ID',
    config_path VARCHAR(500) COMMENT 'YAML配置文件路径',
    output_dir VARCHAR(500) COMMENT '输出目录路径',
    
    -- 时间戳
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    started_at DATETIME COMMENT '开始执行时间',
    finished_at DATETIME COMMENT '完成时间',
    
    -- 创建者
    created_by BIGINT COMMENT '创建者用户ID',
    
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='爬虫任务表';-- 创建任务域名结果表
CREATE TABLE IF NOT EXISTS task_domains (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    task_id VARCHAR(36) NOT NULL COMMENT '任务ID',
    domain VARCHAR(255) NOT NULL COMMENT '域名',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, discovering, crawling, completed, failed',
    
    -- 结果统计
    landing_url VARCHAR(500) COMMENT '着陆页URL',
    subdomain_count INT NOT NULL DEFAULT 0 COMMENT '发现的子域名数',
    url_count INT NOT NULL DEFAULT 0 COMMENT '发现的URL数',
    visited_count INT NOT NULL DEFAULT 0 COMMENT '已访问页面数',
    login_count INT NOT NULL DEFAULT 0 COMMENT '发现的登录入口数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败的URL数',
    
    -- JSON 文件路径
    discovery_output VARCHAR(500) COMMENT '子域名发现结果JSON路径',
    crawl_output VARCHAR(500) COMMENT '爬取结果JSON路径',
    
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_task_id (task_id),
    INDEX idx_domain (domain),
    CONSTRAINT fk_task_domains_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务域名结果表';

-- 创建LLM配置表
CREATE TABLE IF NOT EXISTS llm_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
    name VARCHAR(100) NOT NULL COMMENT '配置名称',
    provider VARCHAR(50) NOT NULL COMMENT '提供商: google, openai',
    api_key VARCHAR(500) NOT NULL COMMENT 'API Key (加密存储)',
    model VARCHAR(100) NOT NULL COMMENT '模型名称',
    is_active TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否激活: 0-否, 1-是',
    
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_provider (provider),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM配置表';