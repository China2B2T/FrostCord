package net.md_5.bungee.command;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class CommandWhere extends Command {
    public CommandWhere() {
        super("whereami");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if(sender instanceof ConsoleCommandSender) {
            // no u dont
            sender.sendMessage(new TextComponent(ChatColor.RED + "Player required."));
            return;
        }

        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(sender.getName());
        sender.sendMessage(new TextComponent(ChatColor.GREEN + "You are currently playing in " + ChatColor.GOLD + player.getServer().getInfo().getName()));
    }
}
