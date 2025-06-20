package ca.on.hojat.mlkitdemo.feature_ink_recognition

// Data class สำหรับเก็บข้อมูลของ Stroke ทั้งหมด
data class InkData(
    val strokes: List<StrokeData>
)

// Data class สำหรับเก็บข้อมูลของแต่ละเส้น (Stroke)
data class StrokeData(
    val points: List<PointData>
)

// Data class สำหรับเก็บข้อมูลของแต่ละจุด (Point)
data class PointData(
    val x: Float,
    val y: Float,
//    val t: Long
)