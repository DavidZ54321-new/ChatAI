package com.zcw.chatai.data.net

import com.zcw.chatai.data.model.ChatConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashScopeUploadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesPolicyFromDataEnvelope() {
        val policy = DashScopeUpload.parsePolicy(
            """{"request_id":"r","data":{"policy":"p","signature":"s","upload_dir":"dashscope-instant/a/2026-09-18/u","upload_host":"https://host","expire_in_seconds":300,"max_file_size_mb":1024,"oss_access_key_id":"LTA","x_oss_object_acl":"private","x_oss_forbid_overwrite":"true"}}""",
        )
        requireNotNull(policy)
        assertEquals("dashscope-instant/a/2026-09-18/u", policy.uploadDir)
        assertEquals(1024, policy.maxFileSizeMb)
        assertEquals("true", policy.xOssForbidOverwrite)
    }

    @Test
    fun rejectsIncompletePolicyAndGarbage() {
        assertNull(DashScopeUpload.parsePolicy("not json"))
        assertNull(DashScopeUpload.parsePolicy("""{"data":{"policy":"p"}}"""))
        assertNull(DashScopeUpload.parsePolicy("""{"request_id":"r"}"""))
    }

    @Test
    fun fetchesPolicyWithGetRequest() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(policyResponse()),
        )
        val upload = DashScopeUpload()
        val policy = upload.fetchPolicy(config(), "qwen3.8-max")

        assertEquals("dashscope-instant/a/u", policy.uploadDir)
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path.orEmpty(), recorded.path.orEmpty().startsWith("/api/v1/uploads"))
        assertTrue(recorded.path.orEmpty(), recorded.path.orEmpty().contains("action=getPolicy"))
        assertTrue(recorded.path.orEmpty(), recorded.path.orEmpty().contains("model=qwen3.8-max"))
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun uploadsMultipartWithFileLastAndAllPolicyFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val file = temp.newFile("clip.mp4").apply { writeBytes("VIDEOBYTES".toByteArray()) }
        val upload = DashScopeUpload()
        val outcome = upload.upload(file, "dashscope-instant/a/u/clip.mp4", policy())

        assertEquals(UploadOutcome.Success, outcome)
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("""name="OSSAccessKeyId""""))
        assertTrue(body, body.contains("LTA"))
        assertTrue(body, body.contains("""name="policy""""))
        assertTrue(body, body.contains("p1"))
        assertTrue(body, body.contains("""name="Signature""""))
        assertTrue(body, body.contains("""name="x-oss-forbid-overwrite""""))
        assertTrue(body, body.contains("""name="key""""))
        assertTrue(body, body.contains("dashscope-instant/a/u/clip.mp4"))
        assertTrue(body, body.contains("VIDEOBYTES"))
        // file 必须是最后一个表单域
        assertTrue(body, body.indexOf("name=\"file\"") > body.lastIndexOf("name=\"key\""))
    }

    @Test
    fun duplicateObjectIsReportedAsAlreadyExists() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """<?xml version="1.0"?><Error><Code>FileAlreadyExists</Code><Message>exists</Message></Error>""",
            ),
        )
        val file = temp.newFile("clip.mp4").apply { writeBytes("X".toByteArray()) }
        val outcome = DashScopeUpload().upload(file, "k", policy())
        assertEquals(UploadOutcome.AlreadyExists, outcome)
    }

    @Test
    fun forbiddenUploadBecomesReadableFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("AccessDenied"))
        val file = temp.newFile("clip.mp4").apply { writeBytes("X".toByteArray()) }
        val outcome = DashScopeUpload().upload(file, "k", policy())
        assertTrue(outcome.toString(), outcome is UploadOutcome.Failure)
        assertTrue((outcome as UploadOutcome.Failure).message, outcome.message.contains("403"))
    }

    @Test
    fun buildsOssUrlFromKey() {
        assertEquals("oss://dashscope-instant/a/u/x.mp4", DashScopeUpload().urlFor("dashscope-instant/a/u/x.mp4"))
    }

    private fun policy() = UploadPolicy(
        policy = "p1",
        signature = "sig",
        uploadDir = "dashscope-instant/a/u",
        uploadHost = server.url("/").toString(),
        expireInSeconds = 300,
        maxFileSizeMb = 1024,
        ossAccessKeyId = "LTA",
    )

    private fun policyResponse() = """
        {"request_id":"r","data":{
          "policy":"p1","signature":"sig",
          "upload_dir":"dashscope-instant/a/u",
          "upload_host":"${server.url("/")}",
          "expire_in_seconds":300,"max_file_size_mb":1024,
          "oss_access_key_id":"LTA","x_oss_object_acl":"private","x_oss_forbid_overwrite":"true"
        }}
    """.trimIndent()

    private fun config() = ChatConfig(
        baseUrl = server.url("/compatible-mode/v1").toString(),
        apiKey = "test-key",
        model = "qwen3.8-max",
        systemPrompt = "",
        temperature = null,
    )
}
