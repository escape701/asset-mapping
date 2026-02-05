package com.example.service;

import com.example.dto.ChatRequest;
import com.example.dto.ChatResponse;
import com.example.entity.LlmConfig;
import com.example.entity.TaskDomain;
import com.example.repository.LlmConfigRepository;
import com.example.repository.TaskDomainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI对话服务
 */
@Slf4j
@Service
public class ChatService {
    
    private final LlmConfigRepository configRepository;
    private final TaskDomainRepository taskDomainRepository;
    private final CrawlerService crawlerService;
    
    public ChatService(LlmConfigRepository configRepository,
                       TaskDomainRepository taskDomainRepository,
                       CrawlerService crawlerService) {
        this.configRepository = configRepository;
        this.taskDomainRepository = taskDomainRepository;
        this.crawlerService = crawlerService;
    }
    
    /**
     * 处理对话请求
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        
        // 获取激活的 LLM 配置
        LlmConfig config = configRepository.findByIsActiveTrue()
            .orElseThrow(() -> new RuntimeException("请先配置并激活 AI 服务"));
        
        // 构建上下文
        String context = buildContext(request.getTaskId(), request.getDomain());
        
        // 构建 Prompt
        String systemPrompt = buildSystemPrompt(context);
        String userMessage = request.getMessage();
        
        // 调用 LLM API
        String reply;
        try {
            reply = callLlmApi(config, systemPrompt, userMessage);
        } catch (Exception e) {
            log.error("调用 LLM API 失败", e);
            // 返回模拟回复（开发阶段）
            reply = generateMockReply(request, context);
        }
        
        long latency = System.currentTimeMillis() - startTime;
        
        return new ChatResponse(reply, config.getModel(), latency);
    }
    
    /**
     * 构建上下文信息
     */
    private String buildContext(String taskId, String domain) {
        StringBuilder context = new StringBuilder();
        
        // 获取域名信息
        TaskDomain taskDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain)
            .orElse(null);
        
        if (taskDomain != null) {
            context.append("域名: ").append(domain).append("\n");
            context.append("状态: ").append(taskDomain.getStatus()).append("\n");
            context.append("子域名数: ").append(taskDomain.getSubdomainCount()).append("\n");
            context.append("URL数: ").append(taskDomain.getUrlCount()).append("\n");
            context.append("已访问页面数: ").append(taskDomain.getVisitedCount()).append("\n");
            context.append("登录入口数: ").append(taskDomain.getLoginCount()).append("\n");
            context.append("失败数: ").append(taskDomain.getFailedCount()).append("\n");
            context.append("\n");
        }
        
        // 读取发现结果
        try {
            Map<String, Object> discovery = crawlerService.readDiscoveryResult(taskId, domain);
            if (!discovery.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> subdomains = (List<String>) discovery.get("subdomains");
                if (subdomains != null && !subdomains.isEmpty()) {
                    context.append("发现的子域名:\n");
                    for (String sub : subdomains) {
                        context.append("- ").append(sub).append("\n");
                    }
                    context.append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("读取发现结果失败: {}", e.getMessage());
        }
        
        // 读取登录页面
        try {
            List<Map<String, Object>> logins = crawlerService.extractLoginPages(taskId, domain);
            if (!logins.isEmpty()) {
                context.append("发现的登录入口:\n");
                for (Map<String, Object> login : logins) {
                    context.append("- URL: ").append(login.get("url")).append("\n");
                    context.append("  标题: ").append(login.get("title")).append("\n");
                    context.append("  识别结果: ").append(login.get("is_login_page")).append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("读取登录页面失败: {}", e.getMessage());
        }
        
        return context.toString();
    }
    
    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        return String.format(
            "你是一个专业的安全分析助手，专门分析资产测绘结果。\n\n" +
            "你需要根据以下资产测绘信息回答用户的问题：\n\n" +
            "%s\n\n" +
            "请注意：\n" +
            "1. 回答要专业、准确、有帮助\n" +
            "2. 重点关注安全风险和潜在漏洞\n" +
            "3. 给出具体的安全建议\n" +
            "4. 使用 Markdown 格式组织回答\n" +
            "5. 如果信息不足，说明需要哪些额外信息", context);
    }
    
    /**
     * 调用 LLM API
     */
    private String callLlmApi(LlmConfig config, String systemPrompt, String userMessage) {
        // TODO: 实际调用 OpenAI 或 Google Gemini API
        // 目前抛出异常，使用模拟回复
        throw new UnsupportedOperationException("LLM API 调用尚未实现");
    }
    
    /**
     * 生成模拟回复（开发阶段使用）
     */
    private String generateMockReply(ChatRequest request, String context) {
        String message = request.getMessage().toLowerCase();
        String domain = request.getDomain();
        
        if (message.contains("安全") || message.contains("风险")) {
            return String.format(
                "## %s 安全分析报告\n\n" +
                "基于测绘结果，我发现以下安全风险点：\n\n" +
                "**高风险：**\n" +
                "1. 存在多个登录入口，建议检查是否有弱密码或暴力破解防护\n" +
                "2. 部分子域名可能存在未授权访问风险\n\n" +
                "**中风险：**\n" +
                "1. 发现敏感目录可能暴露\n" +
                "2. 部分页面缺少 HTTPS\n\n" +
                "**建议措施：**\n" +
                "- 对所有登录入口启用多因素认证\n" +
                "- 审查子域名的访问控制策略\n" +
                "- 定期进行安全审计\n\n" +
                "*注意：这是基于自动化测绘的初步分析，建议进行深入的人工安全评估。*", domain);
        }
        
        if (message.contains("子域名") || message.contains("subdomain")) {
            return String.format(
                "## %s 子域名分析\n\n" +
                "子域名爆破发现了多个子域名，其中需要重点关注：\n\n" +
                "1. **管理类子域名**：admin、manage、portal 等可能暴露管理后台\n" +
                "2. **API子域名**：api、gateway 等可能存在接口安全问题\n" +
                "3. **测试环境**：dev、test、staging 等可能配置不当\n\n" +
                "**建议：**\n" +
                "- 检查每个子域名的用途和安全配置\n" +
                "- 关闭不必要的测试环境公网访问\n" +
                "- 确保敏感子域名有适当的访问控制", domain);
        }
        
        if (message.contains("登录")) {
            return String.format(
                "## %s 登录入口分析\n\n" +
                "发现的登录入口需要关注以下安全问题：\n\n" +
                "1. **认证方式**：检查是否使用安全的认证机制\n" +
                "2. **会话管理**：确保 Session/Token 安全配置\n" +
                "3. **暴力破解防护**：验证是否有登录频率限制\n" +
                "4. **密码策略**：确认密码复杂度要求\n\n" +
                "**测试建议：**\n" +
                "- 检查 SQL 注入漏洞\n" +
                "- 测试 XSS 漏洞\n" +
                "- 验证 CSRF 防护\n" +
                "- 检查密码重置流程安全性", domain);
        }
        
        return String.format(
            "感谢您的提问！\n\n" +
            "关于 **%s** 的测绘分析，我已收到您的问题。\n\n" +
            "您可以问我：\n" +
            "- \"分析这个域名的安全风险\"\n" +
            "- \"总结发现的所有子域名\"\n" +
            "- \"分析发现的登录入口\"\n" +
            "- \"有哪些敏感路径需要关注\"\n\n" +
            "请告诉我您想了解的具体内容。", domain);
    }
}
