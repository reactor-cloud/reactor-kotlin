package reactor

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueTest {
    @Test
    fun queueAndEnqueueUseTheServiceRoutes() = runBlocking {
        val server = MockWebServer()
        fun json(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("content-type", "application/json"))
        json(201, """{"name":"jobs"}""")
        json(200, """[{"name":"jobs","created_at":"2026-01-01T00:00:00Z"}]""")
        json(201, """{"msg_id":7}""")
        json(200, """[{"msg_id":7,"message":{"hello":"world"},"read_ct":0}]""")
        json(200, """[{"msg_id":7,"message":{"hello":"world"},"read_ct":0}]""")
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(204))
        json(201, """{"id":"task-1"}""")
        json(200, """{"id":"task-1","status":"queued"}""")

        val client = ReactorClient(server.url("/").toString().trimEnd('/'), "service-key")
        assertEquals("jobs", client.queue.create("jobs").getString("name"))
        assertEquals("jobs", client.queue.list().getJSONObject(0).getString("name"))
        assertEquals(7, client.queue.send("jobs", JSONObject().put("hello", "world"), delaySecs = 5).getInt("msg_id"))
        assertEquals(1, client.queue.read("jobs", vtSecs = 10, qty = 2).length())
        assertEquals(1, client.queue.peek("jobs").length())
        client.queue.delete("jobs", 7)
        client.queue.archive("jobs", 7)
        client.queue.subscribe("jobs", "echo", vtSecs = 30, qty = 1, maxReads = 3)
        client.queue.unsubscribe("jobs", "echo")
        assertEquals("task-1", client.functions.enqueue("ping", JSONObject().put("n", 1), delaySecs = 2, maxAttempts = 1).getString("id"))
        assertEquals("queued", client.functions.task("task-1").getString("status"))

        val seen = (0 until 11).map { server.takeRequest() }
        assertEquals(
            listOf(
                "POST /queue/v1/queues",
                "GET /queue/v1/queues",
                "POST /queue/v1/queues/jobs/send",
                "POST /queue/v1/queues/jobs/read",
                "GET /queue/v1/queues/jobs/peek",
                "POST /queue/v1/queues/jobs/delete",
                "POST /queue/v1/queues/jobs/archive",
                "POST /queue/v1/queues/jobs/subscriptions",
                "DELETE /queue/v1/queues/jobs/subscriptions",
                "POST /fn/v1/ping/enqueue",
                "GET /fn/v1/_admin/tasks/task-1",
            ),
            seen.map { "${it.method} ${it.path}" },
        )
        assertEquals(true, seen.all { it.getHeader("Authorization") == "Bearer service-key" })
        val send = JSONObject(seen[2].body.readUtf8())
        assertEquals("world", send.getJSONObject("message").getString("hello"))
        assertEquals(5, send.getInt("delay_secs"))
        val read = JSONObject(seen[3].body.readUtf8())
        assertEquals(10, read.getInt("vt_secs"))
        assertEquals(2, read.getInt("qty"))
        assertEquals("echo", JSONObject(seen[7].body.readUtf8()).getString("function_name"))
        assertEquals("echo", JSONObject(seen[8].body.readUtf8()).getString("function_name"))
        val enqueued = JSONObject(seen[9].body.readUtf8())
        assertEquals(2, enqueued.getInt("delay_secs"))
        assertEquals(1, enqueued.getInt("max_attempts"))
        server.shutdown()
    }
}
