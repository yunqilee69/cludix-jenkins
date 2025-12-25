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

    def fbUrl     = args.url
    def localFile = args.file
    def remoteDir = args.remoteDir ?: '/'
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
    echo "📋 登录请求: POST ${fbUrl}/api/login"
    echo "👤 用户名: ${username}"

    def raw = sh(
        script: """#!/bin/sh
                   set +x
                   curl -s -w "%{http_code}" -X POST '${fbUrl}/api/login' \\
                        -H "Content-Type: application/json" \\
                        -d '{"username":"${username}","password":"${password}"}'
               """,
        returnStdout: true
    ).trim()

    def httpCode = raw[-3..-1]
    def response = raw[0..-4]

    echo "📊 登录响应:"
    echo "   HTTP状态码: ${httpCode}"
    echo "   响应内容: ${response}"

    if (httpCode != '200') {
        error "❌ 登录失败 (HTTP ${httpCode}): ${response}"
    }

    echo "✅ 认证令牌获取成功"
    return response.trim()
}

/**
 * 上传文件
 */
private def uploadFile(String fbUrl, String token, String localFile, String remoteDir) {
    def fileName = getFileName(localFile)
    def fileSize = getFileSizeBytes(localFile)
    def targetPath = "${remoteDir.endsWith('/') ? remoteDir : remoteDir + '/'}${fileName}"

    echo "📁 开始上传文件: ${fileName} (${fileSize} bytes)"

    // 验证文件存在
    if (!fileExists(localFile)) {
        error "❌ 本地文件不存在: ${localFile}"
    }

    try {
        // 直接上传文件到 /api/resources 端点
        def uploadUrl = "${fbUrl}/api/resources${targetPath}?override=true"

        echo "📤 上传到: ${uploadUrl}"

        def result = sh(
            script: """#!/bin/sh
                       set +x
                       HTTP_CODE=\$(curl -k -s -w "%{http_code}" -X POST '${uploadUrl}' \\
                            -H 'x-auth: ${token}' \\
                            -F 'file=@${localFile}')
                       echo "\$HTTP_CODE"
                   """,
            returnStdout: true
        ).trim()

        def httpCode = result[-3..-1]

        if (httpCode in ['200', '201', '204']) {
            echo "✅ 文件上传成功!"
            def fileUrl = "${fbUrl}/files${targetPath}"
            echo "🔗 访问路径: ${fileUrl}"
        } else {
            error "❌ 文件上传失败 (HTTP ${httpCode})"
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
 * 获取文件大小（字节数）
 */
private def getFileSizeBytes(String filePath) {
    try {
        def size = sh(
            script: """
                if command -v stat >/dev/null 2>&1; then
                    if stat -c%s "${filePath}" >/dev/null 2>&1; then
                        # Linux
                        stat -c%s "${filePath}"
                    else
                        # macOS
                        stat -f%z "${filePath}"
                    fi
                else
                    echo "0"
                    exit 0
                fi
            """,
            returnStdout: true
        ).trim()
        return size.toLong()
    } catch (Exception e) {
        return 0L
    }
}

/**
 * 获取文件大小（人类可读格式）
 */
private def getFileSize(String filePath) {
    try {
        def sizeBytes = getFileSizeBytes(filePath)

        if (sizeBytes < 1024) {
            return "${sizeBytes}B"
        } else if (sizeBytes < 1048576) {
            return "${(sizeBytes / 1024)}KB"
        } else if (sizeBytes < 1073741824) {
            return "${(sizeBytes / 1048576)}MB"
        } else {
            return "${(sizeBytes / 1073741824)}GB"
        }
    } catch (Exception e) {
        return "未知大小"
    }
}