package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.BridgeCommand
import com.example.model.BridgeResult
import com.example.model.CommandAction
import org.junit.Assert.assertEquals
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
    fun `bridge result to json serialization`() {
        val res = BridgeResult.success("GET_SCREEN_TEXT", "Extracted text", mapOf("length" to 42))
        val json = res.toJson()
        assertTrue(json.getBoolean("success"))
        assertEquals("GET_SCREEN_TEXT", json.getString("command"))
        assertEquals("SUCCESS", json.getString("code"))
    }
}
