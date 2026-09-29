package com.example.harmoniumhost

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Follows this room's Harmonium activity select (`select.harmonium_<room>_activity`, "off" when
 * idle) over [HaLink], so the screen can stay awake while something is playing. subscribe_entities
 * with one entity id: HA sends the current state once, then only changes to it.
 */
class ActivityWatcher(private val prefs: HostPrefs, private val link: HaLink) : HaLink.Listener {

    init { link.addListener(this) }

    override fun onReady() {
        val entity = prefs.activityEntity
        val idle = prefs.idleStates
        var first = true
        val sub = JSONObject()
            .put("type", "subscribe_entities")
            .put("entity_ids", JSONArray().put(entity))
        link.send(sub) { msg ->
            when (msg.optString("type")) {
                "result" -> if (!msg.optBoolean("success")) {
                    Log.w(TAG, "activity subscribe failed: ${msg.optJSONObject("error")}")
                }
                "event" -> {
                    val ev = msg.optJSONObject("event") ?: return@send
                    val added = ev.optJSONObject("a")?.optJSONObject(entity)
                    val changed = ev.optJSONObject("c")?.optJSONObject(entity)?.optJSONObject("+")
                    when {
                        added != null -> update(added.optString("s"), idle)
                        changed != null && changed.has("s") -> update(changed.optString("s"), idle)
                        ev.optJSONArray("r")?.toString()?.contains("\"$entity\"") == true -> update(null, idle)
                        first -> {
                            Log.w(TAG, "$entity not found in HA; keep-awake can't follow activities")
                            update(null, idle)
                        }
                    }
                    first = false
                }
            }
        }
    }

    private fun update(state: String?, idle: Set<String>) {
        Log.i(TAG, "activity: $state")
        HostState.setActivity(state, idle)
    }

    private companion object { const val TAG = "HarmoniumHost" }
}
