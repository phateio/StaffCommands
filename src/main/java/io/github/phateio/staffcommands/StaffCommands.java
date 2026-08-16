package io.github.phateio.staffcommands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Registers the staff commands defined in config.yml as real, permission-gated
 * commands, so players without the permission can neither see them on `/` nor
 * run them (unlike commands.yml aliases, which carry no permission and always
 * show). This class is a generic loader — all command definitions live in
 * config.yml, so entries can be added/changed without touching code.
 *
 * Paper `BasicCommand` API (LifecycleEvents.COMMANDS); permission() gates both
 * visibility (client command graph filtering) and execution.
 */
public final class StaffCommands extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        final ConfigurationSection root = getConfig().getConfigurationSection("aliases");
        if (root == null) {
            getLogger().warning("config.yml has no 'aliases' section — nothing registered");
            return;
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands registrar = event.registrar();
            int count = 0;
            for (final String name : root.getKeys(false)) {
                final ConfigurationSection sec = root.getConfigurationSection(name);
                if (sec == null) {
                    continue;
                }
                final String permission = sec.getString("permission");
                final List<String> templates = sec.getStringList("commands");
                // Fail closed. A permission-less command is visible to and usable by
                // everyone — the exact leak this plugin exists to close — so a typo in
                // config.yml must drop the alias, not publish it.
                if (permission == null || permission.isBlank()) {
                    getLogger().severe("Alias '" + name + "' has no permission — not registered");
                    continue;
                }
                if (templates.isEmpty()) {
                    getLogger().severe("Alias '" + name + "' has no commands — not registered");
                    continue;
                }
                registrar.register(name, "Staff command " + name, sec.getStringList("aliases"),
                        new AliasCommand(name, permission, templates));
                count++;
            }
            getLogger().info("Registered " + count + " permission-gated staff commands from config.yml");
        });
    }

    /**
     * One config-defined alias: gate on {@code permission}, run {@code templates} in
     * order. {@code minArgs} and {@code usage} are derived from the placeholders the
     * templates use, reproducing the required-argument semantics of the {@code $$1} /
     * {@code $$2-} tokens the commands.yml aliases used.
     */
    private record AliasCommand(String name, String permission, List<String> templates,
                                int minArgs, String usage) implements BasicCommand {

        AliasCommand(final String name, final String permission, final List<String> templates) {
            this(name, permission, templates, minArgs(templates), usage(name, minArgs(templates)));
        }

        private static boolean uses(final List<String> templates, final String placeholder) {
            return templates.stream().anyMatch(t -> t.contains(placeholder));
        }

        /** {@code {args}} is everything after the first argument, so it implies one too. */
        private static int minArgs(final List<String> templates) {
            if (uses(templates, "{args}")) {
                return 2;
            }
            return uses(templates, "{player}") ? 1 : 0;
        }

        private static String usage(final String name, final int minArgs) {
            return "/" + name + (minArgs >= 1 ? " <player>" : "") + (minArgs >= 2 ? " <args...>" : "");
        }

        @Override
        public String permission() {
            return permission;
        }

        @Override
        public void execute(final CommandSourceStack source, final String[] args) {
            final CommandSender sender = source.getSender();
            // Paper splits the argument string on spaces and drops empty tokens, so a
            // plain length check is enough to reject a missing player or reason —
            // without it the command silently runs on "" and still fires the follow-up
            // lines (e.g. a Discord broadcast for a ban that never happened).
            if (args.length < minArgs) {
                sender.sendMessage(Component.text("Usage: " + usage, NamedTextColor.RED));
                return;
            }
            final String player = args.length > 0 ? args[0] : "";
            final String rest = args.length > 1
                    ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
            for (String line : templates) {
                boolean console = false;
                if (line.startsWith("@console ")) {
                    console = true;
                    line = line.substring("@console ".length());
                }
                line = line.replace("{player}", player).replace("{args}", rest).strip();
                Bukkit.dispatchCommand(console ? Bukkit.getConsoleSender() : sender, line);
            }
        }

        /**
         * Online-player completion for the first argument, matching what the
         * commands.yml aliases gave for free (FormattedCommandAlias inherits
         * Command#tabComplete, whose default suggests online player names) — Paper's
         * BasicCommand suggests nothing unless told to.
         *
         * Paper hands us the words typed so far with empty tokens dropped, then appends
         * one empty token once a trailing space is present: `/ban ` → {@code []},
         * `/ban St` → {@code ["St"]}, `/ban Steve ` → {@code ["Steve", ""]}. So the
         * first argument is still being typed only while length <= 1.
         */
        @Override
        public Collection<String> suggest(final CommandSourceStack source, final String[] args) {
            if (minArgs < 1 || args.length > 1) {
                return List.of();
            }
            final String prefix = args.length == 1 ? args[0] : "";
            final Player asking = source.getSender() instanceof Player p ? p : null;
            final List<String> names = new ArrayList<>();
            for (final Player online : Bukkit.getOnlinePlayers()) {
                if ((asking == null || asking.canSee(online))
                        && StringUtil.startsWithIgnoreCase(online.getName(), prefix)) {
                    names.add(online.getName());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }
}
