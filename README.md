# FileBrowser 文件上传工具

这是一个用于 Jenkins 的共享库函数，用于将文件上传到 FileBrowser 服务器。该工具支持安全的认证、自动目录创建，以及完整的错误处理和日志记录。

## 功能特性

- ✅ **安全认证**: 通过 Jenkins 凭据管理系统安全处理用户名和密码
- ✅ **自动覆盖**: 总是覆盖已存在的文件
- ✅ **参数验证**: 完整的输入参数验证和错误处理
- ✅ **详细日志**: 提供清晰的上传进度和状态反馈
- ✅ **错误处理**: 完善的异常捕获和错误报告
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
// 引入共享库
@Library('your-shared-library-name') _

pipeline {
    agent any

    stages {
        stage('Upload to FileBrowser') {
            steps {
                script {
                    // 基本用法
                    uploadFile2FB url: 'http://filebrowser.example.com',
                                file: 'path/to/local/file.txt',
                                credentialsId: 'filebrowser-creds'

                    // 指定远程目录
                    uploadFile2FB url: 'https://filebrowser.example.com',
                                file: 'build/output.zip',
                                remoteDir: '/uploads/releases',
                                credentialsId: 'filebrowser-creds'

                    // 使用其他凭据
                    uploadFile2FB url: 'http://filebrowser.example.com',
                                file: 'docs/manual.pdf',
                                remoteDir: '/documentation',
                                credentialsId: 'custom-fb-credentials'
                }
            }
        }
    }
}
```

## 参数说明

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `url` | String | ✅ | - | FileBrowser 服务器地址，以 `http://` 或 `https://` 开头 |
| `file` | String | ✅ | - | 本地文件路径（相对于工作区或绝对路径） |
| `remoteDir` | String | ❌ | `/` | 远程目录路径 |
| `credentialsId` | String | ✅ | - | Jenkins 凭据 ID |

## 使用示例

### 示例 1: 基本文件上传
```groovy
uploadFile2FB url: 'http://filebrowser.company.com',
            file: 'target/application.jar',
            credentialsId: 'filebrowser-creds'
```

### 示例 2: 上传到指定目录
```groovy
uploadFile2FB url: 'https://files.company.com',
            file: 'dist/bundle.tar.gz',
            remoteDir: '/deployments/production',
            credentialsId: 'filebrowser-creds'
```

### 示例 3: 使用自定义凭据
```groovy
uploadFile2FB url: 'http://filebrowser.company.com',
            file: 'backup/database.sql',
            remoteDir: '/backups/daily',
            credentialsId: 'filebrowser-backup-creds'
```

### 示例 4: 在 CI/CD 流水线中使用
```groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'npm run build'
            }
        }

        stage('Upload Artifacts') {
            steps {
                script {
                    // 上传构建产物
                    uploadFile2FB url: 'https://artifacts.company.com',
                                file: 'dist/index.html',
                                remoteDir: '/website/latest',
                                credentialsId: 'filebrowser-creds'

                    // 上传源码映射
                    uploadFile2FB url: 'https://artifacts.company.com',
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

执行成功时的日志输出：
```
准备上传文件: target/app.jar -> /deployments
FileBrowser 服务器: https://files.company.com
🔐 正在获取认证令牌...
✅ 认证令牌获取成功
📁 开始上传文件: app.jar
📤 上传到: https://files.company.com/api/resources/deployments/app.jar?override=true
✅ 文件上传成功!
🔗 访问路径: https://files.company.com/files/deployments/app.jar
文件上传成功!
```

## 错误处理

该工具包含完整的错误处理机制：

### 常见错误类型

1. **参数错误**
   ```
   ❌ FileBrowser URL 必填
   ❌ 本地文件路径必填
   ❌ Jenkins 凭据 ID 必填
   ❌ FileBrowser URL 格式无效，应以 http:// 或 https:// 开头
   ```

2. **文件不存在**
   ```
   ❌ 本地文件不存在: target/nonexistent.txt
   ```

3. **认证失败**
   ```
   ❌ FileBrowser 登录失败 (HTTP 401)
   ```

4. **上传失败**
   ```
   ❌ 文件上传失败 (HTTP 500)
   ```

## 安全注意事项

1. **凭据安全**:
   - 使用 Jenkins 凭据管理系统，不要在代码中硬编码用户名密码
   - 所有敏感信息（密码、token）都不会输出到日志中

2. **路径安全**:
   - 验证 URL 格式，防止注入攻击
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

### 问题: 证书验证失败
**解决方案**:
- 代码已使用 `-k` 参数跳过 SSL 证书验证
- 如需严格证书验证，可修改代码移除 `-k` 参数

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个工具。

## 许可证

本项目采用 MIT 许可证。