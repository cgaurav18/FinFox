package com.mymoney.app.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    /** Drive reports int64 values as strings. */
    val size: String? = null,
    val createdTime: String? = null,
    val md5Checksum: String? = null,
    val appProperties: Map<String, String>? = null,
)

@Serializable
data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

/** Token expired or revoked (HTTP 401) or scope missing (403 insufficient permissions). */
class DriveUnauthorizedException(message: String) : IOException(message)
/** Any other Drive error. [retryable] for 429/5xx/network failures. */
class DriveApiException(message: String, val retryable: Boolean) : IOException(message)

interface DriveService {
    @GET("drive/v3/files")
    suspend fun list(
        @Header("Authorization") auth: String,
        @Query("spaces") spaces: String = "appDataFolder",
        @Query("fields") fields: String = "nextPageToken,files($FILE_FIELDS)",
        @Query("orderBy") orderBy: String = "createdTime desc",
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null,
    ): DriveFileList

    @POST("upload/drive/v3/files")
    suspend fun upload(
        @Header("Authorization") auth: String,
        @Body body: RequestBody,
        @Query("uploadType") uploadType: String = "multipart",
        @Query("fields") fields: String = FILE_FIELDS,
    ): DriveFile

    @Streaming
    @GET("drive/v3/files/{id}")
    suspend fun download(@Header("Authorization") auth: String, @Path("id") id: String, @Query("alt") alt: String = "media"): ResponseBody

    @DELETE("drive/v3/files/{id}")
    suspend fun delete(@Header("Authorization") auth: String, @Path("id") id: String): Response<Unit>

    companion object { const val FILE_FIELDS = "id,name,size,createdTime,md5Checksum,appProperties" }
}

/** The few Drive operations FinFox uses, all confined to the hidden app data folder. */
interface DriveRemote {
    suspend fun list(token: String): List<DriveFile>
    suspend fun upload(token: String, name: String, bytes: ByteArray, appProperties: Map<String, String>): DriveFile
    suspend fun download(token: String, fileId: String, maxBytes: Long): ByteArray
    suspend fun delete(token: String, fileId: String)
}

class RetrofitDriveRemote(baseUrl: String = "https://www.googleapis.com/", client: OkHttpClient = defaultClient()) : DriveRemote {
    private val json = Json { ignoreUnknownKeys = true }
    private val api: DriveService = Retrofit.Builder().baseUrl(baseUrl).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(DriveService::class.java)

    private fun bearer(t: String) = "Bearer $t"

    private suspend fun <T> call(block: suspend () -> T): T = try { block() } catch (e: HttpException) {
        val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Exception) { "" }
        throw when {
            e.code() == 401 -> DriveUnauthorizedException("Google Drive authorization expired")
            e.code() == 403 && (body.contains("insufficient", true) || body.contains("SCOPE", false)) ->
                DriveUnauthorizedException("Google Drive permission is missing")
            e.code() == 403 && body.contains("storageQuotaExceeded") -> DriveApiException("Your Google Drive storage is full", retryable = false)
            e.code() == 404 -> DriveApiException("The backup no longer exists in Google Drive", retryable = false)
            e.code() == 429 || e.code() >= 500 || (e.code() == 403 && body.contains("rateLimit", true)) ->
                DriveApiException("Google Drive is busy (HTTP ${e.code()}). Try again later.", retryable = true)
            else -> DriveApiException("Google Drive request failed (HTTP ${e.code()})", retryable = false)
        }
    } catch (e: DriveUnauthorizedException) { throw e } catch (e: DriveApiException) { throw e } catch (e: IOException) {
        throw DriveApiException("Network error: ${e.message ?: "check your connection"}", retryable = true)
    }

    override suspend fun list(token: String): List<DriveFile> = call {
        val all = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val r = api.list(bearer(token), pageToken = page)
            all += r.files; page = r.nextPageToken
        } while (page != null && all.size < 1000)
        all
    }

    override suspend fun upload(token: String, name: String, bytes: ByteArray, appProperties: Map<String, String>): DriveFile = call {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", MIME)
            putJsonArray("parents") { add("appDataFolder") }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }.toString()
        // Drive's multipart upload expects multipart/related: JSON metadata part, then the media part.
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(MIME.toMediaType()))
            .build()
        api.upload(bearer(token), body)
    }

    override suspend fun download(token: String, fileId: String, maxBytes: Long): ByteArray = call {
        api.download(bearer(token), fileId).use { body ->
            if (body.contentLength() > maxBytes) throw DriveApiException("Backup file is too large", retryable = false)
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > maxBytes) throw DriveApiException("Backup file is too large", retryable = false)
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    override suspend fun delete(token: String, fileId: String) = call {
        val r = api.delete(bearer(token), fileId)
        if (!r.isSuccessful && r.code() != 404) throw HttpException(r)
    }

    companion object {
        const val MIME = "application/octet-stream"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
