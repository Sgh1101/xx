package com.xvd.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 다운로드 기록을 SharedPreferences 에 JSON 으로 보관 */
object History {
    data class Entry(val id: Long, val name: String, val time: Long)

    private const val KEY = "history"
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences("xvd", Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): List<Entry> {
        val arr = runCatching { JSONArray(prefs(c).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val list = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Entry(o.optLong("id"), o.optString("name"), o.optLong("time")))
        }
        return list
    }

    private fun save(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("time", it.time))
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, e: Entry) = save(c, listOf(e) + load(c))

    @Synchronized
    fun remove(c: Context, id: Long) = save(c, load(c).filter { it.id != id })

    @Synchronized
    fun clear(c: Context) = save(c, emptyList())
}
