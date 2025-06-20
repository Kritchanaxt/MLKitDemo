package ca.on.hojat.mlkitdemo.feature_ink_recognition

// Data class to store all Stroke data.
data class InkData(
    val strokes: List<StrokeData>
)

// Data class for storing data for each stroke.
data class StrokeData(
    val points: List<PointData>
)

// Data class for storing data of each point (Point)
data class PointData(
    val x: Float,
    val y: Float,

)