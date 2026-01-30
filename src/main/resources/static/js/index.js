// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', () => {
    initSearch();
});

// 初始化搜索功能
function initSearch() {
    const searchInput = document.getElementById('domainName');
    const searchButton = document.getElementById('searchButton');

    // 点击搜索按钮
    searchButton.addEventListener('click', performSearch);

    // 回车键搜索
    searchInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            performSearch();
        }
    });
}

// 执行搜索
function performSearch() {
    const searchInput = document.getElementById('domainName');
    const domain = searchInput.value.trim();

    if (!domain) {
        showMessage('请输入域名', 'error');
        return;
    }

    // 显示加载状态
    showLoadingState();

    // 模拟搜索（这里需要替换为实际的API调用）
    setTimeout(() => {
        displayResults(domain);
    }, 1500);
}

// 显示加载状态
function showLoadingState() {
    document.getElementById('subdomainContent').innerHTML = '<p class="emptyText">正在检索子域名...</p>';
    document.getElementById('assetsContent').innerHTML = '<p class="emptyText">正在加载资产路径...</p>';
    document.getElementById('loginPathContent').innerHTML = '<p class="emptyText">正在识别登录口...</p>';
}

// 显示搜索结果
function displayResults(domain) {
    // 1. 爆破子域名
    const subdomains = [
        `www.${domain}`,
        `api.${domain}`,
        `admin.${domain}`,
        `mail.${domain}`,
        `ftp.${domain}`,
        `dev.${domain}`,
        `test.${domain}`,
        `staging.${domain}`
    ];

    document.getElementById('subdomainContent').innerHTML = `
        <ul class="dataList">
            ${subdomains.map(sub => `<li class="dataItem">${sub}</li>`).join('')}
        </ul>
    `;

    // 2. 相关资产路径
    const assetPaths = [
        '/admin',
        '/api/v1',
        '/api/v2',
        '/static/assets',
        '/uploads',
        '/dashboard',
        '/config',
        '/backup',
        '/downloads',
        '/images'
    ];

    document.getElementById('assetsContent').innerHTML = `
        <ul class="dataList">
            ${assetPaths.map(path => `<li class="dataItem">${path}</li>`).join('')}
        </ul>
    `;

    // 3. 登录口的路径和验证因子（使用多列布局）
    document.getElementById('loginPathContent').innerHTML = `
        <div class="dataGrid">
            <div>
                <ul class="dataList">
                    <li class="dataItem"><strong>登录路径：</strong> /admin/login</li>
                    <li class="dataItem"><strong>备用登录：</strong> /user/signin</li>
                    <li class="dataItem"><strong>API登录：</strong> /api/auth/login</li>
                </ul>
            </div>
            <div>
                <ul class="dataList">
                    <li class="dataItem"><strong>验证方式：</strong> 用户名 + 密码</li>
                    <li class="dataItem"><strong>CSRF Token：</strong> 已启用</li>
                    <li class="dataItem"><strong>验证码：</strong> 图形验证码</li>
                </ul>
            </div>
            <div>
                <ul class="dataList">
                    <li class="dataItem"><strong>双因素认证：</strong> 未启用</li>
                    <li class="dataItem"><strong>会话管理：</strong> Cookie + JWT</li>
                    <li class="dataItem"><strong>密码策略：</strong> 8位以上，包含特殊字符</li>
                </ul>
            </div>
        </div>
    `;
}