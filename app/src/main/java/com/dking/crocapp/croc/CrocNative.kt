package com.dking.crocapp.croc

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object CrocNative {
    private const val TAG = "CrocNative"
    var loaded = false
        private set
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("croc")
            loaded = true
            Log.i(TAG, "libcroc.so loaded via dlopen")
        } catch (e: UnsatisfiedLinkError) {
            loadError = "libcroc.so not found: ${e.message}"
            Log.e(TAG, loadError!!, e)
        }
    }

    external fun crocStart(configJson: String): Int
    external fun crocWait(): Int
    external fun crocCancel()

    fun buildConfigJson(
        args: List<String>,
        env: Map<String, String>,
        workDir: String
    ): String {
        val json = JSONObject()
        json.put("args", JSONArray(args))
        val envObj = JSONObject()
        env.forEach { (k, v) -> envObj.put(k, v) }
        json.put("env", envObj)
        json.put("workDir", workDir)
        return json.toString()
    }
}
