package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.permissions.PermissionChecker
import java.util.UUID

class UnBanCommand(private val plugin: PunisherX) : BasicCommand {

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {
        if (!PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNBAN)) {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "no_permission"))
            return
        }

        if (args.isEmpty()) {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("unban", "usage"))
            return
        }

        val identifier = args[0]

        if (identifier.matches(IP_REGEX)) {
            handleIpUnban(stack, identifier)
            return
        }

        val uuid = plugin.resolvePlayerUuid(identifier).toString()
        plugin.logger.debug("UUID for player $identifier: [$uuid]")

        handlePlayerUnban(stack, identifier, uuid)
    }

    private fun handlePlayerUnban(stack: CommandSourceStack, playerName: String, uuid: String) {
        plugin.executeDatabaseAsync(
            stack,
            "unban player $playerName",
            {
                val punishments = plugin.databaseHandler.getPunishments(uuid)
                val directBans = punishments.filter { it.type == "BAN" }
                if (directBans.isNotEmpty()) {
                    directBans.forEach { plugin.databaseHandler.removePunishment(uuid, it.type, removeAll = false) }
                    plugin.punishmentService.invalidate(UUID.fromString(uuid))
                    PlayerUnbanResult.DirectSuccess(playerName, uuid)
                } else {
                    if (punishments.isEmpty()) {
                        plugin.logger.debug("Player $playerName ($uuid) has no ban")
                    }
                    val fallbackIps = plugin.playerIPManager.getPlayerIPsByName(playerName)
                    PlayerUnbanResult.NeedsIpFallback(
                        playerName = playerName,
                        uuid = uuid,
                        notifyPlayerNotPunished = punishments.isEmpty(),
                        fallbackIps = fallbackIps
                    )
                }
            }
        ) { result ->
            when (result) {
                is PlayerUnbanResult.DirectSuccess -> {
                    plugin.commandLoggerPlugin.logCommand(stack.sender.name, "UNBAN", result.playerName, "")
                    plugin.logger.info("Player ${result.playerName} (${result.uuid}) has been unbanned")
                    plugin.messageHandler.getSmartMessage(
                        "unban",
                        "unban",
                        mapOf("player" to result.playerName)
                    ).forEach { stack.sender.sendMessage(it) }
                    broadcastUnban(result.playerName)
                }

                is PlayerUnbanResult.NeedsIpFallback -> {
                    if (result.notifyPlayerNotPunished) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "error",
                                "player_not_punished",
                                mapOf("player" to result.playerName)
                            )
                        )
                    }
                    if (result.fallbackIps.isEmpty()) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "error",
                                "player_not_found",
                                mapOf("player" to result.playerName)
                            )
                        )
                        return@executeDatabaseAsync
                    }
                    plugin.logger.debug("Assigned IPs for player ${result.playerName}: ${result.fallbackIps}")
                    handleIpFallback(stack, result.playerName, result.uuid, result.fallbackIps)
                }
            }
        }
    }

    private fun handleIpFallback(
        stack: CommandSourceStack,
        playerName: String,
        uuid: String,
        ips: List<String>
    ) {
        plugin.executeDatabaseAsync(
            stack,
            "unban IPs for $playerName",
            {
                val unbannedIps = mutableListOf<String>()
                ips.forEach { ip ->
                    val punishments = plugin.databaseHandler.getPunishmentsByIP(ip)
                    val ipBans = punishments.filter { it.type == "BANIP" }
                    if (ipBans.isNotEmpty()) {
                        ipBans.forEach { plugin.databaseHandler.removePunishment(ip, it.type) }
                        unbannedIps += ip
                    }
                }
                IpBatchResult(playerName, uuid, unbannedIps)
            }
        ) { result ->
            if (result.unbannedIps.isEmpty()) {
                stack.sender.sendMessage(
                    plugin.messageHandler.getMessage(
                        "error",
                        "player_not_found",
                        mapOf("player" to result.playerName)
                    )
                )
                return@executeDatabaseAsync
            }

            result.unbannedIps.forEach { ip ->
                plugin.commandLoggerPlugin.logCommand(stack.sender.name, "UNBAN (IP)", ip, "")
                plugin.logger.info("IP $ip has been unbanned")
                plugin.messageHandler.getSmartMessage(
                    "unban",
                    "unban",
                    mapOf("player" to ip)
                ).forEach { stack.sender.sendMessage(it) }
                broadcastUnban(ip)
            }
        }
    }

    private fun handleIpUnban(stack: CommandSourceStack, ip: String) {
        plugin.executeDatabaseAsync(
            stack,
            "unban IP $ip",
            {
                val punishments = plugin.databaseHandler.getPunishmentsByIP(ip)
                val ipBans = punishments.filter { it.type == "BANIP" }
                if (ipBans.isNotEmpty()) {
                    ipBans.forEach { plugin.databaseHandler.removePunishment(ip, it.type) }
                }
                IpUnbanResult(ip, ipBans.isNotEmpty())
            }
        ) { result ->
            if (!result.unbanned) {
                plugin.logger.debug("No punishments found for IP ${result.ip}")
                stack.sender.sendMessage(
                    plugin.messageHandler.getMessage(
                        "error",
                        "ip_not_found",
                        mapOf("ip" to result.ip)
                    )
                )
                return@executeDatabaseAsync
            }

            plugin.commandLoggerPlugin.logCommand(stack.sender.name, "UNBAN (IP)", result.ip, "")
            plugin.logger.info("IP ${result.ip} has been unbanned")
            plugin.messageHandler.getSmartMessage(
                "unban",
                "unban",
                mapOf("player" to result.ip)
            ).forEach { stack.sender.sendMessage(it) }
            broadcastUnban(result.ip)
        }
    }

    private fun broadcastUnban(playerOrIp: String) {
        val messages = plugin.messageHandler.getSmartMessage("unban", "unban", mapOf("player" to playerOrIp))

        plugin.server.onlinePlayers
            .filter { PermissionChecker.hasWithSee(it, PermissionChecker.PermissionKey.SEE_UNBAN) }
            .forEach { player -> messages.forEach { player.sendMessage(it) } }
    }

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        return if (PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNBAN) && args.size == 1) {
            plugin.server.onlinePlayers.map { it.name }
        } else {
            emptyList()
        }
    }

    private data class IpUnbanResult(val ip: String, val unbanned: Boolean)

    private sealed interface PlayerUnbanResult {
        data class DirectSuccess(val playerName: String, val uuid: String) : PlayerUnbanResult
        data class NeedsIpFallback(
            val playerName: String,
            val uuid: String,
            val notifyPlayerNotPunished: Boolean,
            val fallbackIps: List<String>
        ) : PlayerUnbanResult
    }

    private data class IpBatchResult(
        val playerName: String,
        val uuid: String,
        val unbannedIps: List<String>
    )

    companion object {
        private val IP_REGEX = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
    }
}
