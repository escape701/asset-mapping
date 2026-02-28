package com.example.service;

import com.example.dto.TaskResponse;
import com.example.entity.Task;
import com.example.entity.TaskDomain;
import com.example.repository.TaskDomainRepository;
import com.example.repository.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
            Path filePath = Paths.get(task.getOutputDir(), safeDomain, "discovery.json");
            if (filePath.toFile().exists()) {
                Map<String, Object> result = readJsonFile(filePath);
                log.debug("读取 discovery 成功: {}, 数据量: {}", filePath, result.size());
                return result;
            }
        }

        // 回退到任务基础目录
        Path taskBaseFile = Paths.get(taskOutputBasePath, taskId, safeDomain, "discovery.json");
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
            Path filePath = Paths.get(task.getOutputDir(), safeDomain, "crawl.json");
            if (filePath.toFile().exists()) {
                Map<String, Object> result = readJsonFile(filePath);
                log.debug("读取 crawl 成功: {}, 数据量: {}", filePath, result.size());
                return result;
            }
        }

        // 回退到任务基础目录
        Path taskBaseFile = Paths.get(taskOutputBasePath, taskId, safeDomain, "crawl.json");
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
     * summary.json 是域名结果数组: [{domain, landing_url, discovery, crawl}, ...]
     */
    @SuppressWarnings("unchecked")
    private void handleSummaryChange(Path summaryPath) {
        try {
            // 从路径提取任务ID
            String taskId = summaryPath.getParent().getFileName().toString();

            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !task.isRunning()) return;

            // summary.json 是一个 JSON 数组
            List<Map<String, Object>> domainResults = readJsonArrayFile(summaryPath);
            if (domainResults.isEmpty()) return;

            int completed = 0;
            for (Map<String, Object> result : domainResults) {
                String domain = (String) result.get("domain");
                updateDomainFromSummary(taskId, domain, result);

                Map<String, Object> crawl = (Map<String, Object>) result.get("crawl");
                if (crawl != null && crawl.get("total_visited") != null) {
                    completed++;
                }
            }

            task.setCompletedDomains(completed);
            if (completed >= task.getTotalDomains()) {
                task.setStatus(Task.STATUS_COMPLETED);
                task.setFinishedAt(LocalDateTime.now());
            }
            taskRepository.save(task);

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
                            // finished_at 为 null: 检查进程是否仍在运行
                            String domainKey = taskId + "_" + domain;
                            Process domainProcess = domainProcesses.get(domainKey);
                            boolean domainProcessRunning = domainProcess != null && domainProcess.isAlive();

                            if (domainProcessRunning) {
                                taskDomain.setStatus(TaskDomain.STATUS_CRAWLING);
                            } else {
                                // 进程不在了但 finished_at 为 null，说明被中断或异常退出
                                // 有数据则标记为完成，无数据则标记为失败
                                int visited = getIntValue(stats != null ? stats : Collections.emptyMap(), "total_visited");
                                if (visited > 0) {
                                    taskDomain.setStatus(TaskDomain.STATUS_COMPLETED);
                                    if (taskDomain.getFinishedAt() == null) {
                                        taskDomain.setFinishedAt(LocalDateTime.now());
                                    }
                                } else {
                                    taskDomain.setStatus(TaskDomain.STATUS_FAILED);
                                    if (taskDomain.getFinishedAt() == null) {
                                        taskDomain.setFinishedAt(LocalDateTime.now());
                                    }
                                }
                            }
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

    /**
     * 根据 crawl.json 中记录的 screenshot_path 解析出磁盘上的实际文件。
     * 解析策略与 ScreenshotController 保持一致。
     */
    public File resolveScreenshotFile(String screenshotPath, String taskId) {
        if (screenshotPath == null || screenshotPath.isBlank()) return null;
        String norm = screenshotPath.replace("\\", "/");
        File f;
        try {
            if (Paths.get(norm).isAbsolute()) {
                f = new File(norm);
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}
        f = Paths.get(taskOutputBasePath, norm).toFile();
        if (f.exists()) return f;
        f = Paths.get(crawlerProjectPath, norm).toFile();
        if (f.exists()) return f;
        if (taskId != null && !taskId.isBlank()) {
            String filename = Paths.get(norm).getFileName().toString();
            Path taskDir = Paths.get(taskOutputBasePath, taskId);
            if (Files.isDirectory(taskDir)) {
                try {
                    var found = new File[1];
                    Files.walkFileTree(taskDir, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (file.getFileName().toString().equals(filename)) { found[0] = file.toFile(); return FileVisitResult.TERMINATE; }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
                    });
                    if (found[0] != null) return found[0];
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * 收集任务所有域名中 visited_pages 里的截图文件。
     * 返回 Map: zipEntryName -> File  (如 "screenshots/abc.png" -> File)
     */
    @SuppressWarnings("unchecked")
    public Map<String, File> collectScreenshots(TaskResponse task) {
        Map<String, File> map = new LinkedHashMap<>();
        if (task.getDomains() == null) return map;
        String taskId = task.getTaskId();
        for (TaskResponse.DomainInfo di : task.getDomains()) {
            Map<String, Object> crawl = di.getCrawl();
            if (crawl == null) continue;
            List<Map<String, Object>> pages = (List<Map<String, Object>>) crawl.get("visited_pages");
            if (pages == null) continue;
            for (Map<String, Object> page : pages) {
                addScreenshot(map, (String) page.get("screenshot_path"), taskId);
                Object popups = page.get("popup_login_screenshot_path");
                if (popups instanceof List) {
                    for (Object p : (List<?>) popups) {
                        if (p instanceof String) addScreenshot(map, (String) p, taskId);
                    }
                }
            }
        }
        return map;
    }

    private void addScreenshot(Map<String, File> map, String path, String taskId) {
        if (path == null || path.isBlank()) return;
        File f = resolveScreenshotFile(path, taskId);
        if (f != null && f.exists()) {
            map.put("screenshots/" + f.getName(), f);
        }
    }

    /**
     * 生成导出用的自包含 HTML 报告 —— 客户端渲染版本
     * 数据以 JSON 嵌入，DOM 懒加载 + 分页，支持 TOC / 折叠 / 过滤
     */
    public String generateExportHtml(TaskResponse task) {
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new RuntimeException("序列化任务数据失败", e);
        }

        StringBuilder sb = new StringBuilder(dataJson.length() + 40_000);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>资产测绘报告 - ").append(escHtml(task.getTaskId())).append("</title>");
        sb.append("<style>");
        appendReportCss(sb);
        sb.append("</style></head><body>");
        sb.append("<div class=\"page-layout\">");
        sb.append("<nav class=\"toc\" id=\"toc\"></nav>");
        sb.append("<div class=\"main-content\" id=\"mainContent\"></div>");
        sb.append("</div>");
        sb.append("<script>");
        sb.append("var DATA=");
        sb.append(dataJson.replace("</", "<\\/"));
        sb.append(";\n");
        appendReportJs(sb);
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /* ==================== CSS ==================== */
    private static void appendReportCss(StringBuilder sb) {
        String[] lines = {
                "*{margin:0;padding:0;box-sizing:border-box}",
                "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f2f5;color:#1e293b;line-height:1.6}",
                ".page-layout{display:grid;grid-template-columns:260px 1fr;min-height:100vh}",

                ".toc{position:sticky;top:0;height:100vh;overflow-y:auto;background:#fff;border-right:1px solid #e5e7eb;padding:20px 0;font-size:13px;z-index:10}",
                ".toc h3{padding:12px 20px;font-size:14px;color:#6b7280;text-transform:uppercase;letter-spacing:.5px}",
                ".toc a{display:block;padding:8px 20px;color:#374151;text-decoration:none;border-left:3px solid transparent;transition:all .15s}",
                ".toc a:hover{background:#f8fafc;color:#1d4ed8}",
                ".toc a.active{background:#eff6ff;color:#1d4ed8;border-left-color:#2477e9;font-weight:600}",
                ".toc-count{margin-left:6px;font-size:11px;color:#9ca3af;font-weight:400}",

                ".main-content{padding:32px;width:100%}",

                ".report-header{background:linear-gradient(135deg,#033f93,#1e40af);color:#fff;padding:32px;border-radius:16px;margin-bottom:28px}",
                ".report-header h1{font-size:24px;margin-bottom:8px}",
                ".report-header .meta{display:flex;flex-wrap:wrap;gap:24px;font-size:14px;opacity:.85;margin-top:12px}",
                ".report-header .meta span{display:flex;align-items:center;gap:6px}",
                ".status-badge{display:inline-block;padding:4px 12px;border-radius:12px;font-size:12px;font-weight:600}",
                ".status-completed{background:#d1fae5;color:#065f46}.status-running{background:#dbeafe;color:#1e40af}",
                ".status-failed{background:#fee2e2;color:#991b1b}.status-pending{background:#fef3c7;color:#92400e}.status-stopped{background:#fef3c7;color:#92400e}",

                ".summary-cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:16px;margin-bottom:32px}",
                ".card{background:#fff;border-radius:12px;padding:20px;text-align:center;border:1px solid #e5e7eb;box-shadow:0 1px 4px rgba(0,0,0,.04)}",
                ".card-value{font-size:28px;font-weight:700;color:#2477e9}.card-label{font-size:13px;color:#6b7280;margin-top:4px}",

                ".domain-section{background:#fff;border-radius:12px;border:1px solid #e5e7eb;margin-bottom:32px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.04);transition:border-color .2s,box-shadow .2s}",
                ".domain-section.active-domain{border-color:#2477e9;box-shadow:0 4px 16px rgba(36,119,233,.12)}",
                ".domain-section.active-domain .domain-header{background:linear-gradient(135deg,#eff6ff,#dbeafe);border-bottom-color:#bfdbfe}",
                ".domain-section.active-domain .domain-header h2{color:#1d4ed8}",
                ".domain-header{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;background:linear-gradient(135deg,#f8fafc,#f0f4f8);border-bottom:1px solid #e5e7eb;cursor:pointer;user-select:none}",
                ".domain-header h2{font-size:17px;color:#1e293b;font-weight:700;transition:color .2s}",
                ".domain-header .stats{display:flex;gap:16px;font-size:13px;color:#6b7280;align-items:center}",
                ".domain-body{padding:24px}",
                ".collapse-toggle{font-size:18px;color:#9ca3af;transition:transform .2s;user-select:none;margin-left:4px}",
                ".collapsed .domain-body{display:none}",
                ".collapsed .collapse-toggle{transform:rotate(-90deg)}",

                ".sec-header{display:flex;align-items:center;gap:8px;padding:12px 16px;margin:16px 0 0;background:#f8fafc;border:1px solid #e5e7eb;border-radius:8px;cursor:pointer;user-select:none;font-size:14px;font-weight:600;color:#374151;transition:background .15s}",
                ".sec-header:first-child{margin-top:0}",
                ".sec-header:hover{background:#f0f4f8}",
                ".sec-arrow{font-size:12px;color:#9ca3af;transition:transform .15s}",
                ".sec-content{display:none;margin-top:8px}",
                ".sec-content.open{display:block}",

                ".sub-list{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:8px;padding:8px 0}",
                ".sub-item{padding:8px 14px;background:#f8fafc;border:1px solid #e5e7eb;border-radius:8px;font-size:13px;color:#334155;word-break:break-all}",

                ".login-grid{display:grid;gap:16px;padding:8px 0}",
                ".login-card{border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;background:#fff}",
                ".login-card-body{padding:16px}",
                ".login-card-url{font-size:13px;color:#2477e9;word-break:break-all;margin-bottom:8px}",
                ".login-card-url a{color:#2477e9;text-decoration:none}.login-card-url a:hover{text-decoration:underline}",
                ".login-card-title{font-size:14px;font-weight:600;color:#1e293b;margin-bottom:8px}",
                ".login-card-tags{display:flex;flex-wrap:wrap;gap:6px}",
                ".tag{padding:3px 10px;border-radius:12px;font-size:11px;font-weight:600}",
                ".tag-conf-high{background:#d1fae5;color:#065f46}.tag-conf-medium{background:#fef3c7;color:#92400e}.tag-conf-low{background:#fee2e2;color:#991b1b}",
                ".tag-auth{background:#dbeafe;color:#1e40af}.tag-mfa-yes{background:#d1fae5;color:#065f46}.tag-mfa-no{background:#fee2e2;color:#991b1b}.tag-unknown{background:#f3f4f6;color:#9ca3af}",

                ".login-card-screenshots{padding:0 16px 12px;display:flex;flex-wrap:wrap;gap:8px}",
                ".login-card-screenshots .thumb{width:200px;height:130px;object-fit:cover;border-radius:6px;border:1px solid #e5e7eb;cursor:pointer;transition:box-shadow .15s}",
                ".login-card-screenshots .thumb:hover{box-shadow:0 2px 8px rgba(0,0,0,.15)}",
                ".login-card-screenshots .thumb-label{font-size:11px;color:#6b7280;text-align:center;margin-top:2px}",
                ".ss-modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.8);backdrop-filter:blur(4px);z-index:9999;justify-content:center;align-items:center}",
                ".ss-modal.show{display:flex}",
                ".ss-modal-content{background:#fff;border-radius:16px;max-width:90vw;max-height:90vh;overflow:hidden;animation:ssZoom .3s ease}",
                "@keyframes ssZoom{from{transform:scale(.8);opacity:0}to{transform:scale(1);opacity:1}}",
                ".ss-modal-header{display:flex;justify-content:space-between;align-items:center;padding:14px 20px;border-bottom:1px solid #e5e7eb}",
                ".ss-modal-header h3{font-size:16px;font-weight:600;color:#1f2937;margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:50vw}",
                ".ss-modal-nav{display:flex;align-items:center;gap:8px}",
                ".ss-nav-btn{background:#e5e7eb;border:none;border-radius:6px;width:32px;height:32px;font-size:18px;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:background .15s}",
                ".ss-nav-btn:hover{background:#d1d5db}",
                ".ss-nav-label{font-size:13px;color:#6b7280;white-space:nowrap}",
                ".ss-close{font-size:24px;color:#9ca3af;cursor:pointer;width:32px;height:32px;display:flex;align-items:center;justify-content:center;border-radius:8px;border:none;background:none;transition:all .2s}",
                ".ss-close:hover{background:#fee2e2;color:#ef4444}",
                ".ss-modal-body{padding:0;max-height:calc(90vh - 120px);overflow:auto}",
                ".ss-modal-body img{display:block;max-width:100%;height:auto}",
                ".ss-modal-footer{padding:12px 20px;background:#fafbfc;border-top:1px solid #e5e7eb}",
                ".ss-modal-footer span{font-size:13px;color:#2477e9;word-break:break-all}",

                ".filter-bar{display:flex;flex-wrap:wrap;gap:8px;padding:12px 0;border-bottom:1px solid #f1f5f9;margin-bottom:12px}",
                ".filter-btn{padding:4px 12px;border-radius:16px;border:1px solid #e5e7eb;background:#fff;font-size:12px;cursor:pointer;transition:all .15s}",
                ".filter-btn:hover{border-color:#2477e9;color:#2477e9}",
                ".filter-btn.active{background:#2477e9;color:#fff;border-color:#2477e9}",
                ".filter-reset{padding:4px 12px;border-radius:16px;border:1px solid #e5e7eb;background:#f8fafc;font-size:12px;cursor:pointer;color:#6b7280}",

                ".asset-list{padding:8px 0}",
                ".asset-item{padding:10px 14px;border:1px solid #e5e7eb;border-radius:8px;margin-bottom:6px;font-size:13px}",
                ".asset-item.fail{border-color:#fca5a5;background:#fef2f2}",
                ".asset-url{color:#2477e9;word-break:break-all}",
                ".asset-meta{display:flex;gap:12px;margin-top:4px;font-size:12px;color:#6b7280}",
                ".asset-title{color:#374151;font-weight:500;margin-bottom:2px}",

                ".load-more-btn{display:block;width:100%;padding:10px;margin-top:10px;text-align:center;background:#f8fafc;border:1px dashed #d1d5db;border-radius:8px;cursor:pointer;font-size:13px;color:#6b7280;transition:all .15s}",
                ".load-more-btn:hover{background:#eff6ff;border-color:#2477e9;color:#2477e9}",

                ".footer{text-align:center;font-size:12px;color:#94a3b8;padding:24px 0}",

                "@media(max-width:900px){.page-layout{grid-template-columns:1fr}.toc{display:none}.main-content{padding:16px}}",
                "@media print{.toc{display:none}.page-layout{display:block}.domain-body{display:block!important}.sec-content{display:block!important}}"
        };
        for (String l : lines) sb.append(l);
    }

    /* ==================== JS ==================== */
    private static void appendReportJs(StringBuilder sb) {
        String[] lines = {
                "var PAGE=100;",
                "var rendered={};",
                "var domFilters={};",
                "function getFilters(idx){if(!domFilters[idx])domFilters[idx]={confidence:[],authType:[],mfa:[]};return domFilters[idx];}",
                "var domainCache=[];",

                "function esc(s){return s==null?'':String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}",
                "function fmtTime(s){if(!s)return '-';try{return s.replace('T',' ').substring(0,19);}catch(e){return s;}}",
                "function fmtDur(s){if(!s&&s!==0)return '-';var n=Number(s);if(isNaN(n))return s;if(n<60)return n.toFixed(1)+'s';if(n<3600)return (n/60).toFixed(1)+'m';return (n/3600).toFixed(1)+'h';}",

                "function isLogin(p){if(!p||!p.is_login_page)return false;var v=String(p.is_login_page).trim().toUpperCase();return v!==''&&v!=='NO'&&v!=='FALSE';}",
                "function getLlm(p){var d=p.login_detection;if(!d)return null;if(d.llm_verification)return d.llm_verification;if(d.auth_types)return d;return null;}",
                "function getConf(p){var v=String(p.is_login_page||'').toUpperCase();var verified=v.indexOf('VERIFIED')>=0;if(verified){var l=getLlm(p);var at=l&&l.auth_types?l.auth_types:[];var allUnk=at.length===0||at.every(function(t){return t.toUpperCase()==='UNKNOWN';});return allUnk?'MEDIUM':'HIGH';}return 'LOW';}",
                "function getAuthTypes(p){var l=getLlm(p);if(!l||!l.auth_types||l.auth_types.length===0)return ['UNKNOWN'];return l.auth_types.map(function(t){return t.toUpperCase();});}",
                "function getMfa(p){var l=getLlm(p);if(!l||!l.mfa_confirmation)return 'UNKNOWN';return String(l.mfa_confirmation).toUpperCase();}",
                "function matchFilter(p,domIdx){",
                "  var f=getFilters(domIdx);",
                "  if(f.confidence.length&&f.confidence.indexOf(getConf(p))<0)return false;",
                "  if(f.authType.length){var at=getAuthTypes(p);if(!at.some(function(t){return f.authType.indexOf(t)>=0;}))return false;}",
                "  if(f.mfa.length&&f.mfa.indexOf(getMfa(p))<0)return false;",
                "  return true;",
                "}",

                "var ds=DATA.domains||[];",
                "ds.forEach(function(d,i){",
                "  var disc=d.discovery||{};var cr=d.crawl||{};",
                "  var subs=disc.subdomains||[];",
                "  var vp=cr.visited_pages||[];",
                "  var logins=vp.filter(isLogin);",
                "  var failed=cr.failed_urls||[];",
                "  var discovered=cr.discovered_urls||disc.urls||[];",
                "  domainCache.push({d:d,subs:subs,vp:vp,logins:logins,failed:failed,discovered:discovered});",
                "});",

                "function renderShell(){",
                "  var hdr='<div class=\"report-header\"><h1>资产测绘报告</h1><div class=\"meta\">';",
                "  hdr+='<span>任务ID: '+esc(DATA.taskId)+'</span>';",
                "  var stMap={completed:'已完成',running:'运行中',failed:'失败',pending:'等待中',stopped:'已停止'};",
                "  var stCls='status-'+(DATA.status||'pending');",
                "  hdr+='<span class=\"status-badge '+stCls+'\">'+esc(stMap[DATA.status]||DATA.status||'')+'</span>';",
                "  if(DATA.createdAt)hdr+='<span>创建: '+fmtTime(DATA.createdAt)+'</span>';",
                "  if(DATA.finishedAt)hdr+='<span>完成: '+fmtTime(DATA.finishedAt)+'</span>';",
                "  hdr+='</div></div>';",

                "  var totalSubs=0,totalLogins=0,totalVisited=0;",
                "  domainCache.forEach(function(c){totalSubs+=c.subs.length;totalLogins+=c.logins.length;totalVisited+=c.vp.length;});",
                "  hdr+='<div class=\"summary-cards\">';",
                "  hdr+='<div class=\"card\"><div class=\"card-value\">'+ds.length+'</div><div class=\"card-label\">域名</div></div>';",
                "  hdr+='<div class=\"card\"><div class=\"card-value\">'+totalSubs+'</div><div class=\"card-label\">子域名</div></div>';",
                "  hdr+='<div class=\"card\"><div class=\"card-value\">'+totalLogins+'</div><div class=\"card-label\">登录入口</div></div>';",
                "  hdr+='<div class=\"card\"><div class=\"card-value\">'+totalVisited+'</div><div class=\"card-label\">已访问页面</div></div>';",
                "  hdr+='</div>';",

                "  var toc='<h3>域名目录</h3>';",
                "  domainCache.forEach(function(c,i){",
                "    toc+='<a href=\"#domain-'+i+'\" data-idx=\"'+i+'\">'+esc(c.d.domain)+'<span class=\"toc-count\">('+c.logins.length+')</span></a>';",
                "  });",
                "  document.getElementById('toc').innerHTML=toc;",

                "  var main=hdr;",
                "  domainCache.forEach(function(c,idx){",
                "    main+='<div class=\"domain-section collapsed\" id=\"domain-'+idx+'\">';",
                "    main+='<div class=\"domain-header\" data-domtoggle=\"'+idx+'\">';",
                "    main+='<h2>'+esc(c.d.domain)+'</h2>';",
                "    main+='<div class=\"stats\">';",
                "    main+='<span>子域名: '+c.subs.length+'</span>';",
                "    main+='<span>登录: '+c.logins.length+'</span>';",
                "    main+='<span>发现: '+c.discovered.length+'</span>';",
                "    main+='<span>已访问: '+c.vp.length+'</span>';",
                "    if(c.failed.length)main+='<span>失败: '+c.failed.length+'</span>';",
                "    main+='<span class=\"collapse-toggle\">▼</span>';",
                "    main+='</div></div>';",
                "    main+='<div class=\"domain-body\">';",

                "    main+='<div class=\"sec-header\" data-sec=\"subs-'+idx+'\"><span class=\"sec-arrow\">▶</span> 子域名 ('+c.subs.length+')</div>';",
                "    main+='<div class=\"sec-content\" id=\"sec-subs-'+idx+'\"></div>';",

                "    main+='<div class=\"sec-header\" data-sec=\"logins-'+idx+'\"><span class=\"sec-arrow\">▶</span> 登录入口 ('+c.logins.length+')</div>';",
                "    main+='<div class=\"sec-content\" id=\"sec-logins-'+idx+'\"></div>';",

                "    var assetLabel='资产链接 (发现:'+c.discovered.length+' 访问:'+c.vp.length+(c.failed.length?' 失败:'+c.failed.length:'')+')';",
                "    main+='<div class=\"sec-header\" data-sec=\"assets-'+idx+'\"><span class=\"sec-arrow\">▶</span> '+assetLabel+'</div>';",
                "    main+='<div class=\"sec-content\" id=\"sec-assets-'+idx+'\"></div>';",

                "    main+='</div></div>';",
                "  });",
                "  main+='<div class=\"footer\">报告生成于 '+fmtTime(new Date().toISOString())+'</div>';",
                "  document.getElementById('mainContent').innerHTML=main;",
                "}",

                "function lazyRender(secKey){",
                "  if(rendered[secKey])return;",
                "  rendered[secKey]=true;",
                "  var parts=secKey.split('-');var type=parts[0];var idx=parseInt(parts[1]);",
                "  var el=document.getElementById('sec-'+secKey);if(!el)return;",
                "  if(type==='subs'){",
                "    var subs=domainCache[idx].subs;",
                "    var first=subs.slice(0,PAGE);",
                "    var h='<div class=\"sub-list\">';",
                "    first.forEach(function(s){var t=typeof s==='string'?s:JSON.stringify(s);h+='<div class=\"sub-item\"><a href=\"https://'+esc(t)+'\" target=\"_blank\" style=\"color:#2477e9;text-decoration:none\">'+esc(t)+'</a></div>';});",
                "    if(subs.length>PAGE)h+=loadMoreBtn('subs',idx,first.length,subs.length);",
                "    h+='</div>';",
                "    el.innerHTML=h;",
                "  }else if(type==='logins'){renderLoginSection(el,idx);}",
                "  else if(type==='assets'){renderAssetSection(el,idx);}",
                "}",

                "function renderLoginSection(el,idx){",
                "  var c=domainCache[idx];",
                "  var pages=c.logins.filter(function(p){return matchFilter(p,idx);});",
                "  var h=buildFilterBar(c.logins,idx);",
                "  h+='<div class=\"login-grid\">';",
                "  var first=pages.slice(0,PAGE);",
                "  first.forEach(function(p){h+=renderLoginCard(p);});",
                "  if(pages.length>PAGE)h+=loadMoreBtn('logins',idx,first.length,pages.length);",
                "  h+='</div>';",
                "  if(pages.length===0)h+='<p style=\"padding:16px;color:#6b7280;text-align:center\">无匹配的登录入口</p>';",
                "  el.innerHTML=h;",
                "}",

                "function renderAssetSection(el,idx){",
                "  var c=domainCache[idx];",
                "  var cr=c.d.crawl||{};var stats=cr.statistics||{};var meta=cr.metadata||{};",
                "  var h='<div style=\"display:flex;gap:16px;flex-wrap:wrap;padding:8px 0;font-size:13px;color:#6b7280\">';",
                "  if(meta.started_at)h+='<span>开始: '+fmtTime(meta.started_at)+'</span>';",
                "  if(meta.finished_at)h+='<span>结束: '+fmtTime(meta.finished_at)+'</span>';",
                "  if(meta.duration_seconds)h+='<span>耗时: '+fmtDur(meta.duration_seconds)+'</span>';",
                "  h+='</div>';",
                "  h+='<div style=\"display:flex;gap:16px;flex-wrap:wrap;padding:4px 0 8px;font-size:13px\">';",
                "  h+='<span style=\"color:#2477e9\"><strong>已发现:</strong> '+(c.discovered.length||stats.total_discovered||0)+' URL</span>';",
                "  h+='<span style=\"color:#059669\"><strong>已访问:</strong> '+(c.vp.length||stats.total_visited||0)+'</span>';",
                "  if(c.failed.length)h+='<span style=\"color:#dc2626\"><strong>失败:</strong> '+c.failed.length+'</span>';",
                "  h+='</div>';",
                "  if(c.vp.length){",
                "    h+='<h4 style=\"font-size:13px;font-weight:600;color:#374151;margin:8px 0 4px\">已访问页面 ('+c.vp.length+')</h4>';",
                "    h+='<div class=\"asset-list\">';",
                "    var first=c.vp.slice(0,PAGE);",
                "    first.forEach(function(p){h+=renderAssetItem(p);});",
                "    if(c.vp.length>PAGE)h+=loadMoreBtn('assets',idx,first.length,c.vp.length);",
                "    h+='</div>';",
                "  }",
                "  if(c.failed.length){",
                "    h+='<h4 style=\"font-size:13px;font-weight:600;color:#dc2626;margin:16px 0 4px\">失败URL ('+c.failed.length+')</h4>';",
                "    h+='<div class=\"asset-list\">';",
                "    var ff=c.failed.slice(0,PAGE);",
                "    ff.forEach(function(u){h+='<div class=\"asset-item fail\"><span class=\"asset-url\">'+esc(u)+'</span></div>';});",
                "    if(c.failed.length>PAGE)h+=loadMoreBtn('failed',idx,ff.length,c.failed.length);",
                "    h+='</div>';",
                "  }",
                "  if(c.discovered.length){",
                "    h+='<h4 style=\"font-size:13px;font-weight:600;color:#2477e9;margin:16px 0 4px\">已发现URL ('+c.discovered.length+')</h4>';",
                "    h+='<div class=\"asset-list\">';",
                "    var df=c.discovered.slice(0,PAGE);",
                "    df.forEach(function(u){h+='<div class=\"asset-item\"><span class=\"asset-url\">'+esc(u)+'</span></div>';});",
                "    if(c.discovered.length>PAGE)h+=loadMoreBtn('discovered',idx,df.length,c.discovered.length);",
                "    h+='</div>';",
                "  }",
                "  if(!c.vp.length&&!c.failed.length&&!c.discovered.length)h+='<p style=\"padding:16px;color:#6b7280;text-align:center\">暂无发现的资产路径</p>';",
                "  el.innerHTML=h;",
                "}",

                "function renderAssetItem(p){",
                "  var login=isLogin(p);",
                "  var h='<div class=\"asset-item'+(login?' login-row':'')+'\">';",
                "  if(p.title)h+='<div class=\"asset-title\">'+esc(p.title)+'</div>';",
                "  h+='<div class=\"asset-url\"><a href=\"'+esc(p.url||'')+'\" target=\"_blank\">'+esc(p.url||'')+'</a></div>';",
                "  h+='<div class=\"asset-meta\">';",
                "  if(p.status_code)h+='<span>状态: '+p.status_code+'</span>';",
                "  if(p.depth!==undefined)h+='<span>深度: '+p.depth+'</span>';",
                "  if(login)h+='<span style=\"color:#2477e9;font-weight:600\">登录页</span>';",
                "  h+='</div></div>';",
                "  return h;",
                "}",

                "function parseStatus(v){if(!v)return{s:'',d:''};var str=String(v).trim();var u=str.toUpperCase();if(u==='YES'||u==='NO')return{s:u,d:''};var m=str.match(/^(YES|NO)\\s*[-–]\\s*(.+)$/i);if(m)return{s:m[1].toUpperCase(),d:m[2].trim()};if(u!==''&&u!=='FALSE')return{s:'YES',d:str};return{s:'',d:''};}",

                "function renderLoginCard(p){",
                "  var conf=getConf(p);var authTypes=getAuthTypes(p);var mfa=getMfa(p);",
                "  var det=p.login_detection||{};var llm=getLlm(p);var signals=det.signals||[];",
                "  var parsed=parseStatus(p.is_login_page);",
                "  var verified=parsed.d&&parsed.d.toUpperCase().indexOf('VERIFIED')===0;",
                "  var scope=det.scope||'';var popups=p.popup_login_screenshot_path||[];",
                "  var h='<div class=\"login-card\"><div class=\"login-card-body\">';",
                "  if(p.title)h+='<div class=\"login-card-title\">'+esc(p.title||'登录页面')+'</div>';",
                "  h+='<div class=\"login-card-url\"><a href=\"'+esc(p.url||'')+'\" target=\"_blank\">'+esc(p.url||'')+'</a></div>';",
                "  h+='<div class=\"login-card-tags\">';",
                "  var confMap={'HIGH':'高','MEDIUM':'中','LOW':'低'};",
                "  var confCls=conf==='HIGH'?'tag-conf-high':conf==='MEDIUM'?'tag-conf-medium':'tag-conf-low';",
                "  h+='<span class=\"tag '+confCls+'\">'+(confMap[conf]||conf)+'</span>';",
                "  h+='<span class=\"tag tag-auth\">'+(verified?'已验证':'登录页')+'</span>';",
                "  if(popups.length)h+='<span class=\"tag\" style=\"background:#e0e7ff;color:#3730a3\">+'+popups.length+'弹窗</span>';",
                "  if(p.status_code)h+='<span class=\"tag\" style=\"background:#f3f4f6;color:#374151\">'+p.status_code+'</span>';",
                "  h+='<span class=\"tag\" style=\"background:#f3f4f6;color:#374151\">深度'+(p.depth||0)+'</span>';",
                "  if(scope==='external_one_hop')h+='<span class=\"tag\" style=\"background:#fef3c7;color:#92400e\">外部跳转</span>';",
                "  else if(scope&&scope!=='in_scope')h+='<span class=\"tag\" style=\"background:#fef3c7;color:#92400e\">'+esc(scope)+'</span>';",
                "  authTypes.forEach(function(t){var cls=t==='UNKNOWN'?'tag-unknown':'tag-auth';h+='<span class=\"tag '+cls+'\">'+esc(t)+'</span>';});",
                "  if(mfa==='NO MFA')h+='<span class=\"tag tag-mfa-no\">无MFA</span>';",
                "  else if(mfa==='UNKNOWN')h+='<span class=\"tag tag-unknown\">未确认MFA</span>';",
                "  else h+='<span class=\"tag tag-mfa-yes\">'+esc(mfa)+'</span>';",
                "  signals.forEach(function(s){h+='<span class=\"tag\" style=\"background:#f0fdf4;color:#166534\">'+esc(s.replace(/_/g,' '))+'</span>';});",
                "  h+='</div>';",
                "  var desc=[];",
                "  if(llm){if(llm.auth_types&&llm.auth_types.length)desc.push('认证方式: '+llm.auth_types.join(', '));if(llm.mfa_confirmation&&llm.mfa_confirmation!=='NO MFA')desc.push('MFA: '+llm.mfa_confirmation);}",
                "  if(desc.length===0&&parsed.d&&parsed.d.toUpperCase().indexOf('VERIFIED')!==0)desc.push(parsed.d);",
                "  if(desc.length)h+='<div style=\"font-size:12px;color:#6b7280;margin-top:6px\">'+esc(desc.join(' | '))+'</div>';",
                "  if(det.referrer)h+='<div style=\"font-size:12px;color:#9ca3af;margin-top:4px\">来源: '+esc(det.referrer)+'</div>';",
                "  if(p.discovered_at)h+='<div style=\"font-size:12px;color:#9ca3af;margin-top:2px\">发现于 '+fmtTime(p.discovered_at)+'</div>';",
                "  h+='</div>';",
                "  var imgs=[];",
                "  if(p.screenshot_path)imgs.push({src:ssrc(p.screenshot_path),label:'主截图'});",
                "  if(popups.length)popups.forEach(function(pp,i){imgs.push({src:ssrc(pp),label:'弹窗截图'+(popups.length>1?(' '+(i+1)):'')});});",
                "  if(imgs.length){",
                "    var imgJson=JSON.stringify(imgs).replace(/\"/g,'&quot;');",
                "    h+='<div class=\"login-card-screenshots\" data-title=\"'+esc(p.title||'登录页面截图')+'\" data-url=\"'+esc(p.url||'')+'\" data-imgs=\"'+imgJson+'\">';",
                "    imgs.forEach(function(im,idx){h+='<div style=\"display:inline-block;text-align:center\"><img class=\"thumb\" data-idx=\"'+idx+'\" src=\"'+esc(im.src)+'\" alt=\"'+esc(im.label)+'\" onerror=\"this.parentNode.style.display=\\'none\\'\"><div class=\"thumb-label\">'+esc(im.label)+'</div></div>';});",
                "    h+='</div>';",
                "  }",
                "  h+='</div>';",
                "  return h;",
                "}",
                "function ssrc(p){if(!p)return '';var s=String(p).replace(/\\\\/g,'/');var i=s.lastIndexOf('/');return 'screenshots/'+(i>=0?s.substring(i+1):s);}",

                "function cnt(s){return '<span style=\"margin-left:4px;font-size:10px;opacity:.7\">('+s+')</span>';}",
                "function buildFilterBar(pages,domIdx){",
                "  var f=getFilters(domIdx);",
                "  var confCnt={'HIGH':0,'MEDIUM':0,'LOW':0},authCnt={},mfaCnt={};",
                "  pages.forEach(function(p){",
                "    confCnt[getConf(p)]=(confCnt[getConf(p)]||0)+1;",
                "    getAuthTypes(p).forEach(function(t){authCnt[t]=(authCnt[t]||0)+1;});",
                "    var m=getMfa(p);mfaCnt[m]=(mfaCnt[m]||0)+1;",
                "  });",
                "  var confLabels={'HIGH':'高','MEDIUM':'中','LOW':'低'};",
                "  var h='<div class=\"filter-bar\" data-domain=\"'+domIdx+'\">';",
                "  h+='<span style=\"font-size:12px;color:#6b7280;align-self:center\">置信度:</span>';",
                "  h+='<button class=\"filter-btn'+(f.confidence.length===0?' active':'')+'\" data-group=\"confidence\" data-val=\"all\" data-action=\"filter\">全部'+cnt(pages.length)+'</button>';",
                "  Object.keys(confCnt).forEach(function(c){if(confCnt[c])h+='<button class=\"filter-btn'+(f.confidence.indexOf(c)>=0?' active':'')+'\" data-group=\"confidence\" data-val=\"'+esc(c)+'\" data-action=\"filter\">'+(confLabels[c]||c)+cnt(confCnt[c])+'</button>';});",
                "  h+='<button class=\"filter-reset\" data-action=\"reset\">清除过滤</button>';",
                "  h+='</div>';",
                "  h+='<div class=\"filter-bar\" data-domain=\"'+domIdx+'\" style=\"border:none;padding-top:0\">';",
                "  h+='<span style=\"font-size:12px;color:#6b7280;align-self:center\">认证:</span>';",
                "  h+='<button class=\"filter-btn'+(f.authType.length===0?' active':'')+'\" data-group=\"authType\" data-val=\"all\" data-action=\"filter\">全部'+cnt(pages.length)+'</button>';",
                "  Object.keys(authCnt).forEach(function(k){h+='<button class=\"filter-btn'+(f.authType.indexOf(k)>=0?' active':'')+'\" data-group=\"authType\" data-val=\"'+esc(k)+'\" data-action=\"filter\">'+esc(k)+cnt(authCnt[k])+'</button>';});",
                "  h+='</div>';",
                "  h+='<div class=\"filter-bar\" data-domain=\"'+domIdx+'\" style=\"border:none;padding-top:0\">';",
                "  h+='<span style=\"font-size:12px;color:#6b7280;align-self:center\">MFA:</span>';",
                "  h+='<button class=\"filter-btn'+(f.mfa.length===0?' active':'')+'\" data-group=\"mfa\" data-val=\"all\" data-action=\"filter\">全部'+cnt(pages.length)+'</button>';",
                "  Object.keys(mfaCnt).forEach(function(m){h+='<button class=\"filter-btn'+(f.mfa.indexOf(m)>=0?' active':'')+'\" data-group=\"mfa\" data-val=\"'+esc(m)+'\" data-action=\"filter\">'+esc(m)+cnt(mfaCnt[m])+'</button>';});",
                "  h+='</div>';",
                "  return h;",
                "}",

                "function loadMoreBtn(type,idx,shown,total){",
                "  return '<button class=\"load-more-btn\" data-type=\"'+type+'\" data-idx=\"'+idx+'\" data-shown=\"'+shown+'\">加载更多 ('+shown+'/'+total+')</button>';",
                "}",

                "function handleLoadMore(btn){",
                "  var type=btn.dataset.type;var idx=parseInt(btn.dataset.idx);var shown=parseInt(btn.dataset.shown);",
                "  var c=domainCache[idx];var src;",
                "  if(type==='subs')src=c.subs;",
                "  else if(type==='logins')src=c.logins.filter(function(p){return matchFilter(p,idx);});",
                "  else if(type==='assets')src=c.vp;",
                "  else if(type==='failed')src=c.failed;",
                "  else if(type==='discovered')src=c.discovered||[];",
                "  else return;",
                "  var next=src.slice(shown,shown+PAGE);",
                "  var h='';",
                "  if(type==='subs')next.forEach(function(s){var t=typeof s==='string'?s:JSON.stringify(s);h+='<div class=\"sub-item\"><a href=\"https://'+esc(t)+'\" target=\"_blank\" style=\"color:#2477e9;text-decoration:none\">'+esc(t)+'</a></div>';});",
                "  else if(type==='logins')next.forEach(function(p){h+=renderLoginCard(p);});",
                "  else if(type==='assets')next.forEach(function(p){h+=renderAssetItem(p);});",
                "  else if(type==='failed')next.forEach(function(u){h+='<li class=\"asset-item fail\"><span class=\"asset-url\">'+esc(u)+'</span></li>';});",
                "  else if(type==='discovered')next.forEach(function(u){h+='<div class=\"asset-item\"><span class=\"asset-url\">'+esc(u)+'</span></div>';});",
                "  var container=btn.parentElement;",
                "  var temp=document.createElement('div');temp.innerHTML=h;",
                "  while(temp.firstChild)container.insertBefore(temp.firstChild,btn);",
                "  var newShown=shown+next.length;",
                "  if(newShown>=src.length)btn.remove();",
                "  else{btn.dataset.shown=newShown;btn.textContent='加载更多 ('+newShown+'/'+src.length+')';}",
                "}",

                "function activateDomain(idx){",
                "  document.querySelectorAll('.domain-section').forEach(function(s,i){",
                "    if(i===idx){s.classList.remove('collapsed');s.classList.add('active-domain');}",
                "    else{s.classList.add('collapsed');s.classList.remove('active-domain');}",
                "  });",
                "  updateTocActive(idx);",
                "}",

                "var tocClickIdx=-1,tocClickTimer=0;",
                "document.addEventListener('click',function(e){",
                "  var tocA=e.target.closest('.toc a');",
                "  if(tocA){",
                "    e.preventDefault();",
                "    var idx=parseInt(tocA.dataset.idx);",
                "    if(!isNaN(idx)){",
                "      tocClickIdx=idx;",
                "      clearTimeout(tocClickTimer);",
                "      activateDomain(idx);",
                "      var target=document.getElementById('domain-'+idx);",
                "      if(target)target.scrollIntoView({behavior:'smooth',block:'start'});",
                "      tocClickTimer=setTimeout(function(){tocClickIdx=-1;},1500);",
                "    }",
                "  }",
                "",
                "  var domToggle=e.target.closest('[data-domtoggle]');",
                "  if(domToggle){",
                "    var sec=domToggle.closest('.domain-section');",
                "    var idx2=parseInt(domToggle.dataset.domtoggle);",
                "    var willExpand=sec.classList.contains('collapsed');",
                "    sec.classList.toggle('collapsed');",
                "    document.querySelectorAll('.domain-section').forEach(function(s){s.classList.remove('active-domain');});",
                "    if(willExpand)sec.classList.add('active-domain');",
                "    updateTocActive(willExpand?idx2:-1);",
                "    return;",
                "  }",
                "",
                "  var secH=e.target.closest('.sec-header');",
                "  if(secH){",
                "    var key=secH.dataset.sec;",
                "    var content=document.getElementById('sec-'+key);",
                "    if(!content)return;",
                "    var isOpen=content.classList.toggle('open');",
                "    secH.querySelector('.sec-arrow').textContent=isOpen?'\\u25BC':'\\u25B6';",
                "    if(isOpen){lazyRender(key);}",
                "    return;",
                "  }",
                "",
                "  var lmBtn=e.target.closest('.load-more-btn');",
                "  if(lmBtn){handleLoadMore(lmBtn);return;}",
                "",
                "  var fBtn=e.target.closest('.filter-btn,.filter-reset');",
                "  if(fBtn){",
                "    var bar=fBtn.closest('.filter-bar');",
                "    if(!bar)return;",
                "    var domIdx=parseInt(bar.dataset.domain);",
                "    var f=getFilters(domIdx);",
                "    var action=fBtn.dataset.action;",
                "    if(action==='reset'){domFilters[domIdx]={confidence:[],authType:[],mfa:[]};}",
                "    else{",
                "      var g=fBtn.dataset.group,v=fBtn.dataset.val;",
                "      var arr=f[g];",
                "      if(v==='all'){f[g]=[];}",
                "      else{var i=arr.indexOf(v);if(i>=0)arr.splice(i,1);else arr.push(v);}",
                "    }",
                "    var loginEl=document.getElementById('sec-logins-'+domIdx);",
                "    if(loginEl){rendered['logins-'+domIdx]=false;renderLoginSection(loginEl,domIdx);rendered['logins-'+domIdx]=true;}",
                "    return;",
                "  }",
                "  var thumb=e.target.closest('.thumb');",
                "  if(thumb){",
                "    var wrap=thumb.closest('.login-card-screenshots');if(!wrap)return;",
                "    var imgs=JSON.parse(wrap.dataset.imgs||'[]');if(!imgs.length)return;",
                "    var startIdx=parseInt(thumb.dataset.idx)||0;",
                "    openSSModal(wrap.dataset.title||'',wrap.dataset.url||'',imgs,startIdx);return;",
                "  }",
                "});",

                "var ssGallery={imgs:[],idx:0};",
                "function openSSModal(title,url,imgs,startIdx){",
                "  ssGallery={imgs:imgs,idx:startIdx||0};",
                "  var m=document.getElementById('ssModal');",
                "  if(!m){",
                "    m=document.createElement('div');m.id='ssModal';m.className='ss-modal';",
                "    m.innerHTML='<div class=\"ss-modal-content\">"
                        + "<div class=\"ss-modal-header\"><h3 id=\"ssTitle\"></h3>"
                        + "<div class=\"ss-modal-nav\" id=\"ssNav\"><button class=\"ss-nav-btn\" id=\"ssPrev\">\\u2039</button><span class=\"ss-nav-label\" id=\"ssLabel\"></span><button class=\"ss-nav-btn\" id=\"ssNext\">\\u203A</button></div>"
                        + "<button class=\"ss-close\" id=\"ssClose\">\\u00D7</button></div>"
                        + "<div class=\"ss-modal-body\"><img id=\"ssImg\" src=\"\" alt=\"\"></div>"
                        + "<div class=\"ss-modal-footer\"><span id=\"ssUrl\"></span></div></div>';",
                "    document.body.appendChild(m);",
                "    document.getElementById('ssClose').addEventListener('click',closeSSModal);",
                "    m.addEventListener('click',function(ev){if(ev.target===m)closeSSModal();});",
                "    document.getElementById('ssPrev').addEventListener('click',function(){if(ssGallery.imgs.length<=1)return;ssGallery.idx=(ssGallery.idx-1+ssGallery.imgs.length)%ssGallery.imgs.length;renderSSGallery();});",
                "    document.getElementById('ssNext').addEventListener('click',function(){if(ssGallery.imgs.length<=1)return;ssGallery.idx=(ssGallery.idx+1)%ssGallery.imgs.length;renderSSGallery();});",
                "    document.addEventListener('keydown',function(ev){if(!m.classList.contains('show'))return;if(ev.key==='Escape')closeSSModal();if(ev.key==='ArrowLeft')document.getElementById('ssPrev').click();if(ev.key==='ArrowRight')document.getElementById('ssNext').click();});",
                "  }",
                "  document.getElementById('ssTitle').textContent=title;",
                "  document.getElementById('ssUrl').textContent=url;",
                "  document.getElementById('ssNav').style.display=imgs.length>1?'flex':'none';",
                "  renderSSGallery();",
                "  m.classList.add('show');",
                "}",
                "function renderSSGallery(){",
                "  var g=ssGallery;var img=document.getElementById('ssImg');var lb=document.getElementById('ssLabel');",
                "  img.src=g.imgs[g.idx].src||'';img.alt=g.imgs[g.idx].label||'';",
                "  lb.textContent=g.imgs[g.idx].label+' ('+(g.idx+1)+'/'+g.imgs.length+')';",
                "}",
                "function closeSSModal(){document.getElementById('ssModal').classList.remove('show');}",

                "renderShell();",

                "var lastTocIdx=-1;",
                "function updateTocOnly(idx){",
                "  if(idx===lastTocIdx)return;",
                "  lastTocIdx=idx;",
                "  var tocLinks=document.querySelectorAll('.toc a');",
                "  tocLinks.forEach(function(a,i){a.classList.toggle('active',i===idx);});",
                "  if(idx>=0&&idx<tocLinks.length){",
                "    var link=tocLinks[idx];var toc=document.getElementById('toc');",
                "    var lr=link.getBoundingClientRect();var tr=toc.getBoundingClientRect();",
                "    if(lr.top<tr.top+40||lr.bottom>tr.bottom-40){link.scrollIntoView({block:'center',behavior:'smooth'});}",
                "  }",
                "}",
                "function updateTocActive(idx){",
                "  updateTocOnly(idx);",
                "  document.querySelectorAll('.domain-section').forEach(function(s,i){",
                "    s.classList.toggle('active-domain',i===idx&&!s.classList.contains('collapsed'));",
                "  });",
                "}",

                "var scrollRaf=0;",
                "function onScroll(){",
                "  cancelAnimationFrame(scrollRaf);",
                "  scrollRaf=requestAnimationFrame(function(){",
                "    if(tocClickIdx>=0)return;",
                "    var sections=document.querySelectorAll('.domain-section');",
                "    if(!sections.length)return;",
                "    var expanded=[];",
                "    sections.forEach(function(s,i){if(!s.classList.contains('collapsed'))expanded.push(i);});",
                "    if(expanded.length===1){updateTocOnly(expanded[0]);return;}",
                "    var threshold=120,best=-1;",
                "    for(var i=0;i<sections.length;i++){",
                "      if(sections[i].classList.contains('collapsed'))continue;",
                "      var r=sections[i].getBoundingClientRect();",
                "      if(r.top<=threshold&&r.bottom>threshold){best=i;break;}",
                "    }",
                "    if(best<0){",
                "      for(var j=sections.length-1;j>=0;j--){",
                "        if(sections[j].classList.contains('collapsed'))continue;",
                "        if(sections[j].getBoundingClientRect().top<=threshold){best=j;break;}",
                "      }",
                "    }",
                "    if(best<0&&expanded.length>0)best=expanded[0];",
                "    if(best<0)best=0;",
                "    updateTocOnly(best);",
                "  });",
                "}",
                "window.addEventListener('scroll',onScroll,{passive:true});"
        };
        for (String l : lines) { sb.append(l).append('\n'); }
    }
}
