// 全局变量
let uploadedDomains = [];
let currentTasks = [];

// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', () => {
    initDomainInput();
    initFileUpload();
    initTaskSubmit();
    initConfirmModal();
    loadTasks();
    
    // 绑定刷新按钮
    document.getElementById('refreshTasksBtn').addEventListener('click', loadTasks);
    
    // 绑定清理按钮
    const cleanupBtn = document.getElementById('cleanupBtn');
    if (cleanupBtn) {
        cleanupBtn.addEventListener('click', confirmCleanupCompleted);
    }
});

// 初始化域名输入
function initDomainInput() {
    const domainInput = document.getElementById('domainInput');
    const domainCountText = document.getElementById('domainCountText');
    
    domainInput.addEventListener('input', () => {
        const domains = parseDomains(domainInput.value);
        domainCountText.textContent = `已输入 ${domains.length} 个域名`;
    });
}

// 解析域名
function parseDomains(text) {
    if (!text.trim()) return [];
    
    return text.split('\n')
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#'))
        .map(domain => {
            // 自动补全协议
            if (!domain.startsWith('http://') && !domain.startsWith('https://')) {
                return 'https://' + domain;
            }
            return domain;
        });
}

// 初始化文件上传
function initFileUpload() {
    const uploadBox = document.getElementById('uploadBox');
    const fileInput = document.getElementById('fileInput');
    const uploadedFile = document.getElementById('uploadedFile');
    const removeFileBtn = document.getElementById('removeFileBtn');
    
    // 点击上传
    uploadBox.addEventListener('click', () => {
        fileInput.click();
    });
    
    // 文件选择
    fileInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) {
            handleFileUpload(e.target.files[0]);
        }
    });
    
    // 拖拽上传
    uploadBox.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadBox.classList.add('dragover');
    });
    
    uploadBox.addEventListener('dragleave', () => {
        uploadBox.classList.remove('dragover');
    });
    
    uploadBox.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadBox.classList.remove('dragover');
        
        if (e.dataTransfer.files.length > 0) {
            const file = e.dataTransfer.files[0];
            if (file.name.endsWith('.txt')) {
                handleFileUpload(file);
            } else {
                showMessage('请上传 .txt 文件', 'error');
            }
        }
    });
    
    // 移除文件
    removeFileBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        uploadedDomains = [];
        fileInput.value = '';
        uploadedFile.style.display = 'none';
        uploadBox.style.display = 'flex';
    });
}

// 处理文件上传
function handleFileUpload(file) {
    const reader = new FileReader();
    
    reader.onload = (e) => {
        const content = e.target.result;
        uploadedDomains = parseDomains(content);
        
        // 显示上传结果
        document.getElementById('fileName').textContent = file.name;
        document.getElementById('fileCount').textContent = `(${uploadedDomains.length} 个域名)`;
        document.getElementById('uploadedFile').style.display = 'flex';
        document.getElementById('uploadBox').style.display = 'none';
        
        showMessage(`成功解析 ${uploadedDomains.length} 个域名`, 'success');
    };
    
    reader.onerror = () => {
        showMessage('文件读取失败', 'error');
    };
    
    reader.readAsText(file);
}

// 初始化任务提交
function initTaskSubmit() {
    const submitBtn = document.getElementById('submitTaskBtn');
    
    submitBtn.addEventListener('click', submitTask);
}

// 提交任务
async function submitTask() {
    const domainInput = document.getElementById('domainInput');
    const submitBtn = document.getElementById('submitTaskBtn');
    
    // 合并手动输入和文件上传的域名
    const inputDomains = parseDomains(domainInput.value);
    const allDomains = [...new Set([...inputDomains, ...uploadedDomains])]; // 去重
    
    if (allDomains.length === 0) {
        showMessage('请输入至少一个域名或上传文件', 'error');
        return;
    }
    
    // 禁用按钮
    submitBtn.disabled = true;
    submitBtn.innerHTML = `
        <svg class="spinner" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" stroke-dasharray="32" stroke-dashoffset="32">
                <animate attributeName="stroke-dashoffset" values="32;0" dur="1s" repeatCount="indefinite"/>
            </circle>
        </svg>
        创建任务中...
    `;
    
    try {
        // 1. 创建任务
        const createResponse = await fetch('/api/tasks', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                domains: allDomains
            })
        });
        
        const createResult = await createResponse.json();
        
        if (createResult.code === 200) {
            const taskId = createResult.data.id || createResult.data.taskId;
            showMessage('任务创建成功，正在启动...', 'success');
            
            // 2. 自动启动任务
            submitBtn.innerHTML = `
                <svg class="spinner" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="10" stroke-dasharray="32" stroke-dashoffset="32">
                        <animate attributeName="stroke-dashoffset" values="32;0" dur="1s" repeatCount="indefinite"/>
                    </circle>
                </svg>
                启动爬虫中...
            `;
            
            try {
                const startResponse = await fetch(`/api/tasks/${taskId}/start`, {
                    method: 'POST'
                });
                
                const startResult = await startResponse.json();
                
                if (startResult.code === 200) {
                    showMessage('任务已启动，爬虫正在运行', 'success');
                } else {
                    showMessage('任务已创建但启动失败: ' + (startResult.message || '未知错误'), 'warning');
                }
            } catch (startError) {
                console.error('启动任务失败:', startError);
                showMessage('任务已创建但启动失败，请手动启动', 'warning');
            }
            
            // 清空输入
            domainInput.value = '';
            document.getElementById('domainCountText').textContent = '已输入 0 个域名';
            uploadedDomains = [];
            document.getElementById('fileInput').value = '';
            document.getElementById('uploadedFile').style.display = 'none';
            document.getElementById('uploadBox').style.display = 'flex';
            
            // 刷新任务列表
            loadTasks();
        } else {
            showMessage(createResult.message || '任务创建失败', 'error');
        }
    } catch (error) {
        console.error('创建任务失败:', error);
        showMessage('创建任务失败，请稍后重试', 'error');
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = `
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
            </svg>
            开始测绘任务
        `;
    }
}

// 加载任务列表
async function loadTasks() {
    const taskListWrapper = document.getElementById('taskListWrapper');
    const emptyTaskList = document.getElementById('emptyTaskList');
    
    try {
        const response = await fetch('/api/tasks');
        const result = await response.json();
        
        if (result.code === 200) {
            currentTasks = result.data || [];
            
            if (currentTasks.length === 0) {
                emptyTaskList.style.display = 'flex';
                // 清空除空状态外的内容
                const taskItems = taskListWrapper.querySelectorAll('.taskItem');
                taskItems.forEach(item => item.remove());
            } else {
                emptyTaskList.style.display = 'none';
                renderTasks(currentTasks);
            }
        } else {
            console.error('加载任务列表失败: 接口返回异常');
        }
    } catch (error) {
        console.error('加载任务列表失败:', error);
    }
}

// 渲染任务列表
function renderTasks(tasks) {
    const taskListWrapper = document.getElementById('taskListWrapper');
    
    // 保留空状态元素
    const emptyEl = document.getElementById('emptyTaskList');
    
    // 清空现有任务项
    const existingItems = taskListWrapper.querySelectorAll('.taskItem');
    existingItems.forEach(item => item.remove());
    
    // 渲染任务
    tasks.forEach(task => {
        const taskEl = createTaskElement(task);
        taskListWrapper.insertBefore(taskEl, emptyEl);
    });
}

// 创建任务元素
function createTaskElement(task) {
    // 兼容 taskId 或 id 属性
    const taskId = task.taskId || task.id;
    
    const div = document.createElement('div');
    div.className = 'taskItem';
    div.dataset.taskId = taskId;
    
    const progress = task.totalDomains > 0 
        ? Math.round((task.completedDomains / task.totalDomains) * 100) 
        : 0;
    
    const statusClass = {
        'pending': 'statusPending',
        'running': 'statusRunning',
        'completed': 'statusCompleted',
        'failed': 'statusFailed',
        'stopped': 'statusFailed'
    }[task.status] || 'statusPending';
    
    const statusText = {
        'pending': '等待中',
        'running': '运行中',
        'completed': '已完成',
        'failed': '失败',
        'stopped': '已停止'
    }[task.status] || '未知';
    
    div.innerHTML = `
        <div class="taskItemHeader">
            <div class="taskItemTitle">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"></path>
                    <rect x="9" y="3" width="6" height="4" rx="1"></rect>
                </svg>
                任务 ${task.name || taskId}
            </div>
            <div class="taskItemHeaderRight">
                <span class="taskStatus ${statusClass}">${statusText}</span>
                <button class="deleteTaskBtn" onclick="confirmDeleteTask(event, '${taskId}')" title="删除任务">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        <line x1="10" y1="11" x2="10" y2="17"></line>
                        <line x1="14" y1="11" x2="14" y2="17"></line>
                    </svg>
                </button>
            </div>
        </div>
        <div class="taskItemBody">
            <div class="taskDomainCount">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="2" y1="12" x2="22" y2="12"></line>
                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                </svg>
                ${task.totalDomains} 个域名
            </div>
            <div class="taskProgress">
                <div class="progressBar">
                    <div class="progressFill" style="width: ${progress}%"></div>
                </div>
                <span class="progressText">${progress}%</span>
            </div>
            <div class="taskItemTime">${formatDateTime(task.createdAt)}</div>
        </div>
    `;
    
    // 绑定整个任务卡片的点击事件
    div.addEventListener('click', () => viewTaskDetail(taskId));
    
    return div;
}

// 查看任务详情
function viewTaskDetail(taskId) {
    window.location.href = `/tasks/${taskId}`;
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

// 确认删除单个任务
function confirmDeleteTask(event, taskId) {
    event.stopPropagation();
    
    showConfirmModal(
        '确认删除任务',
        `确定要删除任务 "${taskId}" 吗？此操作不可恢复。`,
        (cleanupFiles) => deleteTask(taskId, cleanupFiles)
    );
}

// 删除单个任务
async function deleteTask(taskId, cleanupFiles = true) {
    try {
        const response = await fetch(`/api/tasks/${taskId}?cleanupFiles=${cleanupFiles}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            showMessage('任务删除成功', 'success');
            loadTasks();
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (error) {
        console.error('删除任务失败:', error);
        showMessage('删除任务失败，请稍后重试', 'error');
    }
}

// 确认清理已完成任务
function confirmCleanupCompleted() {
    const completedCount = currentTasks.filter(t => 
        t.status === 'completed' || t.status === 'failed' || t.status === 'stopped'
    ).length;
    
    if (completedCount === 0) {
        showMessage('没有可清理的任务', 'info');
        return;
    }
    
    showConfirmModal(
        '清理已完成任务',
        `确定要删除所有已完成/失败/已停止的任务吗？共 ${completedCount} 个任务将被删除。`,
        (cleanupFiles) => cleanupCompletedTasks(cleanupFiles)
    );
}

// 清理已完成任务
async function cleanupCompletedTasks(cleanupFiles = true) {
    try {
        const response = await fetch(`/api/tasks/cleanup?cleanupFiles=${cleanupFiles}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            const deleted = result.data?.deleted || 0;
            showMessage(`已清理 ${deleted} 个任务`, 'success');
            loadTasks();
        } else {
            showMessage(result.message || '清理失败', 'error');
        }
    } catch (error) {
        console.error('清理任务失败:', error);
        showMessage('清理任务失败，请稍后重试', 'error');
    }
}

// 开始轮询运行中的任务
function startPolling() {
    setInterval(() => {
        const hasRunningTask = currentTasks.some(t => t.status === 'running');
        if (hasRunningTask) {
            loadTasks();
        }
    }, 3000);
}

// 启动轮询
startPolling();
