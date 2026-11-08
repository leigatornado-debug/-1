package com.example.virtualgps

data class Pace(val minutes: Int, val seconds: Int) {
    init {
        require(minutes >= 0 && seconds in 0..59) { "配速格式无效" }
        require(minutes * 60 + seconds > 0) { "配速必须大于 0" }
    }

    fun metersPerSecond(): Double = 1000.0 / (minutes * 60 + seconds)

    companion object {
        fun parse(text: String): Pace {
            val parts = text.trim().split(":")
            require(parts.size == 2) { "请输入分:秒/公里，例如 6:00" }
            return Pace(parts[0].trim().toInt(), parts[1].trim().toInt())
        }
    }
}

data class RoutePoint(val latitude: Double, val longitude: Double)
