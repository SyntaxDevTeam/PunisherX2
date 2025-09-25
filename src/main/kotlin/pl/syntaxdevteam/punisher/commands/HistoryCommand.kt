package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.jetbrains.annotations.NotNull
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.databases.PunishmentData
import pl.syntaxdevteam.punisher.permissions.PermissionChecker
import pl.syntaxdevteam.punisher.players.PlayerIPManager
import java.text.SimpleDateFormat
import java.util.*

class HistoryCommand(private val plugin: PunisherX, private val playerIPManager: PlayerIPManager) : BasicCommand {

    private val dateFormat = SimpleDateFormat("yy-MM-dd HH:mm:ss")

    override fun execute(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>) {

        if (args.isEmpty()) {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("history", "usage"))
            return
        }

        val player = args[0]
        if (player.equals(stack.sender.name, ignoreCase = true) || PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.HISTORY)) {

            val page = if (args.size > 1) args[1].toIntOrNull() ?: 1 else 1
            val limit = 10
            val offset = (page - 1) * limit

            val uuid = plugin.resolvePlayerUuid(player)
            val targetPlayer = when (Bukkit.getPlayer(player)?.name) {
                null -> Bukkit.getOfflinePlayer(uuid).name
                else -> Bukkit.getPlayer(player)?.name
            }
            plugin.punishmentService.getPunishmentHistory(uuid, limit, offset)
                .thenApply { punishments ->
                    val playerIP = playerIPManager.getPlayerIPByName(player)
                    plugin.logger.debug("Player IP: $playerIP")
                    val geoLocation = playerIP?.let { resolveGeoLocation(it) } ?: UNKNOWN_LOCATION
                    plugin.logger.debug("GeoLocation: $geoLocation")
                    HistoryResult(
                        uuid = uuid,
                        targetPlayer = targetPlayer,
                        punishments = punishments,
                        playerIP = playerIP,
                        geoLocation = geoLocation,
                        page = page
                    )
                }
                .deliverToCommand(plugin, stack, "fetch punishment history for $player") { result ->
                    if (result.punishments.isEmpty()) {
                        stack.sender.sendMessage(
                            plugin.messageHandler.getMessage(
                                "history",
                                "no_punishments",
                                mapOf("player" to player)
                            )
                        )
                    } else {
                        renderHistory(stack, player, result)
                    }
                }
        } else {
            stack.sender.sendMessage(plugin.messageHandler.getMessage("history", "no_permission"))
        }
    }

    override fun suggest(@NotNull stack: CommandSourceStack, @NotNull args: Array<String>): List<String> {
        if (!PermissionChecker.hasWithLegacy(stack.sender, PermissionChecker.PermissionKey.HISTORY)) {
            return emptyList()
        }
        return when (args.size) {
            1 -> plugin.server.onlinePlayers.map { it.name }
            else -> emptyList()
        }
    }

    private fun resolveGeoLocation(ip: String): String {
        val country = playerIPManager.geoIPHandler.getCountry(ip)
        val city = playerIPManager.geoIPHandler.getCity(ip)
        plugin.logger.debug("Country: $country, City: $city")
        return "$city, $country"
    }

    private fun renderHistory(stack: CommandSourceStack, player: String, result: HistoryResult) {
        val uuid = result.uuid
        val mh = plugin.messageHandler
        val id = mh.getCleanMessage("history", "id")
        val types = mh.getCleanMessage("history", "type")
        val reasons = mh.getCleanMessage("history", "reason")
        val times = mh.getCleanMessage("history", "time")
        val title = mh.getCleanMessage("history", "title")
        val fullGeoLocation = if (stack.sender.hasPermission("punisherx.view_ip")) {
            "${result.playerIP ?: UNKNOWN_LOCATION} (${result.geoLocation})"
        } else {
            result.geoLocation
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

        result.punishments.forEach { punishment ->
            val formattedDate = dateFormat.format(Date(punishment.start))
            val punishmentMessage =
                mh.miniMessageFormat("<blue>|   <white>#${punishment.id}</white> <blue>|</blue> <white>${punishment.type}</white> <blue>|</blue> <white>${punishment.reason}</white> <blue>|</blue> <white>$formattedDate</blue>")
            stack.sender.sendMessage(punishmentMessage)
        }
        stack.sender.sendMessage(hr)

        val nextPage = result.page + 1
        val prevPage = if (result.page > 1) result.page - 1 else 1
        val navigation =
            mh.miniMessageFormat("<blue>| <click:run_command:'/history $player $prevPage'>[Previous]</click>   <click:run_command:'/history $player $nextPage'>[Next]</click> </blue>")
        stack.sender.sendMessage(navigation)
    }

    private data class HistoryResult(
        val uuid: UUID,
        val targetPlayer: String?,
        val punishments: List<PunishmentData>,
        val playerIP: String?,
        val geoLocation: String,
        val page: Int
    )

    companion object {
        private const val UNKNOWN_LOCATION = "Unknown location"
    }
}
