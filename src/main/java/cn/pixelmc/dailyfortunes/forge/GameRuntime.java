package cn.pixelmc.dailyfortunes.forge;

import static cn.pixelmc.dailyfortunes.service.FortuneBackend.*;

import cn.pixelmc.dailyfortunes.DailyFortunes;
import cn.pixelmc.dailyfortunes.service.FortuneBackend;
import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod.EventBusSubscriber(modid = DailyFortunes.ID)
public final class GameRuntime {
  private static GameRuntime current;
  private final MinecraftServer server;
  private final Set<UUID> registered = new HashSet<>(), busy = new HashSet<>();
  private final Map<UUID, Long> next = new HashMap<>(), rates = new HashMap<>();

  private record Session(String dimension, BlockPos pos) {}

  private final Map<UUID, Session> sessions = new HashMap<>();
  private final ThreadPoolExecutor io =
      new ThreadPoolExecutor(
          1,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          r -> {
            Thread t = new Thread(r, "DailyFortunes-IO");
            t.setDaemon(true);
            return t;
          });
  private FortuneBackend backend;
  private boolean remote = true, stopped;
  private long tickAt;

  private GameRuntime(MinecraftServer server) {
    this.server = server;
  }

  @SubscribeEvent
  public static void start(ServerStartedEvent e) {
    current = new GameRuntime(e.getServer());
    current.initialize();
  }

  private void initialize() {
    try {
      Path dir = FMLPaths.CONFIGDIR.get().resolve("dailyfortunes");
      Files.createDirectories(dir);
      Path path = dir.resolve("server.json");
      if (!Files.exists(path))
        atomic(
            path,
            JSON.toJson(
                obj(
                    "mode",
                    "auto",
                    "database",
                    obj(
                        "url",
                        "jdbc:mariadb://127.0.0.1:3306/dailyfortunes",
                        "user",
                        "dailyfortunes",
                        "password",
                        "CHANGE_ME"))));
      JsonObject config = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      String mode = s(config, "mode");
      boolean embedded =
          !server.isDedicatedServer() && (mode.equals("auto") || mode.equals("local"));
      remote = !embedded && !mode.equals("database");
      if (embedded) {
        backend =
            new FortuneBackend(
                "jdbc:h2:file:"
                    + dir.resolve("local-data/fortunes")
                        .toAbsolutePath()
                        .toString()
                        .replace('\\', '/')
                    + ";MODE=MySQL;NON_KEYWORDS=DAY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
                "sa",
                "",
                dir);
      } else if (remote) {
        Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi")
            .getMethod("register", String.class, String.class, String.class, String.class)
            .invoke(null, "dailyfortunes", "DailyFortunes", "0.1.0", "每日抽签,玩家签历,永久收藏,解签龛");
      } else {
        JsonObject db = config.getAsJsonObject("database");
        backend = new FortuneBackend(s(db, "url"), s(db, "user"), s(db, "password"), dir);
      }
    } catch (Exception e) {
      System.err.println(
          "[DailyFortunes] 配置/Manager桥接未就绪，抽签暂不可用；请检查 config/dailyfortunes/server.json。");
    }
  }

  @SubscribeEvent
  public static void stopping(ServerStoppingEvent e) {
    if (current == null) return;
    current.stopped = true;
    current.io.shutdown();
    if (current.backend != null) current.backend.close();
    current = null;
  }

  private static boolean real(ServerPlayer p) {
    String type = p.getClass().getName().toLowerCase(Locale.ROOT);
    return !(p instanceof FakePlayer)
        && !type.contains("fakeplayer")
        && !type.contains("playerfake")
        && !type.contains("entityplayerfake");
  }

  @SubscribeEvent
  public static void login(PlayerEvent.PlayerLoggedInEvent e) {
    if (current != null && e.getEntity() instanceof ServerPlayer p && real(p))
      current.next.put(p.getUUID(), System.currentTimeMillis() + 10000);
  }

  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
    if (current != null) {
      UUID id = e.getEntity().getUUID();
      current.registered.remove(id);
      current.busy.remove(id);
      current.next.remove(id);
      current.sessions.remove(id);
      current.rates.remove(id);
    }
  }

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent e) {
    GameRuntime r = current;
    if (r == null || e.phase != TickEvent.Phase.END) return;
    long now = System.currentTimeMillis();
    if (now < r.tickAt) return;
    r.tickAt = now + 1000;
    for (ServerPlayer p : r.server.getPlayerList().getPlayers()) {
      if (!real(p)
          || r.busy.contains(p.getUUID())
          || now < r.next.getOrDefault(p.getUUID(), now + 10000)) continue;
      r.next.put(p.getUUID(), now + 5000);
      if (!r.registered.contains(p.getUUID()))
        r.run(
            p,
            () -> r.call(p, "login", p.getGameProfile().getName()),
            v -> r.registered.add(p.getUUID()),
            false);
      else
        r.run(
            p,
            () -> r.call(p, "pending"),
            v -> {
              for (JsonElement record : v.getAsJsonArray("records"))
                r.deliver(p, record.getAsJsonObject());
            },
            false);
    }
  }

  private CompletableFuture<JsonObject> call(ServerPlayer p, String op, Object... args) {
    JsonArray a = new JsonArray();
    a.add(p.getUUID().toString());
    for (Object v : args) a.add(JSON.toJsonTree(v));
    JsonObject data = obj("args", a);
    if (remote) {
      try {
        Class<?> api = Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi");
        @SuppressWarnings("unchecked")
        CompletableFuture<String> f =
            (CompletableFuture<String>)
                api.getMethod("call", String.class, String.class, String.class)
                    .invoke(null, "dailyfortunes", op, JSON.toJson(data));
        return f.thenApply(j -> JsonParser.parseString(j).getAsJsonObject());
      } catch (Exception e) {
        return CompletableFuture.failedFuture(new IllegalStateException("Manager 未连接，稍后再试"));
      }
    }
    if (backend == null)
      return CompletableFuture.failedFuture(new IllegalStateException("抽签数据库未配置"));
    Set<UUID> admitted = Set.of(p.getUUID());
    try {
      return CompletableFuture.supplyAsync(
          () -> backend.rpc(op, data, p.getUUID().toString(), admitted), io);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(new IllegalStateException("抽签服务繁忙，请稍后重试"));
    }
  }

  @FunctionalInterface
  private interface Operation {
    CompletableFuture<JsonObject> get();
  }

  private void run(ServerPlayer p, Operation job, Consumer<JsonObject> done, boolean report) {
    UUID id = p.getUUID();
    if (stopped || !real(p) || busy.contains(id)) {
      if (report) message(p, "操作处理中，请稍后再试");
      return;
    }
    busy.add(id);
    try {
      job.get()
          .orTimeout(20, TimeUnit.SECONDS)
          .whenComplete(
              (value, error) ->
                  server.execute(
                      () -> {
                        busy.remove(id);
                        if (stopped || server.getPlayerList().getPlayer(id) != p) return;
                        if (error != null) {
                          if (report) message(p, safeError(error));
                          return;
                        }
                        try {
                          done.accept(value);
                        } catch (Exception ex) {
                          if (report) message(p, "数据暂不可用，请稍后重试");
                        }
                      }));
    } catch (Exception e) {
      busy.remove(id);
      if (report) message(p, "抽签服务暂不可用");
    }
  }

  private static String safeError(Throwable e) {
    while (e instanceof CompletionException && e.getCause() != null) e = e.getCause();
    if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
      String m = e.getMessage();
      if (m != null && !m.contains("jdbc:") && m.length() < 160) return m;
    }
    return "抽签服务暂不可用；资格和原签会保留，请稍后重试。";
  }

  private static void message(ServerPlayer p, String text) {
    p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
  }

  private static GameRuntime ready(ServerPlayer p) {
    GameRuntime r = current;
    if (r == null || !r.registered.contains(p.getUUID())) {
      message(p, "签历正在加载，请稍后再试。");
      return null;
    }
    return r;
  }

  public static void draw(ServerPlayer p) {
    GameRuntime r = ready(p);
    if (r == null) return;
    if (p.getInventory().getFreeSlot() < 0) {
      message(p, "请先留出一个背包空位。");
      return;
    }
    r.run(
        p,
        () -> r.call(p, "draw"),
        v -> {
          r.deliver(p, v.getAsJsonObject("record"));
          message(p, "已得一签，持签前往解签龛解读。");
        },
        true);
  }

  private void deliver(ServerPlayer p, JsonObject record) {
    String token = s(record, "token");
    for (int n = 0; n < p.getInventory().getContainerSize(); n++) {
      JsonObject old = FortuneItem.receipt(p.getInventory().getItem(n));
      if (old != null && s(old, "token").equals(token)) {
        p.getInventory().setItem(n, FortuneItem.stack(record));
        p.inventoryMenu.broadcastChanges();
        ack(p, record);
        return;
      }
    }
    if (p.getInventory().add(FortuneItem.stack(record))) {
      p.inventoryMenu.broadcastChanges();
      ack(p, record);
    }
  }

  private void ack(ServerPlayer p, JsonObject record) {
    call(p, "ack", s(record, "date"), s(record, "token")).exceptionally(e -> null);
  }

  public static void resolve(ServerPlayer p, ItemStack item) {
    GameRuntime r = ready(p);
    if (r == null) return;
    JsonObject receipt = FortuneItem.receipt(item);
    if (receipt == null || !item.is(DailyFortunes.UNREAD.get())) {
      message(p, "请手持自己的未解之签。");
      return;
    }
    if (!s(receipt, "player").equals(p.getUUID().toString())) {
      message(p, "这支签不属于你。");
      return;
    }
    r.run(
        p,
        () -> r.call(p, "resolve", s(receipt, "date"), s(receipt, "token")),
        v -> {
          JsonObject resolved = v.getAsJsonObject("record");
          r.deliver(p, resolved);
          FortuneNetwork.send(p, "read", JSON.toJson(resolved));
        },
        true);
  }

  public static void read(ServerPlayer p, ItemStack item) {
    GameRuntime r = ready(p);
    if (r == null) return;
    JsonObject receipt = FortuneItem.receipt(item);
    if (receipt == null) return;
    r.run(
        p,
        () -> r.call(p, "read", s(receipt, "date"), s(receipt, "token")),
        v -> FortuneNetwork.send(p, "read", JSON.toJson(v.get("record"))),
        true);
  }

  public static void open(ServerPlayer p, BlockPos pos) {
    GameRuntime r = ready(p);
    if (r == null) return;
    r.sessions.put(
        p.getUUID(), new Session(p.level().dimension().location().toString(), pos.immutable()));
    List<JsonObject> items = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
      ItemStack stack = p.getInventory().getItem(slot);
      JsonObject record = FortuneItem.receipt(stack);
      if (stack.is(DailyFortunes.SLIP.get())
          && record != null
          && s(record, "player").equals(p.getUUID().toString())
          && seen.add(s(record, "token"))) items.add(record);
    }
    java.util.concurrent.atomic.AtomicInteger failures =
        new java.util.concurrent.atomic.AtomicInteger();
    r.run(
        p,
        () -> {
          CompletableFuture<JsonObject> f = CompletableFuture.completedFuture(obj());
          for (JsonObject record : items)
            f =
                f.thenCompose(
                    unused ->
                        r.call(p, "archive", s(record, "date"), s(record, "token"))
                            .handle(
                                (result, error) -> {
                                  if (error != null) failures.incrementAndGet();
                                  if (error == null)
                                    r.server.execute(
                                        () -> {
                                          if (r.server.getPlayerList().getPlayer(p.getUUID()) == p)
                                            r.consume(p, record);
                                        });
                                  return obj();
                                }));
          return f.thenCompose(unused -> r.call(p, "calendar", ""));
        },
        v -> {
          if (failures.get() > 0) message(p, "部分签笺暂未存入，实物已保留；请稍后再打开藏签匣。");
          r.calendar(p, v);
        },
        true);
  }

  private void consume(ServerPlayer p, JsonObject receipt) {
    for (int n = 0; n < p.getInventory().getContainerSize(); n++) {
      ItemStack item = p.getInventory().getItem(n);
      JsonObject old = FortuneItem.receipt(item);
      if (old != null
          && s(old, "token").equals(s(receipt, "token"))
          && s(old, "player").equals(p.getUUID().toString()))
        p.getInventory().setItem(n, ItemStack.EMPTY);
    }
    p.inventoryMenu.broadcastChanges();
  }

  private ChestEntity session(ServerPlayer p) {
    Session s = sessions.get(p.getUUID());
    if (s == null
        || !s.dimension.equals(p.level().dimension().location().toString())
        || p.distanceToSqr(s.pos.getX() + .5, s.pos.getY() + .5, s.pos.getZ() + .5) > 64
        || !(p.level().getBlockEntity(s.pos) instanceof ChestEntity chest))
      throw new IllegalArgumentException("请靠近藏签匣重新打开");
    return chest;
  }

  private void calendar(ServerPlayer p, JsonObject v) {
    ChestEntity chest = session(p);
    v.addProperty("canDisplay", p.getUUID().equals(chest.owner));
    FortuneNetwork.send(p, "calendar", JSON.toJson(v));
  }

  public static void request(ServerPlayer p, String action, String value) {
    GameRuntime r = ready(p);
    if (r == null) return;
    long now = System.currentTimeMillis();
    if (now < r.rates.getOrDefault(p.getUUID(), 0L)) return;
    r.rates.put(p.getUUID(), now + 250);
    try {
      ChestEntity chest = r.session(p);
      if (action.equals("calendar")) {
        YearMonth month = YearMonth.parse(value);
        if (month.getYear() < 1970 || month.getYear() > 2200)
          throw new IllegalArgumentException("月份超出范围");
        r.run(p, () -> r.call(p, "calendar", value), v -> r.calendar(p, v), true);
      } else if (action.equals("display") || action.equals("clear")) {
        if (!p.getUUID().equals(chest.owner)) throw new IllegalArgumentException("只有放置者可以选择展示签笺");
        if (action.equals("clear")) {
          chest.display = "";
          chest.changed();
          return;
        }
        r.run(
            p,
            () -> r.call(p, "display", value),
            v -> {
              ChestEntity live = r.session(p);
              if (!p.getUUID().equals(live.owner)) return;
              JsonObject display = v.getAsJsonObject("record");
              display.addProperty("hover", b(v.getAsJsonObject("settings"), "hover"));
              live.display = JSON.toJson(display);
              live.changed();
              message(p, "已更新藏签匣的展示签笺。");
            },
            true);
      } else if (action.equals("recover")) {
        LocalDate.parse(value);
        r.run(
            p,
            () -> r.call(p, "recover", value, UUID.randomUUID().toString()),
            v -> {
              r.deliver(p, v.getAsJsonObject("record"));
              message(p, "已补领原签，旧副本已失效。");
            },
            true);
      }
    } catch (Exception e) {
      message(p, safeError(e));
    }
  }

  @SubscribeEvent
  public static void commands(RegisterCommandsEvent e) {
    e.getDispatcher()
        .register(
            Commands.literal("df")
                .executes(
                    c -> {
                      message(
                          c.getSource().getPlayerOrException(),
                          "手持签筒右键抽签；在解签龛解签，打开藏签匣收藏。/df recover yyyy-MM-dd 补领原签");
                      return 1;
                    })
                .then(
                    Commands.literal("recover")
                        .then(
                            Commands.argument("date", StringArgumentType.word())
                                .executes(
                                    c -> {
                                      ServerPlayer p = c.getSource().getPlayerOrException();
                                      GameRuntime r = ready(p);
                                      if (r == null) return 0;
                                      String date = StringArgumentType.getString(c, "date");
                                      try {
                                        LocalDate.parse(date);
                                      } catch (Exception ex) {
                                        message(p, "日期格式应为 yyyy-MM-dd");
                                        return 0;
                                      }
                                      r.run(
                                          p,
                                          () ->
                                              r.call(
                                                  p, "recover", date, UUID.randomUUID().toString()),
                                          v -> {
                                            r.deliver(p, v.getAsJsonObject("record"));
                                            message(p, "已补领原签，旧副本已失效。");
                                          },
                                          true);
                                      return 1;
                                    }))));
  }
}
