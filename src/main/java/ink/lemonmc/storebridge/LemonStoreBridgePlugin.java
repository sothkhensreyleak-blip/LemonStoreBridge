package ink.lemonmc.storebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class LemonStoreBridgePlugin extends JavaPlugin {

    private static final Pattern ALLOWED_COMMAND_PATTERN =
            Pattern.compile("^(lp|crate|give)\\s+.+", Pattern.CASE_INSENSITIVE);

    private HttpClient httpClient;
    private BukkitTask pollTask;
    private final Set<String> inFlightOrderIds = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        long intervalSeconds = Math.max(2L, getConfig().getLong("poll-interval-seconds", 5L));
        long periodTicks = intervalSeconds * 20L;

        this.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::pollPendingOrders,
                40L,
                periodTicks
        );

        getLogger().info("LemonStoreBridge enabled (1.21.11 - 26.2 target). Polling every " + intervalSeconds + "s.");
    }

    @Override
    public void onDisable() {
        if (this.pollTask != null) {
            this.pollTask.cancel();
        }
        getLogger().info("LemonStoreBridge disabled.");
    }

    private void pollPendingOrders() {
        String baseUrl = getConfig().getString("api-base-url", "").trim().replaceAll("/+$", "");
        String secret = getConfig().getString("api-secret", "").trim();

        if (baseUrl.isEmpty()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/plugin/pending-orders"))
                    .timeout(Duration.ofSeconds(10))
                    .header("x-store-secret", secret)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("orders") || !root.get("orders").isJsonArray()) {
                return;
            }

            JsonArray orders = root.getAsJsonArray("orders");
            for (JsonElement el : orders) {
                if (!el.isJsonObject()) continue;
                JsonObject order = el.getAsJsonObject();

                String orderId = order.has("id") ? order.get("id").getAsString() : "";
                String playerName = order.has("player") ? order.get("player").getAsString().trim() : "";
                String productName = order.has("productName") ? order.get("productName").getAsString().trim() : "Item";
                String command = order.has("command") ? order.get("command").getAsString().trim() : "";

                if (orderId.isEmpty() || playerName.isEmpty() || command.isEmpty()) {
                    continue;
                }

                if (!ALLOWED_COMMAND_PATTERN.matcher(command).matches()) {
                    reportOrderResult(baseUrl, secret, orderId, false, "Command not allowed");
                    continue;
                }

                synchronized (inFlightOrderIds) {
                    if (inFlightOrderIds.contains(orderId)) {
                        continue;
                    }
                    inFlightOrderIds.add(orderId);
                }

                Bukkit.getScheduler().runTask(
                        this,
                        () -> executeOrderOnMainThread(baseUrl, secret, orderId, playerName, productName, command)
                );
            }
        } catch (Exception ignored) {
        }
    }

    private void executeOrderOnMainThread(
            String baseUrl,
            String secret,
            String orderId,
            String playerName,
            String productName,
            String command
    ) {
        try {
            Player onlinePlayer = Bukkit.getPlayerExact(playerName);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                releaseOrderLock(orderId);
                return;
            }

            ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
            boolean success = Bukkit.dispatchCommand(console, command);

            Component alert = Component.text("[LemonMC Store] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text(onlinePlayer.getName(), NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text(" បានទិញ ", NamedTextColor.YELLOW))
                    .append(Component.text(productName, NamedTextColor.AQUA, TextDecoration.BOLD));

            Bukkit.getServer().sendMessage(alert);

            String resultMsg = success ? "Executed" : "Dispatch returned false";
            Bukkit.getScheduler().runTaskAsynchronously(
                    this,
                    () -> {
                        reportOrderResult(baseUrl, secret, orderId, success, resultMsg);
                        releaseOrderLock(orderId);
                    }
            );
        } catch (Exception ex) {
            Bukkit.getScheduler().runTaskAsynchronously(
                    this,
                    () -> {
                        reportOrderResult(baseUrl, secret, orderId, false, ex.getMessage());
                        releaseOrderLock(orderId);
                    }
            );
        }
    }

    private void reportOrderResult(String baseUrl, String secret, String orderId, boolean ok, String detail) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("orderId", orderId);
            payload.addProperty("ok", ok);
            payload.addProperty("detail", detail == null ? "" : detail);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/plugin/complete-order"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-store-secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
        }
    }

    private void releaseOrderLock(String orderId) {
        synchronized (inFlightOrderIds) {
            inFlightOrderIds.remove(orderId);
        }
    }
}
