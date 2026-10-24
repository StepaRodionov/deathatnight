package com.example.scooterauth

import java.util.Random

/**
 * Прямой порт BleMutualAuthentication.java (kugookirin): полный протокол
 * взаимного рукопожатия поверх +VER/+PM/+PA, с вызовом KugooCrypto
 * (твой алгоритм) там же, где в оригинале был LDSDKEncryption.encryptionStringOfValue.
 *
 * Логика НЕ менялась — только синтаксис и явные имена колбэков вместо
 * прямого вызова LFBluetootService.
 */
class KugooAuth(
    private val mode: () -> Int,
    private val sender: (String) -> Unit,
    private val log: (String) -> Unit
) {
    private var isCredible = false
    private var isTrusted = false
    private var sendHash: String? = null
    private var sendCode: String? = null
    private var receivedHash: String? = null
    private var isNeedAuth = true
    private var isNeedRequest = true

    fun reset() {
        isCredible = false
        isTrusted = false
        sendHash = null
        sendCode = null
        receivedHash = null
        isNeedAuth = true
        isNeedRequest = true
    }

    /** Старт рукопожатия: шаг 1 — "+VER?" */
    fun start() {
        log("Авторизация: старт -> +VER?")
        sender("+VER?")
    }

    fun isMutualAuthentication(): Boolean = isCredible && isTrusted

    private fun requestAuth() {
        if (isNeedRequest) {
            log("Авторизация: -> +PM?")
            sender("+PM?")
        }
    }

    /**
     * Скармливаем сюда КАЖДОЕ текстовое сообщение, пришедшее от самоката
     * (как есть, без фильтрации непечатных символов — у протокола фиксированная
     * длина строк, это важно).
     */
    fun onMessage(rawText: String) {
        if (!isNeedAuth || isMutualAuthentication()) return

        val upper = rawText.uppercase()
        log("Авторизация RX: \"$upper\"")

        if (!isTrusted && upper.length >= 6 && upper.substring(0, 3) == "+PM") {
            if (upper.length == 16 && upper[3] == '>') {
                // Шаг 4-5: самокат прислал nonce, считаем и отправляем хэш.
                isNeedRequest = false
                val nonce = upper.substring(4)
                val m = mode()
                val hash = KugooCrypto.encryptToken(nonce, m)
                sendHash = hash
                log("nonce=$nonce mode=$m -> hash=$hash")
                sender("+PM<$hash")
            } else if (upper == "+PM=OK" || upper == "+PM>OK") {
                // Шаг 6: наш хэш принят. Готовим встречный вызов (шаг 7).
                isTrusted = true
                log("Хэш принят (+PM=OK/+PM>OK). Доверие установлено, генерирую встречный код...")
                val random = Random()
                val sb = StringBuilder()
                repeat(6) { sb.append(random.nextInt() and 255) }
                val code = sb.toString()
                sendCode = code
                sender("+PA<$code")
                receivedHash = KugooCrypto.encryptToken(code, mode()).uppercase()
                log("Отправлен встречный код +PA<$code, ожидаю +PA>$receivedHash")
            } else if (upper == "+PM=NK") {
                isTrusted = false
                log("Самокат отклонил хэш (+PM=NK). mode=${mode()} не подошёл — попробуй другой.")
            }
        } else if (!isCredible && upper.length == 12 && upper.substring(0, 4) == "+PA>") {
            // Шаг 8-9: самокат ответил на встречный вызов, сверяем.
            val got = upper.substring(4)
            isCredible = (receivedHash == got)
            if (isCredible) {
                log("Встречная проверка совпала ($got). ВЗАИМНАЯ АВТОРИЗАЦИЯ ПРОЙДЕНА.")
            } else {
                log("Встречная проверка НЕ совпала: ждал $receivedHash, получил $got.")
            }
        } else if (upper.length > 8 && upper.substring(0, 5) == "+VER=") {
            log("Версия самоката: $upper")
            requestAuth()
        }
    }
}
