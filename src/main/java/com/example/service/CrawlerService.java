package com.example.service;

import com.example.entity.Task;
import com.example.entity.TaskDomain;
import com.example.repository.TaskDomainRepository;
import com.example.repository.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 爬虫服务 - 管理 Python 爬虫进程和结果文件
 */
@Slf4j
@Service
public class CrawlerService {
    
    private final TaskRepository taskRepository;
    private final TaskDomainRepository taskDomainRepository;
    private final ObjectMapper objectMapper;
    
    @Value("${crawler.python-path:python}")
    private String pythonPath;
    
    @Value("${crawler.project-path:../login_crawler}")
    private String crawlerProjectPath;
    
    // 任务输出目录
    @Value("${crawler.task-output-base:./crawler-output}")
    private String taskOutputBasePath;
    
    @Value("${crawler.config-template:config/combined/default.yaml}")
    private String configTemplate;
    
    @Value("${crawler.max-concurrent:3}")
    private int maxConcurrent;
    
    // 爬虫专用线程池（使用 @Resource 替代 @Autowired + @Qualifier）
    @javax.annotation.Resource(name = "crawlerExecutor")
    private Executor crawlerExecutor;
    
    // 存储域名级别的进程（domain key = taskId + "_" + domain）
    private final Map<String, Process> domainProcesses = new ConcurrentHashMap<>();
    
    // 存储日志写入器（用于实时日志）
    private final Map<String, BufferedWriter> logWriters = new ConcurrentHashMap<>();
    
    // 存储日志文件路径
    private final Map<String, Path> logFilePaths = new ConcurrentHashMap<>();
    
    // 文件监控线程
    private Thread watcherThread;
    private volatile boolean watching = false;
    
    // 日志文件名
    private static final String LOG_FILE_NAME = "crawler.log";
    
    // 时间格式化
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    public CrawlerService(TaskRepository taskRepository,
                          TaskDomainRepository taskDomainRepository,
                          ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskDomainRepository = taskDomainRepository;
        this.objectMapper = objectMapper;
    }
    
    @PostConstruct
    public void init() {
        // 确保任务输出目录存在
        try {
            Files.createDirectories(Paths.get(taskOutputBasePath));
            log.info("任务输出目录: {}", taskOutputBasePath);
        } catch (IOException e) {
            log.error("创建任务输出目录失败: {}", taskOutputBasePath, e);
        }
        
        // 启动文件监控
        startFileWatcher();
    }
    
    @PreDestroy
    public void cleanup() {
        // 停止文件监控
        watching = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        
        // 关闭所有日志写入器
        for (BufferedWriter writer : logWriters.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                log.error("关闭日志写入器失败", e);
            }
        }
        logWriters.clear();
        
        // 终止所有运行中的域名进程
        for (Map.Entry<String, Process> entry : domainProcesses.entrySet()) {
            Process process = entry.getValue();
            if (process.isAlive()) {
                process.destroyForcibly();
                log.info("清理时终止域名进程: {}", entry.getKey());
            }
        }
        domainProcesses.clear();
    }
    
    /**
     * 启动爬虫（并发模式）
     * 为每个域名启动独立的 Python 进程，并发执行
     */
    @Async("taskExecutor")
    public void startCrawl(Task task, List<String> domains) {
        // 重要：不要使用传入的 task 对象（它是脱管状态），而是通过 taskId 重新查询
        String taskId = task.getId();
        
        try {
            // 重新查询任务（确保在当前线程的数据库会话中）
            Task currentTask = taskRepository.findById(taskId).orElse(null);
            if (currentTask == null) {
                log.error("任务不存在: {}", taskId);
                return;
            }
            
            // 1. 创建任务输出目录
            Path taskOutputDir = Paths.get(taskOutputBasePath, taskId);
            Files.createDirectories(taskOutputDir);
            
            // 2. 保存所有域名到 input.txt（用于记录）
            Path inputFile = taskOutputDir.resolve("input.txt");
            Files.write(inputFile, domains);
            log.info("生成域名文件: {}, 共 {} 个域名", inputFile, domains.size());
            
            // 3. 更新任务信息
            currentTask.setOutputDir(taskOutputDir.toString());
            taskRepository.save(currentTask);
            
            // 4. 创建任务级别的日志文件
            Path taskLogFile = taskOutputDir.resolve(LOG_FILE_NAME);
            logFilePaths.put(taskId, taskLogFile);
            appendToTaskLog(taskLogFile, String.format("任务启动，共 %d 个域名，最大并发数: %d", 
                domains.size(), maxConcurrent));
            
            // 5. 并发执行每个域名的爬取
            log.info("========================================");
            log.info("开始并发爬取, 任务: {}", taskId);
            log.info("域名列表: {}", domains);
            log.info("域名数量: {}, 最大并发数: {}", domains.size(), maxConcurrent);
            log.info("========================================");
            
            // 创建固定大小的线程池（直接创建，确保并发执行）
            int poolSize = Math.min(maxConcurrent, domains.size());
            ExecutorService executorService = Executors.newFixedThreadPool(poolSize, r -> {
                Thread t = new Thread(r);
                t.setName("crawler-" + System.currentTimeMillis() % 1000);
                return t;
            });
            
            log.info("创建线程池，大小: {}", poolSize);
            
            // 使用 CompletableFuture 并发执行
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            final String finalTaskId = taskId;
            final Path finalTaskOutputDir = taskOutputDir;
            final Path finalTaskLogFile = taskLogFile;
            
            for (String domain : domains) {
                final String finalDomain = domain;
                log.info("提交域名爬取任务: {}", domain);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.info("===== 开始执行域名: {} (线程: {}) =====", finalDomain, Thread.currentThread().getName());
                        // 传递 taskId 而不是 Task 对象，避免多线程共享 JPA 实体
                        crawlSingleDomain(finalTaskId, finalDomain, finalTaskOutputDir, finalTaskLogFile);
                        log.info("===== 域名执行完成: {} =====", finalDomain);
                    } catch (Exception e) {
                        log.error("域名爬取异常: {} - {}", finalDomain, e.getMessage(), e);
                        appendToTaskLog(finalTaskLogFile, String.format("[%s] 爬取异常: %s", finalDomain, e.getMessage()));
                    }
                }, executorService);
                futures.add(future);
            }
            
            log.info("已提交 {} 个域名任务到线程池，等待完成...", futures.size());
            
            try {
                // 等待所有域名完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                // 关闭线程池
                executorService.shutdown();
                log.info("线程池已关闭");
            }
            
            // 6. 更新任务状态
            Task updatedTask = taskRepository.findById(taskId).orElse(null);
            if (updatedTask != null) {
                // 统计完成和失败的域名数
                List<TaskDomain> taskDomains = taskDomainRepository.findByTaskId(taskId);
                long completedCount = taskDomains.stream()
                    .filter(d -> TaskDomain.STATUS_COMPLETED.equals(d.getStatus()))
                    .count();
                long failedCount = taskDomains.stream()
                    .filter(d -> TaskDomain.STATUS_FAILED.equals(d.getStatus()))
                    .count();
                
                log.info("域名执行统计: 总数={}, 完成={}, 失败={}", domains.size(), completedCount, failedCount);
                
                updatedTask.setCompletedDomains((int) completedCount);
                
                // 判断任务最终状态
                if (completedCount + failedCount >= domains.size()) {
                    if (failedCount == domains.size()) {
                        updatedTask.setStatus(Task.STATUS_FAILED);
                    } else {
                        updatedTask.setStatus(Task.STATUS_COMPLETED);
                    }
                    updatedTask.setFinishedAt(LocalDateTime.now());
                }
                updatedTask.setPid(null);
                taskRepository.save(updatedTask);
                
                appendToTaskLog(taskLogFile, String.format("任务完成，成功: %d, 失败: %d", 
                    completedCount, failedCount));
            }
            
            log.info("任务爬取完成, 任务: {}", taskId);
            
        } catch (Exception e) {
            log.error("启动爬虫失败, 任务: {}", taskId, e);
            
            Task failedTask = taskRepository.findById(taskId).orElse(null);
            if (failedTask != null) {
                failedTask.setStatus(Task.STATUS_FAILED);
                failedTask.setFinishedAt(LocalDateTime.now());
                failedTask.setPid(null);
                taskRepository.save(failedTask);
            }
        }
    }
    
    /**
     * 爬取单个域名
     * @param taskId 任务ID（不传递 Task 对象，避免多线程共享 JPA 实体）
     */
    private void crawlSingleDomain(String taskId, String domain, Path taskOutputDir, Path taskLogFile) {
        String domainKey = taskId + "_" + domain;
        log.info("crawlSingleDomain 开始, taskId: {}, domain: {}, 线程: {}", taskId, domain, Thread.currentThread().getName());
        
        // 在当前线程中查询 Task（每个线程独立的数据库会话）
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.error("任务不存在: {}", taskId);
            appendToTaskLog(taskLogFile, String.format("[%s] 错误: 任务不存在", domain));
            return;
        }
        
        // 获取或创建 TaskDomain 记录
        TaskDomain taskDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain)
            .orElseGet(() -> {
                log.info("创建新的 TaskDomain 记录: {} - {}", taskId, domain);
                TaskDomain td = new TaskDomain();
                td.setTask(task);
                td.setDomain(domain);
                return taskDomainRepository.save(td);
            });
        
        try {
            // 1. 创建域名独立的输出目录
            Path domainOutputDir = taskOutputDir.resolve(sanitizeDomainForPath(domain));
            Files.createDirectories(domainOutputDir);
            
            // 2. 生成单域名的 input.txt
            Path domainInputFile = domainOutputDir.resolve("input.txt");
            Files.write(domainInputFile, Collections.singletonList(domain));
            
            // 3. 生成配置文件
            Path configFile = generateConfigForDomain(domain, domainInputFile, domainOutputDir);
            
            // 4. 更新 TaskDomain 状态
            taskDomain.setStatus(TaskDomain.STATUS_DISCOVERING);
            taskDomain.setOutputDir(domainOutputDir.toString());
            taskDomain.setStartedAt(LocalDateTime.now());
            taskDomainRepository.save(taskDomain);
            
            appendToTaskLog(taskLogFile, String.format("[%s] 开始爬取", domain));
            log.info("开始爬取域名: {}, 任务: {}", domain, taskId);
            
            // 5. 启动 Python 进程（使用绝对路径确保跨工作目录访问）
            ProcessBuilder pb = new ProcessBuilder(
                pythonPath, "-m", "login_crawler.combined_cli",
                "-c", configFile.toAbsolutePath().toString()
            );
            pb.directory(new File(crawlerProjectPath));
            pb.redirectErrorStream(true);
            
            // 设置 PYTHONPATH
            Map<String, String> env = pb.environment();
            String srcPath = Paths.get(crawlerProjectPath, "src").toString();
            String existingPythonPath = env.get("PYTHONPATH");
            if (existingPythonPath != null && !existingPythonPath.isEmpty()) {
                env.put("PYTHONPATH", srcPath + File.pathSeparator + existingPythonPath);
            } else {
                env.put("PYTHONPATH", srcPath);
            }
            
            Process process = pb.start();
            long pid = process.pid();
            
            // 记录进程
            domainProcesses.put(domainKey, process);
            taskDomain.setPid((int) pid);
            taskDomainRepository.save(taskDomain);
            
            log.info("域名爬虫已启动, 任务: {}, 域名: {}, PID: {}", taskId, domain, pid);
            
            // 6. 读取进程输出并写入域名日志
            Path domainLogFile = domainOutputDir.resolve(LOG_FILE_NAME);
            taskDomain.setLogFile(domainLogFile.toString());
            taskDomainRepository.save(taskDomain);
            
            readDomainProcessOutput(taskId, domain, process, domainOutputDir, taskLogFile);
            
            // 7. 等待进程完成
            int exitCode = process.waitFor();
            
            // 8. 更新域名状态
            domainProcesses.remove(domainKey);
            
            TaskDomain updatedDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain).orElse(taskDomain);
            updatedDomain.setFinishedAt(LocalDateTime.now());
            updatedDomain.setPid(null);
            
            if (exitCode == 0) {
                updatedDomain.setStatus(TaskDomain.STATUS_COMPLETED);
                appendToTaskLog(taskLogFile, String.format("[%s] 爬取完成", domain));
            } else {
                updatedDomain.setStatus(TaskDomain.STATUS_FAILED);
                appendToTaskLog(taskLogFile, String.format("[%s] 爬取失败，退出码: %d", domain, exitCode));
            }
            taskDomainRepository.save(updatedDomain);
            
            // 9. 同步读取结果文件更新统计
            syncDomainResult(taskId, domain);
            
            log.info("域名爬取完成, 任务: {}, 域名: {}, 退出码: {}", taskId, domain, exitCode);
            
        } catch (Exception e) {
            log.error("爬取域名失败, 任务: {}, 域名: {}", taskId, domain, e);
            domainProcesses.remove(domainKey);
            
            taskDomain.setStatus(TaskDomain.STATUS_FAILED);
            taskDomain.setFinishedAt(LocalDateTime.now());
            taskDomain.setPid(null);
            taskDomainRepository.save(taskDomain);
            
            appendToTaskLog(taskLogFile, String.format("[%s] 爬取异常: %s", domain, e.getMessage()));
        }
    }
    
    /**
     * 为单个域名生成配置文件
     */
    private Path generateConfigForDomain(String domain, Path inputFile, Path outputDir) throws IOException {
        // 读取模板配置
        Path templatePath = Paths.get(crawlerProjectPath, configTemplate);
        String template = Files.readString(templatePath);
        
        // 替换配置项 - 使用绝对路径以确保跨进程工作目录访问
        String config = template
            .replace("domains_file: input.txt", "domains_file: " + inputFile.toAbsolutePath().toString().replace("\\", "/"))
            .replace("output_dir: out/combined", "output_dir: " + outputDir.toAbsolutePath().toString().replace("\\", "/"))
            .replace("summary_output: out/combined/summary.json", 
                     "summary_output: " + outputDir.resolve("summary.json").toAbsolutePath().toString().replace("\\", "/"));
        
        // 写入配置文件
        Path configFile = outputDir.resolve("config.yaml");
        Files.writeString(configFile, config);
        
        return configFile;
    }
    
    /**
     * 读取域名进程输出
     */
    private void readDomainProcessOutput(String taskId, String domain, Process process, 
                                          Path outputDir, Path taskLogFile) {
        new Thread(() -> {
            Path logFile = outputDir.resolve(LOG_FILE_NAME);
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile.toFile(), false), StandardCharsets.UTF_8))) {
                
                // 写入日志头
                String header = String.format("[%s] 爬虫启动: %s%n", 
                    LocalDateTime.now().format(LOG_TIME_FORMAT), domain);
                writer.write(header);
                writer.flush();
                
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[{}][{}] {}", taskId, domain, line);
                    
                    String logLine = String.format("[%s] %s%n", 
                        LocalDateTime.now().format(LOG_TIME_FORMAT), line);
                    writer.write(logLine);
                    writer.flush();
                    
                    // 同时写入任务级别的日志（带域名前缀）
                    appendToTaskLog(taskLogFile, String.format("[%s] %s", domain, line));
                }
                
                // 写入日志尾
                String footer = String.format("[%s] 爬虫进程结束%n", 
                    LocalDateTime.now().format(LOG_TIME_FORMAT));
                writer.write(footer);
                writer.flush();
                
            } catch (IOException e) {
                log.error("读取/写入域名进程输出失败, 任务: {}, 域名: {}", taskId, domain, e);
            }
        }, "crawler-" + taskId + "-" + domain).start();
    }
    
    /**
     * 同步单个域名的结果
     */
    @SuppressWarnings("unchecked")
    private void syncDomainResult(String taskId, String domain) {
        try {
            TaskDomain taskDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain).orElse(null);
            if (taskDomain == null) return;
            
            // 读取 discovery 结果
            Map<String, Object> discovery = readDiscoveryResult(taskId, domain);
            if (!discovery.isEmpty()) {
                List<?> subdomains = (List<?>) discovery.get("subdomains");
                if (subdomains != null) {
                    taskDomain.setSubdomainCount(subdomains.size());
                }
                List<?> urls = (List<?>) discovery.get("urls");
                if (urls != null) {
                    taskDomain.setUrlCount(urls.size());
                }
            }
            
            // 读取 crawl 结果
            Map<String, Object> crawl = readCrawlResult(taskId, domain);
            if (!crawl.isEmpty()) {
                Map<String, Object> metadata = (Map<String, Object>) crawl.get("metadata");
                if (metadata != null) {
                    String landingUrl = (String) metadata.get("start_url");
                    if (landingUrl != null) {
                        taskDomain.setLandingUrl(landingUrl);
                    }
                }
                
                Map<String, Object> stats = (Map<String, Object>) crawl.get("statistics");
                if (stats != null) {
                    taskDomain.setVisitedCount(getIntValue(stats, "total_visited"));
                    taskDomain.setFailedCount(getIntValue(stats, "total_failed"));
                }
                
                // 计算登录页数量
                List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawl.get("visited_pages");
                if (visitedPages != null) {
                    int loginCount = 0;
                    for (Map<String, Object> page : visitedPages) {
                        if (isLoginPage(page.get("is_login_page"))) {
                            loginCount++;
                        }
                    }
                    taskDomain.setLoginCount(loginCount);
                }
            }
            
            taskDomainRepository.save(taskDomain);
            
        } catch (Exception e) {
            log.warn("同步域名结果失败: {} - {}", taskId, domain, e);
        }
    }
    
    /**
     * 追加日志到任务级别的日志文件
     */
    private void appendToTaskLog(Path logFile, String message) {
        try {
            String logLine = String.format("[%s] %s%n", 
                LocalDateTime.now().format(LOG_TIME_FORMAT), message);
            Files.writeString(logFile, logLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("写入任务日志失败: {}", logFile, e);
        }
    }
    
    /**
     * 将域名转换为安全的目录名
     */
    private String sanitizeDomainForPath(String domain) {
        // 移除协议前缀，将特殊字符替换为下划线
        return domain
            .replaceAll("^https?://", "")
            .replaceAll("[^a-zA-Z0-9.-]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }
    
    /**
     * 停止爬虫（停止所有域名进程）
     */
    public void stopCrawl(Task task) {
        String taskId = task.getId();
        
        // 停止所有域名级别的进程
        List<TaskDomain> taskDomains = taskDomainRepository.findByTaskId(taskId);
        for (TaskDomain taskDomain : taskDomains) {
            String domainKey = taskId + "_" + taskDomain.getDomain();
            Process domainProcess = domainProcesses.get(domainKey);
            
            if (domainProcess != null && domainProcess.isAlive()) {
                stopProcess(domainProcess, domainKey);
                domainProcesses.remove(domainKey);
            }
            
            // 通过 PID 终止（如果有）
            if (taskDomain.getPid() != null) {
                killProcessByPid(taskDomain.getPid());
                taskDomain.setPid(null);
            }
            
            // 更新域名状态
            if (TaskDomain.STATUS_DISCOVERING.equals(taskDomain.getStatus()) ||
                TaskDomain.STATUS_CRAWLING.equals(taskDomain.getStatus())) {
                taskDomain.setStatus(TaskDomain.STATUS_FAILED);
                taskDomain.setFinishedAt(LocalDateTime.now());
                taskDomainRepository.save(taskDomain);
            }
        }
        
        log.info("爬虫已停止, 任务: {}", taskId);
    }
    
    /**
     * 停止单个进程
     */
    private void stopProcess(Process process, String identifier) {
        // 先尝试优雅停止
        process.destroy();
        
        try {
            // 等待 5 秒
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                // 强制终止
                process.destroyForcibly();
                log.warn("强制终止爬虫进程: {}", identifier);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 通过 PID 终止进程
     */
    private void killProcessByPid(Integer pid) {
        if (pid == null) return;
        
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("taskkill", "/F", "/PID", pid.toString());
            } else {
                pb = new ProcessBuilder("kill", "-9", pid.toString());
            }
            pb.start().waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("通过 PID 终止进程失败: {}", pid, e);
        }
    }
    
    /**
     * 读取发现结果（子域名）
     * 文件格式: {taskOutputDir}/{domain}/{domain}/discovery.json
     */
    public Map<String, Object> readDiscoveryResult(String taskId, String domain) {
        String safeDomain = sanitizeDomainForPath(domain);
        log.debug("读取 discovery 结果, taskId: {}, domain: {}", taskId, domain);
        
        // 从任务输出目录读取
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task != null && task.getOutputDir() != null) {
            // 目录结构: {outputDir}/{domain}/{domain}/discovery.json
            Path filePath = Paths.get(task.getOutputDir(), safeDomain, safeDomain, "discovery.json");
            if (filePath.toFile().exists()) {
                Map<String, Object> result = readJsonFile(filePath);
                log.debug("读取 discovery 成功: {}, 数据量: {}", filePath, result.size());
                return result;
            }
        }
        
        // 回退到任务基础目录
        Path taskBaseFile = Paths.get(taskOutputBasePath, taskId, safeDomain, safeDomain, "discovery.json");
        if (taskBaseFile.toFile().exists()) {
            Map<String, Object> result = readJsonFile(taskBaseFile);
            log.debug("从任务基础目录读取 discovery: {}", taskBaseFile);
            return result;
        }
        
        log.warn("discovery.json 不存在, taskId: {}, domain: {}", taskId, domain);
        return Collections.emptyMap();
    }
    
    /**
     * 读取爬取结果
     * 文件格式: {taskOutputDir}/{domain}/{domain}/crawl.json
     */
    public Map<String, Object> readCrawlResult(String taskId, String domain) {
        String safeDomain = sanitizeDomainForPath(domain);
        log.debug("读取 crawl 结果, taskId: {}, domain: {}", taskId, domain);
        
        // 从任务输出目录读取
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task != null && task.getOutputDir() != null) {
            // 目录结构: {outputDir}/{domain}/{domain}/crawl.json
            Path filePath = Paths.get(task.getOutputDir(), safeDomain, safeDomain, "crawl.json");
            if (filePath.toFile().exists()) {
                Map<String, Object> result = readJsonFile(filePath);
                log.debug("读取 crawl 成功: {}, 数据量: {}", filePath, result.size());
                return result;
            }
        }
        
        // 回退到任务基础目录
        Path taskBaseFile = Paths.get(taskOutputBasePath, taskId, safeDomain, safeDomain, "crawl.json");
        if (taskBaseFile.toFile().exists()) {
            Map<String, Object> result = readJsonFile(taskBaseFile);
            log.debug("从任务基础目录读取 crawl: {}", taskBaseFile);
            return result;
        }
        
        log.warn("crawl.json 不存在, taskId: {}, domain: {}", taskId, domain);
        return Collections.emptyMap();
    }
    
    /**
     * 读取 JSON 数组文件
     */
    private List<Map<String, Object>> readJsonArrayFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }
        
        try {
            String content = Files.readString(filePath);
            if (content.trim().isEmpty()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(content, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            log.error("读取 JSON 数组文件失败: {}", filePath, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 提取登录页面（从爬取结果中过滤）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractLoginPages(String taskId, String domain) {
        Map<String, Object> crawlResult = readCrawlResult(taskId, domain);
        
        List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawlResult.get("visited_pages");
        if (visitedPages == null) {
            return Collections.emptyList();
        }
        
        List<Map<String, Object>> loginPages = new ArrayList<>();
        for (Map<String, Object> page : visitedPages) {
            if (isLoginPage(page.get("is_login_page"))) {
                loginPages.add(page);
            }
        }
        
        return loginPages;
    }
    
    
    /**
     * 获取任务的实时日志
     * @param taskId 任务ID
     * @param fromLine 从第几行开始读取（0表示从头开始）
     * @param maxLines 最多读取多少行（-1表示全部）
     * @return 日志内容和总行数
     */
    public Map<String, Object> getTaskLogs(String taskId, int fromLine, int maxLines) {
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("fromLine", fromLine);
        result.put("lines", Collections.emptyList());
        result.put("totalLines", 0);
        result.put("hasMore", false);
        
        // 从任务记录获取输出目录
        Task task = taskRepository.findById(taskId).orElse(null);
        Path logFile = null;
        
        if (task != null && task.getOutputDir() != null) {
            logFile = Paths.get(task.getOutputDir(), LOG_FILE_NAME);
        } else {
            // 尝试从缓存获取
            logFile = logFilePaths.get(taskId);
        }
        
        if (logFile == null || !Files.exists(logFile)) {
            return result;
        }
        
        try {
            List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int totalLines = allLines.size();
            result.put("totalLines", totalLines);
            
            // 从指定行开始读取
            int startLine = Math.max(0, fromLine);
            int endLine = maxLines > 0 ? Math.min(startLine + maxLines, totalLines) : totalLines;
            
            if (startLine < totalLines) {
                List<String> lines = allLines.subList(startLine, endLine);
                result.put("lines", lines);
                result.put("hasMore", endLine < totalLines);
            }
            
            return result;
        } catch (IOException e) {
            log.error("读取日志文件失败: {}", logFile, e);
            return result;
        }
    }
    
    /**
     * 获取最新的日志（从末尾读取）
     */
    public Map<String, Object> getLatestLogs(String taskId, int lines) {
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("lines", Collections.emptyList());
        result.put("totalLines", 0);
        
        Task task = taskRepository.findById(taskId).orElse(null);
        Path logFile = null;
        
        if (task != null && task.getOutputDir() != null) {
            logFile = Paths.get(task.getOutputDir(), LOG_FILE_NAME);
        } else {
            logFile = logFilePaths.get(taskId);
        }
        
        if (logFile == null || !Files.exists(logFile)) {
            return result;
        }
        
        try {
            List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int totalLines = allLines.size();
            result.put("totalLines", totalLines);
            
            // 从末尾读取指定行数
            int startLine = Math.max(0, totalLines - lines);
            result.put("lines", allLines.subList(startLine, totalLines));
            result.put("fromLine", startLine);
            
            return result;
        } catch (IOException e) {
            log.error("读取日志文件失败: {}", logFile, e);
            return result;
        }
    }
    
    /**
     * 检查任务是否有正在运行的进程
     */
    public boolean isProcessRunning(String taskId) {
        // 检查是否有域名进程在运行
        return domainProcesses.entrySet().stream()
            .filter(e -> e.getKey().startsWith(taskId + "_"))
            .anyMatch(e -> e.getValue().isAlive());
    }
    
    /**
     * 启动文件监控
     */
    private void startFileWatcher() {
        watching = true;
        watcherThread = new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Map<WatchKey, Path> watchKeyPathMap = new HashMap<>();
                
                // 监控任务输出根目录及其子目录
                Path taskOutputPath = Paths.get(taskOutputBasePath);
                if (Files.exists(taskOutputPath)) {
                    // 注册根目录（用于监控新任务目录的创建）
                    WatchKey rootKey = taskOutputPath.register(watchService, 
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                    watchKeyPathMap.put(rootKey, taskOutputPath);
                    log.info("文件监控已启动: {}", taskOutputBasePath);
                    
                    // 注册已存在的任务子目录
                    try {
                        Files.list(taskOutputPath)
                            .filter(Files::isDirectory)
                            .forEach(dir -> {
                                try {
                                    WatchKey key = dir.register(watchService,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_MODIFY);
                                    watchKeyPathMap.put(key, dir);
                                    log.debug("监控任务目录: {}", dir);
                                } catch (IOException e) {
                                    log.warn("无法监控目录: {}", dir, e);
                                }
                            });
                    } catch (IOException e) {
                        log.warn("无法列出任务目录", e);
                    }
                }
                
                while (watching) {
                    WatchKey key;
                    try {
                        key = watchService.poll(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    if (key == null) continue;
                    
                    Path watchedDir = watchKeyPathMap.get(key);
                    if (watchedDir == null) {
                        key.reset();
                        continue;
                    }
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        Path fullPath = watchedDir.resolve(changed);
                        
                        // 如果是新创建的目录（可能是新任务或域名），注册监控
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE 
                            && Files.isDirectory(fullPath)) {
                            try {
                                WatchKey newKey = fullPath.register(watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY);
                                watchKeyPathMap.put(newKey, fullPath);
                                log.debug("新增监控目录: {}", fullPath);
                            } catch (IOException e) {
                                log.warn("无法监控新目录: {}", fullPath, e);
                            }
                        }
                        
                        // 处理 summary.json 变化
                        String fileName = changed.toString();
                        if (fileName.equals("summary.json")) {
                            handleSummaryChange(fullPath);
                        }
                        
                        // 处理 crawl.json 或 discovery.json 变化
                        if (fileName.equals("crawl.json") || fileName.equals("discovery.json")) {
                            handleResultFileChange(watchedDir, fullPath, fileName);
                        }
                    }
                    
                    key.reset();
                }
                
            } catch (IOException e) {
                log.error("文件监控启动失败", e);
            }
        }, "file-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }
    
    /**
     * 处理结果文件变化（用于实时更新）
     * 新版目录结构: {taskOutputDir}/{domain}/crawl.json 或 discovery.json
     */
    private void handleResultFileChange(Path watchedDir, Path filePath, String fileName) {
        try {
            // watchedDir 是域名目录，其父目录是任务目录
            Path taskDir = watchedDir.getParent();
            if (taskDir == null) return;
            
            String taskId = taskDir.getFileName().toString();
            String domain = watchedDir.getFileName().toString();
            
            // 验证任务是否存在且正在运行
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !task.isRunning()) {
                return;
            }
            
            // 更新域名进度
            TaskDomain taskDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain).orElse(null);
            if (taskDomain == null) {
                return;
            }
            
            // 根据文件类型更新状态
            if (fileName.equals("crawl.json")) {
                Map<String, Object> crawl = readJsonFile(filePath);
                if (!crawl.isEmpty()) {
                    updateDomainFromCrawlFile(taskDomain, crawl);
                }
            } else if (fileName.equals("discovery.json")) {
                Map<String, Object> discovery = readJsonFile(filePath);
                if (!discovery.isEmpty()) {
                    updateDomainFromDiscoveryFile(taskDomain, discovery);
                }
            }
            
        } catch (Exception e) {
            log.debug("处理结果文件变化失败: {}", filePath, e);
        }
    }
    
    /**
     * 从 crawl.json 更新域名状态
     */
    @SuppressWarnings("unchecked")
    private void updateDomainFromCrawlFile(TaskDomain taskDomain, Map<String, Object> crawl) {
        Map<String, Object> stats = (Map<String, Object>) crawl.get("statistics");
        if (stats != null) {
            taskDomain.setVisitedCount(getIntValue(stats, "total_visited"));
            taskDomain.setFailedCount(getIntValue(stats, "total_failed"));
        }
        
        Map<String, Object> metadata = (Map<String, Object>) crawl.get("metadata");
        if (metadata != null) {
            String landingUrl = (String) metadata.get("start_url");
            if (landingUrl != null) {
                taskDomain.setLandingUrl(landingUrl);
            }
        }
        
        // 计算登录页数量
        List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawl.get("visited_pages");
        if (visitedPages != null) {
            int loginCount = 0;
            for (Map<String, Object> page : visitedPages) {
                if (isLoginPage(page.get("is_login_page"))) {
                    loginCount++;
                }
            }
            taskDomain.setLoginCount(loginCount);
            
            if (visitedPages.size() > 0) {
                taskDomain.setStatus(TaskDomain.STATUS_CRAWLING);
            }
        }
        
        // 检查是否完成（metadata.finished_at 不为空）
        if (metadata != null && metadata.get("finished_at") != null) {
            taskDomain.setStatus(TaskDomain.STATUS_COMPLETED);
        }
        
        taskDomainRepository.save(taskDomain);
    }
    
    /**
     * 从 discovery.json 更新域名状态
     */
    @SuppressWarnings("unchecked")
    private void updateDomainFromDiscoveryFile(TaskDomain taskDomain, Map<String, Object> discovery) {
        List<?> subdomains = (List<?>) discovery.get("subdomains");
        if (subdomains != null) {
            taskDomain.setSubdomainCount(subdomains.size());
        }
        
        List<?> urls = (List<?>) discovery.get("urls");
        if (urls != null) {
            taskDomain.setUrlCount(urls.size());
        }
        
        // 如果当前是 pending，更新为 discovering
        if (TaskDomain.STATUS_PENDING.equals(taskDomain.getStatus())) {
            taskDomain.setStatus(TaskDomain.STATUS_DISCOVERING);
        }
        
        taskDomainRepository.save(taskDomain);
    }
    
    /**
     * 处理 summary.json 变化
     */
    @SuppressWarnings("unchecked")
    private void handleSummaryChange(Path summaryPath) {
        try {
            // 从路径提取任务ID
            String taskId = summaryPath.getParent().getFileName().toString();
            
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !task.isRunning()) return;
            
            // 读取 summary.json
            Map<String, Object> summary = readJsonFile(summaryPath);
            if (summary.isEmpty()) return;
            
            // 更新各域名结果
            List<Map<String, Object>> domainResults = (List<Map<String, Object>>) summary.get("domains");
            if (domainResults != null) {
                int completed = 0;
                for (Map<String, Object> result : domainResults) {
                    String domain = (String) result.get("domain");
                    updateDomainFromSummary(taskId, domain, result);
                    
                    // 检查是否完成
                    Map<String, Object> crawl = (Map<String, Object>) result.get("crawl");
                    if (crawl != null && crawl.get("total_visited") != null) {
                        completed++;
                    }
                }
                
                // 更新任务进度
                task.setCompletedDomains(completed);
                if (completed >= task.getTotalDomains()) {
                    task.setStatus(Task.STATUS_COMPLETED);
                    task.setFinishedAt(LocalDateTime.now());
                }
                taskRepository.save(task);
            }
            
        } catch (Exception e) {
            log.error("处理 summary 变化失败: {}", summaryPath, e);
        }
    }
    
    /**
     * 从 summary 更新域名结果
     */
    @SuppressWarnings("unchecked")
    private void updateDomainFromSummary(String taskId, String domain, Map<String, Object> result) {
        TaskDomain taskDomain = taskDomainRepository.findByTaskIdAndDomain(taskId, domain)
            .orElse(null);
        if (taskDomain == null) return;
        
        // 更新 landing URL
        String landingUrl = (String) result.get("landing_url");
        if (landingUrl != null) {
            taskDomain.setLandingUrl(landingUrl);
        }
        
        // 更新 discovery 结果
        Map<String, Object> discovery = (Map<String, Object>) result.get("discovery");
        if (discovery != null) {
            taskDomain.setSubdomainCount(getIntValue(discovery, "subdomain_count"));
            taskDomain.setUrlCount(getIntValue(discovery, "url_count"));
            taskDomain.setDiscoveryOutput((String) discovery.get("output"));
            taskDomain.setStatus(TaskDomain.STATUS_DISCOVERING);
        }
        
        // 更新 crawl 结果
        Map<String, Object> crawl = (Map<String, Object>) result.get("crawl");
        if (crawl != null) {
            taskDomain.setVisitedCount(getIntValue(crawl, "total_visited"));
            taskDomain.setFailedCount(getIntValue(crawl, "total_failed"));
            taskDomain.setCrawlOutput((String) crawl.get("output"));
            
            if (getIntValue(crawl, "total_visited") > 0) {
                taskDomain.setStatus(TaskDomain.STATUS_COMPLETED);
            } else {
                taskDomain.setStatus(TaskDomain.STATUS_CRAWLING);
            }
        }
        
        taskDomainRepository.save(taskDomain);
    }
    
    /**
     * 读取 JSON 文件
     */
    private Map<String, Object> readJsonFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return Collections.emptyMap();
        }
        
        try {
            String content = Files.readString(filePath);
            if (content.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.error("读取 JSON 文件失败: {}", filePath, e);
            return Collections.emptyMap();
        }
    }
    
    
    /**
     * 安全获取整数值
     */
    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
    
    /**
     * 判断页面是否为登录页面
     * 兼容新旧两种 Python 输出格式:
     * - 旧版: is_login_page 为空字符串(非登录) 或 描述文字(登录)
     * - 新版: is_login_page 为 "NO"(非登录) 或 "YES"(登录)
     */
    public static boolean isLoginPage(Object isLoginPageValue) {
        if (isLoginPageValue == null) return false;
        String val = isLoginPageValue.toString().trim();
        if (val.isEmpty()) return false;
        if ("NO".equalsIgnoreCase(val) || "FALSE".equalsIgnoreCase(val)) return false;
        return true;
    }
    
    /**
     * 清理任务的输出目录
     * @param taskId 任务ID
     * @return 是否成功清理
     */
    public boolean cleanupTaskOutput(String taskId) {
        try {
            // 1. 从任务记录获取输出目录
            Task task = taskRepository.findById(taskId).orElse(null);
            Path outputDir = null;
            
            if (task != null && task.getOutputDir() != null) {
                outputDir = Paths.get(task.getOutputDir());
            } else {
                // 尝试默认路径
                outputDir = Paths.get(taskOutputBasePath, taskId);
            }
            
            // 2. 删除输出目录
            if (outputDir != null && Files.exists(outputDir)) {
                deleteDirectoryRecursively(outputDir);
                log.info("已清理任务输出目录: {}", outputDir);
            }
            
            // 3. 清理日志文件路径缓存
            logFilePaths.remove(taskId);
            
            return true;
        } catch (Exception e) {
            log.error("清理任务输出目录失败: {}", taskId, e);
            return false;
        }
    }
    
    /**
     * 递归删除目录
     */
    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("删除文件失败: {}", path, e);
                }
            });
    }
    
    /**
     * 获取任务输出目录大小（字节）
     */
    public long getTaskOutputSize(String taskId) {
        try {
            Task task = taskRepository.findById(taskId).orElse(null);
            Path outputDir = null;
            
            if (task != null && task.getOutputDir() != null) {
                outputDir = Paths.get(task.getOutputDir());
            } else {
                outputDir = Paths.get(taskOutputBasePath, taskId);
            }
            
            if (outputDir != null && Files.exists(outputDir)) {
                return Files.walk(outputDir)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            }
        } catch (Exception e) {
            log.warn("获取任务输出目录大小失败: {}", taskId, e);
        }
        return 0;
    }
    
    /**
     * 手动同步任务进度（从文件读取并更新数据库）
     */
    @SuppressWarnings("unchecked")
    public void syncTaskProgress(Task task) {
        String taskId = task.getId();
        log.info("同步任务进度: {}", taskId);
        
        // 获取所有域名
        List<TaskDomain> taskDomains = taskDomainRepository.findByTaskId(taskId);
        
        // 检查是否有域名进程仍在运行
        boolean processRunning = domainProcesses.entrySet().stream()
            .filter(e -> e.getKey().startsWith(taskId + "_"))
            .anyMatch(e -> e.getValue().isAlive());
        
        int completedCount = 0;
        int failedCount = 0;
        int runningCount = 0;
        
        for (TaskDomain taskDomain : taskDomains) {
            String domain = taskDomain.getDomain();
            String currentStatus = taskDomain.getStatus();
            
            // 重要：如果域名已经是终态（completed 或 failed），不要覆盖状态
            boolean isTerminalState = TaskDomain.STATUS_COMPLETED.equals(currentStatus) 
                                   || TaskDomain.STATUS_FAILED.equals(currentStatus);
            
            try {
                // 读取 discovery 结果
                Map<String, Object> discovery = readDiscoveryResult(taskId, domain);
                if (!discovery.isEmpty()) {
                    List<?> subdomains = (List<?>) discovery.get("subdomains");
                    if (subdomains != null) {
                        taskDomain.setSubdomainCount(subdomains.size());
                    }
                    List<?> urls = (List<?>) discovery.get("urls");
                    if (urls != null) {
                        taskDomain.setUrlCount(urls.size());
                    }
                    // 只有非终态才更新状态
                    if (!isTerminalState && TaskDomain.STATUS_PENDING.equals(currentStatus)) {
                        taskDomain.setStatus(TaskDomain.STATUS_DISCOVERING);
                    }
                }
                
                // 读取 crawl 结果
                Map<String, Object> crawl = readCrawlResult(taskId, domain);
                if (!crawl.isEmpty()) {
                    Map<String, Object> metadata = (Map<String, Object>) crawl.get("metadata");
                    boolean crawlFinished = false;
                    if (metadata != null) {
                        String landingUrl = (String) metadata.get("start_url");
                        if (landingUrl != null) {
                            taskDomain.setLandingUrl(landingUrl);
                        }
                        // 检查 finished_at 字段判断爬取是否完成
                        Object finishedAt = metadata.get("finished_at");
                        crawlFinished = finishedAt != null && !finishedAt.toString().trim().isEmpty();
                    }
                    
                    Map<String, Object> stats = (Map<String, Object>) crawl.get("statistics");
                    if (stats != null) {
                        taskDomain.setVisitedCount(getIntValue(stats, "total_visited"));
                        taskDomain.setFailedCount(getIntValue(stats, "total_failed"));
                    }
                    
                    // 计算登录页数量
                    List<Map<String, Object>> visitedPages = (List<Map<String, Object>>) crawl.get("visited_pages");
                    if (visitedPages != null) {
                        int loginCount = 0;
                        for (Map<String, Object> page : visitedPages) {
                            if (isLoginPage(page.get("is_login_page"))) {
                                loginCount++;
                            }
                        }
                        taskDomain.setLoginCount(loginCount);
                    }
                    
                    // 只有非终态才更新状态
                    if (!isTerminalState) {
                        if (crawlFinished) {
                            taskDomain.setStatus(TaskDomain.STATUS_COMPLETED);
                            if (taskDomain.getFinishedAt() == null) {
                                taskDomain.setFinishedAt(LocalDateTime.now());
                            }
                        } else {
                            // 有 crawl 数据但未完成，说明在爬取中
                            taskDomain.setStatus(TaskDomain.STATUS_CRAWLING);
                        }
                    }
                }
                
                taskDomainRepository.save(taskDomain);
                
            } catch (Exception e) {
                log.warn("同步域名进度失败: {} - {}", taskId, domain, e);
            }
            
            // 重新获取最新状态进行统计
            String finalStatus = taskDomain.getStatus();
            if (TaskDomain.STATUS_COMPLETED.equals(finalStatus)) {
                completedCount++;
            } else if (TaskDomain.STATUS_FAILED.equals(finalStatus)) {
                failedCount++;
            } else if (TaskDomain.STATUS_CRAWLING.equals(finalStatus) 
                    || TaskDomain.STATUS_DISCOVERING.equals(finalStatus)) {
                runningCount++;
            }
        }
        
        // 更新任务进度
        task.setCompletedDomains(completedCount);
        
        // 更新任务状态
        String oldStatus = task.getStatus();
        if (Task.STATUS_PENDING.equals(oldStatus)) {
            // 如果任务是 pending 状态，但有域名在运行或有结果，更新为 running
            if (processRunning || runningCount > 0 || completedCount > 0 || failedCount > 0) {
                task.setStatus(Task.STATUS_RUNNING);
                if (task.getStartedAt() == null) {
                    task.setStartedAt(LocalDateTime.now());
                }
                log.info("任务状态从 PENDING 更新为 RUNNING: {}", taskId);
            }
        }
        
        // 判断任务是否完成：所有域名都达到终态且进程不再运行
        boolean allDomainsFinished = (completedCount + failedCount) >= task.getTotalDomains();
        boolean noRunningDomains = runningCount == 0;
        
        log.info("任务完成判断: taskId={}, 完成={}, 失败={}, 运行中={}, 总数={}, 进程运行={}", 
            taskId, completedCount, failedCount, runningCount, task.getTotalDomains(), processRunning);
        
        // 只有当所有域名完成且进程不再运行时才标记任务完成
        if (allDomainsFinished && !processRunning && noRunningDomains) {
            if (completedCount == 0 && failedCount > 0) {
                task.setStatus(Task.STATUS_FAILED);
            } else {
                task.setStatus(Task.STATUS_COMPLETED);
            }
            if (task.getFinishedAt() == null) {
                task.setFinishedAt(LocalDateTime.now());
            }
            log.info("任务状态更新为 {}: {}", task.getStatus(), taskId);
        } else if (Task.STATUS_RUNNING.equals(task.getStatus()) && !processRunning && noRunningDomains && allDomainsFinished) {
            // 进程已经不在运行，但任务状态还是 running，强制更新
            if (completedCount == 0 && failedCount > 0) {
                task.setStatus(Task.STATUS_FAILED);
            } else {
                task.setStatus(Task.STATUS_COMPLETED);
            }
            if (task.getFinishedAt() == null) {
                task.setFinishedAt(LocalDateTime.now());
            }
            log.info("强制更新任务状态为 {}: {}", task.getStatus(), taskId);
        }
        
        taskRepository.save(task);
        
        log.info("任务进度同步完成: {}, 状态: {}, 完成 {}/{}, 失败 {}, 运行中 {}, 进程运行: {}", 
            taskId, task.getStatus(), completedCount, task.getTotalDomains(), failedCount, runningCount, processRunning);
    }
}
