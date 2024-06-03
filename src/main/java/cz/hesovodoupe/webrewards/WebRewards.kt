package cz.hesovodoupe.webrewards

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player
import java.sql.SQLException
import java.util.UUID
import java.sql.Timestamp
import java.time.Instant

class WebRewards : JavaPlugin() {
    private var databaseManager: DatabaseManager? = null

    override fun onEnable() {
        // Načte konfiguraci
        saveDefaultConfig()
        reloadConfig()
        config.options().copyDefaults(true)
        saveConfig()

        // Načítání a logování konfiguračních hodnot pro ladění
        val dbHost = config.getString("database.host")
        val dbPort = config.getInt("database.port")
        val dbName = config.getString("database.name")
        val dbUser = config.getString("database.user")
        val dbPassword = config.getString("database.password")

        val rewardUrlBase = config.getString("reward.url")

        logger.info("Database Host: $dbHost")
        logger.info("Database Port: $dbPort")
        logger.info("Database Name: $dbName")
        logger.info("Database User: $dbUser")
        logger.info("Reward URL Base: $rewardUrlBase")

        // Inicializace a připojení k databázi
        databaseManager = DatabaseManager(this)
        databaseManager!!.connect()

        // Vytvoření tabulek v databázi
        createTablesIfNotExists()

        logger.info("Please read the manual thoroughly for proper setup.")
        logger.info("All support is TBA only")
    }

    override fun onDisable() {
        // Zajistíme synchronizaci, aby všechny operace byly řádně dokončeny před odpojením
        synchronized(this) {
            // Odpojení od databáze
            if (databaseManager != null) {
                databaseManager!!.disconnect()
            }
        }

        logger.info("The plugin is shutting down")
    }

    // Metoda pro vytvoření tabulek, pokud neexistují
    private fun createTablesIfNotExists() {
        if (databaseManager != null && databaseManager!!.connection != null) {
            try {
                val connection = databaseManager!!.connection!!
                val statement1 = connection.createStatement()
                val sql1 = """
                    CREATE TABLE IF NOT EXISTS rewards_web (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        last_used TIMESTAMP NOT NULL,
                        reward_id VARCHAR(36) NOT NULL,
                        reward1_img VARCHAR(255),
                        reward1_command VARCHAR(255),
                        reward1_label VARCHAR(255),
                        reward2_img VARCHAR(255),
                        reward2_command VARCHAR(255),
                        reward2_label VARCHAR(255),
                        reward3_img VARCHAR(255),
                        reward3_command VARCHAR(255),
                        reward3_label VARCHAR(255)
                    );
                """
                statement1.executeUpdate(sql1)
                statement1.close()

                val statement2 = connection.createStatement()
                val sql2 = """
                    CREATE TABLE IF NOT EXISTS rewards_log (
                        player_uuid VARCHAR(36) NOT NULL,
                        reward_id VARCHAR(36) NOT NULL,
                        reward_img VARCHAR(255),
                        reward_command VARCHAR(255),
                        reward_label VARCHAR(255),
                        claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                """
                statement2.executeUpdate(sql2)
                statement2.close()

                val statement3 = connection.createStatement()
                val sql3 = """
                    CREATE TABLE IF NOT EXISTS rewards_usage (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        last_used TIMESTAMP NOT NULL
                    );
                """
                statement3.executeUpdate(sql3)
                statement3.close()

            } catch (e: SQLException) {
                e.printStackTrace()
                logger.severe("An error occurred while creating the tables: ${e.message}")
            }
        }
    }

    // Implementace příkazu /rewards a /rewards reset
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (command.name.equals("rewards", ignoreCase = true)) {
            if (args.isEmpty()) {
                if (sender is Player) {
                    val player = sender
                    val playerUUID = player.uniqueId.toString()

                    if (canUseRewards(playerUUID)) {
                        val rewardId = UUID.randomUUID().toString()
                        val rewards = getRandomRewards(3)
                        if (rewards.size == 3) {
                            saveReward(playerUUID, rewardId, rewards)
                            val rewardUrlBase = config.getString("reward.url")
                            val rewardUrl = "$rewardUrlBase?reward_id=$rewardId"
                            val message = config.getString("messages.reward_received")?.replace("%url%", rewardUrl)
                            player.sendMessage("${ChatColor.YELLOW}$message ${ChatColor.WHITE}$rewardUrl")
                        } else {
                            val message = config.getString("messages.not_enough_rewards")
                            player.sendMessage("${ChatColor.YELLOW}$message")
                        }
                    } else {
                        val message = config.getString("messages.already_claimed")
                        player.sendMessage("${ChatColor.YELLOW}$message")
                    }
                }
            } else if (args[0].equals("reset", ignoreCase = true) && args.size == 2) {
                if (sender.isOp) {
                    val playerName = args[1]
                    val playerUUID = Bukkit.getOfflinePlayer(playerName).uniqueId.toString()
                    resetRewards(playerUUID)
                    val message = config.getString("messages.reset_success")?.replace("%player%", playerName)
                    sender.sendMessage("${ChatColor.YELLOW}$message")
                } else {
                    val message = config.getString("messages.no_permission")
                    sender.sendMessage("${ChatColor.YELLOW}$message")
                }
            }
            return true
        }
        return false
    }

    // Metoda pro kontrolu, zda hráč může použít příkaz /rewards
    private fun canUseRewards(playerUUID: String): Boolean {
        if (databaseManager != null && databaseManager!!.connection != null) {
            try {
                val statement = databaseManager!!.connection!!.prepareStatement(
                    "SELECT last_used FROM rewards_usage WHERE player_uuid = ?"
                )
                statement.setString(1, playerUUID)
                val resultSet = statement.executeQuery()

                if (resultSet.next()) {
                    val lastUsed = resultSet.getTimestamp("last_used").toInstant()
                    val now = Instant.now()
                    if (now.isBefore(lastUsed.plusSeconds(7200))) { // 7200 sekund = 2 hodiny
                        resultSet.close()
                        statement.close()
                        return false
                    }
                }

                resultSet.close()
                statement.close()
            } catch (e: SQLException) {
                e.printStackTrace()
                logger.severe("An error occurred while checking rewards usage: ${e.message}")
            }
        }
        return true
    }

    // Metoda pro výběr náhodných odměn
    private fun getRandomRewards(count: Int): List<Reward> {
        val rewards = config.getConfigurationSection("rewards")?.getKeys(false)?.map { key ->
            val section = config.getConfigurationSection("rewards.$key")!!
            Reward(
                img = section.getString("img")!!,
                command = section.getString("command")!!,
                labelName = section.getString("labelName")!!
            )
        } ?: emptyList()
        return rewards.shuffled().take(count)
    }

    // Metoda pro uložení odměn do databáze
    private fun saveReward(playerUUID: String, rewardId: String, rewards: List<Reward>) {
        if (databaseManager != null && databaseManager!!.connection != null) {
            try {
                val connection = databaseManager!!.connection!!
                connection.autoCommit = false

                val statementInsert = connection.prepareStatement(
                    "REPLACE INTO rewards_web (player_uuid, last_used, reward_id, reward1_img, reward1_command, reward1_label, reward2_img, reward2_command, reward2_label, reward3_img, reward3_command, reward3_label) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
                statementInsert.setString(1, playerUUID)
                statementInsert.setTimestamp(2, Timestamp.from(Instant.now()))
                statementInsert.setString(3, rewardId)
                statementInsert.setString(4, rewards[0].img)
                statementInsert.setString(5, rewards[0].command)
                statementInsert.setString(6, rewards[0].labelName)
                statementInsert.setString(7, rewards[1].img)
                statementInsert.setString(8, rewards[1].command)
                statementInsert.setString(9, rewards[1].labelName)
                statementInsert.setString(10, rewards[2].img)
                statementInsert.setString(11, rewards[2].command)
                statementInsert.setString(12, rewards[2].labelName)
                statementInsert.executeUpdate()
                statementInsert.close()

                val statementUsage = connection.prepareStatement(
                    "REPLACE INTO rewards_usage (player_uuid, last_used) VALUES (?, ?)"
                )
                statementUsage.setString(1, playerUUID)
                statementUsage.setTimestamp(2, Timestamp.from(Instant.now()))
                statementUsage.executeUpdate()
                statementUsage.close()

                connection.commit()
                connection.autoCommit = true

            } catch (e: SQLException) {
                e.printStackTrace()
                logger.severe("An error occurred while saving the reward: ${e.message}")
                if (databaseManager!!.connection != null) {
                    try {
                        databaseManager!!.connection!!.rollback()
                    } catch (rollbackEx: SQLException) {
                        rollbackEx.printStackTrace()
                        logger.severe("Failed to rollback transaction: ${rollbackEx.message}")
                    }
                }
            }
        }
    }

    // Metoda pro udělení odměn a přesun do rewards_log
    private fun claimReward(playerUUID: String, rewardId: String, reward: Reward) {
        if (databaseManager != null && databaseManager!!.connection != null) {
            try {
                val connection = databaseManager!!.connection!!
                connection.autoCommit = false

                // Přidání odměny do rewards_log
                val statementLog = connection.prepareStatement(
                    "INSERT INTO rewards_log (player_uuid, reward_id, reward_img, reward_command, reward_label) VALUES (?, ?, ?, ?, ?)"
                )
                statementLog.setString(1, playerUUID)
                statementLog.setString(2, rewardId)
                statementLog.setString(3, reward.img)
                statementLog.setString(4, reward.command)
                statementLog.setString(5, reward.labelName)
                statementLog.executeUpdate()
                statementLog.close()

                // Smazání záznamu z rewards_web
                val statementDelete = connection.prepareStatement(
                    "DELETE FROM rewards_web WHERE player_uuid = ?"
                )
                statementDelete.setString(1, playerUUID)
                statementDelete.executeUpdate()
                statementDelete.close()

                connection.commit()
                connection.autoCommit = true

            } catch (e: SQLException) {
                e.printStackTrace()
                logger.severe("An error occurred while claiming the reward: ${e.message}")
                if (databaseManager!!.connection != null) {
                    try {
                        databaseManager!!.connection!!.rollback()
                    } catch (rollbackEx: SQLException) {
                        rollbackEx.printStackTrace()
                        logger.severe("Failed to rollback transaction: ${rollbackEx.message}")
                    }
                }
            }
        }
    }

    // Metoda pro resetování odměn hráče
    private fun resetRewards(playerUUID: String) {
        if (databaseManager != null && databaseManager!!.connection != null) {
            try {
                val statement = databaseManager!!.connection!!.prepareStatement(
                    "DELETE FROM rewards_usage WHERE player_uuid = ?"
                )
                statement.setString(1, playerUUID)
                statement.executeUpdate()
                statement.close()
            } catch (e: SQLException) {
                e.printStackTrace()
                logger.severe("An error occurred while resetting the rewards: ${e.message}")
            }
        }
    }

    data class Reward(val img: String, val command: String, val labelName: String)
}
