package com.example.stepshift.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zepp Life (小米运动 / 华米) cloud client implementing the public mimotion flow:
 *  1. POST api-user.zepp.com/v2/registrations/tokens (AES-128-CBC encrypted form,
 *     303 redirect carries the short-lived access token in the Location header)
 *  2. POST account.huami.com/v2/client/login -> login_token / app_token / user_id
 *  3. POST api-mifit-cn.huami.com/v1/data/band_data.json with a URL-encoded band
 *     payload whose summary step total is replaced by the requested count
 *
 * After binding 小米运动 as a data source in WeChat (微信运动 -> 设置 -> 数据来源),
 * the uploaded steps surface in WeChat/QQ/Alipay sport rankings.
 */
class ZeppApiClient {

    data class ZeppTokens(
        val userId: String,
        val loginToken: String,
        val appToken: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // region Step 1: password login -> access token (via 303 Location)

    private fun aesEncrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(plain)
    }

    private fun loginAccessToken(email: String, password: String): Pair<String?, String?> {
        val form = listOf(
            "emailOrPhone" to email,
            "password" to password,
            "state" to "REDIRECTION",
            "client_id" to "HuaMi",
            "country_code" to "CN",
            "token" to "access",
            "redirect_uri" to "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html"
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val cipherBytes = aesEncrypt(form.toByteArray(Charsets.UTF_8))

        val request = Request.Builder()
            .url("https://api-user.zepp.com/v2/registrations/tokens")
            .post(cipherBytes.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
            .header("user-agent", "MiFit6.14.0 (M2007J1SC; Android 12; Density/2.75)")
            .header("app_name", "com.xiaomi.hm.health")
            .header("appname", "com.xiaomi.hm.health")
            .header("appplatform", "android_phone")
            .header("x-hm-ekv", "1")
            .header("hm-privacy-ceip", "false")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 303) {
                return null to "登录异常，服务器返回 ${response.code}"
            }
            val location = response.header("Location") ?: return null to "登录异常：无重定向"
            // NOTE: access/error may be the LAST query parameter (no trailing '&')
            val access = Regex("(?<=access=)[^&]+").find(location)?.value
            if (access != null) return access to null
            val error = Regex("(?<=error=)[^&]+").find(location)?.value
            return null to "账号或密码错误 (${error ?: "未知错误"})"
        }
    }

    // endregion

    // region Step 2: access token -> login/app tokens

    private fun grantLoginTokens(accessToken: String): Pair<ZeppTokens?, String?> {
        val deviceId = UUID.randomUUID().toString()
        val form = listOf(
            "allow_registration" to "false",
            "app_name" to "com.xiaomi.hm.health",
            "app_version" to "6.14.0",
            "code" to accessToken,
            "country_code" to "CN",
            "device_id" to deviceId,
            "device_model" to "android_phone",
            "dn" to "account.zepp.com,api-user.zepp.com,api-mifit.zepp.com,api-watch.zepp.com,app-analytics.zepp.com,api-analytics.huami.com,auth.zepp.com",
            "grant_type" to "access_token",
            "lang" to "zh_CN",
            "os_version" to "1.5.0",
            "source" to "com.xiaomi.hm.health:6.14.0:50818",
            "third_name" to "email"
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val request = Request.Builder()
            .url("https://account.huami.com/v2/client/login")
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
            .header("app_name", "com.xiaomi.hm.health")
            .header("x-request-id", UUID.randomUUID().toString())
            .header("accept-language", "zh-CN")
            .header("appname", "com.xiaomi.hm.health")
            .header("cv", "50818_6.14.0")
            .header("v", "2.0")
            .header("appplatform", "android_phone")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null to "客户端登录无响应"
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return null to "客户端登录响应解析失败 (${response.code})"
            }
            val result = json.optString("result", "")
            if (result != "ok") {
                return null to "客户端登录失败: $result"
            }
            val tokenInfo = json.optJSONObject("token_info") ?: return null to "缺少 token_info"
            val loginToken = tokenInfo.optString("login_token", "")
            val appToken = tokenInfo.optString("app_token", "")
            val userId = tokenInfo.optString("user_id", "")
            if (loginToken.isEmpty() || appToken.isEmpty() || userId.isEmpty()) {
                return null to "token_info 不完整"
            }
            return ZeppTokens(userId, loginToken, appToken) to null
        }
    }

    // endregion

    // region Step 3: upload steps

    private fun postSteps(tokens: ZeppTokens, steps: Long): Pair<Boolean, String?> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dataJson = ZEPP_DATA_TEMPLATE
            .replace("2021-08-07", today)
            .replace("%3A18272%2C", "%3A$steps%2C")

        val timestamp = System.currentTimeMillis()
        val url = "https://api-mifit-cn.huami.com/v1/data/band_data.json?&t=$timestamp&r=${UUID.randomUUID()}"
        val body = "userid=${tokens.userId}&last_sync_data_time=1597306380" +
                "&device_type=0&last_deviceid=$DEFAULT_DEVICE_ID&data_json=$dataJson"

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("apptoken", tokens.appToken)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                return false to "步数上传异常: HTTP ${response.code}"
            }
            val respBody = response.body?.string() ?: return false to "步数上传无响应"
            val message = try {
                JSONObject(respBody).optString("message", "")
            } catch (e: Exception) {
                return false to "步数上传响应解析失败"
            }
            return if (message == "success") {
                true to null
            } else {
                false to "步数上传被拒绝: $message"
            }
        }
    }

    // endregion

    /**
     * Full login + upload. Returns a human-readable error message, or null on success.
     * Tokens are cached per account (Z5): repeat applies reuse them and only fall
     * back to a fresh login once if the cached token got rejected. Serialized with
     * a Mutex so rapid applies cannot interleave multiple logins/uploads.
     */
    private val pushMutex = Mutex()
    private var cachedEmail: String? = null
    private var cachedTokens: ZeppTokens? = null

    suspend fun pushSteps(email: String, password: String, steps: Long): String? = pushMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                var tokens = cachedTokens.takeIf { cachedEmail == email }
                val fromCache = tokens != null

                if (tokens == null) {
                    val (accessToken, loginError) = loginAccessToken(email, password)
                    if (accessToken == null) return@withContext (loginError ?: "登录失败")
                    val (t, grantError) = grantLoginTokens(accessToken)
                    if (t == null) return@withContext (grantError ?: "令牌获取失败")
                    tokens = t
                }

                var (ok, uploadError) = postSteps(tokens, steps)
                if (!ok && fromCache) {
                    // Cached token may have expired — one fresh login, then retry once
                    val (accessToken, _) = loginAccessToken(email, password)
                    if (accessToken != null) {
                        val (t, _) = grantLoginTokens(accessToken)
                        if (t != null) {
                            tokens = t
                            val retry = postSteps(tokens, steps)
                            ok = retry.first
                            uploadError = retry.second
                        }
                    }
                }

                if (ok) {
                    cachedEmail = email
                    cachedTokens = tokens
                    null
                } else {
                    uploadError ?: "步数上传失败"
                }
            } catch (e: Exception) {
                "网络异常: ${e.message}"
            }
        }
    }

    companion object {
        // Public transport credentials from the mimotion / Zepp_API references
        private const val AES_KEY = "xeNtBVqzDc6tuNTh"
        private const val AES_IV = "MAAAYAAAAAAAAABg"
        private const val DEFAULT_DEVICE_ID = "DA932FFFFE8816E7"
    }
}
