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

            // 直接上传文件（FileBrowser 会自动创建必要的目录）
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
 * 使用 Jenkins 自带 readJSON 解析登录响应
 */
private def getAuthToken(String fbUrl, String username, String password) {
    echo '🔐 正在获取认证令牌...'
    echo "=== 登录请求详情 ==="
    echo "📋 请求URL: POST ${fbUrl}/api/login"
    echo "👤 用户名: ${username}"
    echo "🔑 密码: [${'*' * password.length()}]"

    echo "📤 请求头:"
    echo "   Content-Type: application/json"

    echo "📦 请求体:"
    def requestBody = """{"username":"${username}","password":"${password}"}"""
    echo "   ${requestBody}"

    // 单引号整段脚本，零插值
    def raw = sh(
        script: '''#!/bin/sh
                   set +x
                   echo "🚀 开始发送登录请求..."

                   echo "=== CURL 请求详情 ==="
                   echo "请求方法: POST"
                   echo "请求URL: '''+fbUrl+'''/api/login"
                   echo "请求头: Content-Type: application/json"
                   echo "请求体: {\\"username\\":\\"'$FB_USER'\\",\\"password\\":\\"[HIDDEN]\\"}"

                   RESP=$(curl -v -w '\\n=== CURL 统计信息 ===\\nHTTP_CODE:%{http_code}\\nTOTAL_TIME:%{time_total}\\nSIZE_UPLOAD:%{size_upload}\\nSIZE_DOWNLOAD:%{size_download}\\n=== 完整响应开始 ===\\n' \
                             -X POST '''+fbUrl+'''/api/login \
                             -H "Content-Type: application/json" \
                             -d "{\"username\":\"'$FB_USER'\",\"password\":\"'$FB_PASS'\"}" 2>&1)

                   echo "\\n=== 完整响应结束 ==="
                   echo "✅ 登录请求完成"
                   echo "$RESP"
               ''',
        returnStdout: true
    ).trim()

    echo "=== 完整原始响应 ==="
    echo "${raw}"
    echo "=== 响应解析 ==="

    def httpCode = (raw =~ /HTTP_CODE:(\d{3})/)[0][1]
    def token    = raw.replaceAll(/HTTP_CODE:\d{3}\S*\n=== 完整响应开始 ===\n/, '').trim()
    token = token.replaceAll(/\n=== 完整响应结束 ===.*/, '').trim()

    echo "📊 响应解析结果:"
    echo "   HTTP状态码: ${httpCode}"
    echo "   响应体内容: ${token}"
    echo "   Token长度: ${token.length()}"
    echo "   Token前10字符: ${token.length() > 10 ? token[0..9] : 'N/A'}"

    if (httpCode != '200' || !token) {
        echo "❌ 错误详情:"
        echo "   HTTP状态码: ${httpCode}"
        echo "   响应内容: ${token}"
        error "❌ 获取 token 失败"
    }

    echo '✅ 认证令牌获取成功'
    return token
}

/**
 * 上传文件（使用 tus-resumable-upload 协议）
 */
private def uploadFile(String fbUrl, String token, String localFile, String remoteDir) {
    def fileName = getFileName(localFile)
    def fileSize = getFileSizeBytes(localFile)
    def targetPath = "${remoteDir.endsWith('/') ? remoteDir : remoteDir + '/'}${fileName}"

    echo "📁 开始上传文件流程..."
    echo "📄 文件名: ${fileName}"
    echo "🎯 目标路径: ${targetPath}"
    echo "📊 文件大小: ${fileSize} bytes"
    echo "🌐 FileBrowser服务器: ${fbUrl}"
    echo "📍 远程目录: ${remoteDir}"

    // 验证文件存在
    if (!fileExists(localFile)) {
        error "❌ 本地文件不存在: ${localFile}"
    }

    try {
        echo "🔄 开始TUS协议上传流程..."

        // 第一步：创建文件信息
        echo "---"
        echo "📝 步骤 1/2: 创建文件信息"
        def createResult = createFileInfo(fbUrl, token, fileName, fileSize)

        echo "---"
        echo "📤 步骤 2/2: 上传文件内容"
        // 第二步：上传文件内容
        uploadFileContent(fbUrl, token, fileName, localFile, fileSize)

        echo "---"
        echo "🎉 文件上传成功!"
        def fileUrl = "${fbUrl}/files${targetPath}"
        echo "🔗 访问路径: ${fileUrl}"

    } catch (Exception e) {
        echo "❌ 上传过程中发生异常"
        echo "🐛 错误信息: ${e.getMessage()}"
        echo "📍 异常位置: ${e.getStackTrace()[0]?.getLineNumber()}"
        error "文件上传失败: ${e.getMessage()}"
    }
}

/**
 * 创建文件信息（tus协议第一步）
 */
private def createFileInfo(String fbUrl, String token, String fileName, long fileSize) {
    echo "📋 创建文件信息..."
    def createUrl = "${fbUrl}/api/tus/${fileName}"

    echo "=== 创建文件请求详情 ==="
    echo "📋 请求URL: POST ${createUrl}"
    echo "📄 文件名: ${fileName}"
    echo "📊 文件大小: ${fileSize} bytes"

    echo "📤 请求头:"
    echo "   X-Auth: ${token.length() > 10 ? token[0..9] + '...' : token}"
    echo "   Upload-Length: ${fileSize}"
    echo "   Tus-Resumable: 1.0.0"

    echo "📦 请求体: (无 - TUS创建请求无请求体)"

    def result = sh(
        script: """
            set +x
            echo "🔧 开始发送创建文件请求..."

            echo "=== CURL 请求详情 ==="
            echo "请求方法: POST"
            echo "请求URL: ${createUrl}"
            echo "请求头:"
            echo "   X-Auth: ${token:0:10}..."
            echo "   Upload-Length: ${fileSize}"
            echo "   Tus-Resumable: 1.0.0"
            echo "请求体: (无)"

            CREATE_RESPONSE=\$(curl -v -w '\\n=== CURL 统计信息 ===\\nHTTP_CODE:%{http_code}\\nTOTAL_TIME:%{time_total}\\nSIZE_UPLOAD:%{size_upload}\\nSIZE_DOWNLOAD:%{size_download}\\nREQUEST_HEADER:%{size_request_header}\\n=== 完整响应开始 ===\\n' \
                 -X POST '${createUrl}' \
                 -H 'X-Auth: ${token}' \
                 -H 'Upload-Length: ${fileSize}' \
                 -H 'Tus-Resumable: 1.0.0' \
                 -H 'Connection: close' 2>&1)

            echo "\\n=== 完整响应结束 ==="

            HTTP_CODE="\$(echo "\$CREATE_RESPONSE" | grep -o 'HTTP_CODE:[0-9]*' | cut -d: -f2)"

            echo "📥 完整响应内容:"
            echo "\$CREATE_RESPONSE"

            if [ "\$HTTP_CODE" != "201" ] && [ "\$HTTP_CODE" != "200" ]; then
                echo "❌ 创建文件信息失败"
                echo "HTTP_ERROR:\$HTTP_CODE"
                echo "RESPONSE:\$CREATE_RESPONSE"
                exit 1
            fi

            echo "✅ 创建文件信息成功"
            echo "CREATE_SUCCESS"
        """,
        returnStdout: true
    ).trim()

    echo "=== 完整原始响应 ==="
    echo "${result}"

    if (result.contains('HTTP_ERROR:')) {
        def errorCode = (result =~ /HTTP_ERROR:(\d{3})/)[0][1]
        echo "=== 错误详情 ==="
        echo "❌ HTTP状态码: ${errorCode}"
        echo "❌ 完整响应: ${result}"
        error "❌ 创建文件信息失败 (HTTP ${errorCode})"
    }

    echo "✅ 文件信息创建成功"
    return result
}

/**
 * 上传文件内容（tus协议第二步）
 */
private def uploadFileContent(String fbUrl, String token, String fileName, String localFile, long fileSize) {
    echo "📤 上传文件内容..."
    def uploadUrl = "${fbUrl}/api/tus/${fileName}"

    echo "=== 上传文件内容请求详情 ==="
    echo "📋 请求URL: PATCH ${uploadUrl}"
    echo "📄 文件名: ${fileName}"
    echo "📁 本地文件路径: ${localFile}"
    echo "📊 文件大小: ${fileSize} bytes"

    echo "📤 请求头:"
    echo "   X-Auth: ${token.length() > 10 ? token[0..9] + '...' : token}"
    echo "   Upload-Offset: 0"
    echo "   Content-Type: application/offset+octet-stream"
    echo "   Tus-Resumable: 1.0.0"

    echo "📦 请求体:"
    echo "   二进制文件数据 (${fileSize} bytes)"
    echo "   文件内容: (二进制数据，省略显示)"

    def result = sh(
        script: """
            set +x
            echo "🔧 开始上传文件内容..."

            echo "=== CURL 请求详情 ==="
            echo "请求方法: PATCH"
            echo "请求URL: ${uploadUrl}"
            echo "请求头:"
            echo "   X-Auth: ${token:0:10}..."
            echo "   Upload-Offset: 0"
            echo "   Content-Type: application/offset+octet-stream"
            echo "   Tus-Resumable: 1.0.0"
            echo "请求体: 二进制文件数据 (${fileSize} bytes)"

            UPLOAD_RESPONSE=\$(curl -v -w '\\n=== CURL 统计信息 ===\\nHTTP_CODE:%{http_code}\\nTOTAL_TIME:%{time_total}\\nSIZE_UPLOAD:%{size_upload}\\nSIZE_DOWNLOAD:%{size_download}\\nREQUEST_HEADER:%{size_request_header}\\nSPEED_UPLOAD:%{speed_upload}\\n=== 完整响应开始 ===\\n' \
                 -X PATCH '${uploadUrl}' \
                 -H 'X-Auth: ${token}' \
                 -H 'Upload-Offset: 0' \
                 -H 'Content-Type: application/offset+octet-stream' \
                 -H 'Tus-Resumable: 1.0.0' \
                 -H 'Connection: close' \
                 --data-binary @${localFile} 2>&1)

            echo "\\n=== 完整响应结束 ==="

            HTTP_CODE="\$(echo "\$UPLOAD_RESPONSE" | grep -o 'HTTP_CODE:[0-9]*' | cut -d: -f2)"

            echo "📥 完整响应内容:"
            echo "\$UPLOAD_RESPONSE"

            if [ "\$HTTP_CODE" != "200" ] && [ "\$HTTP_CODE" != "204" ]; then
                echo "❌ 文件内容上传失败"
                echo "HTTP_ERROR:\$HTTP_CODE"
                echo "RESPONSE:\$UPLOAD_RESPONSE"
                exit 1
            fi

            echo "✅ 文件内容上传成功"
            echo "UPLOAD_SUCCESS"
        """,
        returnStdout: true
    ).trim()

    echo "=== 完整原始响应 ==="
    echo "${result}"

    if (result.contains('HTTP_ERROR:')) {
        def errorCode = (result =~ /HTTP_ERROR:(\d{3})/)[0][1]
        echo "=== 错误详情 ==="
        echo "❌ HTTP状态码: ${errorCode}"
        echo "❌ 完整响应: ${result}"
        error "文件内容上传失败 (HTTP ${errorCode})"
    }

    echo "✅ 文件内容上传成功"
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