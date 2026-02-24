// AI 配置管理

// 可用的模型列表
const MODELS = {
    google: [
        { value: 'gemini-2.0-flash', label: 'gemini-2.0-flash (推荐·快速)' },
        { value: 'gemini-1.5-pro', label: 'gemini-1.5-pro (强推理)' },
        { value: 'gemini-1.5-flash', label: 'gemini-1.5-flash (快速)' }
    ],
    openai: [
        { value: 'gpt-4o', label: 'gpt-4o (推荐·多模态)' },
        { value: 'gpt-4o-mini', label: 'gpt-4o-mini (经济型)' },
        { value: 'gpt-4-turbo', label: 'gpt-4-turbo (强推理)' }
    ]
};

// 配置列表
let configs = [];
let activeConfig = null;
let editingConfigId = null;

// DOM 元素
let modal, configForm, providerSelect, modelSelect, apiKeyInput;

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    checkLogin();
    initElements();
    initEventListeners();
    loadConfigs();
});

// 初始化 DOM 元素引用
function initElements() {
    modal = document.getElementById('configModal');
    configForm = document.getElementById('configForm');
    providerSelect = document.getElementById('configProvider');
    modelSelect = document.getElementById('configModel');
    apiKeyInput = document.getElementById('configApiKey');
}

// 初始化事件监听
function initEventListeners() {
    // 添加配置按钮
    document.getElementById('addConfigBtn').addEventListener('click', () => {
        openModal();
    });

    // 关闭模态框
    document.getElementById('closeModal').addEventListener('click', closeModal);
    document.getElementById('cancelBtn').addEventListener('click', closeModal);

    // 点击模态框外部关闭
    modal.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
    });

    // 提供商选择变化
    providerSelect.addEventListener('change', (e) => {
        updateModelOptions(e.target.value);
    });

    // 切换密码可见性
    document.getElementById('toggleApiKey').addEventListener('click', () => {
        const input = apiKeyInput;
        input.type = input.type === 'password' ? 'text' : 'password';
    });

    // 保存配置
    document.getElementById('saveConfigBtn').addEventListener('click', (e) => {
        e.preventDefault();
        saveConfig();
    });

    // 测试连接
    document.getElementById('testConfigBtn').addEventListener('click', () => {
        testConnection();
    });

    // 退出登录
    document.getElementById('logoutButton').addEventListener('click', logout);
}

// 加载配置列表（从后端 API）
async function loadConfigs() {
    try {
        const response = await fetch('/api/llm-configs');
        const result = await response.json();
        
        if (result.code === 200 && result.data) {
            configs = result.data;
            activeConfig = configs.find(c => c.isActive) || null;
        } else {
            configs = [];
            activeConfig = null;
        }
    } catch (error) {
        console.error('加载配置失败:', error);
        configs = [];
        activeConfig = null;
    }
    
    renderConfigs();
}

// 渲染配置列表
function renderConfigs() {
    renderActiveConfig();
    renderConfigList();
}

// 渲染当前激活配置
function renderActiveConfig() {
    const container = document.getElementById('activeConfigBody');
    
    if (!activeConfig) {
        container.innerHTML = `
            <div class="noConfig">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="8" x2="12" y2="12"></line>
                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
                <p>暂无激活的 AI 配置</p>
                <span>请先添加并激活一个配置</span>
            </div>
        `;
        return;
    }

    const providerLabel = activeConfig.provider === 'google' ? 'Google Gemini' : 'OpenAI';
    const maskedKey = activeConfig.apiKey || '****';

    container.innerHTML = `
        <div class="activeConfigDisplay">
            <div class="activeConfigIcon ${activeConfig.provider}">
                ${activeConfig.provider === 'google' ? 'G' : 'O'}
            </div>
            <div class="activeConfigInfo">
                <div class="activeConfigName">${activeConfig.name}</div>
                <div class="activeConfigMeta">
                    <span class="provider">${providerLabel}</span>
                    <span>模型: ${activeConfig.model}</span>
                    <span>Key: ${maskedKey}</span>
                </div>
            </div>
            <div class="activeConfigActions">
                <button class="btn btnOutline btnSmall" onclick="testConnectionForConfig(${activeConfig.id})">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
                    </svg>
                    测试
                </button>
                <button class="btn btnSecondary btnSmall" onclick="editConfig(${activeConfig.id})">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                        <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                    </svg>
                    编辑
                </button>
            </div>
        </div>
    `;
}

// 渲染配置列表
function renderConfigList() {
    const container = document.getElementById('configList');
    
    if (configs.length === 0) {
        container.innerHTML = `
            <div class="configListEmpty">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                    <line x1="12" y1="8" x2="12" y2="16"></line>
                    <line x1="8" y1="12" x2="16" y2="12"></line>
                </svg>
                <p>暂无配置</p>
                <span>点击上方按钮添加 AI 配置</span>
            </div>
        `;
        return;
    }

    container.innerHTML = configs.map(config => {
        const providerLabel = config.provider === 'google' ? 'Google Gemini' : 'OpenAI';
        return `
            <div class="configItem ${config.isActive ? 'active' : ''}">
                <div class="configItemIcon ${config.provider}">
                    ${config.provider === 'google' ? 'G' : 'O'}
                </div>
                <div class="configItemInfo">
                    <div class="configItemName">${config.name}</div>
                    <div class="configItemModel">${providerLabel} · ${config.model}</div>
                </div>
                <div class="configItemStatus ${config.isActive ? 'active' : 'inactive'}">
                    ${config.isActive ? '已激活' : '未激活'}
                </div>
                <div class="configItemActions">
                    ${!config.isActive ? `
                        <button class="btn btnSuccess btnSmall" onclick="activateConfig(${config.id})">
                            激活
                        </button>
                    ` : ''}
                    <button class="btn btnSecondary btnSmall" onclick="editConfig(${config.id})">
                        编辑
                    </button>
                    <button class="btn btnDanger btnSmall" onclick="deleteConfig(${config.id})">
                        删除
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

// 打开模态框
function openModal(config = null) {
    editingConfigId = config ? config.id : null;
    
    // 设置标题
    document.getElementById('modalTitle').textContent = config ? '编辑 AI 配置' : '添加 AI 配置';
    
    // 填充表单
    if (config) {
        document.getElementById('configId').value = config.id;
        document.getElementById('configName').value = config.name;
        document.getElementById('configProvider').value = config.provider;
        updateModelOptions(config.provider, config.model);
        document.getElementById('configApiKey').value = '';
        document.getElementById('apiKeyHint').textContent = '留空则不修改 API Key';
    } else {
        configForm.reset();
        document.getElementById('configId').value = '';
        modelSelect.innerHTML = '<option value="">请先选择提供商</option>';
        modelSelect.disabled = true;
        document.getElementById('apiKeyHint').textContent = '请输入有效的 API Key';
    }
    
    modal.classList.add('show');
}

// 关闭模态框
function closeModal() {
    modal.classList.remove('show');
    configForm.reset();
    editingConfigId = null;
}

// 更新模型选项
function updateModelOptions(provider, selectedModel = null) {
    if (!provider) {
        modelSelect.innerHTML = '<option value="">请先选择提供商</option>';
        modelSelect.disabled = true;
        return;
    }
    
    const models = MODELS[provider] || [];
    modelSelect.innerHTML = models.map(m => 
        `<option value="${m.value}" ${m.value === selectedModel ? 'selected' : ''}>${m.label}</option>`
    ).join('');
    modelSelect.disabled = false;
    
    // 更新 API Key 提示
    if (provider === 'google') {
        document.getElementById('apiKeyHint').innerHTML = 
            '获取 Key: <a href="https://aistudio.google.com/apikey" target="_blank">aistudio.google.com</a>';
    } else {
        document.getElementById('apiKeyHint').innerHTML = 
            '获取 Key: <a href="https://platform.openai.com/api-keys" target="_blank">platform.openai.com</a>';
    }
}

// 保存配置（调用后端 API）
async function saveConfig() {
    const name = document.getElementById('configName').value.trim();
    const provider = document.getElementById('configProvider').value;
    const model = document.getElementById('configModel').value;
    const apiKey = document.getElementById('configApiKey').value.trim();
    
    // 验证
    if (!name || !provider || !model) {
        showMessage('请填写完整配置信息', 'error');
        return;
    }
    
    if (!editingConfigId && !apiKey) {
        showMessage('请输入 API Key', 'error');
        return;
    }
    
    const requestBody = { name, provider, model };
    if (apiKey) {
        requestBody.apiKey = apiKey;
    }
    
    try {
        let response;
        if (editingConfigId) {
            // 编辑现有配置
            response = await fetch(`/api/llm-configs/${editingConfigId}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody)
            });
        } else {
            // 添加新配置
            response = await fetch('/api/llm-configs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody)
            });
        }
        
        const result = await response.json();
        
        if (result.code === 200) {
            closeModal();
            await loadConfigs(); // 重新加载列表
            showMessage(editingConfigId ? '配置已更新' : '配置已添加', 'success');
        } else {
            showMessage(result.message || '保存失败', 'error');
        }
    } catch (error) {
        console.error('保存配置失败:', error);
        showMessage('保存配置失败: ' + error.message, 'error');
    }
}

// 编辑配置
function editConfig(id) {
    const config = configs.find(c => c.id === id);
    if (config) {
        openModal(config);
    }
}

// 删除配置（调用后端 API）
async function deleteConfig(id) {
    if (!confirm('确定要删除这个配置吗？')) return;
    
    try {
        const response = await fetch(`/api/llm-configs/${id}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            await loadConfigs(); // 重新加载列表
            showMessage('配置已删除', 'success');
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (error) {
        console.error('删除配置失败:', error);
        showMessage('删除配置失败: ' + error.message, 'error');
    }
}

// 激活配置（调用后端 API）
async function activateConfig(id) {
    try {
        const response = await fetch(`/api/llm-configs/${id}/activate`, {
            method: 'POST'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            await loadConfigs(); // 重新加载列表
            const config = configs.find(c => c.id === id);
            showMessage(`已激活配置: ${config ? config.name : id}`, 'success');
        } else {
            showMessage(result.message || '激活失败', 'error');
        }
    } catch (error) {
        console.error('激活配置失败:', error);
        showMessage('激活配置失败: ' + error.message, 'error');
    }
}

// 测试连接（调用后端 API）
async function testConnection() {
    const provider = document.getElementById('configProvider').value;
    const model = document.getElementById('configModel').value;
    const apiKey = document.getElementById('configApiKey').value.trim();
    
    if (!provider || !model || !apiKey) {
        showMessage('请填写完整配置信息后再测试', 'error');
        return;
    }
    
    showTestResult('loading', '测试中...', '正在验证 API 连接');
    
    if (editingConfigId) {
        // 如果是编辑模式，调用后端测试
        await testConnectionForConfig(editingConfigId);
    } else {
        // 新增模式，先保存再测试，或简单验证 key 格式
        // 简单验证: key 长度
        setTimeout(() => {
            if (apiKey.length > 10) {
                showTestResult('success', '格式验证通过', 'API Key 格式正确，保存后可进行完整测试');
            } else {
                showTestResult('error', '格式验证失败', 'API Key 格式不正确');
            }
        }, 500);
    }
}

// 测试指定配置的连接（调用后端 API）
async function testConnectionForConfig(id) {
    const config = configs.find(c => c.id === id);
    showTestResult('loading', '测试中...', `正在验证 ${config ? config.name : '配置'}`);
    
    try {
        const response = await fetch(`/api/llm-configs/${id}/test`, {
            method: 'POST'
        });
        
        const result = await response.json();
        
        if (result.code === 200 && result.data) {
            const data = result.data;
            if (data.status === 'connected') {
                showTestResult('success', '连接成功', `${data.message}，延迟 ${data.latency}ms`);
            } else {
                showTestResult('error', '连接失败', data.message || '未知错误');
            }
        } else {
            showTestResult('error', '测试失败', result.message || '请求失败');
        }
    } catch (error) {
        console.error('测试连接失败:', error);
        showTestResult('error', '测试失败', '网络错误: ' + error.message);
    }
}

// 显示测试结果
function showTestResult(type, title, message) {
    const toast = document.getElementById('testResultToast');
    const toastTitle = toast.querySelector('.toastTitle');
    const toastMessage = toast.querySelector('.toastMessage');
    
    toast.className = 'testResultToast show ' + type;
    toastTitle.textContent = title;
    toastMessage.textContent = message;
    
    if (type !== 'loading') {
        setTimeout(() => {
            toast.classList.remove('show');
        }, 4000);
    }
}

// 显示消息
function showMessage(message, type = 'info') {
    // 使用 common.js 中的 showMessage 或创建简单提示
    if (typeof window.showMessage === 'function') {
        window.showMessage(message, type);
    } else {
        alert(message);
    }
}
