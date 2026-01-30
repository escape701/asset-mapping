let currentEditingUserId = null;

// 加载用户列表
async function loadUsers() {
    try {
        const response = await fetch('/api/users');
        const result = await response.json();
        
        const tbody = document.getElementById('userTableBody');
        
        if (result.code === 200 && result.data.length > 0) {
            tbody.innerHTML = result.data.map(user => `
                <tr>
                    <td>${user.userId}</td>
                    <td>${user.username}</td>
                    <td>${user.realName || '-'}</td>
                    <td>${user.email || '-'}</td>
                    <td>${user.phone || '-'}</td>
                    <td>
                        <span class="statusBadge ${user.status === 1 ? 'statusActive' : 'statusInactive'}">
                            ${user.status === 1 ? '启用' : '禁用'}
                        </span>
                    </td>
                    <td>${formatDateTime(user.createTime)}</td>
                    <td>
                        <div class="actionButtons">
                            <button class="btn btnSuccess" onclick="editUser(${user.userId})">编辑</button>
                            <button class="btn btnDanger" onclick="deleteUser(${user.userId}, '${user.username}')">删除</button>
                        </div>
                    </td>
                </tr>
            `).join('');
        } else {
            tbody.innerHTML = '<tr><td colspan="8" class="emptyMessage">暂无数据</td></tr>';
        }
    } catch (error) {
        console.error('加载用户列表失败:', error);
        showMessage('加载用户列表失败', 'error');
    }
}

// 显示添加用户模态框
function showAddUserModal() {
    currentEditingUserId = null;
    
    document.getElementById('modalTitle').textContent = '添加用户';
    document.getElementById('userId').value = '';
    document.getElementById('formUsername').value = '';
    document.getElementById('formPassword').value = '';
    document.getElementById('formPassword').required = true;
    document.getElementById('passwordHint').style.display = 'none';
    document.getElementById('formRealName').value = '';
    document.getElementById('formEmail').value = '';
    document.getElementById('formPhone').value = '';
    document.getElementById('formStatus').value = '1';
    
    document.getElementById('userModal').classList.add('show');
}

// 编辑用户
async function editUser(userId) {
    try {
        const response = await fetch(`/api/users/${userId}`);
        const result = await response.json();
        
        if (result.code === 200) {
            currentEditingUserId = userId;
            const user = result.data;
            
            document.getElementById('modalTitle').textContent = '编辑用户';
            document.getElementById('userId').value = user.userId;
            document.getElementById('formUsername').value = user.username;
            document.getElementById('formPassword').value = '';
            document.getElementById('formPassword').required = false;
            document.getElementById('passwordHint').style.display = 'inline';
            document.getElementById('formRealName').value = user.realName || '';
            document.getElementById('formEmail').value = user.email || '';
            document.getElementById('formPhone').value = user.phone || '';
            document.getElementById('formStatus').value = user.status;
            
            document.getElementById('userModal').classList.add('show');
        } else {
            showMessage(result.message || '获取用户信息失败', 'error');
        }
    } catch (error) {
        console.error('获取用户信息失败:', error);
        showMessage('获取用户信息失败', 'error');
    }
}

// 删除用户
async function deleteUser(userId, username) {
    if (!confirm(`确定要删除用户 "${username}" 吗？`)) {
        return;
    }
    
    try {
        const response = await fetch(`/api/users/${userId}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadUsers();
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (error) {
        console.error('删除用户失败:', error);
        showMessage('删除用户失败', 'error');
    }
}

// 关闭模态框
function closeModal() {
    document.getElementById('userModal').classList.remove('show');
    currentEditingUserId = null;
}

// 保存用户
async function saveUser(event) {
    event.preventDefault();
    
    const formData = {
        username: document.getElementById('formUsername').value.trim(),
        password: document.getElementById('formPassword').value,
        realName: document.getElementById('formRealName').value.trim(),
        email: document.getElementById('formEmail').value.trim(),
        phone: document.getElementById('formPhone').value.trim(),
        status: parseInt(document.getElementById('formStatus').value)
    };
    
    // 如果是编辑且密码为空，则移除密码字段
    if (currentEditingUserId && !formData.password) {
        delete formData.password;
    }
    
    try {
        let url, method;
        
        if (currentEditingUserId) {
            // 更新用户
            url = `/api/users/${currentEditingUserId}`;
            method = 'PUT';
        } else {
            // 创建用户
            url = '/api/users';
            method = 'POST';
        }
        
        const response = await fetch(url, {
            method: method,
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(formData)
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            showMessage(currentEditingUserId ? '更新成功' : '添加成功', 'success');
            closeModal();
            loadUsers();
        } else {
            showMessage(result.message || '操作失败', 'error');
        }
    } catch (error) {
        console.error('保存用户失败:', error);
        showMessage('保存用户失败', 'error');
    }
}

// 页面加载完成后执行
document.addEventListener('DOMContentLoaded', () => {
    // 加载用户列表
    loadUsers();
    
    // 绑定添加用户按钮
    document.getElementById('addUserButton').addEventListener('click', showAddUserModal);
    
    // 绑定关闭模态框按钮
    document.getElementById('closeModal').addEventListener('click', closeModal);
    document.getElementById('cancelButton').addEventListener('click', closeModal);
    
    // 点击模态框外部关闭
    document.getElementById('userModal').addEventListener('click', (e) => {
        if (e.target.id === 'userModal') {
            closeModal();
        }
    });
    
    // 绑定提交按钮
    document.getElementById('submitButton').addEventListener('click', saveUser);
});

