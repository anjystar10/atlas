package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenTest {
    private val pic = "A".repeat(400)

    @Test fun findsThePictureInAnInteractionsReply() {
        val r = JSONObject("""{"id":"x","steps":[{"type":"model_output","content":[{"type":"text","text":"Here you go"},{"type":"image","mime_type":"image/png","data":"$pic"}]}]}""")
        assertEquals(pic to "image/png", ImageGen.findImage(r))
        assertEquals("Here you go", ImageGen.findText(r))
    }

    @Test fun findsThePictureInTheOlderShapeToo() {
        val r = JSONObject("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/jpeg","data":"$pic"}}]}}]}""")
        assertEquals(pic to "image/jpeg", ImageGen.findImage(r))
    }

    @Test fun noPictureWhenOnlyTextCameBack() {
        assertNull(ImageGen.findImage(JSONObject("""{"steps":[{"content":[{"type":"text","text":"I can't make that."}]}]}""")))
    }

    @Test fun openAiSizeFollowsTheShape() {
        assertEquals("1024x1024", ImageGen.openAiSize("1:1"))
        assertEquals("1536x1024", ImageGen.openAiSize("16:9"))
        assertEquals("1024x1536", ImageGen.openAiSize("9:16"))
        assertEquals("1536x1024", ImageGen.openAiSize("5:4"))
        assertEquals("1024x1024", ImageGen.openAiSize("bad"))
    }
}
