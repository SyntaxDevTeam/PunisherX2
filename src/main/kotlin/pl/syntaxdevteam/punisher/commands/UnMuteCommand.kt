package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.permissions.PermissionChecker

class UnMuteCommand(private val plugin: PunisherX) : BasicCommand {

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {
        if (PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNMUTE)) {
            if (args.isNotEmpty()) {
                val player = args[0]
                val uuid = plugin.resolvePlayerUuid(player).toString()
                plugin.executeDatabaseAsync(
                    stack,
                    "unmute $player",
                    {
                        val punishments = plugin.databaseHandler.getPunishments(uuid)
                        val mutePunishments = punishments.filter { it.type == "MUTE" }
                        if (mutePunishments.isNotEmpty()) {
                            mutePunishments.forEach { punishment ->
                                plugin.databaseHandler.removePunishment(uuid, punishment.type)
                            }
                            plugin.punishmentService.invalidate(java.util.UUID.fromString(uuid))
                        }
                        UnmuteResult(player, uuid, mutePunishments.isNotEmpty())
                    }
                ) { result ->
                    if (result.removed) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "unmute",
                                "unmute",
                                mapOf("player" to result.player)
                            )
                        )
                        Bukkit.getPlayer(result.player)?.sendMessage(
                            plugin.messageHandler.getMessage("unmute", "unmute_message")
                        )
                        plugin.logger.info("Player ${result.player} (${result.uuid}) has been unmuted")
                    } else {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "error",
                                "player_not_found",
                                mapOf("player" to result.player)
                            )
                        )
                    }
                }
            } else {
                stack.sender.sendMessage(plugin.messageHandler.getMessage("unmute", "usage"))
            }
        } else {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "no_permission"))
        }
    }

    private data class UnmuteResult(val player: String, val uuid: String, val removed: Boolean)

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        if (!PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNMUTE)) {
            return emptyList()
        }
        return when (args.size) {
            1 -> plugin.server.onlinePlayers.map { it.name }
            else -> emptyList()
        }
    }
}
