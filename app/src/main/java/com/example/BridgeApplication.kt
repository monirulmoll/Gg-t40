package com.example

import android.app.Application
import com.example.command.CommandDispatcher
import com.example.server.BridgeHttpServer
import com.example.util.BridgeLogger
import com.example.util.OpenCVHelper

class BridgeApplication : Application() {

    lateinit var commandDispatcher: CommandDispatcher
        private set

    lateinit var httpServer: BridgeHttpServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        BridgeLogger.logSystem("Bridge Controller App Initialized")

        // Initialize OpenCV
        OpenCVHelper.init()

        commandDispatcher = CommandDispatcher(this)
        httpServer = BridgeHttpServer(commandDispatcher, port = 8765)
        httpServer.start()
    }

    override fun onTerminate() {
        super.onTerminate()
        httpServer.stop()
    }

    companion object {
        lateinit var instance: BridgeApplication
            private set
    }
}
