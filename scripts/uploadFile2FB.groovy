/**
 * 上传文件到 FileBrowser 服务器的 Jenkins 共享库函数
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

            // 确保远程目录存在
            ensureRemoteDirectory(fbUrl, token, remoteDir)

            // 上传文件（总是覆盖）
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
    echo "正在获取认证令牌..."

    def token = sh(
        script: """
            set +x
            RESPONSE=\$(curl -s -w "%{http_code}" -X POST ${fbUrl}/api/login \\
                   -H 'Content-Type: application/json' \\
                   -d '{"username":"${username}","password":"${password}"')
            HTTP_CODE="\${RESPONSE: -3}"
            BODY="\${RESPONSE%???}"

            if [ "\$HTTP_CODE" != "200" ]; then
                echo "HTTP_ERROR:\$HTTP_CODE"
                exit 1
            fi

            echo "\$BODY" | jq -r '.token // empty'
        """,
        returnStdout: true
    ).trim()

    if (token == 'null' || !token || token.startsWith('HTTP_ERROR:')) {
        def errorCode = token.startsWith('HTTP_ERROR:') ? token.substring(11) : 'UNKNOWN'
        error "FileBrowser 登录失败 (HTTP $errorCode)"
    }

    echo "认证令牌获取成功"
    return token
}

/**
 * 确保远程目录存在
 */
private def ensureRemoteDirectory(String fbUrl, String token, String remoteDir) {
    echo "检查远程目录: ${remoteDir}"

    def result = sh(
        script: """
            set +x
            curl -s -w "%{http_code}" -X POST '${fbUrl}/api/resources${remoteDir}' \\
                 -H 'X-Auth: ${token}' \\
                 -H 'Content-Type: application/json' \\
                 -d '{}' || true
        """,
        returnStdout: true
    ).trim()

    // 提取 HTTP 状态码
    def httpCode = result.length() >= 3 ? result[-3..-1] : "000"

    if (httpCode in ["200", "201", "409"]) {
        echo "远程目录已就绪"
    } else {
        echo "远程目录检查返回状态码: ${httpCode}"
    }
}

/**
 * 上传文件（总是覆盖已存在的文件）
 */
private def uploadFile(String fbUrl, String token, String localFile, String remoteDir) {
    def fileName = getFileName(localFile)
    def uploadUrl = "${fbUrl}/api/resources${remoteDir}/${fileName}?override=true"

    echo "开始上传文件..."
    echo "文件名: ${fileName}"
    echo "文件大小: ${getFileSize(localFile)}"

    def result = sh(
        script: """
            set +x
            UPLOAD_RESPONSE=\$(curl -s -w "%{http_code}" -X PUT '${uploadUrl}' \\
                 -H 'X-Auth: ${token}' \\
                 -H 'Content-Type: application/octet-stream' \\
                 --data-binary @${localFile})

            HTTP_CODE="\${UPLOAD_RESPONSE: -3}"

            if [ "\$HTTP_CODE" != "200" ] && [ "\$HTTP_CODE" != "201" ]; then
                echo "HTTP_ERROR:\$HTTP_CODE"
                echo "RESPONSE:\${UPLOAD_RESPONSE%???}"
                exit 1
            fi

            echo "\${UPLOAD_RESPONSE%???}"
        """,
        returnStdout: true
    ).trim()

    if (result.startsWith('HTTP_ERROR:')) {
        def errorCode = result.substring(11)
        error "文件上传失败 (HTTP $errorCode)"
    }

    def fileUrl = "${fbUrl}/files${remoteDir.endsWith('/') ? remoteDir : remoteDir + '/'}${fileName}"
    echo "访问路径: ${fileUrl}"
    echo "文件上传完成"
}

/**
 * 从完整路径中提取文件名
 */
private def getFileName(String filePath) {
    return filePath.tokenize('/')[-1]
}

/**
 * 获取文件大小（人类可读格式）
 */
private def getFileSize(String filePath) {
    try {
        def size = sh(
            script: """
                if command -v stat >/dev/null 2>&1; then
                    if stat -c%s "${filePath}" >/dev/null 2>&1; then
                        # Linux
                        SIZE=\$(stat -c%s "${filePath}")
                    else
                        # macOS
                        SIZE=\$(stat -f%z "${filePath}")
                    fi
                else
                    echo "0"
                    exit 0
                fi

                if [ \$SIZE -lt 1024 ]; then
                    echo "\${SIZE}B"
                elif [ \$SIZE -lt 1048576 ]; then
                    echo "\$((\$SIZE / 1024))KB"
                elif [ \$SIZE -lt 1073741824 ]; then
                    echo "\$((\$SIZE / 1048576))MB"
                else
                    echo "\$((\$SIZE / 1073741824))GB"
                fi
            """,
            returnStdout: true
        ).trim()
        return size
    } catch (Exception e) {
        return "未知大小"
    }
}