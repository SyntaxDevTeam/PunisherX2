package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.permissions.PermissionChecker

class UnWarnCommand(private val plugin: PunisherX) : BasicCommand {

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {
        if (PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNWARN)) {
            if (args.isNotEmpty()) {
                val player = args[0]
                val uuid = plugin.resolvePlayerUuid(player).toString()
                plugin.executeDatabaseAsync(
                    stack,
                    "unwarn $player",
                    {
                        val punishments = plugin.databaseHandler.getPunishments(uuid)
                        val hasWarns = punishments.any { it.type == "WARN" }
                        if (hasWarns) {
                            plugin.databaseHandler.removePunishment(uuid, "WARN")
                            plugin.punishmentService.invalidate(java.util.UUID.fromString(uuid))
                        }
                        UnwarnResult(player, uuid, hasWarns)
                    }
                ) { result ->
                    if (result.removed) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "unwarn",
                                "unwarn",
                                mapOf("player" to result.player)
                            )
                        )
                        plugin.logger.info("Player ${result.player} (${result.uuid}) has been unwarned")
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
                stack.sender.sendMessage(plugin.messageHandler.getMessage("unwarn", "usage"))
            }
        } else {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "no_permission"))
        }
    }

    private data class UnwarnResult(val player: String, val uuid: String, val removed: Boolean)

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        if (!PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.UNWARN)) {
            return emptyList()
        }
        return when (args.size) {
            1 -> plugin.server.onlinePlayers.map { it.name }
            else -> emptyList()
        }
    }
}
