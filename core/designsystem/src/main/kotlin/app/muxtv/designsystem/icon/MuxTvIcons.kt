package app.muxtv.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Repo-owned icon family for the Lounge TV shell and primary daily states.
 *
 * Navigation/workspace glyphs share one 24dp canvas, rounded 1.9px stroke and
 * restrained visual mass. State glyphs may be filled where fill carries actual
 * state (currently playing/favourite), while the `LiveTv` destination stays an
 * outlined television instead of a generic filled-play triangle.
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

    val Sources: ImageVector by lazy {
        outlinedIcon("MuxTvSources") {
            moveTo(4.5f, 5f)
            lineTo(19.5f, 5f)
            lineTo(19.5f, 9f)
            lineTo(4.5f, 9f)
            close()
            moveTo(4.5f, 11f)
            lineTo(19.5f, 11f)
            lineTo(19.5f, 15f)
            lineTo(4.5f, 15f)
            close()
            moveTo(4.5f, 17f)
            lineTo(19.5f, 17f)
            lineTo(19.5f, 21f)
            lineTo(4.5f, 21f)
            close()
            moveTo(7f, 7f)
            lineTo(7.2f, 7f)
            moveTo(7f, 13f)
            lineTo(7.2f, 13f)
            moveTo(7f, 19f)
            lineTo(7.2f, 19f)
        }
    }

    val Doctor: ImageVector by lazy {
        outlinedIcon("MuxTvDoctor") {
            moveTo(12f, 3.75f)
            cubicTo(7.44f, 3.75f, 3.75f, 7.44f, 3.75f, 12f)
            cubicTo(3.75f, 16.56f, 7.44f, 20.25f, 12f, 20.25f)
            cubicTo(16.56f, 20.25f, 20.25f, 16.56f, 20.25f, 12f)
            cubicTo(20.25f, 7.44f, 16.56f, 3.75f, 12f, 3.75f)
            close()
            moveTo(6.75f, 12f)
            lineTo(9f, 12f)
            lineTo(10.25f, 9.25f)
            lineTo(12.25f, 15f)
            lineTo(13.75f, 12f)
            lineTo(17.25f, 12f)
        }
    }

    val Info: ImageVector by lazy {
        outlinedIcon("MuxTvInfo") {
            moveTo(12f, 3.75f)
            cubicTo(7.44f, 3.75f, 3.75f, 7.44f, 3.75f, 12f)
            cubicTo(3.75f, 16.56f, 7.44f, 20.25f, 12f, 20.25f)
            cubicTo(16.56f, 20.25f, 20.25f, 16.56f, 20.25f, 12f)
            cubicTo(20.25f, 7.44f, 16.56f, 3.75f, 12f, 3.75f)
            close()
            moveTo(12f, 10.5f)
            lineTo(12f, 16.25f)
            moveTo(12f, 7.4f)
            lineTo(12.05f, 7.4f)
        }
    }

    /** Filled state glyph: fill is meaningful because it means playback is active. */
    val Playing: ImageVector by lazy {
        filledIcon("MuxTvPlaying") {
            moveTo(8f, 5.5f)
            cubicTo(8f, 4.68f, 8.92f, 4.2f, 9.59f, 4.67f)
            lineTo(19f, 11.17f)
            cubicTo(19.6f, 11.58f, 19.6f, 12.42f, 19f, 12.83f)
            lineTo(9.59f, 19.33f)
            cubicTo(8.92f, 19.8f, 8f, 19.32f, 8f, 18.5f)
            close()
        }
    }

    /** Filled state glyph: mirrors the small persistent favourite marker in the reference. */
    val Favorite: ImageVector by lazy {
        filledIcon("MuxTvFavorite") {
            moveTo(12f, 3.4f)
            lineTo(14.65f, 8.77f)
            lineTo(20.58f, 9.63f)
            lineTo(16.29f, 13.81f)
            lineTo(17.3f, 19.72f)
            lineTo(12f, 16.93f)
            lineTo(6.7f, 19.72f)
            lineTo(7.71f, 13.81f)
            lineTo(3.42f, 9.63f)
            lineTo(9.35f, 8.77f)
            close()
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

    private inline fun filledIcon(
        name: String,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder)
    }.build()
}
