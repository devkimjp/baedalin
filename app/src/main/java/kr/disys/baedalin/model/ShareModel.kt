package kr.disys.baedalin.model

import org.json.JSONArray
import org.json.JSONObject

data class DeviceInfo(
    val model: String,
    val width: Int,
    val height: Int,
    val dpi: Int
) {
    fun toJSONObject(): JSONObject = JSONObject().apply {
        put("model", model)
        put("width", width)
        put("height", height)
        put("dpi", dpi)
    }

    companion object {
        fun fromJSONObject(json: JSONObject): DeviceInfo = DeviceInfo(
            model = json.optString("model", "Unknown"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            dpi = json.optInt("dpi", 0)
        )
    }
}

data class CoordinateEntry(
    val preset: String,
    val function: String,
    val x: Int,
    val y: Int
)

data class CustomWidgetInfo(
    val preset: String,
    val activeWidgets: String,
    val counter: Int,
    val lastX: Int,
    val lastY: Int
)

data class ShareConfig(
    val version: Int = 1,
    val deviceInfo: DeviceInfo,
    val coordinates: List<CoordinateEntry>,
    val customWidgets: List<CustomWidgetInfo>
) {
    fun toJSONString(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("deviceInfo", deviceInfo.toJSONObject())
        
        val coordsArray = JSONArray()
        coordinates.forEach { 
            coordsArray.put(JSONObject().apply {
                put("p", it.preset)
                put("f", it.function)
                put("x", it.x)
                put("y", it.y)
            })
        }
        root.put("coords", coordsArray)

        val customArray = JSONArray()
        customWidgets.forEach {
            customArray.put(JSONObject().apply {
                put("p", it.preset)
                put("a", it.activeWidgets)
                put("c", it.counter)
                put("lx", it.lastX)
                put("ly", it.lastY)
            })
        }
        root.put("custom", customArray)

        return root.toString()
    }

    companion object {
        fun fromJSONString(jsonStr: String): ShareConfig {
            val root = JSONObject(jsonStr)
            val deviceInfo = DeviceInfo.fromJSONObject(root.getJSONObject("deviceInfo"))
            
            val coords = mutableListOf<CoordinateEntry>()
            val coordsArray = root.optJSONArray("coords")
            if (coordsArray != null) {
                for (i in 0 until coordsArray.length()) {
                    val obj = coordsArray.getJSONObject(i)
                    coords.add(CoordinateEntry(
                        preset = obj.getString("p"),
                        function = obj.getString("f"),
                        x = obj.getInt("x"),
                        y = obj.getInt("y")
                    ))
                }
            }

            val custom = mutableListOf<CustomWidgetInfo>()
            val customArray = root.optJSONArray("custom")
            if (customArray != null) {
                for (i in 0 until customArray.length()) {
                    val obj = customArray.getJSONObject(i)
                    custom.add(CustomWidgetInfo(
                        preset = obj.getString("p"),
                        activeWidgets = obj.getString("a"),
                        counter = obj.getInt("c"),
                        lastX = obj.getInt("lx"),
                        lastY = obj.getInt("ly")
                    ))
                }
            }

            return ShareConfig(
                version = root.optInt("version", 1),
                deviceInfo = deviceInfo,
                coordinates = coords,
                customWidgets = custom
            )
        }
    }
}
