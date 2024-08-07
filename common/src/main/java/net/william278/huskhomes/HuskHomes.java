/*
 * This file is part of HuskHomes, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.huskhomes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.william278.desertwell.util.ThrowingConsumer;
import net.william278.desertwell.util.UpdateChecker;
import net.william278.desertwell.util.Version;
import net.william278.huskhomes.command.Command;
import net.william278.huskhomes.config.ConfigProvider;
import net.william278.huskhomes.config.Server;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.database.Database;
import net.william278.huskhomes.event.EventDispatcher;
import net.william278.huskhomes.hook.*;
import net.william278.huskhomes.importer.Importer;
import net.william278.huskhomes.manager.Manager;
import net.william278.huskhomes.network.Broker;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.random.RandomTeleportEngine;
import net.william278.huskhomes.user.ConsoleUser;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.user.SavedUser;
import net.william278.huskhomes.user.User;
import net.william278.huskhomes.util.*;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a cross-platform instance of the plugin.
 */
public interface HuskHomes extends Task.Supplier, EventDispatcher, SafetyResolver, TransactionResolver, ConfigProvider {

    /**
     * The spigot resource ID, used for update checking.
     */
    int SPIGOT_RESOURCE_ID = 83767;

    /**
     * Get the user representing the server console.
     *
     * @return the {@link ConsoleUser}
     */
    @NotNull
    ConsoleUser getConsole();

    /**
     * The {@link Set} of online {@link OnlineUser}s on this server.
     *
     * @return a {@link Set} of currently online {@link OnlineUser}s
     */
    @NotNull
    List<OnlineUser> getOnlineUsers();


    /**
     * Get the adventure {@link Audience} for a given {@link UUID}.
     *
     * @param user The {@link UUID} of the user to get the {@link Audience} for.
     * @return The {@link Audience} for the given {@link UUID}.
     */
    @NotNull
    Audience getAudience(@NotNull UUID user);

    /**
     * Finds a local {@link OnlineUser} by their name. Auto-completes partially typed names for the closest match
     *
     * @param playerName the name of the player to find
     * @return an {@link Optional} containing the {@link OnlineUser} if found, or an empty {@link Optional} if not found
     */
    default Optional<OnlineUser> getOnlineUser(@NotNull String playerName) {
        return getOnlineUserExact(playerName)
                .or(() -> getOnlineUsers().stream()
                        .filter(user -> user.getUsername().toLowerCase().startsWith(playerName.toLowerCase()))
                        .findFirst());
    }

    /**
     * Finds a local {@link OnlineUser} by their name.
     *
     * @param playerName the name of the player to find
     * @return an {@link Optional} containing the {@link OnlineUser} if found, or an empty {@link Optional} if not found
     */
    default Optional<OnlineUser> getOnlineUserExact(@NotNull String playerName) {
        return getOnlineUsers().stream()
                .filter(user -> user.getUsername().equalsIgnoreCase(playerName))
                .findFirst();
    }

    @NotNull
    Set<SavedUser> getSavedUsers();

    default Optional<SavedUser> getSavedUser(@NotNull User user) {
        return getSavedUsers().stream()
                .filter(savedUser -> savedUser.getUser().equals(user))
                .findFirst();
    }

    default void editUserData(@NotNull User user, @NotNull Consumer<SavedUser> editor) {
        runAsync(() -> getSavedUser(user)
                .ifPresent(result -> {
                    editor.accept(result);
                    getDatabase().updateUserData(result);
                }));
    }

    /**
     * Initialize a faucet of the plugin.
     *
     * @param name   the name of the faucet
     * @param runner a runnable for initializing the faucet
     */
    default void initialize(@NotNull String name, @NotNull ThrowingConsumer<HuskHomes> runner) {
        log(Level.INFO, "Initializing " + name + "...");
        try {
            runner.accept(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize " + name, e);
        }
        log(Level.INFO, "Successfully initialized " + name);
    }

    /**
     * The canonical spawn {@link Position} of this server, if it has been set.
     *
     * @return the {@link Position} of the spawn, or an empty {@link Optional} if it has not been set
     */
    default Optional<Position> getSpawn() {
        final Settings.CrossServerSettings crossServer = getSettings().getCrossServer();
        return crossServer.isEnabled() && crossServer.getGlobalSpawn().isEnabled()
                ? getDatabase().getWarp(crossServer.getGlobalSpawn().getWarpName()).map(warp -> (Position) warp)
                : getServerSpawn().map(spawn -> spawn.getPosition(getServerName()));
    }

    /**
     * Update the spawn position of a world on the server.
     *
     * @param position The new spawn world and coordinates.
     */
    void setWorldSpawn(@NotNull Position position);

    /**
     * Returns the {@link Server} the plugin is on.
     *
     * @return The {@link Server} object
     */
    @NotNull
    String getServerName();

    void setServer(@NotNull Server server);

    void setUnsafeBlocks(@NotNull UnsafeBlocks unsafeBlocks);

    @NotNull
    UnsafeBlocks getUnsafeBlocks();

    /**
     * The {@link Database} that store persistent plugin data.
     *
     * @return the {@link Database} implementation for accessing data
     */
    @NotNull
    Database getDatabase();

    /**
     * The {@link Validator} for validating home names and descriptions.
     *
     * @return the {@link Validator} instance
     */
    @NotNull
    Validator getValidator();

    /**
     * The {@link Manager} that manages home, warp and user data.
     *
     * @return the {@link Manager} implementation
     */
    @NotNull
    Manager getManager();

    /**
     * The {@link Broker} that sends cross-network messages.
     *
     * @return the {@link Broker} implementation
     */
    @NotNull
    Broker getMessenger();

    /**
     * The {@link RandomTeleportEngine} that manages random teleports.
     *
     * @return the {@link RandomTeleportEngine} implementation
     */
    @NotNull
    RandomTeleportEngine getRandomTeleportEngine();

    /**
     * Sets the {@link RandomTeleportEngine} to be used for processing random teleports.
     *
     * @param randomTeleportEngine the {@link RandomTeleportEngine} to use
     */
    void setRandomTeleportEngine(@NotNull RandomTeleportEngine randomTeleportEngine);

    /**
     * Set of active {@link Hook}s running on the server.
     *
     * @return the {@link Set} of active {@link Hook}s
     */
    @NotNull
    List<Hook> getHooks();

    void setHooks(@NotNull List<Hook> hooks);

    default <T extends Hook> Optional<T> getHook(@NotNull Class<T> hookClass) {
        return getHooks().stream()
                .filter(hook -> hookClass.isAssignableFrom(hook.getClass()))
                .map(hookClass::cast)
                .findFirst();
    }

    default Optional<MapHook> getMapHook() {
        return getHook(MapHook.class);
    }

    @NotNull
    default List<Importer> getImporters() {
        return getHooks().stream()
                .filter(hook -> Importer.class.isAssignableFrom(hook.getClass()))
                .map(Importer.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Returns a resource read from the plugin resources folder.
     *
     * @param name the name of the resource
     * @return the resource read as an {@link InputStream}
     */
    @Nullable
    InputStream getResource(@NotNull String name);

    /**
     * Returns a list of worlds on the server.
     *
     * @return a list of worlds on the server
     */
    @NotNull
    List<World> getWorlds();

    /**
     * Returns the plugin version.
     *
     * @return the plugin {@link Version}
     */
    @NotNull
    Version getVersion();

    /**
     * Returns a list of enabled commands.
     *
     * @return A list of registered and enabled {@link Command}s
     */
    @NotNull
    List<Command> getCommands();

    default <T extends Command> Optional<T> getCommand(@NotNull Class<T> type) {
        return getCommands().stream()
                .filter(command -> command.getClass() == type)
                .findFirst()
                .map(type::cast);
    }

    default void registerHooks() {
        setHooks(new ArrayList<>());

        if (getSettings().getMapHook().isEnabled()) {
            if (isDependencyLoaded("Dynmap")) {
                getHooks().add(new DynmapHook(this));
            } else if (isDependencyLoaded("BlueMap")) {
                getHooks().add(new BlueMapHook(this));
            } else if (isDependencyLoaded("Pl3xMap")) {
                getHooks().add(new Pl3xMapHook(this));
            }
        }
        if (isDependencyLoaded("Plan")) {
            getHooks().add(new PlanHook(this));
        }
    }

    default void registerImporters() {
    }

    boolean isDependencyLoaded(@NotNull String name);

    @NotNull
    Map<String, List<String>> getGlobalPlayerList();

    default List<String> getPlayerList(boolean includeVanished) {
        return Stream.concat(
                getGlobalPlayerList().values().stream().flatMap(Collection::stream),
                getLocalPlayerList(includeVanished).stream()
        ).distinct().sorted().toList();
    }

    @NotNull
    @SuppressWarnings("unused")
    default List<String> getPlayerList() {
        return getPlayerList(true);
    }

    default void setPlayerList(@NotNull String server, @NotNull List<String> players) {
        getGlobalPlayerList().values().forEach(list -> {
            list.removeAll(players);
            list.removeAll(getLocalPlayerList());
        });
        getGlobalPlayerList().put(server, players);
    }

    @NotNull
    default List<String> getLocalPlayerList(boolean includeVanished) {
        return getOnlineUsers().stream()
                .filter(user -> includeVanished || !user.isVanished())
                .map(OnlineUser::getUsername)
                .toList();
    }

    default List<String> getLocalPlayerList() {
        return getLocalPlayerList(true);
    }

    @NotNull
    Set<UUID> getCurrentlyOnWarmup();

    /**
     * Returns if the given user is currently warming up to teleport to a home.
     *
     * @param userUuid The user to check.
     * @return If the user is currently warming up.
     */
    default boolean isWarmingUp(@NotNull UUID userUuid) {
        return this.getCurrentlyOnWarmup().contains(userUuid);
    }

    @NotNull
    Set<UUID> getCurrentlyInvulnerable();

    /**
     * Returns if the given user is currently invulnerable and if it should be removed.
     *
     * @param uuid the user to check.
     * @return if the user is currently invulnerable.
     */
    default boolean isInvulnerable(@NotNull UUID uuid) {
        return this.getCurrentlyInvulnerable().contains(uuid);
    }

    @NotNull
    default UpdateChecker getUpdateChecker() {
        return UpdateChecker.builder()
                .currentVersion(getVersion())
                .endpoint(UpdateChecker.Endpoint.SPIGOT)
                .resource(Integer.toString(SPIGOT_RESOURCE_ID))
                .build();
    }

    default void checkForUpdates() {
        if (getSettings().isCheckForUpdates()) {
            getUpdateChecker().check().thenAccept(checked -> {
                if (!checked.isUpToDate()) {
                    log(Level.WARNING, "A new version of HuskHomes is available: v"
                            + checked.getLatestVersion() + " (running v" + getVersion() + ")");
                }
            });
        }
    }

    /**
     * Registers the plugin with bStats metrics.
     *
     * @param metricsId the bStats id for the plugin
     */
    void registerMetrics(int metricsId);

    /**
     * Initialize plugin messaging channels.
     */
    void initializePluginChannels();

    /**
     * Log a message to the console.
     *
     * @param level      the level to log at
     * @param message    the message to log
     * @param exceptions any exceptions to log
     */
    void log(@NotNull Level level, @NotNull String message, Throwable... exceptions);

    /**
     * Create a resource key namespaced with the plugin id.
     *
     * @param data the string ID elements to join
     * @return the key
     */
    @NotNull
    default Key getKey(@NotNull String... data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("Cannot create a key with no data");
        }
        @Subst("foo") final String joined = String.join("/", data);
        return Key.key("huskhomes", joined);
    }

    @NotNull
    default Gson getGson() {
        return new GsonBuilder().create();
    }

}
