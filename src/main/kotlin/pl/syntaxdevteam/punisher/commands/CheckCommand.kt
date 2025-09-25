package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.databases.PunishmentData
import pl.syntaxdevteam.punisher.permissions.PermissionChecker
import pl.syntaxdevteam.punisher.players.PlayerIPManager
import java.util.UUID

class CheckCommand(private val plugin: PunisherX, private val playerIPManager: PlayerIPManager) : BasicCommand {

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {

        if (args.isEmpty()) {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("check", "usage"))
            return
        }
        val player = args[0]

        if (player.equals(stack.sender.name, ignoreCase = true) || PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.CHECK)) {
            if (args.size < 2) {
                stack.sender.sendMessage(plugin.messageHandler.getMessage("check", "usage"))
                return
            }

            val type = args[1].lowercase()
            val uuid = plugin.resolvePlayerUuid(player)
            val targetPlayer = when (Bukkit.getPlayer(player)?.name) {
                null -> Bukkit.getOfflinePlayer(uuid).name
                else -> Bukkit.getPlayer(player)?.name
            }

            val filter = createFilter(type) ?: run {
                stack.sender.sendMessage(plugin.messageHandler.getMessage("check", "invalid_type"))
                return
            }

            plugin.punishmentService.getActivePunishments(uuid)
                .thenApply { punishments ->
                    val filtered = punishments.filter(filter)
                    val playerIP = playerIPManager.getPlayerIPByName(player)
                    plugin.logger.debug("Player IP: $playerIP")
                    val geoLocation = playerIP?.let { resolveGeoLocation(it) } ?: UNKNOWN_LOCATION
                    plugin.logger.debug("GeoLocation: $geoLocation")
                    CheckResult(uuid, targetPlayer, filtered, playerIP, geoLocation)
                }
                .deliverToCommand(plugin, stack, "fetch punishments for $player") { result ->
                    if (result.filteredPunishments.isEmpty()) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage("check", "no_punishments", mapOf("player" to player))
                        )
                    } else {
                        renderPunishments(stack, player, result)
                    }
                }
        } else {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "no_permission"))
        }
    }

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        return when (args.size) {
            1 -> plugin.server.onlinePlayers.map { it.name }
            2 -> listOf("all", "warn", "mute", "jail", "ban")
            else -> emptyList()
        }
    }

    private fun resolveGeoLocation(ip: String): String {
        val country = playerIPManager.geoIPHandler.getCountry(ip)
        val city = playerIPManager.geoIPHandler.getCity(ip)
        plugin.logger.debug("Country: $country, City: $city")
        return "$city, $country"
    }

    private fun createFilter(type: String): ((PunishmentData) -> Boolean)? {
        return when (type) {
            "all" -> { _ -> true }
            "ban" -> { punishment -> punishment.type == "BAN" || punishment.type == "BANIP" }
            "jail" -> { punishment -> punishment.type == "JAIL" }
            "mute" -> { punishment -> punishment.type == "MUTE" }
            "warn" -> { punishment -> punishment.type == "WARN" }
            else -> null
        }
    }

    private fun renderPunishments(stack: CommandSourceStack, player: String, result: CheckResult) {
        val uuid = result.uuid
        val filteredPunishments = result.filteredPunishments
        val mh = plugin.messageHandler
        val id = mh.getCleanMessage("check", "id")
        val types = mh.getCleanMessage("check", "type")
        val reasons = mh.getCleanMessage("check", "reason")
        val times = mh.getCleanMessage("check", "time")
        val title = mh.getCleanMessage("check", "title")
        val geoLocation = result.geoLocation
        val fullGeoLocation = if (PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.VIEW_IP)) {
            "${result.playerIP ?: UNKNOWN_LOCATION} ($geoLocation)"
        } else {
            geoLocation
        }
        val gamer = if (stack.sender.name == "CONSOLE") {
            "<gold>${result.targetPlayer ?: player} <gray>[$uuid, $fullGeoLocation]</gray>:</gold>"
        } else {
            "<gold><hover:show_text:'[<white>$uuid, $fullGeoLocation</white>]'>${result.targetPlayer ?: player}:</gold>"
        }
        val topHeader = mh.miniMessageFormat("<blue>--------------------------------------------------</blue>")
        val header = mh.miniMessageFormat("<blue>|    $title $gamer</blue>")
        val tableHeader = mh.miniMessageFormat("<blue>|   $id  |  $types  |  $reasons  |  $times</blue>")
        val br = mh.miniMessageFormat("<blue> </blue>")
        val hr = mh.miniMessageFormat("<blue>|</blue>")
        stack.sender.sendMessage(br)
        stack.sender.sendMessage(header)
        stack.sender.sendMessage(topHeader)
        stack.sender.sendMessage(tableHeader)
        stack.sender.sendMessage(hr)

        filteredPunishments.forEach { punishment ->
            val duration = if (punishment.end == -1L) {
                "permanent"
            } else {
                val remainingTime = (punishment.end - System.currentTimeMillis()) / 1000
                plugin.timeHandler.formatTime(remainingTime.toString())
            }
            val row = mh.miniMessageFormat(
                "<blue>|   <white>#${punishment.id}</white> <blue>|</blue> <white>${punishment.type}</white> " +
                        "<blue>|</blue> <white>${punishment.reason}</white> <blue>|</blue> <white>$duration</white>"
            )
            stack.sender.sendMessage(row)
        }
    }

    private data class CheckResult(
        val uuid: UUID,
        val targetPlayer: String?,
        val filteredPunishments: List<PunishmentData>,
        val playerIP: String?,
        val geoLocation: String
    )

    companion object {
        private const val UNKNOWN_LOCATION = "Unknown location"
    }
}
