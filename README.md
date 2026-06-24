# FileBrowser Jenkins 共享库

用于与 FileBrowser 服务器交互的 Jenkins 共享库，支持文件上传和删除操作。提供安全的认证、自动目录创建，以及完整的错误处理和日志记录。

## 功能特性

- ✅ **安全认证**: 通过 Jenkins 凭据管理系统安全处理用户名和密码
- ✅ **文件上传**: 上传文件到指定远程目录，自动覆盖已存在的文件
- ✅ **文件删除**: 删除远程文件或目录（递归），拒绝删除根路径以防误操作
- ✅ **参数验证**: 完整的输入参数验证和错误处理
- ✅ **详细日志**: 提供清晰的操作进度和状态反馈
- ✅ **安全性**: 敏感信息（密码、token）不会输出到日志

## 安装要求

### Jenkins 插件依赖
- **Credentials Binding Plugin**: 用于凭据管理
- **Pipeline**: 用于 Jenkins 流水线支持

### 系统工具依赖
- `curl`: 用于 HTTP 请求

## 使用方法

### 1. 在 Jenkins 中配置凭据

在 Jenkins 凭据管理系统中添加用户名密码类型的凭据：
- **类型**: Username with password
- **ID**: 建议使用 `fb-yunke-icu`（或自定义 ID）
- **用户名**: FileBrowser 用户名
- **密码**: FileBrowser 密码

### 2. 在 Jenkinsfile 中使用

```groovy
@Library('your-shared-library-name') _

pipeline {
    agent any

    stages {
        stage('Deploy') {
            steps {
                script {
                    // 上传文件
                    filebrowser.upload url: 'https://filebrowser.example.com',
                                       file: 'build/output.zip',
                                       remoteDir: '/uploads/releases',
                                       credentialsId: 'filebrowser-creds'

                    // 删除旧版本
                    filebrowser.delete url: 'https://filebrowser.example.com',
                                       path: '/uploads/releases/old-output.zip',
                                       credentialsId: 'filebrowser-creds'
                }
            }
        }
    }
}
```

## API 说明

### filebrowser.upload

上传本地文件到 FileBrowser 服务器。

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `url` | String | ✅ | - | FileBrowser 服务器地址，以 `http://` 或 `https://` 开头 |
| `file` | String | ✅ | - | 本地文件路径（相对于工作区或绝对路径） |
| `remoteDir` | String | ❌ | `/` | 远程目录路径 |
| `credentialsId` | String | ✅ | - | Jenkins 凭据 ID |

**返回值**: `int` — HTTP 状态码。成功时返回 `200`/`201`/`204`，失败时返回实际状态码（如 `401`、`403`、`500`）。

### filebrowser.delete

删除 FileBrowser 服务器上的远程文件或目录（递归删除）。

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `url` | String | ✅ | - | FileBrowser 服务器地址，以 `http://` 或 `https://` 开头 |
| `path` | String | ✅ | - | 远程文件/目录完整路径，不可为 `/` |
| `credentialsId` | String | ✅ | - | Jenkins 凭据 ID |

**返回值**: `int` — HTTP 状态码。成功时返回 `200`/`202`/`204`，失败时返回实际状态码（如 `401`、`403`、`404`）。

## 使用示例

### 示例 1: 基本文件上传
```groovy
filebrowser.upload url: 'http://filebrowser.company.com',
                   file: 'target/application.jar',
                   credentialsId: 'filebrowser-creds'
```

### 示例 2: 上传到指定目录
```groovy
filebrowser.upload url: 'https://files.company.com',
                   file: 'dist/bundle.tar.gz',
                   remoteDir: '/deployments/production',
                   credentialsId: 'filebrowser-creds'
```

### 示例 3: 删除远程文件
```groovy
filebrowser.delete url: 'https://files.company.com',
                   path: '/deployments/production/old-bundle.tar.gz',
                   credentialsId: 'filebrowser-creds'
```

### 示例 4: 删除远程目录
```groovy
filebrowser.delete url: 'https://files.company.com',
                   path: '/deployments/staging',
                   credentialsId: 'filebrowser-creds'
```

### 示例 5: 根据返回值判断操作结果
```groovy
def code = filebrowser.upload url: 'https://files.company.com',
                              file: 'dist/app.zip',
                              remoteDir: '/releases',
                              credentialsId: 'filebrowser-creds'

if (code in [200, 201, 204]) {
    echo "上传成功 (HTTP ${code})"
} else {
    error "上传失败 (HTTP ${code})"
}
```

### 示例 6: CI/CD 流水线
```groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'npm run build'
            }
        }

        stage('Deploy') {
            steps {
                script {
                    // 删除旧版本
                    filebrowser.delete url: 'https://artifacts.company.com',
                                       path: '/website/latest',
                                       credentialsId: 'filebrowser-creds'

                    // 上传构建产物
                    filebrowser.upload url: 'https://artifacts.company.com',
                                       file: 'dist/index.html',
                                       remoteDir: '/website/latest',
                                       credentialsId: 'filebrowser-creds'

                    filebrowser.upload url: 'https://artifacts.company.com',
                                       file: 'dist/main.js.map',
                                       remoteDir: '/website/latest',
                                       credentialsId: 'filebrowser-creds'
                }
            }
        }
    }
}
```

## 输出示例

### 上传成功
```
准备上传文件: target/app.jar -> /deployments
FileBrowser 服务器: https://files.company.com
🔐 正在获取认证令牌...
✅ 认证令牌获取成功
📁 开始上传文件: app.jar
✅ 文件上传成功!
文件上传成功!
```

### 删除成功
```
准备删除远程路径: /deployments/old-app.jar
FileBrowser 服务器: https://files.company.com
🔐 正在获取认证令牌...
✅ 认证令牌获取成功
🗑️ 开始删除远程资源: /deployments/old-app.jar
✅ 远程资源删除成功! 路径: /deployments/old-app.jar
远程资源删除成功!
```

## 错误处理

### 常见错误类型

1. **参数错误**
   ```
   ❌ FileBrowser URL 必填
   ❌ 本地文件路径必填
   ❌ 远程路径必填
   ❌ Jenkins 凭据 ID 必填
   ❌ FileBrowser URL 格式无效，应以 http:// 或 https:// 开头
   ```

2. **文件不存在**
   ```
   ❌ 本地文件不存在: target/nonexistent.txt
   ```

3. **删除根路径保护**
   ```
   ❌ 不允许删除根路径 /
   ```

4. **认证失败**
   ```
   ❌ 登录失败 (HTTP 401)
   ```

5. **操作失败**
   ```
   ❌ 文件上传失败 (HTTP 500)
   ❌ 远程资源删除失败 (HTTP 404)
   ```

## 安全注意事项

1. **凭据安全**:
   - 使用 Jenkins 凭据管理系统，不要在代码中硬编码用户名密码
   - 所有敏感信息（密码、token）都不会输出到日志中

2. **路径安全**:
   - 验证 URL 格式，防止注入攻击
   - 删除操作拒绝根路径 `/`，防止误删全部数据
   - 文件路径通过 Jenkins 内置安全机制处理

3. **网络安全**:
   - 支持 HTTPS 协议，支持 `-k` 参数跳过 SSL 证书验证
   - 生产环境建议使用 HTTPS

## 故障排除

### 问题: 上传失败，提示文件不存在
**解决方案**:
- 检查文件路径是否正确
- 确认文件在 Jenkins 工作区中存在
- 使用绝对路径或相对于工作区的路径

### 问题: 认证失败
**解决方案**:
- 检查 FileBrowser 服务器地址是否正确
- 验证 Jenkins 凭据配置是否正确
- 确认用户名密码是否正确

### 问题: 删除失败，提示 404
**解决方案**:
- 确认远程路径是否正确
- 检查文件是否已被删除

### 问题: 删除失败，提示 403
**解决方案**:
- 确认用户是否拥有删除权限
- 确认不是在尝试删除根路径

### 问题: 证书验证失败
**解决方案**:
- 代码已使用 `-k` 参数跳过 SSL 证书验证
- 如需严格证书验证，可修改代码移除 `-k` 参数

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个工具。

## 许可证

本项目采用 MIT 许可证。
