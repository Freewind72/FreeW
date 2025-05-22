package me.yourname;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer; // 该API已过时，但Java自带轻量级HTTP服务常用此类

import java.io.OutputStream;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Enumeration;
import java.net.URL;
import java.io.InputStream;
import java.util.Scanner;

// 你可以安全忽略该警告，或在 build.gradle 的 compile 任务中添加 -Xlint:deprecation 以获得详细信息。
// 若需消除警告，需换用第三方HTTP库（如 NanoHTTPD、Jetty），但对插件功能无影响。

public class FreeW extends JavaPlugin implements Listener, CommandExecutor {
    private File configFile;
    private YamlConfiguration config;
    private File worldResourcePackFolder;
    private List<File> resourcePackFiles = new ArrayList<>();
    private Map<String, Integer> playerPackIndex = new HashMap<>();
    private HttpServer localHttpServer;
    private Set<String> cancelledPlayers = new HashSet<>();
    private int httpPort = 8080;
    private String detectedBaseUrl = null;
    private List<String> detectedUrls = new ArrayList<>();
    private String detectedPublicUrl = null;

    @Override
    public void onEnable() {
        // 自动定位 FreeW 文件夹
        File pluginFolder = getDataFolder();
        File dataFolder = new File(pluginFolder.getParentFile(), "FreeW");
        if (!dataFolder.exists()) dataFolder.mkdirs();

        // 配置文件
        configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                List<String> lines = Arrays.asList(
                    "# 资源包相关配置",
                    "base_url: \"http://127.0.0.1:8080/resourcepacks/\"",
                    "",
                    "# 是否强制加载资源包（true=拒绝时踢出，false=仅取消加载），默认false",
                    "force_pack: false",
                    "",
                    "# 资源包选择弹窗提示，可用 %pack_name% 占位资源包文件名",
                    "pack_prompt: \"需要下载资源包 %pack_name% 才能游玩，是否下载？\"",
                    "# 资源包弹窗的“拒绝”按钮文本",
                    "pack_decline_button: \"拒绝\"",
                    "# 资源包弹窗的“接受”按钮文本",
                    "pack_accept_button: \"接受\"",
                    "",
                    "# 玩家拒绝资源包时的踢出提示",
                    "kick.decline: \"§c你拒绝了资源包下载，已断开连接。\"",
                    "# 资源包下载失败时的提示",
                    "kick.fail: \"§c资源包下载失败，请检查网络或联系管理员。\"",
                    "# 没有资源包时的提示",
                    "msg.no_resourcepack: \"§a服务器未检测到资源包，无需下载即可游玩。\"",
                    "# 重载配置提示",
                    "reload.success: \"§a配置文件已重载。\"",
                    "",
                    "# 其他展示配置",
                    "title: \"§a自定义包\"",
                    "subtitle: \"§e欢迎使用发包插件\"",
                    "fadeIn: 10",
                    "stay: 70",
                    "fadeOut: 20",
                    "",
                    "# HTTP服务端口配置",
                    "http_port: 8080"
                );
                java.nio.file.Files.write(configFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                getLogger().warning("无法创建FreeW/config.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        // 读取端口配置
        this.httpPort = config.getInt("http_port", 8080);

        // 资源包文件夹（改为 world 文件夹）
        worldResourcePackFolder = new File(dataFolder, "world");
        if (!worldResourcePackFolder.exists()) worldResourcePackFolder.mkdirs();
        reloadResourcePackList();

        // 注册事件监听
        getServer().getPluginManager().registerEvents(this, this);

        // 注册配置重载命令
        this.getCommand("freewreload").setExecutor(this);

        // 启动本地资源包HTTP托管服务（自动检测IP和端口）
        startLocalResourcePackServer();

        // 输出所有检测到的可用资源包访问URL
        getLogger().info("可用资源包访问URL（请根据实际网络环境配置 base_url）：");
        for (String url : detectedUrls) {
            getLogger().info("  " + url);
        }
        if (detectedPublicUrl != null) {
            getLogger().info("检测到公网IP，推荐公网访问URL（如已开放端口）：" + detectedPublicUrl);
        } else {
            getLogger().info("未检测到公网IP，若为云服务器请确认防火墙和端口映射。");
        }

        // 清除取消记录
        cancelledPlayers.clear();
    }

    @Override
    public void onDisable() {
        if (localHttpServer != null) {
            localHttpServer.stop(0);
            getLogger().info("本地资源包HTTP服务已关闭。");
        }
    }

    private void reloadResourcePackList() {
        resourcePackFiles.clear();
        File[] files = worldResourcePackFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (files != null) resourcePackFiles.addAll(Arrays.asList(files));
    }

    private String lang(String key) {
        return config.getString(key, "§c[配置缺失] " + key);
    }

    private String getBaseUrl() {
        String url = config.getString("base_url");
        if (url == null || url.isEmpty()) url = "http://127.0.0.1:8080/resourcepacks/";
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        reloadResourcePackList();
        cancelledPlayers.remove(player.getName()); // 玩家进服时清除取消记录
        if (resourcePackFiles.isEmpty()) {
            player.sendMessage(lang("msg.no_resourcepack"));
        } else {
            playerPackIndex.put(player.getName(), 0);
            sendSingleResourcePack(player);
        }
    }

    private void sendSingleResourcePack(Player player) {
        if (resourcePackFiles.isEmpty()) return;
        File pack = resourcePackFiles.get(0);
        String encodedName = URLEncoder.encode(pack.getName(), StandardCharsets.UTF_8);
        String url = getBaseUrl() + encodedName;
        String prompt = config.getString("pack_prompt", "需要下载资源包 %pack_name% 才能游玩，是否下载？")
                .replace("%pack_name%", pack.getName());
        String declineBtn = config.getString("pack_decline_button", "拒绝");
        String acceptBtn = config.getString("pack_accept_button", "接受");
        boolean force = config.getBoolean("force_pack", false);

        // setResourcePack 的 declineButton/acceptButton 仅新版本支持，老版本只显示 prompt
        // 这里兼容性处理：只用 prompt，按钮文本仅供新版客户端参考
        getLogger().info("向玩家 " + player.getName() + " 推送资源包: " + url);
        player.setResourcePack(url, null, prompt, force);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        Integer idx = playerPackIndex.get(player.getName());
        boolean force = config.getBoolean("force_pack", false);
        if (idx == null) {
            if (event.getStatus() == Status.DECLINED) {
                if (force) {
                    player.kickPlayer(lang("kick.decline"));
                } else {
                    cancelledPlayers.add(player.getName());
                    player.sendMessage("§e你取消了资源包加载。");
                }
            } else if (event.getStatus() == Status.FAILED_DOWNLOAD) {
                player.sendMessage(lang("kick.fail"));
            }
            return;
        }
        if (event.getStatus() == Status.DECLINED) {
            playerPackIndex.remove(player.getName());
            if (force) {
                player.kickPlayer(lang("kick.decline"));
            } else {
                cancelledPlayers.add(player.getName());
                player.sendMessage("§e你取消了资源包加载。");
            }
        } else if (event.getStatus() == Status.FAILED_DOWNLOAD) {
            File pack = resourcePackFiles.get(0);
            String url = getBaseUrl() + URLEncoder.encode(pack.getName(), StandardCharsets.UTF_8);
            getLogger().warning("玩家 " + player.getName() + " 资源包下载失败，URL: " + url + "，请检查 base_url 配置和该文件的公网可访问性。");
            player.sendMessage(lang("kick.fail"));
            playerPackIndex.remove(player.getName());
        } else if (event.getStatus() == Status.ACCEPTED || event.getStatus() == Status.SUCCESSFULLY_LOADED) {
            playerPackIndex.remove(player.getName());
            cancelledPlayers.remove(player.getName());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("freewreload")) {
            config = YamlConfiguration.loadConfiguration(configFile);
            reloadResourcePackList();
            boolean force = config.getBoolean("force_pack", false);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (force || !cancelledPlayers.contains(player.getName())) {
                    playerPackIndex.put(player.getName(), 0);
                    sendSingleResourcePack(player);
                }
            }
            sender.sendMessage(lang("reload.success"));
            return true;
        }
        return false;
    }

    private void startLocalResourcePackServer() {
        try {
            File resourceDir = worldResourcePackFolder;
            // 读取端口配置
            int port = this.httpPort;
            boolean started = false;
            while (port < 65535) {
                try {
                    localHttpServer = HttpServer.create(new InetSocketAddress(port), 0);
                    started = true;
                    break;
                } catch (IOException e) {
                    port++;
                }
            }
            if (!started) {
                getLogger().warning("无法启动本地HTTP服务，所有端口均被占用。");
                return;
            }
            if (port != this.httpPort) {
                getLogger().warning("配置端口 " + this.httpPort + " 被占用，已自动切换到可用端口 " + port);
                this.httpPort = port;
            }

            localHttpServer.createContext("/resourcepacks/", exchange -> {
                String uriPath = exchange.getRequestURI().getPath();
                String fileName = uriPath.substring("/resourcepacks/".length());
                File file = new File(resourceDir, fileName);
                if (file.exists() && file.isFile()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, file.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        Files.copy(file.toPath(), os);
                    }
                } else {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
            });
            localHttpServer.setExecutor(null);
            localHttpServer.start();

            // 检测所有本机IPv4地址
            List<String> ipList = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets.hasMoreElements()) {
                    NetworkInterface netint = nets.nextElement();
                    if (!netint.isUp() || netint.isLoopback() || netint.isVirtual()) continue;
                    Enumeration<InetAddress> addrs = netint.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            ipList.add(addr.getHostAddress());
                        }
                    }
                }
            } catch (SocketException e) {
                getLogger().warning("无法检测本机IP: " + e.getMessage());
            }
            detectedUrls.clear();
            detectedUrls.add("http://127.0.0.1:" + port + "/resourcepacks/");
            for (String ip : ipList) {
                detectedUrls.add("http://" + ip + ":" + port + "/resourcepacks/");
            }

            // 检测公网IP
            detectedPublicUrl = null;
            final int finalPort = port; // 修复lambda闭包问题
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    URL url = new URL("https://api.ipify.org");
                    try (InputStream in = url.openStream(); Scanner scanner = new Scanner(in)) {
                        if (scanner.hasNextLine()) {
                            String publicIp = scanner.nextLine().trim();
                            detectedPublicUrl = "http://" + publicIp + ":" + finalPort + "/resourcepacks/";
                            getLogger().info("检测到公网IP，推荐公网访问URL（如已开放端口）：" + detectedPublicUrl);
                        }
                    }
                } catch (Exception e) {
                    getLogger().info("公网IP检测失败：" + e.getMessage());
                }
            });

        } catch (Exception e) {
            getLogger().warning("本地资源包HTTP服务启动失败: " + e.getMessage());
        }
    }
}
