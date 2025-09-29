package io.ib67.sfcraft;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import lombok.extern.log4j.Log4j2;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@Log4j2
public class BackupCommands {
    protected final JustBackupMod mod;
    protected Runnable restoreIssued;
    protected final List<String> helpMessage = List.of(
            "__ Help messages of JustBackup __",
            " /backup help ",
            "    -- Show this message",
            " /backup list ",
            "    -- List backups",
            " /backup restore <backupName>",
            "    -- Restore the backup on next server restart.",
            "    Can be cancelled by /backup cancelrestore",
            " /backup delete <backupName>",
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
                .then(literal("create")
                        .then(argument("incremental", BoolArgumentType.bool())
                                .then(argument("subject", StringArgumentType.greedyString())
                                        .executes(this::createBackupBySubject))
                                .then(literal("@all").executes(this::fullBackup)))
                        .then(literal("suspend").executes(this::cmdSuspend))
                ));
    }

    private int createBackupBySubject(CommandContext<ServerCommandSource> context) {
        var incremental = BoolArgumentType.getBool(context, "incremental");
        var subject = StringArgumentType.getString(context, "subject");
        var s = context.getSource();
        s.sendMessage(Text.literal("The issued backup has been scheduled.").withColor(Color.CYAN.getRGB()));
        mod.issueBackup(subject, incremental);
        return Command.SINGLE_SUCCESS;
    }

    private int fullBackup(CommandContext<ServerCommandSource> context) {
        var incremental = BoolArgumentType.getBool(context, "incremental");
        var s = context.getSource();
        s.sendMessage(Text.literal("The issued backup has been scheduled.").withColor(Color.CYAN.getRGB()));
        var weakRef = new WeakReference<>(s);
        mod.backupAll(incremental).thenAccept(result -> {
            var ref = weakRef.get();
            if (ref == null) {
                ref = mod.server.getCommandSource();
            }
            ref.sendMessage(Text.literal("-- BACKUP RESULT SUMMARY --"));
            for (var entry : result.entrySet()) {
                var k = entry.getKey();
                var v = entry.getValue();
                ref.sendMessage(Text.literal("  - [" + k + "]: ").append(v.toText()));
                ref.sendMessage(Text.literal("BACKUP KEY: " + v.backupKey()).withColor(Color.GRAY.getRGB()));
                ref.sendMessage(Text.literal("GENERATED FROM: " + v.from()).withColor(Color.GRAY.getRGB()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cmdSuspend(CommandContext<ServerCommandSource> context) {
        var s = context.getSource();
        // this is safe as we are the only writer here.
        var suspend = mod.suspend;
        mod.suspend = !suspend;
        if (suspend) {
            s.sendMessage(Text.of("The automatic backup system is now re-enabled."));
        } else {
            s.sendMessage(Text.of("The automatic backup system is now disabled."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cmdRestore(CommandContext<ServerCommandSource> context) {
        var s = context.getSource();
        if (restoreIssued != null) {
            s.sendMessage(Text.literal("Another restore request is already present.").withColor(Color.RED.getRGB()));
            s.sendMessage(Text.of("TIP: Use `/backup cancelrestore` to cancel that request."));
            return 0;
        }
        var backupName = StringArgumentType.getString(context, "backupName");
        restoreIssued = () -> {
            //todo implementation
        };
        return Command.SINGLE_SUCCESS;
    }

    private int cmdDelete(CommandContext<ServerCommandSource> context) {
        var backupName = StringArgumentType.getString(context, "backupName");
        var backup = mod.tracker.getTrackedBackups().get(backupName);
        var source = context.getSource();
        if (backup == null) {
            source.sendMessage(Text.of("Invalid backup. No backups are named '" + backupName + "'"));
            return 0;
        }
        try {
            mod.tracker.deleteBackup(backup);
            source.sendMessage(Text.of("Backup has been deleted."));
        } catch (Exception e) {
            source.sendMessage(Text.of("Backup has not been deleted successfully. ERROR: " + e));
            log.error(e);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cmdList(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        var trackedBackups = mod.tracker.getTrackedBackups();
        if (trackedBackups.isEmpty()) {
            source.sendMessage(Text.of("No backups are neither created nor tracked yet."));
            return Command.SINGLE_SUCCESS;
        }
        source.sendMessage(Text.of("ALL TRACKED BACKUPS:"));
        for (var entry : trackedBackups.entrySet()) {
            var backupKey = entry.getKey();
            var backup = entry.getValue();
            source.sendMessage(Text.literal(" - [" + backup.name() + "] ").append(backup.toText()));
            source.sendMessage(Text.literal("   ").append(
                            Text.literal("[DELETE]").withColor(Color.RED.getRGB()).styled(it -> it.withClickEvent(
                                    new ClickEvent.SuggestCommand("/backup delete " + backupKey)
                            ))).append(Text.literal(" ")).append(
                            Text.literal("[RESTORE]").withColor(Color.CYAN.getRGB()).styled(it -> it.withClickEvent(
                                    new ClickEvent.SuggestCommand("/backup restore " + backupKey)
                            ))
                    )
            );
        }
        return 0;
    }

    private int cmdCancelRestore(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        if (restoreIssued != null) {
            restoreIssued = null;
            source.sendMessage(Text.of("The backup restoration request has been cancelled."));
            return 0;
        }
        source.sendMessage(Text.of("You have no backup restoration request in flight."));
        return 0;
    }

    private int cmdHelp(CommandContext<ServerCommandSource> ctx) {
        var source = ctx.getSource();
        for (String s : helpMessage) {
            if (s.startsWith(" /")) {
                source.sendMessage(Text.literal(s).withColor(Color.CYAN.getRGB()));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestBackups(CommandContext<ServerCommandSource> serverCommandSourceCommandContext, SuggestionsBuilder suggestionsBuilder) {
        for (Backup value : mod.tracker.getTrackedBackups().values()) {
            suggestionsBuilder = suggestionsBuilder.suggest(value.backupKey());
        }
        return CompletableFuture.completedFuture(suggestionsBuilder.build());
    }
}
