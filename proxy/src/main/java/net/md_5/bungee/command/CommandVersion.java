package net.md_5.bungee.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;

public class CommandVersion extends Command {

    public CommandVersion() {
        super("version");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage(ChatColor.GOLD + ChatColor.BOLD.toString() + "This server is running BungeeCord version " + ProxyServer.getInstance().getVersion());
    }
}
