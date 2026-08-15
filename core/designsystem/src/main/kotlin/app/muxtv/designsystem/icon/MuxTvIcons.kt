package app.muxtv.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small repo-owned icon family for the Lounge TV shell.
 *
 * Navigation glyphs intentionally share one 24dp canvas, rounded 1.9px stroke,
 * and no filled-play treatment so the rail has one visual mass across all five
 * destinations. The brand mark is a filled bow-tie silhouette derived from the
 * approved MuxTV Lounge reference rather than a generic Material glyph.
 */
object MuxTvIcons {
    val Home: ImageVector by lazy {
        outlinedIcon("MuxTvHome") {
            moveTo(3.5f, 10.5f)
            lineTo(12f, 3.75f)
            lineTo(20.5f, 10.5f)
            moveTo(5.5f, 9.35f)
            lineTo(5.5f, 20f)
            lineTo(10f, 20f)
            lineTo(10f, 14f)
            lineTo(14f, 14f)
            lineTo(14f, 20f)
            lineTo(18.5f, 20f)
            lineTo(18.5f, 9.35f)
        }
    }

    val LiveTv: ImageVector by lazy {
        outlinedIcon("MuxTvLiveTv") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            lineTo(20f, 18f)
            lineTo(4f, 18f)
            close()
            moveTo(8.5f, 3.75f)
            lineTo(12f, 7f)
            lineTo(15.5f, 3.75f)
            moveTo(8f, 21f)
            lineTo(16f, 21f)
        }
    }

    val Guide: ImageVector by lazy {
        outlinedIcon("MuxTvGuide") {
            moveTo(5f, 5.5f)
            lineTo(19f, 5.5f)
            lineTo(19f, 20f)
            lineTo(5f, 20f)
            close()
            moveTo(5f, 9.5f)
            lineTo(19f, 9.5f)
            moveTo(8f, 3.5f)
            lineTo(8f, 7.5f)
            moveTo(16f, 3.5f)
            lineTo(16f, 7.5f)
            moveTo(8f, 13f)
            lineTo(9f, 13f)
            moveTo(11.5f, 13f)
            lineTo(12.5f, 13f)
            moveTo(15f, 13f)
            lineTo(16f, 13f)
            moveTo(8f, 16.5f)
            lineTo(9f, 16.5f)
            moveTo(11.5f, 16.5f)
            lineTo(12.5f, 16.5f)
        }
    }

    val Search: ImageVector by lazy {
        outlinedIcon("MuxTvSearch") {
            moveTo(10.5f, 4f)
            cubicTo(6.91f, 4f, 4f, 6.91f, 4f, 10.5f)
            cubicTo(4f, 14.09f, 6.91f, 17f, 10.5f, 17f)
            cubicTo(14.09f, 17f, 17f, 14.09f, 17f, 10.5f)
            cubicTo(17f, 6.91f, 14.09f, 4f, 10.5f, 4f)
            close()
            moveTo(15.4f, 15.4f)
            lineTo(20.25f, 20.25f)
        }
    }

    val Settings: ImageVector by lazy {
        outlinedIcon("MuxTvSettings") {
            moveTo(12f, 4.25f)
            cubicTo(7.72f, 4.25f, 4.25f, 7.72f, 4.25f, 12f)
            cubicTo(4.25f, 16.28f, 7.72f, 19.75f, 12f, 19.75f)
            cubicTo(16.28f, 19.75f, 19.75f, 16.28f, 19.75f, 12f)
            cubicTo(19.75f, 7.72f, 16.28f, 4.25f, 12f, 4.25f)
            close()
            moveTo(12f, 9.25f)
            cubicTo(10.48f, 9.25f, 9.25f, 10.48f, 9.25f, 12f)
            cubicTo(9.25f, 13.52f, 10.48f, 14.75f, 12f, 14.75f)
            cubicTo(13.52f, 14.75f, 14.75f, 13.52f, 14.75f, 12f)
            cubicTo(14.75f, 10.48f, 13.52f, 9.25f, 12f, 9.25f)
            close()
            moveTo(12f, 1.75f)
            lineTo(12f, 4.25f)
            moveTo(12f, 19.75f)
            lineTo(12f, 22.25f)
            moveTo(1.75f, 12f)
            lineTo(4.25f, 12f)
            moveTo(19.75f, 12f)
            lineTo(22.25f, 12f)
            moveTo(4.75f, 4.75f)
            lineTo(6.5f, 6.5f)
            moveTo(17.5f, 17.5f)
            lineTo(19.25f, 19.25f)
            moveTo(19.25f, 4.75f)
            lineTo(17.5f, 6.5f)
            moveTo(6.5f, 17.5f)
            lineTo(4.75f, 19.25f)
        }
    }

    val BrandMark: ImageVector by lazy {
        ImageVector.Builder(
            name = "MuxTvBrandMark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3.25f, 4.25f)
                lineTo(11f, 10.25f)
                lineTo(11f, 13.75f)
                lineTo(3.25f, 19.75f)
                cubicTo(2.55f, 20.3f, 1.5f, 19.8f, 1.5f, 18.9f)
                lineTo(1.5f, 5.1f)
                cubicTo(1.5f, 4.2f, 2.55f, 3.7f, 3.25f, 4.25f)
                close()
                moveTo(20.75f, 4.25f)
                lineTo(13f, 10.25f)
                lineTo(13f, 13.75f)
                lineTo(20.75f, 19.75f)
                cubicTo(21.45f, 20.3f, 22.5f, 19.8f, 22.5f, 18.9f)
                lineTo(22.5f, 5.1f)
                cubicTo(22.5f, 4.2f, 21.45f, 3.7f, 20.75f, 4.25f)
                close()
                moveTo(11f, 10.25f)
                lineTo(12f, 9.45f)
                lineTo(13f, 10.25f)
                lineTo(13f, 13.75f)
                lineTo(12f, 14.55f)
                lineTo(11f, 13.75f)
                close()
            }
        }.build()
    }

    private inline fun outlinedIcon(
        name: String,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
    }.build()
}
