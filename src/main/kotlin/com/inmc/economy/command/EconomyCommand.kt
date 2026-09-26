package com.inmc.economy.command

import com.inmc.economy.Eco
import com.inmc.economy.EconomyPlugin
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.Transfers
import com.inmc.economy.gui.CurrencyListMenu
import com.inmc.economy.gui.RankMenu
import com.inmc.economy.gui.WalletMenu
import com.inmc.economy.util.Ph
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/돈` 한 트리. 화폐 인자는 언제나 맨 뒤이고 생략하면 기본 화폐다.
 *
 * 화폐 id 는 영문이다 — Brigadier 의 `word()` 가 한글 첫 글자에서 멈춘다. 보이는 이름은 한글이다.
 */
class EconomyCommand(private val eco: Eco, private val plugin: EconomyPlugin) {

    fun register(owner: JavaPlugin) {
        owner.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 화폐", listOf("money", "eco", "화폐", "잔고"))
        }
    }

    private val players = SuggestionProvider<CommandSourceStack> { _, builder ->
        Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    private val currencies = SuggestionProvider<CommandSourceStack> { _, builder ->
        eco.currencies.all().map { it.id }.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    private val bankCurrencies = SuggestionProvider<CommandSourceStack> { _, builder ->
        eco.currencies.all().filter { it.bank }.map { it.id }.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
        builder.buildFuture()
    }

    private fun sender(ctx: CommandContext<CommandSourceStack>): CommandSender = ctx.source.sender

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { eco.messages.send(sender(ctx), "player-only") }

    private fun currencyArg(ctx: CommandContext<CommandSourceStack>): CurrencyDef? {
        val raw = runCatching { StringArgumentType.getString(ctx, "화폐") }.getOrNull()
        return eco.currency(raw) ?: null.also { eco.messages.send(sender(ctx), "unknown-currency", Ph.of().currency(raw ?: "기본")) }
    }

    /** `<금액> [화폐]` 를 붙인다 — 화폐를 빼면 기본 화폐. */
    private fun <T : ArgumentBuilder<CommandSourceStack, T>> T.amountThenCurrency(run: (CommandContext<CommandSourceStack>) -> Int): T =
        then(
            Commands.argument("금액", LongArgumentType.longArg(1)).executes(run)
                .then(Commands.argument("화폐", StringArgumentType.word()).suggests(currencies).executes(run)),
        )

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("돈")
            .requires { it.sender.hasPermission(USE) }
            .executes { ctx ->
                val player = ctx.source.executor as? Player ?: ctx.source.sender as? Player
                if (player == null) usage(sender(ctx)) else WalletMenu(eco, player).open(player)
                1
            }
            .then(Commands.literal("도움말").executes { ctx -> usage(sender(ctx)); 1 })
            .then(
                Commands.literal("보기")
                    .executes { ctx -> player(ctx)?.let { show(sender(ctx), it.uniqueId) }; 1 }
                    .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players)
                        .requires { it.sender.hasPermission(SEE_OTHERS) }
                        .executes { ctx ->
                            val name = StringArgumentType.getString(ctx, "플레이어")
                            eco.findPlayer(name)?.let { show(sender(ctx), it) } ?: eco.messages.send(sender(ctx), "player-not-found", Ph.of().player(name))
                            1
                        }),
            )
            .then(
                Commands.literal("보내기").requires { it.sender.hasPermission(PAY) }
                    .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players).amountThenCurrency { ctx ->
                        val from = player(ctx) ?: return@amountThenCurrency 0
                        val def = currencyArg(ctx) ?: return@amountThenCurrency 0
                        val name = StringArgumentType.getString(ctx, "플레이어")
                        val to = eco.findPlayer(name) ?: return@amountThenCurrency 0.also { eco.messages.send(from, "player-not-found", Ph.of().player(name)) }
                        if (eco.transfers.pay(from, to, def, LongArgumentType.getLong(ctx, "금액"))) 1 else 0
                    }),
            )
            .then(
                Commands.literal("순위")
                    .executes { ctx -> rank(ctx, null) }
                    .then(Commands.argument("화폐", StringArgumentType.word()).suggests(currencies).executes { ctx -> rank(ctx, StringArgumentType.getString(ctx, "화폐")) }),
            )
            .then(
                Commands.literal("입금")
                    .then(Commands.argument("화폐", StringArgumentType.word()).suggests(bankCurrencies)
                        .executes { ctx -> deposit(ctx, null) }
                        .then(Commands.argument("수량", LongArgumentType.longArg(1)).executes { ctx -> deposit(ctx, LongArgumentType.getLong(ctx, "수량")) })),
            )
            .then(
                Commands.literal("출금")
                    .then(Commands.argument("화폐", StringArgumentType.word()).suggests(bankCurrencies)
                        .then(Commands.argument("수량", LongArgumentType.longArg(1)).executes { ctx ->
                            val player = player(ctx) ?: return@executes 0
                            val def = bank(ctx) ?: return@executes 0
                            val amount = LongArgumentType.getLong(ctx, "수량")
                            if (!eco.items.withdrawItems(player, def, amount)) return@executes 0.also { eco.messages.send(player, "not-enough", Ph.of().currency(def.name).amount(def.format(amount))) }
                            eco.messages.send(player, "bank-withdrew", Ph.of().currency(def.name).amount(def.format(amount)).balance(def.format(eco.accounts.balance(player.uniqueId, def))))
                            1
                        })),
            )
            .then(
                Commands.literal("수표").requires { it.sender.hasPermission(CHEQUE) }.amountThenCurrency { ctx ->
                    val player = player(ctx) ?: return@amountThenCurrency 0
                    val def = currencyArg(ctx) ?: return@amountThenCurrency 0
                    if (eco.cheques.issue(player, def, LongArgumentType.getLong(ctx, "금액"))) 1 else 0
                },
            )
            .then(admin("지급", Transfers.AdminAction.GIVE))
            .then(admin("차감", Transfers.AdminAction.TAKE))
            .then(admin("설정", Transfers.AdminAction.SET))
            .then(
                Commands.literal("관리").requires { it.sender.hasPermission(ADMIN) }.executes { ctx ->
                    player(ctx)?.let { CurrencyListMenu(eco, it).open(it) }
                    1
                },
            )
            .then(
                Commands.literal("리로드").requires { it.sender.hasPermission(ADMIN) }.executes { ctx ->
                    val who = sender(ctx)
                    plugin.reload { eco.messages.send(who, "reloaded", Ph.of().count(eco.currencies.all().size)) }
                    1
                },
            )

    private fun admin(label: String, action: Transfers.AdminAction): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(label).requires { it.sender.hasPermission(ADMIN) }
            .then(Commands.argument("플레이어", StringArgumentType.word()).suggests(players).then(
                Commands.argument("금액", LongArgumentType.longArg(0)).executes { ctx -> adminRun(ctx, action) }
                    .then(Commands.argument("화폐", StringArgumentType.word()).suggests(currencies).executes { ctx -> adminRun(ctx, action) }),
            ))

    private fun adminRun(ctx: CommandContext<CommandSourceStack>, action: Transfers.AdminAction): Int {
        val def = currencyArg(ctx) ?: return 0
        val name = StringArgumentType.getString(ctx, "플레이어")
        val target = eco.findPlayer(name) ?: return 0.also { eco.messages.send(sender(ctx), "player-not-found", Ph.of().player(name)) }
        return if (eco.transfers.admin(sender(ctx), target, def, action, LongArgumentType.getLong(ctx, "금액"))) 1 else 0
    }

    private fun bank(ctx: CommandContext<CommandSourceStack>): CurrencyDef? {
        val def = currencyArg(ctx) ?: return null
        if (!def.bank) return null.also { eco.messages.send(sender(ctx), "not-bank", Ph.of().currency(def.name)) }
        return def
    }

    private fun deposit(ctx: CommandContext<CommandSourceStack>, amount: Long?): Int {
        val player = player(ctx) ?: return 0
        val def = bank(ctx) ?: return 0
        val moved = eco.items.depositItems(player, def, amount)
        eco.messages.send(player, if (moved > 0) "bank-deposited" else "bank-nothing",
            Ph.of().currency(def.name).amount(def.format(moved)).balance(def.format(eco.accounts.balance(player.uniqueId, def))))
        return if (moved > 0) 1 else 0
    }

    private fun rank(ctx: CommandContext<CommandSourceStack>, currency: String?): Int {
        val def = eco.currency(currency) ?: return 0.also { eco.messages.send(sender(ctx), "unknown-currency", Ph.of().currency(currency ?: "기본")) }
        val player = ctx.source.executor as? Player ?: ctx.source.sender as? Player
        if (player != null) {
            RankMenu(eco, player, def.id).open(player)
            return 1
        }
        eco.ranks.refresh()
        eco.messages.send(sender(ctx), "rank-header", Ph.of().currency(def.name))
        for ((index, entry) in eco.ranks.top(def.id).take(10).withIndex()) {
            eco.messages.send(sender(ctx), "rank-line", Ph.of().count(index + 1).player(eco.nameOf(entry.player)).amount(def.format(entry.amount)))
        }
        return 1
    }

    private fun show(to: CommandSender, target: java.util.UUID) {
        val player = Bukkit.getOfflinePlayer(target)
        eco.messages.send(to, "balance-header", Ph.of().player(eco.nameOf(target)))
        for (def in eco.currencies.all()) {
            val amount = if (def.inventory) eco.items.count(player, def) else eco.accounts.balance(target, def)
            eco.messages.send(to, "balance-line", Ph.of().currency(def.name).amount(def.format(amount)))
        }
    }

    private fun usage(to: CommandSender) {
        eco.messages.sendRaw(to, eco.messages.raw("help"))
    }

    companion object {
        const val USE = "inmceconomy.use"
        const val PAY = "inmceconomy.pay"
        const val CHEQUE = "inmceconomy.cheque"
        const val SEE_OTHERS = "inmceconomy.see.others"
        const val ADMIN = "inmceconomy.admin"
    }
}
