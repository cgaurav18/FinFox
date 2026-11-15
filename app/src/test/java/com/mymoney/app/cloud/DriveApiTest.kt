package com.mymoney.app.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** The Drive REST client against a local mock server: request shapes and error mapping. */
class DriveApiTest {
    private val server = MockWebServer()
    private lateinit var drive: RetrofitDriveRemote

    @Before fun setUp() { server.start(); drive = RetrofitDriveRemote(server.url("/").toString()) }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun uploadIsMultipartRelatedIntoAppDataFolder() = runBlocking {
        server.enqueue(json("""{"id":"abc","name":"mymoney-backup-1.mmbk","size":"5","md5Checksum":"x","createdTime":"2026-11-15T10:00:00Z"}"""))
        val f = drive.upload("tok", "mymoney-backup-1.mmbk", byteArrayOf(1, 2, 3, 4, 5), mapOf("app" to "mymoney"))
        assertThat(f.id).isEqualTo("abc")
        val r = server.takeRequest()
        assertThat(r.method).isEqualTo("POST")
        assertThat(r.path).startsWith("/upload/drive/v3/files")
        assertThat(r.requestUrl!!.queryParameter("uploadType")).isEqualTo("multipart")
        assertThat(r.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(r.getHeader("Content-Type")).startsWith("multipart/related")
        val body = r.body.readUtf8()
        assertThat(body).contains("\"parents\":[\"appDataFolder\"]")
        assertThat(body).contains("\"app\":\"mymoney\"")
    }

    @Test fun listOnlyQueriesAppDataSpaceAndFollowsPages() = runBlocking {
        server.enqueue(json("""{"files":[{"id":"1","name":"a"}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"files":[{"id":"2","name":"b","appProperties":{"app":"mymoney"}}]}"""))
        val files = drive.list("tok")
        assertThat(files.map { it.id }).containsExactly("1", "2").inOrder()
        val first = server.takeRequest()
        assertThat(first.requestUrl!!.queryParameter("spaces")).isEqualTo("appDataFolder")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("pageToken")).isEqualTo("p2")
    }

    @Test fun downloadReturnsBytesAndEnforcesLimit() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(10) { it.toByte() })))
        assertThat(drive.download("tok", "abc", 1_000).size).isEqualTo(10)
        assertThat(server.takeRequest().requestUrl!!.queryParameter("alt")).isEqualTo("media")
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(2_000))))
        val e = assertThrows(DriveApiException::class.java) { runBlocking { drive.download("tok", "abc", 1_000) } }
        assertThat(e.retryable).isFalse()
    }

    @Test fun errorsAreMappedForRetryAndReauthorization() {
        server.enqueue(json("""{"error":{"code":401}}""", 401))
        assertThrows(DriveUnauthorizedException::class.java) { runBlocking { drive.list("tok") } }
        server.enqueue(json("""{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""", 403))
        assertThrows(DriveUnauthorizedException::class.java) { runBlocking { drive.list("tok") } }
        server.enqueue(json("""{"error":{"code":503}}""", 503))
        assertThat(assertThrows(DriveApiException::class.java) { runBlocking { drive.list("tok") } }.retryable).isTrue()
        server.enqueue(json("""{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""", 403))
        val quota = assertThrows(DriveApiException::class.java) { runBlocking { drive.list("tok") } }
        assertThat(quota.retryable).isFalse(); assertThat(quota.message).contains("storage is full")
    }

    @Test fun deletingAMissingFileIsNotAnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        drive.delete("tok", "a")
        server.enqueue(json("{}", 404))
        drive.delete("tok", "gone")
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun networkFailureIsRetryable() {
        server.shutdown()
        val e = assertThrows(DriveApiException::class.java) { runBlocking { drive.list("tok") } }
        assertThat(e.retryable).isTrue()
    }
}
