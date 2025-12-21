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
    echo '正在获取认证令牌...'

    // 单引号整段脚本，零插值
    def raw = sh(
        script: '''#!/bin/sh
                   set +x
                   RESP=$(curl -s -w '\\nHTTP_CODE:%{http_code}' -X POST '''+fbUrl+'''/api/login \
                             -H "Content-Type: application/json" \
                             -d "{\"username\":\"'$FB_USER'\",\"password\":\"'$FB_PASS'\"}")
                   echo "$RESP"
               ''',
        returnStdout: true
    ).trim()

    def httpCode = (raw =~ /HTTP_CODE:(\d{3})/)[0][1]
    def token    = raw.replaceAll(/HTTP_CODE:\d{3}\$/, '').trim()

    if (httpCode != '200' || !token) {
        error "获取 token 失败，HTTP ${httpCode}，响应：${token}"
    }

    echo '认证令牌获取成功'
    return token
}

/**
 * 上传文件（使用 tus-resumable-upload 协议）
 */
private def uploadFile(String fbUrl, String token, String localFile, String remoteDir) {
    def fileName = getFileName(localFile)
    def fileSize = getFileSizeBytes(localFile)
    def targetPath = "${remoteDir.endsWith('/') ? remoteDir : remoteDir + '/'}${fileName}"

    echo "开始上传文件..."
    echo "文件名: ${fileName}"
    echo "目标路径: ${targetPath}"
    echo "文件大小: ${fileSize} bytes"

    try {
        // 第一步：创建文件信息
        def createResult = createFileInfo(fbUrl, token, fileName, fileSize)

        // 第二步：上传文件内容
        uploadFileContent(fbUrl, token, fileName, localFile, fileSize)

        echo "文件上传成功!"
        def fileUrl = "${fbUrl}/files${targetPath}"
        echo "访问路径: ${fileUrl}"

    } catch (Exception e) {
        error "文件上传失败: ${e.getMessage()}"
    }
}

/**
 * 创建文件信息（tus协议第一步）
 */
private def createFileInfo(String fbUrl, String token, String fileName, long fileSize) {
    echo "创建文件信息..."

    def createUrl = "${fbUrl}/api/tus/${fileName}"

    def result = sh(
        script: """
            set +x
            CREATE_RESPONSE=\$(curl -s -w "%{http_code}" -X POST '${createUrl}' \\
                 -H 'X-Auth: ${token}' \\
                 -H 'Upload-Length: ${fileSize}' \\
                 -H 'Tus-Resumable: 1.0.0')

            HTTP_CODE="\${CREATE_RESPONSE: -3}"

            if [ "\$HTTP_CODE" != "201" ] && [ "\$HTTP_CODE" != "200" ]; then
                echo "HTTP_ERROR:\$HTTP_CODE"
                echo "RESPONSE:\${CREATE_RESPONSE%???}"
                exit 1
            fi

            echo "CREATE_SUCCESS"
            echo "\${CREATE_RESPONSE%???}"
        """,
        returnStdout: true
    ).trim()

    if (result.startsWith('HTTP_ERROR:')) {
        def errorCode = result.substring(11)
        error "创建文件信息失败 (HTTP $errorCode)"
    }

    echo "文件信息创建成功"
    return result
}

/**
 * 上传文件内容（tus协议第二步）
 */
private def uploadFileContent(String fbUrl, String token, String fileName, String localFile, long fileSize) {
    echo "上传文件内容..."

    def uploadUrl = "${fbUrl}/api/tus/${fileName}"

    def result = sh(
        script: """
            set +x
            UPLOAD_RESPONSE=\$(curl -s -w "%{http_code}" -X PATCH '${uploadUrl}' \\
                 -H 'X-Auth: ${token}' \\
                 -H 'Upload-Offset: 0' \\
                 -H 'Content-Type: application/offset+octet-stream' \\
                 -H 'Tus-Resumable: 1.0.0' \\
                 --data-binary @${localFile})

            HTTP_CODE="\${UPLOAD_RESPONSE: -3}"

            if [ "\$HTTP_CODE" != "200" ] && [ "\$HTTP_CODE" != "204" ]; then
                echo "HTTP_ERROR:\$HTTP_CODE"
                echo "RESPONSE:\${UPLOAD_RESPONSE%???}"
                exit 1
            fi

            echo "UPLOAD_SUCCESS"
            echo "\${UPLOAD_RESPONSE%???}"
        """,
        returnStdout: true
    ).trim()

    if (result.startsWith('HTTP_ERROR:')) {
        def errorCode = result.substring(11)
        error "文件内容上传失败 (HTTP $errorCode)"
    }

    echo "文件内容上传成功"
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