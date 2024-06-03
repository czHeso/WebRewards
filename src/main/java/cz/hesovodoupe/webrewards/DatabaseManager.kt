package cz.hesovodoupe.webrewards

import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class DatabaseManager(private val plugin: JavaPlugin) {
    var connection: Connection? = null
        private set

    fun connect() {
        val host = plugin.config.getString("database.host")
        val port = plugin.config.getInt("database.port")
        val database = plugin.config.getString("database.name")
        val username = plugin.config.getString("database.user")
        val password = plugin.config.getString("database.password")

        plugin.logger.info("Connecting to database at $host:$port with user $username")

        try {
            if (connection != null && !connection!!.isClosed) {
                return
            }

            connection = DriverManager.getConnection(
                "jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC", username, password
            )
            plugin.logger.info("Connected to the database successfully!")
        } catch (e: SQLException) {
            e.printStackTrace()
            plugin.logger.severe("Failed to connect to the database: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            if (connection != null && !connection!!.isClosed) {
                connection!!.close()
                plugin.logger.info("Disconnected from the database.")
            }
        } catch (e: SQLException) {
            e.printStackTrace()
            plugin.logger.severe("Failed to disconnect from the database: ${e.message}")
        }
    }
}
