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

// 配置列表（模拟数据，实际从后端获取）
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

// 加载配置列表
function loadConfigs() {
    // TODO: 从后端 API 获取配置列表
    // 暂时使用模拟数据
    loadMockConfigs();
}

// 加载模拟数据
function loadMockConfigs() {
    // 从 localStorage 读取配置（模拟持久化）
    const savedConfigs = localStorage.getItem('llm_configs');
    if (savedConfigs) {
        configs = JSON.parse(savedConfigs);
        activeConfig = configs.find(c => c.isActive) || null;
    } else {
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
    const maskedKey = maskApiKey(activeConfig.apiKey);

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
        document.getElementById('configApiKey').value = config.apiKey;
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

// 保存配置
function saveConfig() {
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
    
    if (editingConfigId) {
        // 编辑现有配置
        const index = configs.findIndex(c => c.id === editingConfigId);
        if (index !== -1) {
            configs[index].name = name;
            configs[index].provider = provider;
            configs[index].model = model;
            if (apiKey) {
                configs[index].apiKey = apiKey;
            }
            
            if (configs[index].isActive) {
                activeConfig = configs[index];
            }
        }
    } else {
        // 添加新配置
        const newConfig = {
            id: Date.now(),
            name,
            provider,
            model,
            apiKey,
            isActive: configs.length === 0,  // 第一个配置自动激活
            createdAt: new Date().toISOString()
        };
        configs.push(newConfig);
        
        if (newConfig.isActive) {
            activeConfig = newConfig;
        }
    }
    
    // 保存到 localStorage（模拟持久化）
    saveConfigsToStorage();
    
    closeModal();
    renderConfigs();
    showMessage(editingConfigId ? '配置已更新' : '配置已添加', 'success');
}

// 编辑配置
function editConfig(id) {
    const config = configs.find(c => c.id === id);
    if (config) {
        openModal(config);
    }
}

// 删除配置
function deleteConfig(id) {
    if (!confirm('确定要删除这个配置吗？')) return;
    
    const index = configs.findIndex(c => c.id === id);
    if (index !== -1) {
        const wasActive = configs[index].isActive;
        configs.splice(index, 1);
        
        // 如果删除的是激活配置，清空 activeConfig
        if (wasActive) {
            activeConfig = null;
            // 如果还有其他配置，激活第一个
            if (configs.length > 0) {
                configs[0].isActive = true;
                activeConfig = configs[0];
            }
        }
        
        saveConfigsToStorage();
        renderConfigs();
        showMessage('配置已删除', 'success');
    }
}

// 激活配置
function activateConfig(id) {
    // 取消所有配置的激活状态
    configs.forEach(c => c.isActive = false);
    
    // 激活指定配置
    const config = configs.find(c => c.id === id);
    if (config) {
        config.isActive = true;
        activeConfig = config;
        
        saveConfigsToStorage();
        renderConfigs();
        showMessage(`已激活配置: ${config.name}`, 'success');
    }
}

// 测试连接
function testConnection() {
    const provider = document.getElementById('configProvider').value;
    const model = document.getElementById('configModel').value;
    const apiKey = document.getElementById('configApiKey').value.trim();
    
    if (!provider || !model || !apiKey) {
        showMessage('请填写完整配置信息后再测试', 'error');
        return;
    }
    
    showTestResult('loading', '测试中...', '正在验证 API 连接');
    
    // 模拟测试请求
    // TODO: 实际调用后端 API 测试
    setTimeout(() => {
        // 模拟成功或失败
        const success = apiKey.length > 10;
        if (success) {
            showTestResult('success', '连接成功', `${provider === 'google' ? 'Gemini' : 'OpenAI'} API 响应正常`);
        } else {
            showTestResult('error', '连接失败', 'API Key 无效或网络错误');
        }
    }, 1500);
}

// 测试指定配置的连接
function testConnectionForConfig(id) {
    const config = configs.find(c => c.id === id);
    if (!config) return;
    
    showTestResult('loading', '测试中...', `正在验证 ${config.name}`);
    
    // TODO: 实际调用后端 API 测试
    setTimeout(() => {
        showTestResult('success', '连接成功', `${config.name} 响应正常，延迟 ${Math.floor(Math.random() * 200 + 100)}ms`);
    }, 1500);
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

// 保存配置到 localStorage
function saveConfigsToStorage() {
    localStorage.setItem('llm_configs', JSON.stringify(configs));
    
    // 同时生成 YAML 格式（用于展示，实际由后端生成）
    generateYamlPreview();
}

// 生成 YAML 预览（仅用于调试）
function generateYamlPreview() {
    if (!activeConfig) return;
    
    console.log('=== Generated YAML Preview ===');
    console.log(`# 自动生成的 LLM 配置`);
    console.log(`# Generated at: ${new Date().toISOString()}\n`);
    
    const googleConfig = configs.find(c => c.provider === 'google');
    const openaiConfig = configs.find(c => c.provider === 'openai');
    
    if (googleConfig) {
        console.log(`google:`);
        console.log(`  api_key: "${googleConfig.apiKey}"`);
        console.log(`  model: "${googleConfig.model}"\n`);
    }
    
    if (openaiConfig) {
        console.log(`openai:`);
        console.log(`  api_key: "${openaiConfig.apiKey}"`);
        console.log(`  model: "${openaiConfig.model}"\n`);
    }
    
    console.log(`active_provider: ${activeConfig.provider}`);
    console.log('==============================');
}

// 遮蔽 API Key
function maskApiKey(key) {
    if (!key || key.length < 8) return '****';
    return key.substring(0, 4) + '****' + key.substring(key.length - 4);
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
