package pl.syntaxdevteam.punisher.commands

import io.papermc.paper.command.brigadier.CommandSourceStack
import pl.syntaxdevteam.punisher.PunisherX
import pl.syntaxdevteam.punisher.common.thenOnMainThread
import java.util.concurrent.CompletableFuture

fun <T> CompletableFuture<T>.deliverToCommand(
    plugin: PunisherX,
    stack: CommandSourceStack,
    actionDescription: String,
    onSuccess: (T) -> Unit
) {
    this.whenComplete { _, throwable ->
        if (throwable != null) {
            plugin.logger.err("Failed to $actionDescription: ${throwable.message}")
            plugin.taskDispatcher.runSync {
                stack.sender.sendMessage(plugin.messageHandler.getMessage("error", "db_error"))
            }
        }
    }.thenOnMainThread(plugin.taskDispatcher) { result ->
        onSuccess(result)
    }
}
