package io.ib67.sfcraft;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.ib67.sfcraft.strategy.BackupChains;
import lombok.extern.log4j.Log4j2;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Log4j2
public class BackupCommands {
    protected final JustBackupMod mod;
    protected volatile Runnable restoreIssued;
    protected final List<String> helpMessage = List.of(
            "__ Help messages of JustBackup __",
            " /backup help ",
            "    -- Show this message",
            " /backup list ",
            "    -- List backups",
            " /backup restore [keepdeleted] <backupKey>",
            "    -- Restore the backup on next server restart.",
            "    Restoring an incremental backup automatically applies its full base",
            "    and every incremental in between, in order.",
            "    keepdeleted: skip deletions, keeping files removed after the base backup.",
            "    Can be cancelled by /backup cancelrestore",
            " /backup delete <backupKey>",
            "    -- Delete a backup. This does not ask for a confirmation.",
            " /backup create <incremental: true/false> <subject / \"@all\">",
            "    -- Create a backup.",
            "    Options:",
            "      incremental: Only bundle files detected changes",
            "      subject: specify a subject to backup. Or use \"@all\" to backup all subjects.",
            " /backup suspend",
            "    -- Temporarily stops the auto-backup worker, until next restart or ",
            "    typing this command again."
    );

    public BackupCommands(JustBackupMod mod) {
        this.mod = mod;
    }

    public void registerCommand(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registry, Commands.CommandSelection env) {
        dispatcher.register(literal("backup")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(literal("help").executes(this::cmdHelp))
                .then(literal("list").executes(this::cmdList))
                .then(literal("cancelrestore").executes(this::cmdCancelRestore))
                .then(literal("suspend").executes(this::cmdSuspend))
                .then(literal("delete")
                        .then(argument("backupKey", StringArgumentType.greedyString())
                                .suggests(this::suggestBackups)
                                .executes(this::cmdDelete)))
                .then(literal("restore")
                        .then(literal("keepdeleted")
                                .then(argument("backupKey", StringArgumentType.greedyString())
                                        .suggests(this::suggestBackups)
                                        .executes(ctx -> cmdRestore(ctx, true))))
                        .then(argument("backupKey", StringArgumentType.greedyString())
                                .suggests(this::suggestBackups)
                                .executes(ctx -> cmdRestore(ctx, false))))
                .then(literal("create")
                        .then(argument("incremental", BoolArgumentType.bool())
                                .then(argument("subject", StringArgumentType.greedyString())
                                        .executes(this::createBackupBySubject))
                                .then(literal("@all").executes(this::fullBackup)))));
    }

    private int createBackupBySubject(CommandContext<CommandSourceStack> context) {
        var incremental = BoolArgumentType.getBool(context, "incremental");
        var subject = StringArgumentType.getString(context, "subject");
        var s = context.getSource();
        s.sendSystemMessage(Component.literal("The issued backup has been scheduled.").withColor(Color.CYAN.getRGB()));
        mod.issueBackup(subject, incremental);
        return Command.SINGLE_SUCCESS;
    }

    private int fullBackup(CommandContext<CommandSourceStack> context) {
        var incremental = BoolArgumentType.getBool(context, "incremental");
        var s = context.getSource();
        s.sendSystemMessage(Component.literal("The issued backup has been scheduled.").withColor(Color.CYAN.getRGB()));
        var weakRef = new WeakReference<>(s);
        mod.backupAll(incremental).thenAccept(result -> {
            var server = mod.server;
            if (server == null || !server.isRunning()) return;
            // completion runs on the backup executor; command sources must be used on the main thread
            server.submit(() -> {
                var ref = weakRef.get();
                if (ref == null) {
                    ref = server.createCommandSourceStack();
                }
                ref.sendSystemMessage(Component.literal("-- BACKUP RESULT SUMMARY --"));
                for (var entry : result.entrySet()) {
                    var k = entry.getKey();
                    var v = entry.getValue();
                    ref.sendSystemMessage(Component.literal("  - [" + k + "]: ").append(v.toText()));
                    ref.sendSystemMessage(Component.literal("BACKUP KEY: " + v.backupKey()).withColor(Color.GRAY.getRGB()));
                    ref.sendSystemMessage(Component.literal("GENERATED FROM: " + v.from()).withColor(Color.GRAY.getRGB()));
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cmdSuspend(CommandContext<CommandSourceStack> context) {
        var s = context.getSource();
        // this is safe as we are the only writer here.
        var suspend = mod.suspend;
        mod.suspend = !suspend;
        if (suspend) {
            s.sendSystemMessage(Component.nullToEmpty("The automatic backup system is now re-enabled."));
        } else {
            s.sendSystemMessage(Component.nullToEmpty("The automatic backup system is now disabled."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cmdRestore(CommandContext<CommandSourceStack> context, boolean ignoreDeletions) {
        var s = context.getSource();
        if (restoreIssued != null) {
            s.sendSystemMessage(Component.literal("Another restore request is already present.").withColor(Color.RED.getRGB()));
            s.sendSystemMessage(Component.nullToEmpty("TIP: Use `/backup cancelrestore` to cancel that request."));
            return 0;
        }
        var backupKey = StringArgumentType.getString(context, "backupKey");
        var backup = mod.tracker.getTrackedBackups().get(backupKey);
        if (backup == null) {
            s.sendSystemMessage(Component.literal("Invalid backup " + backupKey + ". Note: Use backupKey instead of backupName!"));
            return 0;
        }
        final List<Backup> chain;
        try {
            chain = BackupChains.resolveChain(mod.tracker.getTrackedBackups().values(), backup);
        } catch (IllegalStateException e) {
            s.sendSystemMessage(Component.literal(e.getMessage()).withColor(Color.RED.getRGB()));
            return 0;
        }
        s.sendSystemMessage(Component.literal("The following " + chain.size() + " bundle(s) will be applied in order:"));
        for (var b : chain) {
            s.sendSystemMessage(Component.literal("  -> [" + (b.incremental() ? "INCR" : "FULL") + "] " + b.backupKey())
                    .withColor(Color.GRAY.getRGB()));
        }
        if (ignoreDeletions) {
            s.sendSystemMessage(Component.literal("Deletions will be ignored: files removed after the base backup are kept.")
                    .withColor(Color.YELLOW.getRGB()));
        }
        restoreIssued = () -> {
            var destination = Path.of(backup.from());
            var movedDst = destination.resolveSibling(destination.getFileName().toString() + "_bak_" + System.currentTimeMillis());
            log.warn("EXTRACTING {} BUNDLE(S) TO {}", chain.size(), destination);
            log.warn(" == STEP 1 == Make a backup for the destination.");
            try {
                Files.move(destination, movedDst);
                log.info("{} has been moved to {}", destination, movedDst);
            } catch (IOException e) {
                log.error("CANNOT MOVE {} TO {}", destination, movedDst, e);
                log.error("RESTORE INTERRUPTED. Here are some tips helping you out:");
                log.error(" -- A. Merge already moved files");
                log.error("  Try this command in your server directory: (linux)");
                log.error("  $ cp ./{}/* ./{}", movedDst, destination);
                log.error(" -- B. Remove them all and use external unbundler tools");
                log.error("  You may want to copy them elsewhere first (see kind A)");
                log.error("  $ rm -r ./{} ./{}", destination, movedDst);
                log.error("  Visit https://github.com/saltedfishclub/justbackup for the usage of bundler tools");
                log.error(" ------- ERROR END -------");
                return;
            }
            log.info(" == STEP 2 == Applying {} bundle(s)", chain.size());
            try {
                for (int i = 0; i < chain.size(); i++) {
                    var b = chain.get(i);
                    log.info("Applying {} ({}/{}){}", b.backupKey(), i + 1, chain.size(),
                            ignoreDeletions ? " [ignoring deletions]" : "");
                    mod.tracker.recoverBackup(b, destination, ignoreDeletions);
                }
                log.info("Backup has been recovered.");
            } catch (Exception e) {
                log.error("RESTORE FAILED midway. Your original world is intact at {}", movedDst, e);
                log.error("Move it back to {} to undo the restore.", destination);
            }
        };
        s.sendSystemMessage(Component.literal("Backup restoration task has been scheduled!"));
        s.sendSystemMessage(Component.literal("Restart your server to take changes."));
        return Command.SINGLE_SUCCESS;
    }

    private int cmdDelete(CommandContext<CommandSourceStack> context) {
        var backupKey = StringArgumentType.getString(context, "backupKey");
        var backup = mod.tracker.getTrackedBackups().get(backupKey);
        var source = context.getSource();
        if (backup == null) {
            source.sendSystemMessage(Component.nullToEmpty("Invalid backup. No backups are named '" + backupKey + "'"));
            return 0;
        }
        try {
            mod.tracker.deleteBackup(backup);
            source.sendSystemMessage(Component.nullToEmpty("Backup has been deleted."));
        } catch (Exception e) {
            source.sendSystemMessage(Component.nullToEmpty("Backup has not been deleted successfully. ERROR: " + e));
            log.error("Cannot delete backup {}", backupKey, e);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cmdList(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var trackedBackups = mod.tracker.getTrackedBackups();
        if (trackedBackups.isEmpty()) {
            source.sendSystemMessage(Component.nullToEmpty("No backups are neither created nor tracked yet."));
            return Command.SINGLE_SUCCESS;
        }
        source.sendSystemMessage(Component.nullToEmpty("ALL TRACKED BACKUPS:"));
        for (var entry : trackedBackups.entrySet()) {
            var backupKey = entry.getKey();
            var backup = entry.getValue();
            source.sendSystemMessage(Component.literal(" - [" + backup.name() + "] ").append(backup.toText()));
            source.sendSystemMessage(Component.literal("   ").append(
                            Component.literal("[DELETE]").withColor(Color.RED.getRGB()).withStyle(it -> it.withClickEvent(
                                    new ClickEvent.SuggestCommand("/backup delete " + backupKey)
                            ))).append(Component.literal(" ")).append(
                            Component.literal("[RESTORE]").withColor(Color.CYAN.getRGB()).withStyle(it -> it.withClickEvent(
                                    new ClickEvent.SuggestCommand("/backup restore " + backupKey)
                            ))
                    )
            );
        }
        return 0;
    }

    private int cmdCancelRestore(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        if (restoreIssued != null) {
            restoreIssued = null;
            source.sendSystemMessage(Component.nullToEmpty("The backup restoration request has been cancelled."));
            return 0;
        }
        source.sendSystemMessage(Component.nullToEmpty("You have no backup restoration request in flight."));
        return 0;
    }

    private int cmdHelp(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        for (String s : helpMessage) {
            if (s.startsWith(" /")) {
                source.sendSystemMessage(Component.literal(s).withColor(Color.CYAN.getRGB()));
            } else {
                source.sendSystemMessage(Component.literal(s));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestBackups(CommandContext<CommandSourceStack> serverCommandSourceCommandContext, SuggestionsBuilder suggestionsBuilder) {
        for (Backup value : mod.tracker.getTrackedBackups().values()) {
            suggestionsBuilder = suggestionsBuilder.suggest(value.backupKey());
        }
        return CompletableFuture.completedFuture(suggestionsBuilder.build());
    }
}
