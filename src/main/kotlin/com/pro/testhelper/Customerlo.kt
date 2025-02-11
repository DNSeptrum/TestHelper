package com.pro.testhelper

import java.util.logging.FileHandler
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object CustomLogger {
    val logger: Logger = Logger.getLogger("MyPluginLogger")

    init {
        try {
            // Ścieżka do pliku logów
            val logFilePath = System.getProperty("user.home") + "/my_plugin.log"

            // Ustawienie FileHandler
            val fileHandler = FileHandler(logFilePath, true)
            fileHandler.formatter = SimpleFormatter()
            logger.addHandler(fileHandler)
            logger.useParentHandlers = false // Wyłączenie logowania do consoli
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
