/**
 * 上传文件到 FileBrowser 服务器的 Jenkins 共享库函数
 *
 * 使用说明：
 * 此脚本需要安装 Pipeline Utility Steps 插件才能正常使用。
 * 该插件提供了 readJSON 函数用于解析 JSON 响应。
 * 请在 Jenkins 管理界面中确保已安装 Pipeline Utility Steps 插件。
 *
 * @param args Map 包含以下参数:
 *   - url: FileBrowser 服务器地址 (必填)
 *   - file: 本地文件路径 (必填)
 *   - remoteDir: 远程目录路径 (可选，默认为 '/')
 *   - credentialsId: Jenkins 凭据 ID (必填)
 */
def call(Map args) {
    // 参数验证
    validateParameters(args)

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

            // 上传文件
            uploadFile(fbUrl, token, localFile, remoteDir)

            echo "文件上传成功!"

        } catch (Exception e) {
            error "文件上传失败: ${e.getMessage()}"
        }
    }
}

/**
 * 验证输入参数
 */
private def validateParameters(Map args) {
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

    echo "✅ 认证令牌获取成功"
    return response
}

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

    try {
        // 直接上传文件到 /api/resources 端点
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
            echo "✅ 文件上传成功!"
        } else {
            error "❌ 文件上传失败\n请求: POST ${uploadUrl}\nHTTP状态码: ${httpCode}\n响应: ${response}"
        }

    } catch (Exception e) {
        error "❌ 文件上传失败: ${e.getMessage()}"
    }
}

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