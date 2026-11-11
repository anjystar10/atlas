package com.anjy.atlas

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Making pictures with the user's own keys: Gemini's image models (Nano Banana, through the Interactions API) or
 * OpenAI's GPT Image, whichever has a ready key (Gemini first). Keys that are used up are rested like the chat's, and
 * the next one takes over. Model ids and request shapes follow the official docs as of November 2026:
 * ai.google.dev/gemini-api/docs/image-generation and developers.openai.com/api/reference/resources/images.
 */
object ImageGen {
    private const val TAG = "AtlasImage"
    /** Newest first; the next is tried when a model isn't available to the key. */
    private val GEMINI_MODELS = listOf("gemini-nano-banana-2.1", "gemini-3.1-flash-lite-image", "gemini-3.1-flash-image")
    private val OPENAI_MODELS = listOf("gpt-image-2.5-sunburst", "gpt-image-2", "gpt-image-1.5")
    val ASPECTS = listOf("1:1", "3:2", "2:3", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9")
    private const val MAX_REFS = 4

    val declaration: JSONObject by lazy {
        JSONObject().put("name", "generate_image").put("description",
            "Make a picture from a description (and optionally from the pictures the user just sent, e.g. to edit or restyle them). The picture appears in the chat with Save and Share; don't describe how you made it.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("prompt", JSONObject().put("type", "string").put("description", "A full description of the picture: subject, setting, style, light, mood, any text to show in it"))
                .put("aspect", JSONObject().put("type", "string").put("description", "Shape of the picture (default 1:1)").put("enum", JSONArray(ASPECTS)))
                .put("use_my_pictures", JSONObject().put("type", "boolean").put("description", "Base it on the pictures in the user's latest message (to edit, combine or restyle them)")))
                .put("required", JSONArray().put("prompt")))
    }

    class Made(val file: File, val mime: String, val note: String, val service: String)

    /** Which service would make a picture now, or null with nothing ready. */
    fun service(ctx: Context): String? = when {
        Providers.readyKey(ctx, "gemini") != null -> "gemini"
        Providers.readyKey(ctx, "openai") != null -> "openai"
        else -> null
    }

    /** Blocking (network): call off the main thread. [refs]: pictures to base it on. Saved in [dir]. */
    fun make(ctx: Context, prompt: String, aspect: String, refs: List<File>, dir: File): Made {
        val order = buildList { if (Providers.hasKeys(ctx, "gemini")) add("gemini"); if (Providers.hasKeys(ctx, "openai")) add("openai") }
        if (order.isEmpty()) throw IllegalStateException("Making pictures needs a Gemini or OpenAI key (Me → Brain).")
        val refData = refs.take(MAX_REFS).mapNotNull { f -> shrink(f)?.let { it to "image/jpeg" } }
        var last: Exception? = null
        for (svc in order) {
            val tried = mutableSetOf<String>()
            while (true) {
                val key = Providers.readyKey(ctx, svc)?.takeIf { it !in tried } ?: break
                tried.add(key)
                try {
                    val (bytes, mime, note) = if (svc == "gemini") gemini(key, prompt, aspect, refData) else openai(key, prompt, aspect, refData)
                    Providers.worked(ctx, svc, key)
                    dir.mkdirs()
                    val f = File(dir, "picture-${System.currentTimeMillis()}.${if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"}")
                    f.writeBytes(bytes)
                    return Made(f, mime, note, if (svc == "gemini") "Gemini" else "OpenAI")
                } catch (e: Gemini.Failure) {
                    last = e
                    Log.w(TAG, "$svc image", e)
                    if (Providers.keyExhausted(e)) { Providers.rest(ctx, svc, key, e.code, e.message ?: ""); continue }
                    break   // not a key problem (refused, not available): try the other service
                }
            }
        }
        throw last ?: IllegalStateException("No key is ready to make pictures right now (they're resting after their limits).")
    }

    /** A picture to send as a reference: at most 1536 px on the long side, JPEG, so requests stay small. */
    private fun shrink(f: File): ByteArray? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 3072) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = 1536f / maxOf(b.width, b.height)
        val sc = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        java.io.ByteArrayOutputStream().also { sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    } catch (e: Exception) { null }

    // ---- Gemini (Interactions API) ----
    private fun gemini(key: String, prompt: String, aspect: String, refs: List<Pair<ByteArray, String>>): Triple<ByteArray, String, String> {
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        refs.forEach { (b, m) -> input.put(JSONObject().put("type", "image").put("mime_type", m).put("data", Base64.encodeToString(b, Base64.NO_WRAP))) }
        var last: Gemini.Failure? = null
        for (model in GEMINI_MODELS) {
            val body = JSONObject().put("model", model).put("input", input)
                .put("response_format", JSONObject().put("type", "image").put("mime_type", "image/jpeg").put("aspect_ratio", aspect).put("image_size", "1K"))
            val r = try { Providers.post("https://generativelanguage.googleapis.com/v1beta/interactions", mapOf("x-goog-api-key" to key), body, "Gemini pictures") }
                catch (f: Gemini.Failure) { last = f; if (f.code == 404 || (f.code == 400 && f.message.orEmpty().contains("model", true))) continue else throw f }
            val img = findImage(r) ?: throw Gemini.Failure("Gemini didn't make a picture" + (findText(r)?.let { ": ${it.take(200)}" } ?: " (it may have turned the request down)."), code = 422)
            return Triple(Base64.decode(img.first, Base64.DEFAULT), img.second, findText(r).orEmpty())
        }
        throw last ?: Gemini.Failure("Gemini has no picture model for this key.", code = 404)
    }

    /** The picture in an Interactions response: an "image" block's data (the last one), with its type. */
    internal fun findImage(o: Any?): Pair<String, String>? {
        var found: Pair<String, String>? = null
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val data = x.optString("data")
                    val type = x.optString("type")
                    val mime = x.optString("mime_type").ifBlank { x.optString("mimeType") }
                    if (data.length > 100 && (type == "image" || mime.startsWith("image/"))) found = data to mime.ifBlank { "image/jpeg" }
                    x.optJSONObject("inlineData")?.let { d -> if (d.optString("mimeType").startsWith("image/") && d.optString("data").length > 100) found = d.optString("data") to d.optString("mimeType") }
                    x.keys().forEach { k -> if (k != "data") walk(x.opt(k)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return found
    }

    internal fun findText(o: Any?): String? {
        val parts = mutableListOf<String>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> { if (x.optString("type") == "text" && x.optString("text").isNotBlank()) parts.add(x.optString("text")); x.keys().forEach { k -> walk(x.opt(k)) } }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return parts.lastOrNull()
    }

    // ---- OpenAI (Images API) ----
    internal fun openAiSize(aspect: String): String {
        val (w, h) = aspect.split(':').mapNotNull { it.toFloatOrNull() }.let { if (it.size == 2) it[0] to it[1] else 1f to 1f }
        return when { w / h > 1.15f -> "1536x1024"; h / w > 1.15f -> "1024x1536"; else -> "1024x1024" }
    }

    private fun openai(key: String, prompt: String, aspect: String, refs: List<Pair<ByteArray, String>>): Triple<ByteArray, String, String> {
        var last: Gemini.Failure? = null
        for (model in OPENAI_MODELS) {
            val body = JSONObject().put("model", model).put("prompt", prompt).put("size", openAiSize(aspect)).put("quality", "medium").put("output_format", "jpeg").put("n", 1)
            val url = if (refs.isEmpty()) "https://api.openai.com/v1/images/generations" else {
                body.put("images", JSONArray().apply { refs.forEach { (b, m) -> put(JSONObject().put("image_url", "data:$m;base64," + Base64.encodeToString(b, Base64.NO_WRAP))) } })
                "https://api.openai.com/v1/images/edits"
            }
            val r = try { Providers.post(url, mapOf("Authorization" to "Bearer $key"), body, "OpenAI pictures") }
                catch (f: Gemini.Failure) { last = f; if (f.code == 404 || (f.code == 400 && f.message.orEmpty().contains("model", true))) continue else throw f }
            val b64 = r.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
            if (b64.isEmpty()) throw Gemini.Failure("OpenAI didn't send a picture back.", code = 422)
            return Triple(Base64.decode(b64, Base64.DEFAULT), "image/jpeg", "")
        }
        throw last ?: Gemini.Failure("OpenAI has no picture model for this key.", code = 404)
    }

    // ---- keeping and sharing ----

    /** Copies a picture into the phone's gallery (Pictures/Atlas). No permission is needed for Atlas's own pictures. */
    fun saveToGallery(ctx: Context, f: File, mime: String): Uri? = try {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name); put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Atlas"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri != null) {
            r.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    } catch (e: Exception) { Log.w(TAG, "save", e); null }

    /** Opens the share sheet for a picture (saved to the gallery first, so other apps can open it). */
    fun share(ctx: Context, f: File, mime: String): Boolean {
        val uri = saveToGallery(ctx, f, mime) ?: return false
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
