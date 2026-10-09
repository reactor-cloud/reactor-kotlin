package reactor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReactorException(val status: Int, message: String) : Exception(message)

data class User(val id: String, val email: String)

data class Session(val accessToken: String, val refreshToken: String, val user: User)

sealed class AuthResult {
    data class SignedIn(val session: Session) : AuthResult()
    data class VerificationRequired(val user: User) : AuthResult()
    data class MfaRequired(val token: String, val factors: List<String>) : AuthResult()
    data class EnrollmentRequired(val token: String, val factors: List<String>) : AuthResult()
}

class ReactorClient(url: String, private val anonKey: String, http: OkHttpClient? = null) {
    private val base = url.trimEnd('/')
    private var session: Session? = null
    private val http = http ?: OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val auth = Auth()
    val storage = Storage()
    val functions = Functions()
    val queue = Queue()

    fun from(table: String) = Query(table)

    private fun token() = session?.accessToken ?: anonKey

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private suspend fun call(
        path: String,
        method: String = "GET",
        token: String? = null,
        json: JSONObject? = null,
        prefer: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val body = json?.toString()?.toRequestBody(JSON)
        val builder = Request.Builder().url(base + path).method(method, if (method == "GET") null else body ?: ByteArray(0).toRequestBody(null))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (prefer != null) builder.header("Prefer", prefer)
        builder.header("Expect", "")
        http.newCall(builder.build()).execute().use { response ->
            response.code to (response.body?.string() ?: "")
        }
    }

    private fun parse(status: Int, text: String): JSONObject {
        if (text.isEmpty()) {
            if (status >= 400) throw ReactorException(status, "request failed")
            return JSONObject()
        }
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            if (status >= 400) throw ReactorException(status, "request failed")
            return JSONObject().put("rows", JSONArray(trimmed))
        }
        val body = JSONObject(trimmed)
        if (status >= 400) {
            throw ReactorException(status, body.optString("error", body.optString("message", "request failed")))
        }
        return body
    }

    private fun readSession(body: JSONObject) = Session(
        body.getString("access_token"),
        body.getString("refresh_token"),
        User(body.getJSONObject("user").getString("id"), body.getJSONObject("user").getString("email")),
    )

    inner class Auth {
        fun getSession() = session

        suspend fun signUp(email: String, password: String) =
            outcome("/auth/v1/signup", JSONObject().put("email", email).put("password", password))

        suspend fun signInWithPassword(email: String, password: String) =
            outcome("/auth/v1/token", JSONObject().put("email", email).put("password", password))

        suspend fun verifyEmail(token: String? = null, email: String? = null, code: String? = null): Session {
            val body = JSONObject()
            if (token != null) body.put("token", token)
            if (email != null) body.put("email", email)
            if (code != null) body.put("code", code)
            return issue("/auth/v1/verify-email", body)
        }

        suspend fun resendVerification(email: String) {
            val (status, text) = call("/auth/v1/verify-email/send", "POST", anonKey, JSONObject().put("email", email))
            parse(status, text)
        }

        suspend fun verifyTotp(token: String, code: String) =
            issue("/auth/v1/factors/totp", JSONObject().put("mfa_token", token).put("code", code))

        suspend fun verifyPasskey(token: String, credential: JSONObject): Session {
            credential.put("mfa_token", token)
            return issue("/auth/v1/factors/passkey/verify", credential)
        }

        suspend fun verifyRecovery(token: String, code: String) =
            issue("/auth/v1/factors/recovery", JSONObject().put("mfa_token", token).put("code", code))

        suspend fun enrollTotp(bearer: String? = null, code: String? = null): JSONObject {
            val path = if (code == null) "/auth/v1/factors/totp/start" else "/auth/v1/factors/totp/confirm"
            val body = if (code == null) JSONObject() else JSONObject().put("code", code)
            val (status, text) = call(path, "POST", bearer ?: token(), body)
            return parse(status, text)
        }

        suspend fun enrollPasskey(bearer: String? = null, credential: JSONObject? = null): JSONObject {
            val path = if (credential == null) "/auth/v1/factors/passkey/register/options" else "/auth/v1/factors/passkey/register"
            val (status, text) = call(path, "POST", bearer ?: token(), credential ?: JSONObject())
            return parse(status, text)
        }

        fun signInWithOAuth(provider: String, redirectTo: String): String {
            val encoded = java.net.URLEncoder.encode(redirectTo, "UTF-8")
            return "$base/auth/v1/authorize?provider=$provider&redirect_to=$encoded"
        }

        suspend fun exchangeCode(code: String) = issue("/auth/v1/token", JSONObject().put("code", code))

        suspend fun getUser(): User {
            if (session == null) throw ReactorException(401, "not signed in")
            val (status, text) = call("/auth/v1/user", token = token())
            val body = parse(status, text)
            return User(body.getString("id"), body.getString("email"))
        }

        suspend fun refreshSession(): Session {
            val refresh = session?.refreshToken ?: throw ReactorException(401, "not signed in")
            return issue("/auth/v1/token", JSONObject().put("refresh_token", refresh))
        }

        suspend fun signOut() {
            val refresh = session?.refreshToken ?: return
            val (status, text) = call("/auth/v1/logout", "POST", json = JSONObject().put("refresh_token", refresh))
            parse(status, text)
            session = null
        }

    }

    private fun factors(body: JSONObject): List<String> {
        val list = body.optJSONArray("factors") ?: return emptyList()
        return (0 until list.length()).map { list.getString(it) }
    }

    private suspend fun outcome(path: String, json: JSONObject): AuthResult {
        val (status, text) = call(path, "POST", anonKey, json)
        val body = parse(status, text)
        if (body.has("access_token")) {
            val next = readSession(body)
            session = next
            return AuthResult.SignedIn(next)
        }
        if (body.optBoolean("verification_required")) {
            val user = body.getJSONObject("user")
            return AuthResult.VerificationRequired(User(user.getString("id"), user.getString("email")))
        }
        if (body.optBoolean("mfa_required")) {
            return AuthResult.MfaRequired(body.getString("mfa_token"), factors(body))
        }
        if (body.optBoolean("enrollment_required")) {
            return AuthResult.EnrollmentRequired(body.getString("enroll_token"), factors(body))
        }
        throw ReactorException(0, "unrecognized auth response")
    }

    private suspend fun issue(path: String, json: JSONObject): Session {
        val (status, text) = call(path, "POST", anonKey, json)
        val next = readSession(parse(status, text))
        session = next
        return next
    }

    inner class Storage {
        fun from(bucket: String) = Bucket(bucket)

        suspend fun createBucket(name: String, public: Boolean = false) {
            val json = JSONObject().put("name", name).put("public", public)
            val (status, text) = call("/storage/v1/bucket", "POST", token(), json)
            parse(status, text)
        }
    }

    inner class Bucket(private val bucket: String) {
        suspend fun getPublicUrl(path: String): String {
            val (status, text) = call("/storage/v1/bucket/${encode(bucket)}", "GET", token(), null)
            val body = parse(status, text)
            val root = body.optString("public_url_base", "")
            if (!body.optBoolean("public") || root.isEmpty()) throw ReactorException(status, "bucket is not public")
            val suffix = path.split("/").joinToString("/") { encode(it) }
            return "$root/$suffix"
        }

        suspend fun upload(path: String, bytes: ByteArray, contentType: String = "application/octet-stream") {
            val signed = presign(path, "PUT")
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(signed)
                    .put(bytes.toRequestBody(contentType.toMediaType()))
                    .header("Expect", "")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ReactorException(response.code, response.body?.string() ?: "upload failed")
                }
            }
        }

        suspend fun download(path: String): ByteArray {
            val signed = presign(path, "GET")
            return withContext(Dispatchers.IO) {
                val request = Request.Builder().url(signed).header("Expect", "").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ReactorException(response.code, response.body?.string() ?: "download failed")
                    response.body?.bytes() ?: ByteArray(0)
                }
            }
        }

        private suspend fun presign(path: String, method: String): String {
            val json = JSONObject().put("bucket", bucket).put("key", path).put("method", method)
            val (status, text) = call("/storage/v1/object/presign", "POST", token(), json)
            return parse(status, text).getString("url")
        }
    }

    inner class Functions {
        suspend fun invoke(name: String, body: JSONObject = JSONObject()): JSONObject {
            val (status, text) = call("/fn/v1/$name", "POST", token(), body)
            return parse(status, text)
        }

        suspend fun enqueue(name: String, body: JSONObject = JSONObject(), delaySecs: Int? = null, maxAttempts: Int? = null): JSONObject {
            val json = JSONObject().put("body", body)
            if (delaySecs != null) json.put("delay_secs", delaySecs)
            if (maxAttempts != null) json.put("max_attempts", maxAttempts)
            val (status, text) = call("/fn/v1/${encode(name)}/enqueue", "POST", token(), json)
            return parse(status, text)
        }

        suspend fun task(id: String): JSONObject {
            val (status, text) = call("/fn/v1/_admin/tasks/${encode(id)}", "GET", token())
            return parse(status, text)
        }
    }

    inner class Queue {
        suspend fun list(): JSONArray = rows(call("/queue/v1/queues", "GET", token()))

        suspend fun create(name: String): JSONObject {
            val (status, text) = call("/queue/v1/queues", "POST", token(), JSONObject().put("name", name))
            return parse(status, text)
        }

        suspend fun send(name: String, message: JSONObject, delaySecs: Int? = null): JSONObject {
            val json = JSONObject().put("message", message)
            if (delaySecs != null) json.put("delay_secs", delaySecs)
            val (status, text) = call("${path(name)}/send", "POST", token(), json)
            return parse(status, text)
        }

        suspend fun read(name: String, vtSecs: Int? = null, qty: Int? = null): JSONArray {
            val json = JSONObject()
            if (vtSecs != null) json.put("vt_secs", vtSecs)
            if (qty != null) json.put("qty", qty)
            return rows(call("${path(name)}/read", "POST", token(), json))
        }

        suspend fun peek(name: String): JSONArray = rows(call("${path(name)}/peek", "GET", token()))

        suspend fun delete(name: String, msgId: Long) {
            val (status, text) = call("${path(name)}/delete", "POST", token(), JSONObject().put("msg_id", msgId))
            parse(status, text)
        }

        suspend fun archive(name: String, msgId: Long) {
            val (status, text) = call("${path(name)}/archive", "POST", token(), JSONObject().put("msg_id", msgId))
            parse(status, text)
        }

        suspend fun subscribe(name: String, functionName: String, vtSecs: Int, qty: Int, maxReads: Int) {
            val json = JSONObject()
                .put("function_name", functionName)
                .put("vt_secs", vtSecs)
                .put("qty", qty)
                .put("max_reads", maxReads)
            val (status, text) = call("${path(name)}/subscriptions", "POST", token(), json)
            parse(status, text)
        }

        suspend fun unsubscribe(name: String, functionName: String) {
            val (status, text) = call("${path(name)}/subscriptions", "DELETE", token(), JSONObject().put("function_name", functionName))
            parse(status, text)
        }

        private fun path(name: String) = "/queue/v1/queues/${encode(name)}"

        private suspend fun rows(response: Pair<Int, String>): JSONArray = parse(response.first, response.second).getJSONArray("rows")
    }

    inner class Query(private val table: String) {
        private var method = "GET"
        private var columns: String? = null
        private var body: JSONObject? = null
        private val filters = mutableListOf<Pair<String, String>>()
        private var orderBy: String? = null
        private var rowLimit: Int? = null

        fun select(columns: String = "*"): Query {
            this.columns = columns
            return this
        }

        fun insert(row: JSONObject): Query {
            method = "POST"
            body = row
            return this
        }

        fun update(row: JSONObject): Query {
            method = "PATCH"
            body = row
            return this
        }

        fun delete(): Query {
            method = "DELETE"
            return this
        }

        fun eq(column: String, value: String): Query {
            filters += column to value
            return this
        }

        fun order(column: String, ascending: Boolean = true): Query {
            orderBy = if (ascending) "$column.asc" else "$column.desc"
            return this
        }

        fun limit(count: Int): Query {
            rowLimit = count
            return this
        }

        suspend fun execute(): JSONArray = withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme(if (base.startsWith("https")) "https" else "http")
                .host(host())
                .port(port())
                .addPathSegments("data/v1/$table".trim('/'))
            columns?.let { url.addQueryParameter("select", it) }
            filters.forEach { (column, value) -> url.addQueryParameter(column, "eq.$value") }
            orderBy?.let { url.addQueryParameter("order", it) }
            rowLimit?.let { url.addQueryParameter("limit", it.toString()) }
            val payload = body?.toString()?.toRequestBody(JSON)
            val requestBody = if (method == "GET") null else payload ?: ByteArray(0).toRequestBody(null)
            val request = Request.Builder()
                .url(url.build())
                .method(method, requestBody)
                .header("Authorization", "Bearer ${token()}")
                .header("Accept", "application/json")
                .header("Expect", "")
                .apply { if (method != "GET") header("Prefer", "return=representation") }
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(text).optString("message", text) }.getOrDefault(text)
                    throw ReactorException(response.code, message.ifEmpty { "request failed" })
                }
                if (text.isEmpty()) JSONArray() else JSONArray(text)
            }
        }
    }

    private fun host(): String = java.net.URI(base).host
    private fun port(): Int {
        val uri = java.net.URI(base)
        return if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
