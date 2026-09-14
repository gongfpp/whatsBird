package com.whatsbird.species

import android.content.Context
import org.json.JSONObject

/**
 * Loads `assets/models/species.json`. Deliberately tolerant: a missing or malformed dictionary
 * degrades the app to detection-only instead of crashing it.
 */
object SpeciesDictionaryLoader {

    fun load(context: Context, assetPath: String = SpeciesDictionary.ASSET_PATH): SpeciesDictionary? {
        val raw = runCatching {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null

        return runCatching { parse(raw) }.getOrNull()
    }

    private fun parse(raw: String): SpeciesDictionary {
        val root = JSONObject(raw)
        val array = root.optJSONArray("classes") ?: return empty()
        val classes = ArrayList<Species>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            classes += Species(
                index = item.optInt("i", i),
                scientificName = item.optString("sci"),
                englishName = item.optString("en"),
                chineseName = item.optString("zh"),
            )
        }
        return SpeciesDictionary(
            version = root.optString("version", "unknown"),
            modelVersion = root.optString("modelVersion", "unknown"),
            inputSize = root.optInt("inputSize", 224),
            inputScale = root.optDouble("inputScale", 1.0 / 127.5).toFloat(),
            inputOffset = root.optDouble("inputOffset", -1.0).toFloat(),
            cropPaddingRatio = root.optDouble("cropPaddingRatio", 0.15).toFloat(),
            backgroundClassIndex = root.optInt("backgroundClassIndex", -1),
            classes = classes,
        )
    }

    private fun empty() = SpeciesDictionary(
        version = "empty",
        modelVersion = "none",
        inputSize = 224,
        inputScale = 1f / 127.5f,
        inputOffset = -1f,
        cropPaddingRatio = 0.15f,
        backgroundClassIndex = -1,
        classes = emptyList(),
    )
}
