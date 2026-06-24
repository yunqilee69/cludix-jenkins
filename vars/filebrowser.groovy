/**
 * FileBrowser Jenkins 共享库 — 分组 API
 *
 * 提供与 FileBrowser 服务器交互的相关方法：
 *   - filebrowser.upload  上传文件
 *   - filebrowser.delete  删除远程文件/目录
 *
 * 使用说明：
 * 此脚本需要安装 Pipeline Utility Steps 插件才能正常使用。
 * 该插件提供了 readJSON 函数用于解析 JSON 响应。
 * 请在 Jenkins 管理界面中确保已安装 Pipeline Utility Steps 插件。
 */

// ─── 上传 ───────────────────────────────────────────────

/**
 * 上传文件到 FileBrowser 服务器
 *
 * @param args Map 包含以下参数:
 *   - url: FileBrowser 服务器地址 (必填)
 *   - file: 本地文件路径 (必填)
 *   - remoteDir: 远程目录路径 (可选，默认为 '/')
 *   - credentialsId: Jenkins 凭据 ID (必填)
 */
def upload(Map args) {
    validateUploadParameters(args)

    def fbUrl     = args.url.replaceAll(/\/+$/, '')
    def localFile = args.file
    def remoteDir = normalizePath(args.remoteDir ?: '/')
    def credentialsId = args.credentialsId

    // 验证本地文件是否存在
    if (!fileExists(localFile)) {
        error "本地文件不存在: ${localFile}"
    }

    echo "准备上传文件: ${localFile} -> ${remoteDir}"
    echo "FileBrowser 服务器: ${fbUrl}"

    // 通过 Jenkins 凭据注入账号密码
    withCredentials([usernamePassword(credentialsId: credentialsId,
                                      usernameVariable: 'FB_USER',
                                      passwordVariable: 'FB_PASS')]) {
        try {
            // 获取认证令牌
            def token = getAuthToken(fbUrl, FB_USER, FB_PASS)

            // 上传文件，返回 HTTP 状态码
            def httpCode = uploadFile(fbUrl, token, localFile, remoteDir)

            if (httpCode in ['200', '201', '204']) {
                echo "文件上传成功! HTTP ${httpCode}"
            } else {
                echo "文件上传失败! HTTP ${httpCode}"
            }

            return httpCode.toInteger()

        } catch (Exception e) {
            error "文件上传失败: ${e.getMessage()}"
        }
    }
}

// ─── 删除 ───────────────────────────────────────────────

/**
 * 删除 FileBrowser 服务器上的远程文件或目录
 *
 * @param args Map 包含以下参数:
 *   - url: FileBrowser 服务器地址 (必填)
 *   - path: 远程文件/目录完整路径 (必填，不可为 '/')
 *   - credentialsId: Jenkins 凭据 ID (必填)
 */
def delete(Map args) {
    validateDeleteParameters(args)

    def fbUrl        = args.url.replaceAll(/\/+$/, '')
    def remotePath   = normalizePath(args.path)
    def credentialsId = args.credentialsId

    // 拒绝删除根路径，防止误操作
    if (remotePath == '/') {
        error '❌ 不允许删除根路径 /'
    }

    echo "准备删除远程路径: ${remotePath}"
    echo "FileBrowser 服务器: ${fbUrl}"

    // 通过 Jenkins 凭据注入账号密码
    withCredentials([usernamePassword(credentialsId: credentialsId,
                                      usernameVariable: 'FB_USER',
                                      passwordVariable: 'FB_PASS')]) {
        try {
            // 获取认证令牌
            def token = getAuthToken(fbUrl, FB_USER, FB_PASS)

            // 删除远程资源，返回 HTTP 状态码
            def httpCode = deleteResource(fbUrl, token, remotePath)

            if (httpCode in ['200', '202', '204']) {
                echo "远程资源删除成功! HTTP ${httpCode}"
            } else {
                echo "远程资源删除失败! HTTP ${httpCode}"
            }

            return httpCode.toInteger()

        } catch (Exception e) {
            error "远程资源删除失败: ${e.getMessage()}"
        }
    }
}

// ─── 参数验证 ───────────────────────────────────────────

/**
 * 验证上传参数
 */
private def validateUploadParameters(Map args) {
    if (!args.url) {
        error 'FileBrowser URL 必填'
    }
    if (!args.file) {
        error '本地文件路径必填'
    }
    if (!args.credentialsId) {
        error 'Jenkins 凭据 ID 必填'
    }

    // 验证 URL 格式
    if (!(args.url ==~ /^https?:\/\/.+/)) {
        error 'FileBrowser URL 格式无效，应以 http:// 或 https:// 开头'
    }
}

/**
 * 验证删除参数
 */
private def validateDeleteParameters(Map args) {
    if (!args.url) {
        error 'FileBrowser URL 必填'
    }
    if (!args.path) {
        error '远程路径必填'
    }
    if (!args.credentialsId) {
        error 'Jenkins 凭据 ID 必填'
    }

    // 验证 URL 格式
    if (!(args.url ==~ /^https?:\/\/.+/)) {
        error 'FileBrowser URL 格式无效，应以 http:// 或 https:// 开头'
    }
}

// ─── 认证 ───────────────────────────────────────────────

/**
 * 获取认证令牌
 */
private def getAuthToken(String fbUrl, String username, String password) {
    echo "🔐 正在获取认证令牌..."

    def raw = sh(
        script: """#!/bin/sh
                   set +x
                   curl -s -w "%{http_code}" -X POST '${fbUrl}/api/login' \\
                        -H "Content-Type: application/json" \\
                        -d '{"username":"${username}","password":"${password}"}'
               """,
        returnStdout: true
    ).trim()

    // 从响应中提取HTTP状态码（最后3个字符）
    def httpCode = raw.substring(raw.length() - 3)
    // 响应内容是HTTP状态码之前的部分
    def response = raw.substring(0, raw.length() - 3)

    if (httpCode != '200') {
        error "❌ 登录失败\n请求: POST ${fbUrl}/api/login\nHTTP状态码: ${httpCode}\n响应: ${response}"
    }

    echo "✅ 认证令牌获取成功，响应: ${response}"
    return response
}

// ─── 上传实现 ───────────────────────────────────────────

/**
 * 上传文件
 */
private def uploadFile(String fbUrl, String token, String localFile, String remoteDir) {
    def fileName = getFileName(localFile)
    def targetPath = "${remoteDir.endsWith('/') ? remoteDir : remoteDir + '/'}${fileName}"

    echo "📁 开始上传文件: ${fileName}"

    // 验证文件存在
    if (!fileExists(localFile)) {
        error "❌ 本地文件不存在: ${localFile}"
    }

    def uploadUrl = "${fbUrl}/api/resources${targetPath}?override=true"

    def raw = sh(
        script: """#!/bin/sh
                   set +x
                   curl -k -s -w "%{http_code}" -X POST '${uploadUrl}' \\
                        -H 'x-auth: ${token}' \\
                        -F 'file=@${localFile}'
               """,
        returnStdout: true
    ).trim()

    def httpCode = raw.substring(raw.length() - 3)
    def response = raw.substring(0, raw.length() - 3)

    if (httpCode in ['200', '201', '204']) {
        echo "✅ 文件上传成功! 响应: ${response}"
    } else {
        echo "❌ 文件上传失败\n请求: POST ${uploadUrl}\nHTTP状态码: ${httpCode}\n响应: ${response}"
    }

    return httpCode
}

// ─── 删除实现 ───────────────────────────────────────────

/**
 * 删除远程资源
 * FileBrowser API: DELETE /api/resources${path}
 * 成功状态码: 200, 202, 204
 */
private def deleteResource(String fbUrl, String token, String remotePath) {
    echo "🗑️ 开始删除远程资源: ${remotePath}"

    def deleteUrl = "${fbUrl}/api/resources${remotePath}"

    def raw = sh(
        script: """#!/bin/sh
                   set +x
                   curl -k -s -w "%{http_code}" -X DELETE '${deleteUrl}' \\
                        -H 'x-auth: ${token}'
               """,
        returnStdout: true
    ).trim()

    def httpCode = raw.substring(raw.length() - 3)
    def response = raw.substring(0, raw.length() - 3)

    if (httpCode in ['200', '202', '204']) {
        echo "✅ 远程资源删除成功! 路径: ${remotePath}，响应: ${response}"
    } else {
        echo "❌ 远程资源删除失败\n请求: DELETE ${deleteUrl}\nHTTP状态码: ${httpCode}\n响应: ${response}"
    }

    return httpCode
}

// ─── 工具方法 ───────────────────────────────────────────

/**
 * 从完整路径中提取文件名
 */
private def getFileName(String filePath) {
    return filePath.tokenize('/')[-1]
}

/**
 * 规范化路径：去除多余的斜杠
 * 例如: "//path//to//dir//" -> "/path/to/dir"
 *       "/" -> "/"
 */
private def normalizePath(String path) {
    def normalized = path.replaceAll(/\/+/, '/')
    if (!normalized.startsWith('/')) {
        normalized = '/' + normalized
    }
    if (normalized.length() > 1 && normalized.endsWith('/')) {
        normalized = normalized.substring(0, normalized.length() - 1)
    }
    return normalized
}
