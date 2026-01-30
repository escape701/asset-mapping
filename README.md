# 智能化资产测绘平台

这是一个基于 Spring Boot + HTML + CSS + JavaScript 开发的智能化资产测绘平台。

## 功能特性

- 用户登录（支持密码显示/隐藏）
- 系统首页（数据统计展示）
- 用户管理（增删改查）
- 响应式设计，支持移动端

## 技术栈

### 后端
- Spring Boot 2.7.14
- Spring Data JPA
- MySQL 8.0
- Lombok

### 前端
- HTML5
- CSS3
- JavaScript (ES6+)

## 项目结构

```
intelligent-asset-mapping-platform/
├── src/
│   ├── main/
│   │   ├── java/com/example/
│   │   │   ├── controller/          # 控制器层
│   │   │   ├── service/             # 业务逻辑层
│   │   │   ├── repository/          # 数据访问层
│   │   │   ├── entity/              # 实体类
│   │   │   ├── dto/                 # 数据传输对象
│   │   │   └── IntelligentAssetMappingApplication.java
│   │   └── resources/
│   │       ├── static/              # 静态资源
│   │       │   ├── css/            # 样式文件
│   │       │   └── js/             # JavaScript文件
│   │       ├── templates/           # HTML模板
│   │       ├── sql/                 # SQL脚本
│   │       └── application.yml      # 配置文件
│   └── test/
├── pom.xml
└── README.md
```

## 快速开始

### 1. 环境要求

- JDK 11 或更高版本
- Maven 3.6+
- MySQL 8.0+

### 2. 数据库配置

#### 创建数据库并导入数据

```bash
# 登录MySQL
mysql -u root -p

# 执行SQL脚本
source src/main/resources/sql/schema.sql
source src/main/resources/sql/data.sql
```

或者直接在MySQL中执行：

```sql
CREATE DATABASE intelligent_asset_mapping DEFAULT CHARACTER SET utf8mb4;
```

#### 修改数据库连接配置

编辑 `src/main/resources/application.yml` 文件，修改数据库连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/intelligent_asset_mapping?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password  # 修改为你的MySQL密码
```

### 3. 运行项目

#### 使用Maven命令

```bash
# 编译项目
mvn clean compile

# 运行项目
mvn spring-boot:run
```

#### 使用IDE运行

直接运行 `IntelligentAssetMappingApplication` 类的 main 方法

### 4. 访问系统

打开浏览器访问：`http://localhost:8080`

### 默认账号

- 用户名：`admin`
- 密码：`123456`

## 主要功能说明

### 1. 登录页面
- 用户名和密码登录
- 密码可见/不可见切换功能
- 登录状态保持（Session）

### 2. 首页
- 用户统计信息展示
- 实时时间显示
- 系统状态展示
- 快速导航

### 3. 用户管理
- 用户列表展示
- 添加新用户
- 编辑用户信息
- 删除用户
- 启用/禁用用户状态

## API接口

### 认证相关
- `POST /api/login` - 用户登录
- `POST /api/logout` - 用户登出
- `GET /api/currentUser` - 获取当前登录用户

### 用户管理
- `GET /api/users` - 获取所有用户
- `GET /api/users/{userId}` - 获取指定用户
- `POST /api/users` - 创建用户
- `PUT /api/users/{userId}` - 更新用户
- `DELETE /api/users/{userId}` - 删除用户

## 注意事项

1. 本系统的密码采用明文存储，仅供学习使用。生产环境请使用BCrypt等加密方式。
2. 建议在生产环境中添加权限控制和操作日志功能。
3. 所有变量命名采用驼峰命名法（camelCase）。

## 开发说明

### 添加新功能

1. 在 `entity` 包中创建实体类
2. 在 `repository` 包中创建数据访问接口
3. 在 `service` 包中实现业务逻辑
4. 在 `controller` 包中创建API接口
5. 在 `templates` 和 `static` 中添加前端页面和资源

### 数据库字段命名规则

- 数据库字段使用下划线命名：`user_id`、`real_name`
- Java实体类使用驼峰命名：`userId`、`realName`
- JPA会自动进行映射转换

## 许可证

MIT License

