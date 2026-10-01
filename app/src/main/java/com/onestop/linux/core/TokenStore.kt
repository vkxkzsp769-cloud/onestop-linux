package com.onestop.linux.core

import java.security.SecureRandom

/**
 * token 存放（方案 §8.2.6 R4）。
 * 来源优先级：① 端口提示文件同目录的 dsh-web.token；② 用户在设置里手填；③ 本 App 随机生成一个，
 * 用于「服务端也读同一个文件」的部署方式。
 */
object TokenStore {

    @Volatile private var current: String? = null

    fun current(): String? = current

    fun set(token: String?) { current = token?.trim()?.takeIf { it.isNotEmpty() } }

    fun generateIfAbsent(): String = current ?: generate().also { current = it }

    fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
