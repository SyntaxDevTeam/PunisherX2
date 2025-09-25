package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.permissions.PermissionChecker

class ClearAllCommand(private val plugin: PunisherX) : BasicCommand {

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {
        if (PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.CLEAR_ALL)) {
            if (args.isNotEmpty()) {
                val player = args[0]
                val uuid = plugin.resolvePlayerUuid(player).toString()
                plugin.executeDatabaseAsync(
                    stack,
                    "clear punishments for $player",
                    {
                        val punishments = plugin.databaseHandler.getPunishments(uuid)
                        val clearedTypes = punishments
                            .filter { it.type == "MUTE" || it.type == "BAN" || it.type == "WARN" }
                            .map { it.type }
                        if (clearedTypes.isNotEmpty()) {
                            clearedTypes.forEach { type ->
                                plugin.databaseHandler.removePunishment(uuid, type, true)
                            }
                            plugin.punishmentService.invalidate(java.util.UUID.fromString(uuid))
                        }
                        ClearAllResult(player, uuid, clearedTypes.toSet())
                    }
                ) { result ->
                    if (result.clearedTypes.isEmpty()) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "error",
                                "player_not_found",
                                mapOf("player" to result.player)
                            )
                        )
                        return@executeDatabaseAsync
                    }

                    stack.sender.sendMessage(
                        plugin.messageHandler.getMessage(
                            "clear",
                            "clearall",
                            mapOf("player" to result.player)
                        )
                    )
                    Bukkit.getPlayer(result.player)?.sendMessage(
                        plugin.messageHandler.getMessage("clear", "clear_message")
                    )
                    plugin.logger.success("Player ${result.player} (${result.uuid}) has been cleared of all punishments")
                }
            } else {
                stack.sender.sendMessage(plugin.messageHandler.getMessage("clear", "usage"))
            }
        } else {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "no_permission"))
        }
    }

    private data class ClearAllResult(val player: String, val uuid: String, val clearedTypes: Set<String>)

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        if (!PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.CLEAR_ALL)) {
            return emptyList()
        }
        return when (args.size) {
            1 -> plugin.server.onlinePlayers.map { it.name }
            else -> emptyList()
        }
    }
}
