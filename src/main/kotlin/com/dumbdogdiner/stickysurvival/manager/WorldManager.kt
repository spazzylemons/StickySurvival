/*
 * StickySurvival - an implementation of the Survival Games minigame
 * Copyright (C) 2020 Dumb Dog Diner <dumbdogdiner.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.dumbdogdiner.stickysurvival.manager

import com.dumbdogdiner.stickysurvival.Game
import com.dumbdogdiner.stickysurvival.LobbyHologram
import com.dumbdogdiner.stickysurvival.StickySurvival
import com.dumbdogdiner.stickysurvival.config.Config
import com.dumbdogdiner.stickysurvival.config.ConfigHelper
import com.dumbdogdiner.stickysurvival.util.goToLobby
import com.dumbdogdiner.stickysurvival.util.newWeakSet
import com.dumbdogdiner.stickysurvival.util.schedule
import com.dumbdogdiner.stickysurvival.util.settings
import com.dumbdogdiner.stickysurvival.util.waitFor
import com.dumbdogdiner.stickysurvival.util.warn
import com.dumbdogdiner.stickysurvival.util.worlds
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import java.util.Properties
import java.util.WeakHashMap

object WorldManager {
    private fun String.active() = "$this-active"

    // enforces a cooldown period to prevent players from overloading the server.
    private val lastJoinTimes = WeakHashMap<Player, Long>()

    // currently loaded games
    private val loadedGames = mutableSetOf<Game>()

    // map of holograms to the name of the world they're for
    private val holograms = mutableSetOf<LobbyHologram>()

    private val waitingToJoin = newWeakSet<Player>()

    // Get the default/lobby world. We first attempt to load this from the server.properties file, and if that fails for
    // whatever reason, assume that the first world loaded is the lobby world. I think that's a safe assumption
    // especially since there's no reason to run nether/end on a minigame server.
    val lobbyWorld: World = Bukkit.getWorldContainer().resolve("server.properties").reader().use { reader ->
        val properties = Properties().apply { load(reader) }
        val levelName = properties["level-name"] as? String
        levelName?.let(Bukkit::getWorld) ?: Bukkit.getWorlds().first()
    }

    fun playerJoinCooldownExists(player: Player) = when (val lastJoinTime = lastJoinTimes[player]) {
        null -> false
        else -> System.currentTimeMillis() - lastJoinTime < settings.joinCooldown * 1000
    }

    fun getHolograms() = holograms

    fun getLoadedGame(worldName: String) = loadedGames.find {
        it.world.name == worldName.active()
    }

    fun isPlayerWaitingToJoin(player: Player) = player in waitingToJoin

    fun putPlayerInWorldNamed(player: Player, worldName: String): Boolean {
        if (isPlayerWaitingToJoin(player))
            throw IllegalStateException("Player ${player.name} is already waiting to join")
        if (playerJoinCooldownExists(player))
            throw IllegalStateException("Player ${player.name} has a cooldown in effect")
        try {
            waitingToJoin += player
            val game = getGameForWorldNamed(worldName)
                ?: throw IllegalStateException("Player ${player.name} could not be put in $worldName")
            var result = null as Boolean?
            schedule { result = game.addPlayer(player) }
            if (waitFor { result }) {
                lastJoinTimes[player] = System.currentTimeMillis()
            } else {
                return false
            }
        } finally {
            waitingToJoin -= player
        }

        return true
    }

    private fun getGameForWorldNamed(name: String): Game? {
        if (getLoadedGame(name) == null) {
            try {
                loadWorldNamed(name)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        return getLoadedGame(name)
    }

    private fun loadWorldNamed(worldName: String) {
        val config = worlds[worldName] ?: throw IllegalArgumentException("$worldName is not a registered world")
        val nameActive = "$worldName-active"
        copyWorldFolder(worldName, nameActive)
        var world = null as World?
        schedule {
            world = Bukkit.createWorld(
                WorldCreator.name(nameActive)
            ) ?: throw IllegalStateException("World for $worldName couldn't be made into an arena")
        }
        val world0 = waitFor { world }
        val hologram = holograms.find {
            it.worldName == worldName
        } ?: throw IllegalStateException("World $worldName is missing hologram")
        loadedGames += Game(world0, config, hologram)
    }

    fun unloadGame(game: Game) {
        val world = game.world
        val name = world.name

        for (player in world.players) {
            player.goToLobby()
        }

        Bukkit.unloadWorld(world, false)
        deleteWorldFolder(name)

        AnimatedScoreboardManager.removeWorld(name)
        loadedGames -= game
    }

    fun unloadAll() {
        for (game in loadedGames) {
            unloadGame(game)
        }

        for (hologram in holograms) {
            hologram.cleanup()
        }

        loadedGames.clear()
    }

    fun loadFromConfig() {
        unloadAll()

        Config.current = try {
            Config(ConfigHelper(StickySurvival.defaultConfig, StickySurvival.instance.config))
        } catch (e: Exception) {
            e.printStackTrace()
            warn("Invalid rule config; using default rules.")
            Config.default
        }

        holograms.addAll(
            worlds.keys.map {
                LobbyHologram(it)
            }
        )
    }

    private fun copyWorldFolder(from: String, to: String) {
        val worldsFolder = Bukkit.getWorldContainer()
        val baseWorldFolder = worldsFolder.resolve(from)
        baseWorldFolder.copyRecursively(worldsFolder.resolve(to), overwrite = true)
    }

    private fun deleteWorldFolder(name: String) {
        val worldsFolder = Bukkit.getWorldContainer()
        worldsFolder.resolve(name).deleteRecursively()
    }

    fun getGameForWorld(world: World): Game? {
        return loadedGames.find { world == it.world }
    }
}
