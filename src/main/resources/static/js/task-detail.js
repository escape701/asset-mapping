// 全局变量
let taskId = null;
let taskData = null;
let selectedDomain = null;
let currentTab = 'subdomains';
let conversationHistory = [];
let logPollingInterval = null;
let lastLogLine = 0;
let autoScrollLogs = true;

// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', () => {
    // 从URL获取任务ID
    const pathParts = window.location.pathname.split('/');
    taskId = pathParts[pathParts.length - 1];
    
    document.getElementById('taskTitle').textContent = `任务 ${taskId}`;
    
    initTabs();
    initChat();
    initConfirmModal();
    loadTaskDetail();
    
    // 启动轮询
    startPolling();
});

// 初始化标签页
function initTabs() {
    const tabs = document.querySelectorAll('.resultTab');
    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            
            const prevTab = currentTab;
            currentTab = tab.dataset.tab;
            
            // 切换到日志标签页时开始轮询，离开时停止
            // pending 状态也启动轮询，因为实际可能已经在运行
            if (currentTab === 'logs' && (taskData?.status === 'running' || taskData?.status === 'pending')) {
                startLogPolling();
            } else if (prevTab === 'logs') {
                stopLogPolling();
            }
            
            renderResults();
        });
    });
    
    // 初始化截图模态框
    initScreenshotModal();
}

// 初始化截图模态框
function initScreenshotModal() {
    const modal = document.getElementById('screenshotModal');
    const closeBtn = document.getElementById('closeScreenshotModal');

    closeBtn.addEventListener('click', () => modal.classList.remove('show'));
    modal.addEventListener('click', (e) => {
        if (e.target === modal) modal.classList.remove('show');
    });

    document.getElementById('screenshotPrev').addEventListener('click', () => {
        if (screenshotGallery.images.length <= 1) return;
        screenshotGallery.currentIndex =
            (screenshotGallery.currentIndex - 1 + screenshotGallery.images.length) % screenshotGallery.images.length;
        renderScreenshotGallery();
    });
    document.getElementById('screenshotNext').addEventListener('click', () => {
        if (screenshotGallery.images.length <= 1) return;
        screenshotGallery.currentIndex =
            (screenshotGallery.currentIndex + 1) % screenshotGallery.images.length;
        renderScreenshotGallery();
    });

    document.addEventListener('keydown', (e) => {
        if (!modal.classList.contains('show')) return;
        if (e.key === 'Escape') modal.classList.remove('show');
        if (e.key === 'ArrowLeft') document.getElementById('screenshotPrev').click();
        if (e.key === 'ArrowRight') document.getElementById('screenshotNext').click();
    });
}

// 初始化聊天功能
function initChat() {
    const chatInput = document.getElementById('chatInput');
    const sendBtn = document.getElementById('sendBtn');
    const quickActions = document.querySelectorAll('.quickActionBtn');
    
    // 发送按钮点击
    sendBtn.addEventListener('click', sendMessage);
    
    // Enter 发送（Shift+Enter 换行）
    chatInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    
    // 自动调整高度
    chatInput.addEventListener('input', () => {
        chatInput.style.height = 'auto';
        chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
    });
    
    // 快捷操作
    quickActions.forEach(btn => {
        btn.addEventListener('click', () => {
            const prompt = btn.dataset.prompt;
            chatInput.value = prompt;
            sendMessage();
        });
    });
}

// 加载任务详情
async function loadTaskDetail() {
    try {
        // 尝试从正常任务 API 获取
        let response = await fetch(`/api/tasks/${taskId}`);
        let result = await response.json();
        
        if (result.code === 200 && result.data) {
            taskData = transformApiResponse(result.data);
            renderTaskOverview();
            renderDomainList();
            
            // 如果任务正在运行，尝试同步一次进度
            if (taskData.status === 'running') {
                setTimeout(() => syncTaskProgress(), 2000);
            }
            return;
        }
        
        // 如果任务不存在，尝试 demo API（直接从 Python 输出读取）
        // taskId 可能是域名，如 "baidu.com"
        response = await fetch(`/api/tasks/demo/${taskId}`);
        result = await response.json();
        
        if (result.code === 200 && result.data) {
            taskData = transformApiResponse(result.data);
            renderTaskOverview();
            renderDomainList();
            return;
        }
        
        showLoadError('未找到任务数据，请检查任务ID是否正确。');
    } catch (error) {
        console.error('加载任务详情失败:', error);
        showLoadError('加载任务详情失败，请稍后重试。');
    }
}

// 同步任务进度（从 Python 输出文件读取）
async function syncTaskProgress(silent = false) {
    const syncBtn = document.getElementById('syncBtn');
    
    // 禁用按钮并显示加载状态
    if (syncBtn && !silent) {
        syncBtn.disabled = true;
        syncBtn.innerHTML = `
            <svg class="spinning" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"></polyline>
                <polyline points="1 20 1 14 7 14"></polyline>
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path>
            </svg>
            同步中...
        `;
    }
    
    try {
        const response = await fetch(`/api/tasks/${taskId}/sync`, {
            method: 'POST'
        });
        const result = await response.json();
        
        if (result.code === 200 && result.data) {
            const oldStatus = taskData?.status;
            const oldCompletedDomains = taskData?.completedDomains || 0;
            
            taskData = transformApiResponse(result.data);
            
            // 更新选中的域名数据
            if (selectedDomain) {
                const updatedDomain = taskData.domains.find(d => d.domain === selectedDomain.domain);
                if (updatedDomain) {
                    selectedDomain = updatedDomain;
                }
            }
            
            // 检查域名数据是否有变化（用于判断是否需要强制刷新）
            const newCompletedDomains = taskData.completedDomains;
            const domainsChanged = oldCompletedDomains !== newCompletedDomains;
            
            renderTaskOverview();
            renderDomainList();
            
            // 刷新当前标签页内容（日志标签页除外，避免打断日志显示）
            // 如果域名完成数有变化，强制刷新以显示最新数据
            if (currentTab !== 'logs' || domainsChanged) {
                if (currentTab !== 'logs') {
                    renderResults();
                }
            }
            
            // 更新日志状态显示
            updateLogsStatus();
            
            // 如果任务状态从 pending 变为 running，确保日志轮询已启动
            if (oldStatus === 'pending' && taskData.status === 'running') {
                if (currentTab === 'logs' && !logPollingInterval) {
                    console.log('任务状态变为 running，启动日志轮询');
                    startLogPolling();
                }
            }
            
            // 如果任务状态从运行中变为完成，执行完成时的清理操作
            if ((oldStatus === 'running' || oldStatus === 'pending') && 
                (taskData.status === 'completed' || taskData.status === 'failed' || taskData.status === 'stopped')) {
                handleTaskCompletion();
            }
            
            // 即使任务状态没变，如果域名完成数有变化，也更新结果（处理部分域名完成的情况）
            if (domainsChanged) {
                console.log(`域名完成数变化: ${oldCompletedDomains} -> ${newCompletedDomains}`);
            }
            
            if (!silent && typeof showMessage === 'function') {
                showMessage('进度同步成功', 'success');
            }
            console.log('任务进度已同步');
        } else {
            if (!silent && typeof showMessage === 'function') {
                showMessage(result.message || '同步失败', 'error');
            }
        }
    } catch (error) {
        console.error('同步进度失败:', error);
        if (!silent && typeof showMessage === 'function') {
            showMessage('同步进度失败', 'error');
        }
    } finally {
        // 恢复按钮
        if (syncBtn && !silent) {
            syncBtn.disabled = false;
            syncBtn.innerHTML = `
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 4 23 10 17 10"></polyline>
                    <polyline points="1 20 1 14 7 14"></polyline>
                    <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path>
                </svg>
                同步进度
            `;
        }
    }
}

// 更新日志状态显示
function updateLogsStatus() {
    const logsStatus = document.getElementById('logsStatus');
    if (!logsStatus) return;
    
    let statusText = '等待中';
    let statusClass = 'pending';
    switch (taskData?.status) {
        case 'running':
            statusText = '运行中...';
            statusClass = 'running';
            break;
        case 'completed':
            statusText = '已完成';
            statusClass = 'completed';
            break;
        case 'failed':
            statusText = '已失败';
            statusClass = 'failed';
            break;
        case 'stopped':
            statusText = '已停止';
            statusClass = 'stopped';
            break;
        case 'pending':
            statusText = '等待启动';
            statusClass = 'pending';
            break;
    }
    
    logsStatus.className = `logsStatus ${statusClass}`;
    logsStatus.textContent = statusText;
}

// 转换 API 响应为前端期望的格式
function transformApiResponse(apiData) {
    return {
        taskId: apiData.taskId || apiData.id,
        status: apiData.status || 'pending',
        totalDomains: apiData.totalDomains || (apiData.domains ? apiData.domains.length : 0),
        completedDomains: apiData.completedDomains || 0,
        createdAt: apiData.createdAt,
        domains: (apiData.domains || []).map(d => ({
            domain: d.domain,
            status: d.status || 'pending',
            landingUrl: d.landingUrl,
            // discovery 数据 - 直接从后端返回的完整数据
            discovery: d.discovery || {
                subdomains: [],
                urls: []
            },
            // crawl 数据 - 直接从后端返回的完整数据
            crawl: d.crawl || {
                statistics: {
                    total_discovered: 0,
                    total_visited: 0,
                    total_failed: 0
                },
                visited_pages: [],
                discovered_urls: [],
                failed_urls: []
            }
        }))
    };
}

// 加载模拟数据（用于前端开发演示）
// 模拟数据结构匹配 Python login_crawler 的实际输出格式（参考 baidu.com 运行结果）
function showLoadError(msg) {
    const domainList = document.getElementById('domainList');
    if (domainList) {
        domainList.innerHTML = `
            <div style="padding:40px 20px;text-align:center;color:#6b7280;">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5" style="margin:0 auto 12px;">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="8" x2="12" y2="12"></line>
                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
                <div style="font-size:15px;font-weight:500;margin-bottom:4px;">${msg}</div>
            </div>`;
    }
}

// 判断单个页面是否为登录页（兼容新旧格式）
function isLoginPage(page) {
    if (!page || !page.is_login_page) return false;
    const val = page.is_login_page.toString().trim().toUpperCase();
    if (val === '' || val === 'NO' || val === 'FALSE') return false;
    return true;
}

// 从 visited_pages 中提取登录页面
function extractLoginPages(visitedPages) {
    if (!visitedPages) return [];
    return visitedPages.filter(isLoginPage);
}

// 获取 login_detection 中的 LLM 验证信息（兼容新旧格式）
function getLlmVerification(page) {
    const det = page.login_detection;
    if (!det) return null;
    // 新格式: login_detection.llm_verification.auth_types
    if (det.llm_verification) return det.llm_verification;
    // 旧格式: login_detection.auth_types（直接在顶层）
    if (det.auth_types) return det;
    return null;
}

// 解析 is_login_page 值，分离核心判定和附加信息
function parseLoginPageStatus(value) {
    if (!value) return { status: '', detail: '' };
    const str = value.toString().trim();
    const upper = str.toUpperCase();
    if (upper === 'YES' || upper === 'NO') return { status: upper, detail: '' };
    // "YES - VERIFIED By gpt-5.2" -> status="YES", detail="VERIFIED By gpt-5.2"
    const match = str.match(/^(YES|NO)\s*[-–]\s*(.+)$/i);
    if (match) return { status: match[1].toUpperCase(), detail: match[2].trim() };
    // 旧格式描述文字: "Login page with QR code..."
    if (upper !== '' && upper !== 'FALSE') return { status: 'YES', detail: str };
    return { status: '', detail: '' };
}

// 格式化登录页描述信息
function formatLoginDesc(page) {
    const parts = [];
    const llm = getLlmVerification(page);
    if (llm) {
        if (llm.auth_types && llm.auth_types.length > 0) {
            parts.push('认证方式: ' + llm.auth_types.join(', '));
        }
        if (llm.mfa_confirmation && llm.mfa_confirmation !== 'NO MFA') {
            parts.push('MFA: ' + llm.mfa_confirmation);
        }
    }
    if (parts.length === 0) {
        const parsed = parseLoginPageStatus(page.is_login_page);
        if (parsed.detail && !parsed.detail.toUpperCase().startsWith('VERIFIED')) {
            return parsed.detail;
        }
    }
    return parts.join(' | ');
}

// 将本地截图路径转换为可访问的URL
function getScreenshotUrl(screenshotPath) {
    if (!screenshotPath) return null;
    
    // 如果已经是完整URL，直接返回
    if (screenshotPath.startsWith('http://') || screenshotPath.startsWith('https://')) {
        return screenshotPath;
    }
    
    // 将 Python 输出的路径转换为后端 API URL
    // Python 输出格式: "screenshots\\xxx.png" 或 "screenshots/xxx.png"
    // 后端 API: /api/screenshots/file?path=screenshots/xxx.png
    const encodedPath = encodeURIComponent(screenshotPath);
    return `/api/screenshots/file?path=${encodedPath}`;
}

// 渲染任务概览
function renderTaskOverview() {
    document.getElementById('taskId').textContent = taskData.taskId;
    document.getElementById('taskCreatedAt').textContent = formatDateTime(taskData.createdAt);
    
    // 状态
    const statusEl = document.getElementById('taskStatus');
    const statusClass = {
        'pending': 'statusPending',
        'running': 'statusRunning',
        'completed': 'statusCompleted',
        'failed': 'statusFailed'
    }[taskData.status] || 'statusPending';
    
    const statusText = {
        'pending': '等待中',
        'running': '运行中',
        'completed': '已完成',
        'failed': '失败'
    }[taskData.status] || '未知';
    
    statusEl.className = `taskStatus ${statusClass}`;
    statusEl.textContent = statusText;
    
    // 进度
    const progress = taskData.totalDomains > 0 
        ? Math.round((taskData.completedDomains / taskData.totalDomains) * 100) 
        : 0;
    document.getElementById('progressFill').style.width = `${progress}%`;
    document.getElementById('progressText').textContent = `${progress}%`;
}

// 渲染域名列表
function renderDomainList() {
    const domainList = document.getElementById('domainList');
    const domainTotal = document.getElementById('domainTotal');
    
    domainTotal.textContent = `共 ${taskData.domains.length} 个`;
    
    domainList.innerHTML = taskData.domains.map((domain, index) => {
        const statusIcon = getStatusIcon(domain.status);
        // 兼容不同的数据格式
        let subdomainCount = 0;
        if (domain.discovery) {
            if (Array.isArray(domain.discovery.subdomains)) {
                subdomainCount = domain.discovery.subdomains.length;
            } else if (Array.isArray(domain.discovery)) {
                subdomainCount = domain.discovery.length;
            }
        }
        // 如果子域名为0，尝试从 visited_pages 统计
        if (subdomainCount === 0 && domain.crawl?.visited_pages) {
            const hosts = new Set();
            domain.crawl.visited_pages.forEach(p => {
                try { hosts.add(new URL(p.url).hostname); } catch(e) {}
            });
            subdomainCount = hosts.size;
        }
        const loginCount = extractLoginPages(domain.crawl?.visited_pages).length;
        
        // 计算资产路径数（已访问页面数）
        let assetCount = 0;
        if (domain.crawl?.visited_pages) {
            assetCount = domain.crawl.visited_pages.length;
        } else if (domain.crawl?.statistics?.total_visited) {
            assetCount = domain.crawl.statistics.total_visited;
        }
        
        // 检查是否有错误
        const hasError = domain.discovery?.error || domain.crawl?.error;
        const errorClass = hasError ? 'has-error' : '';
        
        return `
            <div class="domainItem ${index === 0 ? 'active' : ''} ${errorClass}" data-index="${index}">
                <div class="domainIcon">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="10"></circle>
                        <line x1="2" y1="12" x2="22" y2="12"></line>
                        <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                    </svg>
                </div>
                <div class="domainInfo">
                    <div class="domainName">${domain.domain}</div>
                    <div class="domainMeta">${subdomainCount} 子域名 · ${loginCount} 登录口 · ${assetCount} 资产路径</div>
                    ${hasError ? `<div class="domainError">⚠ 存在错误</div>` : ''}
                </div>
                <div class="domainStatusIcon ${domain.status}">${statusIcon}</div>
            </div>
        `;
    }).join('');
    
    // 绑定点击事件
    const domainItems = domainList.querySelectorAll('.domainItem');
    domainItems.forEach(item => {
        item.addEventListener('click', () => {
            domainItems.forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            selectedDomain = taskData.domains[parseInt(item.dataset.index)];
            // 日志是任务级别的，切换域名只需更新非日志内容
            // 无论当前标签页是什么都可以渲染，只是日志标签页不会因此改变
            renderResults();
        });
    });
    
    // 保持之前选中的域名，或默认选中第一个
    if (taskData.domains.length > 0) {
        if (selectedDomain) {
            // 找到之前选中的域名在新数据中的位置
            const prevIndex = taskData.domains.findIndex(d => d.domain === selectedDomain.domain);
            if (prevIndex >= 0) {
                selectedDomain = taskData.domains[prevIndex];
                // 更新高亮状态
                domainItems.forEach((item, idx) => {
                    item.classList.toggle('active', idx === prevIndex);
                });
            } else {
                selectedDomain = taskData.domains[0];
            }
        } else {
            selectedDomain = taskData.domains[0];
        }
        // 只在非日志标签页时渲染结果，避免日志被重置
        if (currentTab !== 'logs') {
            renderResults();
        }
    }
}

// 获取状态图标
function getStatusIcon(status) {
    switch (status) {
        case 'completed':
            return `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="20 6 9 17 4 12"></polyline>
            </svg>`;
        case 'running':
            return `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <polyline points="12 6 12 12 16 14"></polyline>
            </svg>`;
        case 'failed':
            return `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="15" y1="9" x2="9" y2="15"></line>
                <line x1="9" y1="9" x2="15" y2="15"></line>
            </svg>`;
        default:
            return `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
            </svg>`;
    }
}

// 格式化持续时间
function formatDuration(seconds) {
    if (!seconds) return '-';
    if (seconds < 60) {
        return `${seconds.toFixed(1)} 秒`;
    } else if (seconds < 3600) {
        const mins = Math.floor(seconds / 60);
        const secs = (seconds % 60).toFixed(0);
        return `${mins} 分 ${secs} 秒`;
    } else {
        const hours = Math.floor(seconds / 3600);
        const mins = Math.floor((seconds % 3600) / 60);
        return `${hours} 小时 ${mins} 分`;
    }
}

// 格式化时间（从ISO字符串）
function formatTime(isoString) {
    if (!isoString) return '-';
    try {
        const date = new Date(isoString);
        return date.toLocaleString('zh-CN', {
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    } catch (e) {
        return isoString;
    }
}

// 渲染结果
function renderResults() {
    const resultContent = document.getElementById('resultContent');
    
    if (!selectedDomain) {
        resultContent.innerHTML = `
            <div class="resultEmpty">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="8" x2="12" y2="12"></line>
                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
                <p>选择左侧域名查看结果</p>
            </div>
        `;
        return;
    }
    
    switch (currentTab) {
        case 'subdomains':
            // 子域名列表（纯字符串数组）
            // 兼容不同格式：可能是 discovery.subdomains 或直接在 discovery 中
            let subdomains = [];
            if (selectedDomain.discovery) {
                if (Array.isArray(selectedDomain.discovery.subdomains)) {
                    subdomains = selectedDomain.discovery.subdomains;
                } else if (Array.isArray(selectedDomain.discovery)) {
                    subdomains = selectedDomain.discovery;
                }
            }
            // 如果子域名为空，尝试从 visited_pages 中提取子域名
            if (subdomains.length === 0 && selectedDomain.crawl?.visited_pages) {
                const visitedHosts = new Set();
                selectedDomain.crawl.visited_pages.forEach(page => {
                    try {
                        const host = new URL(page.url).hostname;
                        visitedHosts.add(host);
                    } catch (e) {}
                });
                subdomains = Array.from(visitedHosts);
            }
            
            // 获取 discovery 元数据和错误信息
            const discoveryMeta = selectedDomain.discovery?.metadata || {};
            const discoveryError = selectedDomain.discovery?.error;
            const discoveryStats = selectedDomain.discovery?.statistics || {};
            
            // 构建统计信息头部
            let subdomainHeader = '';
            if (discoveryMeta.started_at || discoveryMeta.duration_seconds || discoveryError) {
                subdomainHeader = `
                    <div class="resultStatsHeader">
                        <div class="statsRow">
                            ${discoveryMeta.started_at ? `<span class="statItem"><strong>开始:</strong> ${formatTime(discoveryMeta.started_at)}</span>` : ''}
                            ${discoveryMeta.finished_at ? `<span class="statItem"><strong>结束:</strong> ${formatTime(discoveryMeta.finished_at)}</span>` : ''}
                            ${discoveryMeta.duration_seconds ? `<span class="statItem"><strong>耗时:</strong> ${formatDuration(discoveryMeta.duration_seconds)}</span>` : ''}
                        </div>
                        ${discoveryError ? `
                            <div class="errorAlert">
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                    <circle cx="12" cy="12" r="10"></circle>
                                    <line x1="12" y1="8" x2="12" y2="12"></line>
                                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                                </svg>
                                <span><strong>错误:</strong> ${discoveryError}</span>
                            </div>
                        ` : ''}
                    </div>
                `;
            }
            
            resultContent.innerHTML = subdomainHeader + (subdomains.length > 0 ? `
                <ul class="resultList">
                    ${subdomains.map(subdomain => `
                        <li class="subdomainItem">
                            <div class="subdomainIcon">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                    <circle cx="12" cy="12" r="10"></circle>
                                    <line x1="2" y1="12" x2="22" y2="12"></line>
                                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                                </svg>
                            </div>
                            <div class="subdomainContent">
                                <div class="subdomainUrl">${subdomain}</div>
                            </div>
                            <a href="https://${subdomain}" target="_blank" class="subdomainLink" onclick="event.stopPropagation()">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path>
                                    <polyline points="15 3 21 3 21 9"></polyline>
                                    <line x1="10" y1="14" x2="21" y2="3"></line>
                                </svg>
                            </a>
                        </li>
                    `).join('')}
                </ul>
            ` : '<div class="resultEmpty"><p>暂无发现的子域名</p></div>');
            break;
            
        case 'logins':
            // 从 visited_pages 中提取登录页面
            const loginPages = extractLoginPages(selectedDomain.crawl?.visited_pages);
            // 存储到全局变量供点击使用
            window.currentLoginPages = loginPages;
            
            // 获取 crawl 元数据
            const crawlMeta = selectedDomain.crawl?.metadata || {};
            const crawlStats = selectedDomain.crawl?.statistics || {};
            
            // 构建统计信息头部
            let loginHeader = '';
            if (crawlMeta.started_at || crawlMeta.duration_seconds) {
                loginHeader = `
                    <div class="resultStatsHeader">
                        <div class="statsRow">
                            ${crawlMeta.started_at ? `<span class="statItem"><strong>开始:</strong> ${formatTime(crawlMeta.started_at)}</span>` : ''}
                            ${crawlMeta.finished_at ? `<span class="statItem"><strong>结束:</strong> ${formatTime(crawlMeta.finished_at)}</span>` : ''}
                            ${crawlMeta.duration_seconds ? `<span class="statItem"><strong>耗时:</strong> ${formatDuration(crawlMeta.duration_seconds)}</span>` : ''}
                        </div>
                        <div class="statsRow">
                            <span class="statItem"><strong>总发现:</strong> ${crawlStats.total_discovered || 0} URL</span>
                            <span class="statItem"><strong>已访问:</strong> ${crawlStats.total_visited || 0}</span>
                            <span class="statItem"><strong>登录页:</strong> ${loginPages.length}</span>
                            ${crawlStats.total_failed > 0 ? `<span class="statItem statFailed"><strong>失败:</strong> ${crawlStats.total_failed}</span>` : ''}
                        </div>
                    </div>
                `;
            }
            
            resultContent.innerHTML = loginHeader + (loginPages.length > 0 ? `
                <div class="loginCardGrid">
                    ${loginPages.map((page, index) => {
                        const screenshotUrl = getScreenshotUrl(page.screenshot_path);
                        const hasPopups = page.popup_login_screenshot_path && page.popup_login_screenshot_path.length > 0;
                        return `
                        <div class="loginCard" onclick="openLoginScreenshot(${index})">
                            <div class="loginCardScreenshot">
                                ${screenshotUrl ? `
                                    <img src="${screenshotUrl}" alt="${page.title || '登录页面'}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';">
                                    <div class="screenshotPlaceholder" style="display:none;">
                                        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                                            <circle cx="8.5" cy="8.5" r="1.5"></circle>
                                            <polyline points="21 15 16 10 5 21"></polyline>
                                        </svg>
                                        <span>加载失败</span>
                                    </div>
                                    <div class="viewOverlay">
                                        <span>点击查看大图</span>
                                    </div>
                                ` : `
                                    <div class="screenshotPlaceholder">
                                        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                                            <circle cx="8.5" cy="8.5" r="1.5"></circle>
                                            <polyline points="21 15 16 10 5 21"></polyline>
                                        </svg>
                                        <span>暂无截图</span>
                                    </div>
                                `}
                            </div>
                            <div class="loginCardBody">
                                <div class="loginCardTitle">${page.title || '登录页面'}</div>
                                <div class="loginCardUrl">${page.url}</div>
                                <div class="loginCardTags">
                                    ${(() => {
                                        const parsed = parseLoginPageStatus(page.is_login_page);
                                        const verified = parsed.detail && parsed.detail.toUpperCase().startsWith('VERIFIED');
                                        return `<span class="loginCardTag auth">${verified ? '已验证' : '登录页'}</span>`;
                                    })()}
                                    ${hasPopups ? `<span class="loginCardTag popup">+${page.popup_login_screenshot_path.length}弹窗</span>` : ''}
                                    <span class="loginCardTag">${page.status_code}</span>
                                    <span class="loginCardTag depth">深度${page.depth || 0}</span>
                                    ${(() => {
                                        const det = page.login_detection || {};
                                        const scope = det.scope || '';
                                        if (scope === 'external_one_hop') return '<span class="loginCardTag scope">外部跳转</span>';
                                        if (scope && scope !== 'in_scope') return `<span class="loginCardTag scope">${scope}</span>`;
                                        return '';
                                    })()}
                                    ${(() => {
                                        const llm = getLlmVerification(page);
                                        if (!llm) return '';
                                        let tags = '';
                                        if (llm.auth_types && llm.auth_types.length > 0) {
                                            tags += llm.auth_types.map(t => `<span class="loginCardTag authType">${t}</span>`).join('');
                                        }
                                        if (llm.mfa_confirmation && llm.mfa_confirmation !== 'NO MFA' && llm.mfa_confirmation !== 'UNKNOWN') {
                                            tags += '<span class="loginCardTag mfa">MFA</span>';
                                        }
                                        return tags;
                                    })()}
                                    ${(() => {
                                        const signals = (page.login_detection || {}).signals || [];
                                        return signals.map(s => `<span class="loginCardTag signal">${s.replace(/_/g, ' ')}</span>`).join('');
                                    })()}
                                </div>
                                <div class="loginCardDesc">${formatLoginDesc(page)}</div>
                                ${page.login_detection && page.login_detection.referrer ? `<div class="loginCardReferrer">来源: ${page.login_detection.referrer}</div>` : ''}
                                ${page.login_detection_error ? `<div class="loginCardError">${page.login_detection_error}</div>` : ''}
                                ${page.discovered_at ? `<div class="loginCardTime">发现于 ${formatTime(page.discovered_at)}</div>` : ''}
                            </div>
                        </div>
                    `}).join('')}
                </div>
            ` : '<div class="resultEmpty"><p>暂无发现的登录入口</p></div>');
            break;
            
        case 'assets':
            // 资产路径：显示所有发现的URL
            const discoveredUrls = selectedDomain.crawl?.discovered_urls || selectedDomain.discovery?.urls || [];
            const visitedPages = selectedDomain.crawl?.visited_pages || [];
            const failedUrls = selectedDomain.crawl?.failed_urls || [];
            
            // 获取 crawl 统计信息
            const assetCrawlMeta = selectedDomain.crawl?.metadata || {};
            const assetCrawlStats = selectedDomain.crawl?.statistics || {};
            
            // 构建统计信息头部
            let assetHeader = `
                <div class="resultStatsHeader">
                    <div class="statsRow">
                        ${assetCrawlMeta.started_at ? `<span class="statItem"><strong>开始:</strong> ${formatTime(assetCrawlMeta.started_at)}</span>` : ''}
                        ${assetCrawlMeta.finished_at ? `<span class="statItem"><strong>结束:</strong> ${formatTime(assetCrawlMeta.finished_at)}</span>` : ''}
                        ${assetCrawlMeta.duration_seconds ? `<span class="statItem"><strong>耗时:</strong> ${formatDuration(assetCrawlMeta.duration_seconds)}</span>` : ''}
                    </div>
                    <div class="statsRow">
                        <span class="statItem statDiscovered"><strong>总发现:</strong> ${discoveredUrls.length || assetCrawlStats.total_discovered || 0} URL</span>
                        <span class="statItem statVisited"><strong>已访问:</strong> ${visitedPages.length || assetCrawlStats.total_visited || 0}</span>
                        ${failedUrls.length > 0 ? `<span class="statItem statFailed"><strong>失败:</strong> ${failedUrls.length}</span>` : ''}
                    </div>
                </div>
            `;
            
            // 合并所有URL展示
            let assetContent = '';
            
            // 已访问页面
            if (visitedPages.length > 0) {
                assetContent += `
                    <div class="assetSection">
                        <h4 class="assetSectionTitle">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <polyline points="20 6 9 17 4 12"></polyline>
                            </svg>
                            已访问页面 (${visitedPages.length})
                        </h4>
                        <ul class="resultList">
                            ${visitedPages.map(page => `
                                <li class="resultItem ${isLoginPage(page) ? 'login' : ''}">
                                    <div class="resultItemIcon">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                            ${isLoginPage(page) ? `
                                                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                                                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                                            ` : `
                                                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                                                <polyline points="14 2 14 8 20 8"></polyline>
                                            `}
                                        </svg>
                                    </div>
                                    <div class="resultItemContent">
                                        <div class="resultItemUrl">${page.url}</div>
                                        <div class="resultItemMeta">
                                            <span>${page.title || '无标题'}</span>
                                            <span>状态: ${page.status_code}</span>
                                            <span>深度: ${page.depth}</span>
                                            ${page.discovered_at ? `<span>发现于: ${formatTime(page.discovered_at)}</span>` : ''}
                                        </div>
                                    </div>
                                </li>
                            `).join('')}
                        </ul>
                    </div>
                `;
            }
            
            // 失败URL列表
            if (failedUrls.length > 0) {
                assetContent += `
                    <div class="assetSection">
                        <h4 class="assetSectionTitle failed">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <circle cx="12" cy="12" r="10"></circle>
                                <line x1="15" y1="9" x2="9" y2="15"></line>
                                <line x1="9" y1="9" x2="15" y2="15"></line>
                            </svg>
                            失败URL (${failedUrls.length})
                        </h4>
                        <ul class="resultList">
                            ${failedUrls.map(url => `
                                <li class="resultItem failed">
                                    <div class="resultItemIcon">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                            <circle cx="12" cy="12" r="10"></circle>
                                            <line x1="15" y1="9" x2="9" y2="15"></line>
                                            <line x1="9" y1="9" x2="15" y2="15"></line>
                                        </svg>
                                    </div>
                                    <div class="resultItemContent">
                                        <div class="resultItemUrl">${url}</div>
                                        <div class="resultItemMeta"><span>访问失败</span></div>
                                    </div>
                                </li>
                            `).join('')}
                        </ul>
                    </div>
                `;
            }
            
            // 未访问的发现URL（最多显示100个）
            const unvisitedUrls = discoveredUrls.filter(url => !visitedPages.some(p => p.url === url) && !failedUrls.includes(url));
            if (unvisitedUrls.length > 0) {
                const displayUrls = unvisitedUrls.slice(0, 100);
                assetContent += `
                    <div class="assetSection">
                        <h4 class="assetSectionTitle pending">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <circle cx="12" cy="12" r="10"></circle>
                                <polyline points="12 6 12 12 16 14"></polyline>
                            </svg>
                            待访问URL (${unvisitedUrls.length})
                            ${unvisitedUrls.length > 100 ? `<span class="sectionNote">仅显示前100个</span>` : ''}
                        </h4>
                        <ul class="resultList resultListCompact">
                            ${displayUrls.map(url => `
                                <li class="resultItem pending">
                                    <div class="resultItemIcon">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                            <circle cx="12" cy="12" r="10"></circle>
                                        </svg>
                                    </div>
                                    <div class="resultItemContent">
                                        <div class="resultItemUrl">${url}</div>
                                    </div>
                                </li>
                            `).join('')}
                        </ul>
                    </div>
                `;
            }
            
            resultContent.innerHTML = assetHeader + (assetContent || '<div class="resultEmpty"><p>暂无发现的资产路径</p></div>');
            break;
            
        case 'logs':
            // 实时日志
            renderLogsTab(resultContent);
            break;
    }
}

// 渲染日志标签页
function renderLogsTab(container) {
    // 根据状态显示不同的状态文本和样式
    let statusText = '等待中';
    let statusClass = 'pending';
    switch (taskData?.status) {
        case 'running':
            statusText = '运行中...';
            statusClass = 'running';
            break;
        case 'completed':
            statusText = '已完成';
            statusClass = 'completed';
            break;
        case 'failed':
            statusText = '已失败';
            statusClass = 'failed';
            break;
        case 'stopped':
            statusText = '已停止';
            statusClass = 'stopped';
            break;
        case 'pending':
            statusText = '等待启动';
            statusClass = 'pending';
            break;
    }
    
    container.innerHTML = `
        <div class="logsContainer">
            <div class="logsHeader">
                <div class="logsTitle">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                        <polyline points="14 2 14 8 20 8"></polyline>
                    </svg>
                    <span>任务日志（所有域名）</span>
                    <span class="logsStatus ${statusClass}" id="logsStatus">
                        ${statusText}
                    </span>
                </div>
                <div class="logsActions">
                    <label class="autoScrollToggle">
                        <input type="checkbox" id="autoScrollCheck" ${autoScrollLogs ? 'checked' : ''}>
                        <span>自动滚动</span>
                    </label>
                    <button class="btn btnSmall btnSecondary" id="refreshLogsBtn">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="23 4 23 10 17 10"></polyline>
                            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
                        </svg>
                        刷新
                    </button>
                    <button class="btn btnSmall btnSecondary" id="clearLogsBtn">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        </svg>
                        清空显示
                    </button>
                </div>
            </div>
            <div class="logsContent" id="logsContent">
                <div class="logsLoading">
                    <svg class="spinning" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 12a9 9 0 11-6.219-8.56"></path>
                    </svg>
                    <span>加载日志中...</span>
                </div>
            </div>
            <div class="logsFooter">
                <span class="logsInfo" id="logsInfo">共 0 行</span>
            </div>
        </div>
    `;
    
    // 绑定事件
    document.getElementById('autoScrollCheck').addEventListener('change', (e) => {
        autoScrollLogs = e.target.checked;
    });
    
    document.getElementById('refreshLogsBtn').addEventListener('click', () => {
        loadLogs(true);
    });
    
    document.getElementById('clearLogsBtn').addEventListener('click', () => {
        document.getElementById('logsContent').innerHTML = '<div class="logsEmpty">日志已清空，等待新日志...</div>';
        lastLogLine = 0;
    });
    
    // 开始加载日志
    loadLogs(true);
    
    // 开始轮询（如果任务正在运行或待启动状态）
    // pending 状态也启动轮询，因为实际可能已经在运行（状态更新滞后）
    if (taskData?.status === 'running' || taskData?.status === 'pending') {
        startLogPolling();
    }
}

// 加载日志
async function loadLogs(fullReload = false) {
    const logsContent = document.getElementById('logsContent');
    if (!logsContent) return;
    
    try {
        let url;
        if (fullReload) {
            // 加载最新的 200 行
            url = `/api/tasks/${taskId}/logs/latest?lines=200`;
            lastLogLine = 0;
        } else {
            // 增量加载
            url = `/api/tasks/${taskId}/logs?fromLine=${lastLogLine}&lines=100`;
        }
        
        const response = await fetch(url);
        const result = await response.json();
        
        if (result.code === 200 && result.data) {
            const { lines, totalLines, fromLine, hasMore } = result.data;
            
            if (fullReload) {
                // 全量替换
                if (lines && lines.length > 0) {
                    logsContent.innerHTML = `<pre class="logsText">${escapeHtml(lines.join('\n'))}</pre>`;
                    lastLogLine = (fromLine || 0) + lines.length;
                } else {
                    // 根据任务状态显示不同的提示
                    let emptyMsg = '暂无日志';
                    if (taskData?.status === 'pending') {
                        emptyMsg = '任务尚未启动，启动后将显示实时日志';
                    } else if (taskData?.status === 'completed' || taskData?.status === 'stopped' || taskData?.status === 'failed') {
                        emptyMsg = '此任务没有日志记录（可能是在日志功能启用前运行的）';
                    } else if (taskData?.status === 'running') {
                        emptyMsg = '等待爬虫输出...';
                    }
                    logsContent.innerHTML = `<div class="logsEmpty">
                        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                            <polyline points="14 2 14 8 20 8"></polyline>
                        </svg>
                        <span>${emptyMsg}</span>
                    </div>`;
                }
            } else {
                // 增量追加
                if (lines && lines.length > 0) {
                    let logsText = logsContent.querySelector('.logsText');
                    if (!logsText) {
                        logsContent.innerHTML = '<pre class="logsText"></pre>';
                        logsText = logsContent.querySelector('.logsText');
                    }
                    logsText.textContent += '\n' + lines.join('\n');
                    lastLogLine += lines.length;
                }
            }
            
            // 更新信息
            const logsInfo = document.getElementById('logsInfo');
            if (logsInfo) {
                logsInfo.textContent = `共 ${totalLines} 行`;
            }
            
            // 自动滚动到底部
            if (autoScrollLogs) {
                logsContent.scrollTop = logsContent.scrollHeight;
            }
        }
    } catch (error) {
        console.error('加载日志失败:', error);
        if (fullReload) {
            logsContent.innerHTML = '<div class="logsError">加载日志失败</div>';
        }
    }
}

// 开始日志轮询
function startLogPolling() {
    stopLogPolling(); // 先停止之前的轮询
    
    console.log('启动日志轮询，当前状态:', taskData?.status);
    
    logPollingInterval = setInterval(() => {
        if (currentTab === 'logs') {
            // 检查任务是否还在运行（pending 或 running 都视为运行中）
            const isRunning = taskData?.status === 'running' || taskData?.status === 'pending';
            if (isRunning) {
                loadLogs(false); // 增量加载
            } else {
                // 任务已完成，停止轮询
                console.log('任务已完成，停止日志轮询');
                stopLogPolling();
            }
        }
    }, 2000); // 每2秒轮询一次
}

// 停止日志轮询
function stopLogPolling() {
    if (logPollingInterval) {
        clearInterval(logPollingInterval);
        logPollingInterval = null;
    }
}

// HTML 转义
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 截图画廊状态
let screenshotGallery = { images: [], currentIndex: 0 };

function openLoginScreenshot(index) {
    const loginPages = window.currentLoginPages || [];
    if (index >= loginPages.length) return;

    const page = loginPages[index];
    const images = [];

    if (page.screenshot_path) {
        images.push({ url: getScreenshotUrl(page.screenshot_path), label: '主截图' });
    }
    if (page.popup_login_screenshot_path) {
        page.popup_login_screenshot_path.forEach((p, i) => {
            images.push({ url: getScreenshotUrl(p), label: `弹窗截图 ${i + 1}` });
        });
    }
    if (images.length === 0) return;

    screenshotGallery = { images, currentIndex: 0 };

    document.getElementById('screenshotModalTitle').textContent = page.title || '登录页面截图';
    document.getElementById('screenshotModalUrl').textContent = page.url;

    const nav = document.getElementById('screenshotModalNav');
    nav.style.display = images.length > 1 ? 'flex' : 'none';

    renderScreenshotGallery();
    document.getElementById('screenshotModal').classList.add('show');
}

function renderScreenshotGallery() {
    const { images, currentIndex } = screenshotGallery;
    const img = document.getElementById('screenshotModalImg');
    const label = document.getElementById('screenshotNavLabel');

    img.src = images[currentIndex].url || '';
    img.alt = images[currentIndex].label;
    if (label) {
        label.textContent = `${images[currentIndex].label} (${currentIndex + 1}/${images.length})`;
    }
}

// 发送消息
async function sendMessage() {
    const chatInput = document.getElementById('chatInput');
    const message = chatInput.value.trim();
    
    if (!message) return;
    
    // 添加用户消息
    appendMessage('user', message);
    chatInput.value = '';
    chatInput.style.height = 'auto';
    
    // 显示加载状态
    const loadingId = appendMessage('assistant', null, true);
    
    // 构建上下文（包含整个任务的所有域名）
    const context = buildContext();
    
    try {
        const aiModel = document.getElementById('aiModelSelect').value;
        
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                taskId: taskId,
                domain: selectedDomain ? selectedDomain.domain : '',
                message: message,
                context: context,
                model: aiModel,
                history: conversationHistory.slice(-10) // 最近10条对话历史
            })
        });
        
        const result = await response.json();
        
        // 移除加载状态
        removeMessage(loadingId);
        
        if (result.code === 200) {
            appendMessage('assistant', result.data.reply);
            conversationHistory.push({ role: 'user', content: message });
            conversationHistory.push({ role: 'assistant', content: result.data.reply });
        } else {
            // 显示后端返回的错误信息
            const errorMsg = result.message || '请求失败，请检查AI服务配置';
            console.error('AI对话错误:', errorMsg);
            appendMessage('assistant', `**请求失败：** ${errorMsg}\n\n请检查系统设置中的AI配置是否正确。`);
        }
    } catch (error) {
        console.error('发送消息失败:', error);
        removeMessage(loadingId);
        appendMessage('assistant', `**网络错误：** 无法连接到AI服务，请检查网络连接和后端服务是否正常运行。\n\n错误详情：${error.message}`);
    }
}

// 构建上下文（包含整个任务的所有域名数据）
function buildContext() {
    if (!taskData || !taskData.domains || taskData.domains.length === 0) return '';
    
    let context = `=== 资产测绘任务知识库 ===\n`;
    context += `任务ID: ${taskId}\n`;
    context += `任务状态: ${taskData.status}\n`;
    context += `域名总数: ${taskData.domains.length}\n`;
    if (selectedDomain) {
        context += `当前聚焦域名: ${selectedDomain.domain}\n`;
    }
    context += '\n';
    
    // 遍历所有域名
    taskData.domains.forEach(domain => {
        context += `========================================\n`;
        context += `【域名: ${domain.domain}】\n`;
        context += `========================================\n`;
        context += `状态: ${domain.status}\n`;
        
        const subdomains = domain.discovery?.subdomains || [];
        const loginPages = extractLoginPages(domain.crawl?.visited_pages);
        const stats = domain.crawl?.statistics || {};
        
        context += `子域名数: ${subdomains.length}\n`;
        context += `登录入口数: ${loginPages.length}\n`;
        context += `总发现URL: ${stats.total_discovered || 0}\n`;
        context += `已访问页面: ${stats.total_visited || 0}\n`;
        context += `失败数: ${stats.total_failed || 0}\n\n`;
        
        if (subdomains.length > 0) {
            context += `子域名列表:\n`;
            subdomains.slice(0, 50).forEach(sub => {
                context += `  - ${sub}\n`;
            });
            if (subdomains.length > 50) {
                context += `  ... 还有 ${subdomains.length - 50} 个\n`;
            }
            context += '\n';
        }
        
        if (loginPages.length > 0) {
            context += `登录入口:\n`;
            loginPages.forEach(page => {
                context += `  - ${page.url}\n`;
                context += `    标题: ${page.title || '无'}\n`;
                context += `    识别结果: ${page.is_login_page}\n`;
                const det = page.login_detection || {};
                if (det.scope) context += `    范围: ${det.scope}\n`;
                if (det.signals && det.signals.length > 0) context += `    信号: ${det.signals.join(', ')}\n`;
                const llm = getLlmVerification(page);
                if (llm) {
                    if (llm.auth_types && llm.auth_types.length > 0) context += `    认证方式: ${llm.auth_types.join(', ')}\n`;
                    if (llm.mfa_confirmation !== undefined) context += `    MFA: ${llm.mfa_confirmation}\n`;
                }
                if (det.referrer) context += `    来源: ${det.referrer}\n`;
            });
            context += '\n';
        }
        
        // 访问的页面概要
        const visitedPages = domain.crawl?.visited_pages || [];
        if (visitedPages.length > 0) {
            context += `访问的页面(共 ${visitedPages.length} 个):\n`;
            visitedPages.slice(0, 30).forEach(page => {
                context += `  - ${page.url || ''}\n`;
                if (page.title) context += `    标题: ${page.title}\n`;
            });
            if (visitedPages.length > 30) {
                context += `  ... 还有 ${visitedPages.length - 30} 个页面\n`;
            }
            context += '\n';
        }
    });
    
    return context;
}

// 消息计数器，确保每条消息 ID 唯一
let messageCounter = 0;

// 添加消息到聊天框
function appendMessage(role, content, isLoading = false) {
    const chatMessages = document.getElementById('chatMessages');
    const messageId = 'msg-' + Date.now() + '-' + (++messageCounter);
    
    const messageDiv = document.createElement('div');
    messageDiv.className = `chatMessage ${role}`;
    if (isLoading) {
        messageDiv.className += ' loading';
    }
    messageDiv.id = messageId;
    
    const avatar = role === 'assistant' ? `
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
        </svg>
    ` : `
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
            <circle cx="12" cy="7" r="4"></circle>
        </svg>
    `;
    
    let contentHtml;
    if (isLoading) {
        contentHtml = `
            <div class="typingIndicator">
                <span></span>
                <span></span>
                <span></span>
            </div>
        `;
    } else {
        // 将 Markdown 风格的文本转换为 HTML
        contentHtml = formatMessageContent(content);
    }
    
    messageDiv.innerHTML = `
        <div class="messageAvatar">${avatar}</div>
        <div class="messageContent">${contentHtml}</div>
    `;
    
    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
    
    return messageId;
}

// 格式化消息内容
function formatMessageContent(content) {
    if (!content) return '';
    
    // 简单的 Markdown 转换
    let html = content
        // 标题
        .replace(/^### (.+)$/gm, '<h4>$1</h4>')
        .replace(/^## (.+)$/gm, '<h3>$1</h3>')
        // 粗体
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        // 列表
        .replace(/^- (.+)$/gm, '<li>$1</li>')
        .replace(/^(\d+)\. (.+)$/gm, '<li>$2</li>')
        // 换行
        .replace(/\n\n/g, '</p><p>')
        .replace(/\n/g, '<br>');
    
    // 包装列表
    html = html.replace(/(<li>.*<\/li>)+/gs, match => `<ul>${match}</ul>`);
    
    return `<p>${html}</p>`;
}

// 移除消息
function removeMessage(messageId) {
    const message = document.getElementById(messageId);
    if (message) {
        message.remove();
    } else {
        // 兜底：如果按 ID 找不到，移除所有 loading 状态的消息
        const loadingMessages = document.querySelectorAll('.chatMessage.loading');
        loadingMessages.forEach(msg => msg.remove());
    }
}

// 轮询任务状态
function startPolling() {
    let consecutiveCompletedChecks = 0; // 连续检测到完成状态的次数
    const MAX_COMPLETED_CHECKS = 3; // 完成后额外检查次数（确保数据同步完成）
    
    setInterval(async () => {
        if (!taskData) return;
        
        // 任务正在运行中，正常同步
        if (taskData.status === 'running' || taskData.status === 'pending') {
            consecutiveCompletedChecks = 0; // 重置计数
            await syncTaskProgress(true);
            return;
        }
        
        // 检查是否有域名状态不一致（域名还在运行但任务状态已完成）
        const hasPendingOrRunningDomains = taskData.domains?.some(d => 
            d.status === 'pending' || d.status === 'running' || d.status === 'discovering' || d.status === 'crawling'
        );
        
        // 检查完成的域名数是否与总数一致
        const completedDomains = taskData.domains?.filter(d => 
            d.status === 'completed' || d.status === 'failed'
        ).length || 0;
        const isProgressIncomplete = completedDomains < taskData.totalDomains;
        
        // 如果有域名还在运行或进度不完整，继续同步
        if (hasPendingOrRunningDomains || isProgressIncomplete) {
            console.log('检测到域名状态不一致，继续同步...', {
                hasPendingOrRunningDomains,
                isProgressIncomplete,
                completedDomains,
                totalDomains: taskData.totalDomains
            });
            await syncTaskProgress(true);
            return;
        }
        
        // 任务刚完成时，额外同步几次确保数据完整
        if (taskData.status === 'completed' || taskData.status === 'failed' || taskData.status === 'stopped') {
            if (consecutiveCompletedChecks < MAX_COMPLETED_CHECKS) {
                consecutiveCompletedChecks++;
                console.log(`任务已完成，执行额外同步 ${consecutiveCompletedChecks}/${MAX_COMPLETED_CHECKS}`);
                await syncTaskProgress(true);
            }
        }
    }, 3000); // 3秒轮询一次
}

// 当任务完成时执行最后一次刷新
function handleTaskCompletion() {
    // 停止日志轮询
    stopLogPolling();
    // 更新日志状态显示
    updateLogsStatus();
    // 如果不在日志标签页，刷新当前内容
    if (currentTab !== 'logs') {
        renderResults();
    }
    // 最后加载一次完整日志
    if (currentTab === 'logs') {
        loadLogs(true);
    }
}

// 初始化确认对话框
function initConfirmModal() {
    // 检查是否已存在对话框
    if (document.getElementById('confirmModal')) {
        return;
    }
    
    // 创建确认对话框
    const modalHtml = `
        <div class="confirmModal" id="confirmModal">
            <div class="confirmModalContent">
                <div class="confirmModalTitle">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="10"></circle>
                        <line x1="12" y1="8" x2="12" y2="12"></line>
                        <line x1="12" y1="16" x2="12.01" y2="16"></line>
                    </svg>
                    <span id="confirmModalTitleText">确认删除</span>
                </div>
                <div class="confirmModalMessage" id="confirmModalMessage">
                    确定要删除此任务吗？此操作不可恢复。
                </div>
                <div class="confirmModalOptions">
                    <label>
                        <input type="checkbox" id="cleanupFilesCheckbox" checked>
                        同时删除生成的文件
                    </label>
                </div>
                <div class="confirmModalActions">
                    <button class="btn btnSecondary" onclick="closeConfirmModal()">取消</button>
                    <button class="btn btnDanger" id="confirmModalOkBtn">确认删除</button>
                </div>
            </div>
        </div>
    `;
    
    document.body.insertAdjacentHTML('beforeend', modalHtml);
    
    // 点击背景关闭
    document.getElementById('confirmModal').addEventListener('click', (e) => {
        if (e.target.id === 'confirmModal') {
            closeConfirmModal();
        }
    });
}

// 显示确认对话框
function showConfirmModal(title, message, onConfirm, showCleanupOption = true) {
    const modal = document.getElementById('confirmModal');
    document.getElementById('confirmModalTitleText').textContent = title;
    document.getElementById('confirmModalMessage').textContent = message;
    
    // 显示/隐藏清理选项
    const optionsDiv = modal.querySelector('.confirmModalOptions');
    optionsDiv.style.display = showCleanupOption ? 'block' : 'none';
    
    // 绑定确认按钮
    const okBtn = document.getElementById('confirmModalOkBtn');
    okBtn.onclick = () => {
        const cleanupFiles = document.getElementById('cleanupFilesCheckbox').checked;
        onConfirm(cleanupFiles);
        closeConfirmModal();
    };
    
    modal.classList.add('show');
}

// 关闭确认对话框
function closeConfirmModal() {
    const modal = document.getElementById('confirmModal');
    if (modal) {
        modal.classList.remove('show');
    }
}

// 确认删除任务
function confirmDeleteTask() {
    showConfirmModal(
        '确认删除任务',
        `确定要删除任务 "${taskId}" 吗？此操作不可恢复。`,
        (cleanupFiles) => deleteTask(cleanupFiles)
    );
}

// 删除任务
async function deleteTask(cleanupFiles = true) {
    const deleteBtn = document.getElementById('deleteTaskBtn');
    
    // 禁用按钮
    if (deleteBtn) {
        deleteBtn.disabled = true;
        deleteBtn.innerHTML = `
            <svg class="spinning" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 11-6.219-8.56"></path>
            </svg>
            删除中...
        `;
    }
    
    try {
        const response = await fetch(`/api/tasks/${taskId}?cleanupFiles=${cleanupFiles}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            if (typeof showMessage === 'function') {
                showMessage('任务删除成功', 'success');
            }
            // 跳转回任务列表
            setTimeout(() => {
                window.location.href = '/tasks';
            }, 1000);
        } else {
            if (typeof showMessage === 'function') {
                showMessage(result.message || '删除失败', 'error');
            }
            // 恢复按钮
            if (deleteBtn) {
                deleteBtn.disabled = false;
                deleteBtn.innerHTML = `
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        <line x1="10" y1="11" x2="10" y2="17"></line>
                        <line x1="14" y1="11" x2="14" y2="17"></line>
                    </svg>
                    删除任务
                `;
            }
        }
    } catch (error) {
        console.error('删除任务失败:', error);
        if (typeof showMessage === 'function') {
            showMessage('删除任务失败，请稍后重试', 'error');
        }
        // 恢复按钮
        if (deleteBtn) {
            deleteBtn.disabled = false;
            deleteBtn.innerHTML = `
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    <line x1="10" y1="11" x2="10" y2="17"></line>
                    <line x1="14" y1="11" x2="14" y2="17"></line>
                </svg>
                删除任务
            `;
        }
    }
}
