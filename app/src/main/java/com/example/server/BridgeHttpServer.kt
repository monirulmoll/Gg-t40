package com.example.server

import com.example.command.CommandDispatcher
import com.example.model.BridgeCommand
import com.example.model.BridgeResult
import com.example.model.CommandAction
import com.example.util.BridgeLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class BridgeHttpServer(
    private val commandDispatcher: CommandDispatcher,
    private val port: Int = 8765
) {
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    fun start() {
        if (_isRunning.value) return

        serverScope.launch {
            try {
                // Strictly bind only to localhost / loopback address 127.0.0.1
                val localhost = InetAddress.getByName("127.0.0.1")
                serverSocket = ServerSocket(port, 50, localhost)
                _isRunning.value = true
                BridgeLogger.logSystem("Localhost Server started on 127.0.0.1:$port")

                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleConnection(client)
                        }
                    } catch (e: Exception) {
                        if (serverSocket?.isClosed == true) break
                    }
                }
            } catch (e: Exception) {
                BridgeLogger.logError("Failed to start server on port $port: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        _isRunning.value = false
        BridgeLogger.logSystem("Localhost Server stopped")
    }

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            // Strictly enforce loopback check
            if (!socket.inetAddress.isLoopbackAddress) {
                BridgeLogger.logError("Blocked non-local connection attempt from ${socket.inetAddress}")
                socket.close()
                return@withContext
            }

            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = socket.getOutputStream()

            val firstLine = reader.readLine() ?: return@withContext

            if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ") || firstLine.startsWith("OPTIONS ")) {
                handleHttpRequest(firstLine, reader, output)
            } else if (firstLine.trim().startsWith("{")) {
                // Raw JSON Socket protocol support (e.g. netcat or raw TCP)
                handleRawJsonSocket(firstLine, output)
            } else {
                sendHttpError(output, 400, "Bad Request: Expected HTTP or JSON payload")
            }
        } catch (e: Exception) {
            BridgeLogger.logError("Error handling client connection: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun handleHttpRequest(requestLine: String, reader: BufferedReader, output: OutputStream) {
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"

        // Read HTTP headers
        var contentLength = 0
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrBlank()) break
            val headerLower = line!!.lowercase()
            if (headerLower.startsWith("content-length:")) {
                contentLength = headerLower.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }

        // Handle CORS preflight
        if (method == "OPTIONS") {
            sendHttpResponse(output, 200, "OK", "text/plain")
            return
        }

        when {
            path == "/" || path == "/api/help" -> {
                val helpJson = JSONObject().apply {
                    put("service", "Bridge Controller Local Server")
                    put("version", "1.0.0")
                    put("status", "running")
                    put("endpoints", org.json.JSONArray(listOf(
                        "POST /api/command - Execute JSON command",
                        "GET /api/status - Retrieve service status",
                        "GET /api/screen_text - Read visible screen text",
                        "GET /api/latest_response - Read latest assistant message"
                    )))
                    put("examples", org.json.JSONArray(listOf(
                        JSONObject(mapOf("action" to "OPEN_APP", "package" to "com.termux")),
                        JSONObject(mapOf("action" to "OPEN_APP", "package" to "com.openai.chatgpt")),
                        JSONObject(mapOf("action" to "TYPE", "text" to "ls -la")),
                        JSONObject(mapOf("action" to "ENTER")),
                        JSONObject(mapOf("action" to "GET_LATEST_RESPONSE")),
                        JSONObject(mapOf("action" to "WAIT_FOR_STABLE_TEXT", "timeoutMs" to 15000, "stabilityMs" to 1800))
                    )))
                }
                sendHttpResponse(output, 200, helpJson.toString(2), "application/json")
            }

            path == "/api/status" -> {
                val result = commandDispatcher.execute(BridgeCommand(action = CommandAction.STATUS))
                sendHttpResponse(output, 200, result.toJson().toString(2), "application/json")
            }

            path == "/api/screen_text" -> {
                val result = commandDispatcher.execute(BridgeCommand(action = CommandAction.GET_SCREEN_TEXT))
                sendHttpResponse(output, 200, result.toJson().toString(2), "application/json")
            }

            path == "/api/latest_response" -> {
                val result = commandDispatcher.execute(BridgeCommand(action = CommandAction.GET_LATEST_RESPONSE))
                sendHttpResponse(output, 200, result.toJson().toString(2), "application/json")
            }

            path == "/api/command" || path == "/command" -> {
                if (method != "POST") {
                    sendHttpError(output, 405, "Method Not Allowed. Use POST.")
                    return
                }

                val body = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(body, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                val bodyStr = String(body, 0, readTotal)

                if (bodyStr.isBlank()) {
                    val errorRes = BridgeResult.failed("UNKNOWN", "Request body is empty").toJson().toString(2)
                    sendHttpResponse(output, 400, errorRes, "application/json")
                    return
                }

                try {
                    val command = BridgeCommand.fromJson(bodyStr)
                    val result = commandDispatcher.execute(command)
                    sendHttpResponse(output, 200, result.toJson().toString(2), "application/json")
                } catch (e: Exception) {
                    val errorRes = BridgeResult.failed("PARSE_ERROR", "Invalid command format: ${e.message}").toJson().toString(2)
                    sendHttpResponse(output, 400, errorRes, "application/json")
                }
            }

            else -> {
                sendHttpError(output, 404, "Endpoint not found: $path")
            }
        }
    }

    private suspend fun handleRawJsonSocket(firstLine: String, output: OutputStream) {
        try {
            val command = BridgeCommand.fromJson(firstLine)
            val result = commandDispatcher.execute(command)
            val responseBytes = (result.toJson().toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            output.write(responseBytes)
            output.flush()
        } catch (e: Exception) {
            val err = BridgeResult.failed("PARSE_ERROR", e.message ?: "Failed to parse JSON").toJson().toString() + "\n"
            output.write(err.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, body: String, contentType: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Server Response"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun sendHttpError(output: OutputStream, statusCode: Int, message: String) {
        val json = JSONObject().apply {
            put("success", false)
            put("error", message)
            put("status", statusCode)
        }
        sendHttpResponse(output, statusCode, json.toString(2), "application/json")
    }
}
