package io.ib67.sfcraft;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class BackupCommands {
    protected final JustBackupMod mod;
    protected Runnable restoreIssued;

    public BackupCommands(JustBackupMod mod) {
        this.mod = mod;
    }

    public int cmdList(CommandContext<ServerCommandSource> ctx) {
        var s = ctx.getSource();
        s.sendFeedback(() -> Text.of("Tracked backups:"), false);
        for (Backup value : mod.tracker.getTrackedBackups().values()) {
            var restoreClick = Text.literal("[RESTORE]").withColor(Color.GREEN.getRGB())
                    .styled(it -> it.withClickEvent(new ClickEvent.SuggestCommand("/backup restore " + value.backupKey())));
            var deleteClick = Text.literal("[DELETE]").withColor(Color.RED.getRGB())
                    .styled(it -> it.withClickEvent(new ClickEvent.SuggestCommand("/backup delete " + value.backupKey())));
            var message = Text.literal(" - " + value.name() + " (" + value.backupKey() + ") " +
                    (value.incremental() ? "incremental" : "full") + "  ").append(restoreClick).append("  ").append(deleteClick);
            s.sendMessage(message);
            s.sendMessage(Text.of("    Size: " + Math.floorDiv(value.sizeTotal(), 1024 * 1024) + "MiB"));
        }
        return Command.SINGLE_SUCCESS;
    }

    public int cmdDelete(CommandContext<ServerCommandSource> ctx) {
        var _backup = ctx.getArgument("backupName", String.class);
        var backup = mod.tracker.getTrackedBackups().get(_backup);
        mod.tracker.deleteBackup(backup);
        ctx.getSource().sendMessage(Text.of("Backup " + _backup + " is deleted successfully."));
        return Command.SINGLE_SUCCESS;
    }

    public int cmdSuspend(CommandContext<ServerCommandSource> ctx) {
        mod.suspend = !mod.suspend;
        ctx.getSource().sendMessage(Text.of("Backup disabled: " + mod.suspend));
        return Command.SINGLE_SUCCESS;
    }

    public int cmdRestore(CommandContext<ServerCommandSource> ctx) {
        var s = ctx.getSource();
        var pm = s.getServer().getPlayerManager();
        var _backup = ctx.getArgument("backupName", String.class);
        var backup = mod.tracker.getTrackedBackups().get(_backup);
        if (backup == null) {
            s.sendMessage(Text.of("Invalid backup " + _backup));
            return 0;
        }
        if (restoreIssued != null) {
            s.sendMessage(Text.of("Another restoration is in progress. try /backup cancel to cancel it"));
            return 0;
        }
        s.sendMessage(Text.literal("Please restart your server to take changes"));
        restoreIssued = () -> {
            System.out.println("Recovering backup... ");
            try {
                var toOverride = Path.of(backup.from());
                var old = Path.of(backup.from() + "_old");
                if (Files.exists(old)) {
                    try (var f = Files.walk(old)) {
                        f.sorted(Comparator.reverseOrder())
                                .forEach(it -> {
                                    try {
                                        Files.deleteIfExists(it);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                    }
                }
                Files.deleteIfExists(old);
                Files.move(toOverride, old);
                System.out.println("Moved your old save to " + old);
                mod.tracker.recoverBackup(backup, toOverride);
                System.out.println("Recover successfully!");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return Command.SINGLE_SUCCESS;
    }

    public int cmdHelp(CommandContext<ServerCommandSource> serverCommandSourceCommandContext) {
        // todo i18n
        return 0;
    }

    public int cmdCancelRestore(CommandContext<ServerCommandSource> serverCommandSourceCommandContext) {
        restoreIssued = null;
        serverCommandSourceCommandContext.getSource().sendMessage(Text.of("Operation cancelled."));
        return 0;
    }

    public void registerCommand(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registry, CommandManager.RegistrationEnvironment env) {
        dispatcher.register(literal("backup")
                .requires(it -> it.hasPermissionLevel(3))
                .then(literal("help").executes(this::cmdHelp))
                .then(literal("list").executes(this::cmdList))
                .then(literal("cancelrestore").executes(this::cmdCancelRestore))
                .then(literal("delete")
                        .then(argument("backupName", StringArgumentType.greedyString())
                                .suggests(this::suggestBackups)
                                .executes(this::cmdDelete)))
                .then(literal("restore")
                        .then(argument("backupName", StringArgumentType.greedyString())
                                .suggests(this::suggestBackups)
                                .executes(this::cmdRestore)))
//                .then(literal("create").executes(this::cmdIssueBackupFull)) todo
                .then(literal("suspend").executes(this::cmdSuspend))
        );
    }

    private CompletableFuture<Suggestions> suggestBackups(CommandContext<ServerCommandSource> serverCommandSourceCommandContext, SuggestionsBuilder suggestionsBuilder) {
        for (Backup value : mod.tracker.getTrackedBackups().values()) {
            suggestionsBuilder = suggestionsBuilder.suggest(value.backupKey());
        }
        return CompletableFuture.completedFuture(suggestionsBuilder.build());
    }
}
