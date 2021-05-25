package de.chojo.repbot.commands;

import de.chojo.jdautil.command.SimpleCommand;
import de.chojo.jdautil.localization.util.LocalizedEmbedBuilder;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.DiscordResolver;
import de.chojo.jdautil.wrapper.CommandContext;
import de.chojo.jdautil.wrapper.MessageEventWrapper;
import de.chojo.repbot.data.ReputationData;
import de.chojo.repbot.data.wrapper.ReputationLogEntry;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.sharding.ShardManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class Log extends SimpleCommand {
    private final ShardManager shardManager;
    private final ReputationData reputationData;

    public Log(ShardManager shardManager, DataSource dataSource) {
        super("log",
                null,
                "command.log.description", null, subCommandBuilder()
                        .add("received", "<user> [count]", "command.log.sub.received")
                        .add("donated", "<user> [count]", "command.log.sub.donated")
                        .add("message", "<message_id>", "command.log.sub.message")
                        .build(),
                Permission.ADMINISTRATOR);
        this.shardManager = shardManager;
        this.reputationData = new ReputationData(dataSource);
    }

    @Override
    public boolean onCommand(MessageEventWrapper eventWrapper, CommandContext context) {
        if (context.argsArray().length <= 1) return false;
        var cmd = context.argString(0).get();
        if ("received".equalsIgnoreCase(cmd) || "donated".equalsIgnoreCase(cmd)) {
            var userArg = context.argString(1).get();
            var optUser = DiscordResolver.getUser(shardManager, userArg);
            if (optUser.isEmpty()) {
                eventWrapper.replyErrorAndDelete(eventWrapper.localize("error.userNotFound"), 15);
                return true;
            }
            if ("received".equalsIgnoreCase(cmd)) {
                return received(eventWrapper, context.subContext(cmd), optUser.get());
            }
            return donated(eventWrapper, context.subContext(cmd), optUser.get());
        }
        if ("message".equalsIgnoreCase(cmd)) {
            return message(eventWrapper, context.subContext(cmd));
        }
        return false;
    }

    private boolean message(MessageEventWrapper eventWrapper, CommandContext subContext) {
        var optMessageId = subContext.argLong(0);

        if (optMessageId.isEmpty()) {
            eventWrapper.replyErrorAndDelete(eventWrapper.localize("error.invalidMessage"), 15);
            return false;
        }

        var messageLog = reputationData.getMessageLog(optMessageId.get(), eventWrapper.getGuild(), 50);

        var log = mapMessageLogEntry(eventWrapper, messageLog);

        var message = new LocalizedEmbedBuilder(eventWrapper)
                .setAuthor(eventWrapper.localize("command.log.messageLog", Replacement.create("ID", optMessageId.get())))
                .setDescription(log)
                .build();
        eventWrapper.reply(message).queue();
        return true;
    }

    private void sendUserLog(MessageEventWrapper eventWrapper, User user, String title, String log) {
        var message = new LocalizedEmbedBuilder(eventWrapper)
                .setAuthor(eventWrapper.localize(title,
                        Replacement.create("USER", user.getAsTag())),
                        null, user.getEffectiveAvatarUrl())
                .setDescription(log)
                .build();
        eventWrapper.reply(message).queue();
    }

    private boolean donated(MessageEventWrapper eventWrapper, CommandContext context, User user) {
        var limit = context.argInt(1).orElse(10);

        var userDonatedLog = reputationData.getUserDonatedLog(user, eventWrapper.getGuild(), Math.max(5, Math.min(limit, 50)));

        var log = mapUserLogEntry(eventWrapper, userDonatedLog, ReputationLogEntry::getReceiverId);
        sendUserLog(eventWrapper, user, "command.log.donatedLog", log);
        return true;
    }

    private boolean received(MessageEventWrapper eventWrapper, CommandContext context, User user) {
        var limit = context.argInt(1).orElse(10);

        var userDonatedLog = reputationData.getUserReceivedLog(user, eventWrapper.getGuild(), Math.max(5, Math.min(limit, 50)));

        var log = mapUserLogEntry(eventWrapper, userDonatedLog, ReputationLogEntry::getDonorId);

        sendUserLog(eventWrapper, user, "command.log.receivedLog", log);
        return true;
    }

    private String mapUserLogEntry(MessageEventWrapper wrapper, List<ReputationLogEntry> logEntries, Function<ReputationLogEntry, Long> userId) {
        List<String> entries = new ArrayList<>();
        for (var logEntry : logEntries) {
            var received = wrapper.getGuild().retrieveMemberById(userId.apply(logEntry)).complete();
            var thankType = wrapper.localize("thankType." + logEntry.getType().name().toLowerCase(Locale.ROOT));
            var jumpLink = createJumpLink(wrapper, logEntry);
            entries.add(String.format("**%s** %s %s",
                    thankType, received == null ? "----" : received.getAsMention(), jumpLink));
        }
        return String.join("\n", entries);
    }

    private String mapMessageLogEntry(MessageEventWrapper wrapper, List<ReputationLogEntry> logEntries) {
        if (logEntries.isEmpty()) return "";

        List<String> entries = new ArrayList<>();
        for (var logEntry : logEntries) {
            var jumpLink = createJumpLink(wrapper, logEntry);
            var donator = wrapper.getGuild().retrieveMemberById(logEntry.getDonorId()).complete();
            var receiver = wrapper.getGuild().retrieveMemberById(logEntry.getReceiverId()).complete();
            var thankType = wrapper.localize("thankType." + logEntry.getType().name().toLowerCase(Locale.ROOT));
            entries.add(String.format("**%s** %s ➜ %s **|** %s",
                    thankType, donator == null ? "----" : donator.getAsMention(), receiver == null ? "----" : receiver.getAsMention(), jumpLink));
        }
        return String.join("\n", entries);
    }

    private String createJumpLink(MessageEventWrapper wrapper, ReputationLogEntry log) {
        var jump = wrapper.localize("words.jumpMarker",
                Replacement.create("TARGET", "$words.message$"),
                Replacement.create("URL", log.getMessageJumpLink()));

        String refJump = null;
        if (log.hasRefMessage()) {
            refJump = wrapper.localize("words.jumpMarker",
                    Replacement.create("TARGET", "$words.refMessage$"),
                    Replacement.create("URL", log.getMessageJumpLink()));
        }

        return String.format("**%s** %s", jump, refJump == null ? "" : "➜ **" + refJump + "**");
    }
}