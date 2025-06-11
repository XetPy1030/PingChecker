package org.xet.pingchecker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;

public class PingCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pingcheck")
                .requires(source -> source.hasPermission(2)) // Требует уровень оператора
                .executes(context -> checkPing(context, null))
                .then(Commands.argument("host", StringArgumentType.string())
                        .executes(context -> checkPing(context, StringArgumentType.getString(context, "host")))
                )
        );
    }

    private static int checkPing(CommandContext<CommandSourceStack> context, String customHost) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String hostToCheck = customHost != null ? customHost : Config.defaultPingHost;
        
        source.sendSuccess(() -> Component.literal("Проверяю соединение с " + hostToCheck + "...")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)), false);

        // Выполняем проверку асинхронно, чтобы не блокировать сервер
        CompletableFuture.supplyAsync(() -> performPingCheck(hostToCheck))
                .thenAccept(result -> {
                    Component message;
                    if (result.success) {
                        message = Component.literal("✓ Интернет-соединение работает! Время отклика: " + result.pingTime + "мс")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
                    } else {
                        message = Component.literal("✗ Ошибка соединения: " + result.errorMessage)
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED));
                    }
                    
                    source.sendSuccess(() -> message, false);
                })
                .exceptionally(throwable -> {
                    source.sendSuccess(() -> Component.literal("✗ Критическая ошибка при проверке: " + throwable.getMessage())
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED)), false);
                    return null;
                });

        return 1;
    }

    private static PingResult performPingCheck(String host) {
        try {
            long startTime = System.currentTimeMillis();
            
            // Сначала пробуем резолвить DNS
            InetAddress address;
            try {
                address = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                return new PingResult(false, 0, "Не удалось найти хост: " + host);
            }

            // Пробуем подключиться через TCP сокет
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, Config.pingPort), Config.pingTimeout);
                long pingTime = System.currentTimeMillis() - startTime;
                return new PingResult(true, pingTime, null);
            } catch (IOException e) {
                // Если не удалось подключиться к порту, пробуем простой ping
                if (address.isReachable(Config.pingTimeout)) {
                    long pingTime = System.currentTimeMillis() - startTime;
                    return new PingResult(true, pingTime, null);
                } else {
                    return new PingResult(false, 0, "Хост недоступен");
                }
            }
            
        } catch (Exception e) {
            return new PingResult(false, 0, "Ошибка: " + e.getMessage());
        }
    }

    private static class PingResult {
        final boolean success;
        final long pingTime;
        final String errorMessage;

        PingResult(boolean success, long pingTime, String errorMessage) {
            this.success = success;
            this.pingTime = pingTime;
            this.errorMessage = errorMessage;
        }
    }
} 