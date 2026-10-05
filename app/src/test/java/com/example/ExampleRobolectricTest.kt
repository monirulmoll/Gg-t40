package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.BridgeCommand
import com.example.model.BridgeResult
import com.example.model.CommandAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Bridge Controller", appName)
    }

    @Test
    fun `command parsing from json`() {
        val json = """{"action":"OPEN_APP","package":"com.termux"}"""
        val cmd = BridgeCommand.fromJson(json)
        assertEquals(CommandAction.OPEN_APP, cmd.action)
        assertEquals("com.termux", cmd.packageName)
    }

    @Test
    fun `click command parsing from json`() {
        val json = """{"action":"CLICK","target":"Copy"}"""
        val cmd = BridgeCommand.fromJson(json)
        assertEquals(CommandAction.CLICK, cmd.action)
        assertEquals("Copy", cmd.target)
    }

    @Test
    fun `bridge result to json serialization`() {
        val res = BridgeResult.success("GET_SCREEN_TEXT", "Extracted text", mapOf("length" to 42))
        val json = res.toJson()
        assertTrue(json.getBoolean("success"))
        assertEquals("GET_SCREEN_TEXT", json.getString("command"))
        assertEquals("SUCCESS", json.getString("code"))
    }

    @Test
    fun `chatgpt accessibility success result format`() {
        val res = BridgeResult.chatGptAccessibilitySuccess("CLICK", "ChatGPT button clicked successfully")
        val json = res.toJson()
        assertTrue(json.getBoolean("success"))
        assertEquals("ACCESSIBILITY", json.getString("method"))
        assertEquals("CLICK", json.getString("command"))
        assertEquals("ChatGPT button clicked successfully", json.getString("message"))
    }

    @Test
    fun `chatgpt opencv success result format`() {
        val res = BridgeResult.chatGptOpenCvSuccess("CLICK", 0.91, 540, 1820, "ChatGPT button found and clicked using image matching")
        val json = res.toJson()
        assertTrue(json.getBoolean("success"))
        assertEquals("OPENCV", json.getString("method"))
        assertEquals("CLICK", json.getString("command"))
        assertEquals(0.91, json.getDouble("confidence"), 0.01)
        assertEquals(540, json.getInt("x"))
        assertEquals(1820, json.getInt("y"))
    }

    @Test
    fun `chatgpt click failed result format`() {
        val res = BridgeResult.chatGptClickFailed("CLICK", "Button not found using Accessibility or OpenCV")
        val json = res.toJson()
        assertFalse(json.getBoolean("success"))
        assertEquals("CLICK", json.getString("command"))
        assertEquals("Button not found using Accessibility or OpenCV", json.getString("message"))
    }
}
