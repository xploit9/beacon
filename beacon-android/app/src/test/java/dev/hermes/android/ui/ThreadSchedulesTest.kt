package dev.hermes.android.ui
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
class ThreadSchedulesTest {
    private fun job(id: String, deliver: String?) = JSONObject().put("id", id).apply { if (deliver != null) put("deliver", deliver) }
    @Test fun aThreadListsOnlyTheTasksThatPostToIt() {
        val jobs = listOf(job("a", "phone:t1"), job("b", "telegram, phone:t1"), job("c", "phone"), job("d", "phone:t10"), job("e", "local"), job("f", null))
        assertEquals(listOf("a", "b"), threadJobs(jobs, "t1").map { it.getString("id") })
        assertEquals(listOf("d"), threadJobs(jobs, "t10").map { it.getString("id") })
        assertEquals(emptyList<JSONObject>(), threadJobs(jobs, "t2"))
    }
}
