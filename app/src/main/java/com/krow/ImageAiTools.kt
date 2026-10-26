package com.krow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.flt(k: String): Float? = (this[k] as? JsonPrimitive)?.floatOrNull

// ---------------------------------------------------------------------------------------------------- image_info
class ImageInfo : Tool {
    override val spec = ToolSpec("image_info", "Get width, height, format and file size of an image file in the project.",
        schema(listOf("path"), "path" to prop("string", "Image path relative to the project root")))
    override val kind = "file"
    override fun title(a: JsonObject) = "Image info"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val f = c.res(a.req("path"))
        if (!f.isFile) return ToolOut("Not found: ${a.str("path")}", false)
        val (w, h) = ImageTools.size(f) ?: return ToolOut("Not a readable image.", false)
        return ToolOut("${relTo(c.dir, f)}: ${w}×$h px, ${humanSize(f.length())}")
    }
}

// ---------------------------------------------------------------------------------------------------- process_image
class ProcessImage : Tool {
    override val spec = ToolSpec(
        "process_image",
        "Compress, resize, convert and edit an image file in the project and save the result as a NEW file (the original is kept). " +
            "Supports format conversion (jpeg/webp/png), quality, max side, a target file size in KB, rotate, flip, centered crop to an aspect ratio, grayscale, brightness, contrast and saturation. " +
            "Metadata such as GPS location is removed by re-encoding.",
        schema(listOf("path"),
            "path" to prop("string", "Source image path relative to the project root"),
            "out" to prop("string", "Output path. Default: next to the source, with _krow added to the name"),
            "format" to prop("string", "jpeg | webp | png. Default jpeg"),
            "quality" to prop("integer", "1-100 for jpeg/webp. Default 80"),
            "max_side" to prop("integer", "Shrink so the longest side is at most this many pixels"),
            "target_kb" to prop("integer", "Aim for a file no bigger than this many KB (quality is searched automatically; pixels shrink only if needed)"),
            "rotate" to prop("integer", "Clockwise degrees: 90, 180 or 270"),
            "flip_h" to prop("boolean", "Mirror left-right"),
            "flip_v" to prop("boolean", "Mirror top-bottom"),
            "crop" to prop("string", "Centered crop: 1:1 | 4:3 | 3:4 | 16:9 | 9:16"),
            "grayscale" to prop("boolean", "Convert to black and white"),
            "brightness" to prop("number", "-1.0 to 1.0, default 0"),
            "contrast" to prop("number", "0.0 to 2.0, default 1"),
            "saturation" to prop("number", "0.0 to 2.0, default 1")),
    )
    override val kind = "file"
    override val mutating = true
    override fun title(a: JsonObject) = "Process image"
    override fun cmd(a: JsonObject) = a.str("path") ?: ""
    override suspend fun run(c: ToolCtx, a: JsonObject): ToolOut {
        val src = c.res(a.req("path"))
        if (!src.isFile) return ToolOut("Not found: ${a.str("path")}", false)
        val bmp = ImageTools.decodeFile(src) ?: return ToolOut("Not a readable image.", false)
        val fmt = (a.str("format") ?: "jpeg").lowercase().let { if (it == "jpg") "jpeg" else it }
        if (fmt !in listOf("jpeg", "webp", "png")) return ToolOut("format must be jpeg, webp or png.", false)
        val o = ImgOpts(
            format = fmt, quality = (a.int("quality") ?: 80).coerceIn(1, 100), maxSide = (a.int("max_side") ?: 0).coerceAtLeast(0),
            targetKb = (a.int("target_kb") ?: 0).coerceAtLeast(0), rotate = (((a.int("rotate") ?: 0) % 360) + 360) % 360,
            flipH = a.bool("flip_h") ?: false, flipV = a.bool("flip_v") ?: false, crop = a.str("crop") ?: "original",
            grayscale = a.bool("grayscale") ?: false, brightness = (a.flt("brightness") ?: 0f).coerceIn(-1f, 1f),
            contrast = (a.flt("contrast") ?: 1f).coerceIn(0f, 2f), saturation = (a.flt("saturation") ?: 1f).coerceIn(0f, 2f),
        )
        val res = ImageTools.run(bmp, o)
        val outRel = a.str("out")?.takeIf { it.isNotBlank() }
        val dst = if (outRel != null) c.res(outRel) else java.io.File(src.parentFile, ImageTools.nameWithExt(src.name, fmt))
        if (dst.canonicalPath == src.canonicalPath) return ToolOut("Refusing to overwrite the source image; choose a different out path.", false)
        dst.parentFile?.mkdirs()
        dst.writeBytes(res.bytes)
        val saved = if (src.length() > 0) 100 - (res.bytes.size * 100L / src.length()) else 0
        return ToolOut("Saved ${relTo(c.dir, dst)}: ${res.width}×${res.height} ${fmt.uppercase()}" + (if (fmt != "png") " q${res.quality}" else "") +
            ", ${humanSize(res.bytes.size.toLong())} (was ${humanSize(src.length())}, ${if (saved >= 0) "$saved% smaller" else "${-saved}% larger"}).")
    }
}
