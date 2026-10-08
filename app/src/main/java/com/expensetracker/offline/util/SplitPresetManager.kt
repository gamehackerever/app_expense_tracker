package com.expensetracker.offline.util

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class UserSplitPreset(
    val id: String,
    val name: String,
    val members: List<String>
)

object SplitPresetManager {
    const val KEY_PRESETS_JSON = "key_user_split_presets_v1"

    fun loadPresets(prefs: SharedPreferences): List<UserSplitPreset> {
        val raw = prefs.getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        val list = mutableListOf<UserSplitPreset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val membersArr = obj.getJSONArray("members")
                val members = mutableListOf<String>()
                for (j in 0 until membersArr.length()) {
                    val m = membersArr.getString(j).trim()
                    if (m.isNotBlank()) members.add(m)
                }
                if (name.isNotBlank() && members.isNotEmpty()) {
                    list.add(UserSplitPreset(id, name, members))
                }
            }
        } catch (e: Exception) {
            Log.e("SplitPresetManager", "Error parsing split presets", e)
        }
        return list
    }

    fun savePresets(prefs: SharedPreferences, presets: List<UserSplitPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("members", JSONArray(p.members))
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PRESETS_JSON, arr.toString()).apply()
    }
}
