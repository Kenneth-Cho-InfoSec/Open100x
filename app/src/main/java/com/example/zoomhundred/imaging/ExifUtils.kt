package com.example.zoomhundred.imaging

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

object ExifUtils {

    /**
     * Writes pro metadata into the saved image.
     * @param zoomRatio   total zoom factor applied (e.g. 30.0)
     * @param nativeZoom  optical zoom portion
     * @param iso         manual ISO value, or null if auto
     * @param shutterNs   manual shutter in nanoseconds, or null if auto
     * @param stripGps    if true, clears GPS tags before saving
     */
    fun writeProMetadata(
        resolver: ContentResolver,
        uri: Uri,
        zoomRatio: Float,
        nativeZoom: Float,
        iso: Int?,
        shutterNs: Long?,
        stripGps: Boolean
    ) {
        resolver.openFileDescriptor(uri, "rw")?.use { fd ->
            val exif = ExifInterface(fd.fileDescriptor)

            // Digital zoom ratio
            exif.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, "%.2f".format(zoomRatio / nativeZoom.coerceAtLeast(1f)))

            // Manual exposure, if set
            iso?.let { exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, it.toString()) }
            shutterNs?.let {
                // ExifInterface expects rational: numerator/denominator
                val seconds = it / 1_000_000_000.0
                val denom = (1.0 / seconds).roundToInt().coerceAtLeast(1)
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/$denom")
            }

            // Approximate 35mm equivalent focal length
            // Assumes a ~6mm physical focal length on most mobile telephoto sensors
            val physicalFocalMm = 6f
            val equiv35mm = (physicalFocalMm * zoomRatio).roundToInt()
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, equiv35mm.toString())

            // Software tag
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Open100x")

            // GPS scrub
            if (stripGps) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, null)
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null)
            }

            exif.saveAttributes()
        }
    }
}
