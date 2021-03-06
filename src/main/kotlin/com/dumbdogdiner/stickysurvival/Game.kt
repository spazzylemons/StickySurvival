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

package com.dumbdogdiner.stickysurvival

import com.destroystokyo.paper.Title
import com.dumbdogdiner.stickysurvival.config.KitConfig
import com.dumbdogdiner.stickysurvival.config.WorldConfig
import com.dumbdogdiner.stickysurvival.manager.AnimatedScoreboardManager
import com.dumbdogdiner.stickysurvival.manager.LobbyInventoryManager
import com.dumbdogdiner.stickysurvival.manager.StatsManager
import com.dumbdogdiner.stickysurvival.manager.WorldManager
import com.dumbdogdiner.stickysurvival.stats.PlayerStats
import com.dumbdogdiner.stickysurvival.task.AutoQuitRunnable
import com.dumbdogdiner.stickysurvival.task.ChestRefillRunnable
import com.dumbdogdiner.stickysurvival.task.RandomDropRunnable
import com.dumbdogdiner.stickysurvival.task.TimerRunnable
import com.dumbdogdiner.stickysurvival.util.broadcastMessage
import com.dumbdogdiner.stickysurvival.util.broadcastSound
import com.dumbdogdiner.stickysurvival.util.freeze
import com.dumbdogdiner.stickysurvival.util.hasJoinPermission
import com.dumbdogdiner.stickysurvival.util.info
import com.dumbdogdiner.stickysurvival.util.messages
import com.dumbdogdiner.stickysurvival.util.reset
import com.dumbdogdiner.stickysurvival.util.safeFormat
import com.dumbdogdiner.stickysurvival.util.settings
import com.dumbdogdiner.stickysurvival.util.spectate
import com.dumbdogdiner.stickysurvival.util.trackingStickKey
import com.dumbdogdiner.stickysurvival.util.trackingStickName
import com.dumbdogdiner.stickysurvival.util.warn
import com.google.common.collect.HashBiMap
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.WeakHashMap
import kotlin.math.roundToInt

class Game(val world: World, val config: WorldConfig, private val hologram: LobbyHologram) {
    enum class Phase { WAITING, ACTIVE, COMPLETE }

    private val noDamageTime = config.noDamageTime ?: settings.noDamageTime

    // Runnables
    private val timer = TimerRunnable(this)
    private val randomDrop = RandomDropRunnable(this)
    private val chestRefill = ChestRefillRunnable(this)
    private val autoQuit = AutoQuitRunnable(this)

    // Player metadata
    private val kills = WeakHashMap<Player, Int>()
    private val kits = WeakHashMap<Player, KitConfig>()

    private val freeSpawnPoints = config.spawnPoints.map { spawnPoint ->
        spawnPoint.clone().also { it.world = world }
    }.toMutableSet()
    private val usedSpawnPoints = WeakHashMap<Player, Location>()

    private val tributes = mutableSetOf<Player>()
    private val participants = mutableSetOf<Player>()

    val bossBar = Bukkit.createBossBar(null, BarColor.WHITE, BarStyle.SOLID)

    var refillCount = 1
    private val filledChests = mutableMapOf<Location, Int>()
    private val currentlyOpenChests = mutableSetOf<Location>()

    private val randomChests = HashBiMap.create<Location, Inventory>()

    // for debugging, trying to figure out why sometimes games end with zero players
    private val tributeLog = mutableListOf<String>()

    // specific values that require updating one or both displays

    var countdown = -1
        set(value) {
            field = value
            updateBossBar()
        }

    var winner = null as Player?
        private set(value) {
            field = value
            updateDisplays()
        }

    var phase = Phase.WAITING
        private set(value) {
            field = value
            updateDisplays()
        }

    var noDamage = true
        private set(value) {
            field = value
            updateBossBar()
        }

    init {
        AnimatedScoreboardManager.addWorld(world.name)
        // prepare proper settings for the world
        // note that game mode is not set here; game mode is per-server, not per-world
        // game modes are set when a player joins/leaves
        world.isAutoSave = false
        world.worldBorder.setCenter(config.center.x, config.center.z)
        updateBossBar()
    }

    private fun updateDisplays() {
        updateBossBar()
        hologram.update()
    }

    private fun updateBossBar() {
        fun <T : Number, U : Number> set(title: String, numerator: T, denominator: U, color: BarColor) {
            bossBar.setTitle(title)
            bossBar.progress = (numerator.toDouble() / denominator.toDouble()).coerceIn(0.0..1.0)
            bossBar.color = color
        }

        when (phase) {
            Phase.WAITING -> {
                if (tributes.size >= config.minPlayers) set(
                    messages.bossBar.countdown.safeFormat(countdown),
                    countdown,
                    settings.countdown,
                    BarColor.YELLOW
                ) else set(
                    messages.bossBar.waiting.safeFormat(config.minPlayers - tributes.size),
                    tributes.size,
                    config.minPlayers,
                    BarColor.RED
                )
            }
            Phase.ACTIVE -> {
                if (noDamage) set(
                    messages.bossBar.noDamage.safeFormat(countdown),
                    countdown,
                    noDamageTime,
                    BarColor.PURPLE
                ) else set(
                    messages.bossBar.active.safeFormat(countdown / 60, countdown % 60),
                    countdown,
                    config.time,
                    BarColor.GREEN
                )
            }
            Phase.COMPLETE -> {
                val winnerName = winner?.name
                if (winnerName == null) set(
                    messages.bossBar.draw,
                    1,
                    1,
                    BarColor.WHITE
                ) else set(
                    messages.bossBar.winner.safeFormat(winnerName),
                    1,
                    1,
                    BarColor.BLUE
                )
            }
        }
    }

    fun enableDamage() {
        noDamage = false
        countdown = config.time

        for (tribute in tributes) {
            tribute.isInvulnerable = false
        }

        world.broadcastMessage(messages.chat.damageEnabled)

        randomDrop.runTaskTimer(settings.randomChestInterval, settings.randomChestInterval)
    }

    fun addPlayer(player: Player): Boolean {
        if (!player.hasJoinPermission()) {
            return false // not allowed to join
        }

        if (phase != Phase.WAITING) {
            return player.teleport(config.center.clone().also { it.world = world }).also {
                if (it) {
                    LobbyInventoryManager.saveInventory(player)
                    player.spectate()
                }
            }
        }

        if (usedSpawnPoints.size >= config.maxPlayers) {
            return false // game is full
        }

        LobbyInventoryManager.saveInventory(player)
        givePlayerSpawnPoint(player)
        tributes += player
        player.freeze()

        bossBar.addPlayer(player)
        world.broadcastMessage(messages.chat.join.safeFormat(player.name))
        player.sendMessage(messages.chat.kitPrompt)

        if (tributes.size >= config.minPlayers && countdown == -1) {
            beginStartCountdown()
        }

        updateDisplays()
        return true
    }

    private fun givePlayerSpawnPoint(player: Player) {
        player.teleport(
            freeSpawnPoints.random().also {
                freeSpawnPoints -= it
                usedSpawnPoints += player to it
            }
        )
    }

    private fun takePlayerSpawnPoint(player: Player) {
        usedSpawnPoints.remove(player)?.let {
            freeSpawnPoints += it
        }
    }

    private fun beginStartCountdown() {
        countdown = settings.countdown
        timer.runTaskTimer(1, 1)
        world.broadcastMessage(messages.chat.countdown.safeFormat(settings.countdown))
        playCountdownClick()
    }

    fun playCountdownClick() {
        world.broadcastSound(Sound.BLOCK_NOTE_BLOCK_HAT, 1F, 1F)
    }

    fun startGame() {
        countdown = noDamageTime

        for (tribute in tributes) {
            participants += tribute
            tribute.reset(GameMode.SURVIVAL)
            kits[tribute]?.giveTo(tribute)
            tribute.isInvulnerable = true // no damage until noDamage is false
        }

        world.broadcastMessage(messages.chat.start.safeFormat(noDamageTime))
        world.broadcastSound(Vector(0, 20, 0), Sound.ENTITY_DONKEY_DEATH, 4F, 0.5F)

        if (config.chestRefill > 0) {
            chestRefill.runTaskTimer(config.chestRefill, config.chestRefill)
        }

        logTributes()

        phase = Phase.ACTIVE
    }

    fun onPlayerQuit(player: Player) {
        tributes -= player

        if (phase == Phase.WAITING) {
            takePlayerSpawnPoint(player)
        } else {
            logTributes()
        }

        bossBar.removePlayer(player)
        world.broadcastMessage(messages.chat.leave.safeFormat(player.name))

        if (phase == Phase.WAITING && tributes.size < config.minPlayers) {
            timer.safelyCancel()
            countdown = -1
            world.broadcastMessage(messages.chat.countdownCancelled)
        }

        if (world.players.none { it != player }) {
            close()
        } else {
            checkForWinner()
        }

        updateDisplays()
    }

    private fun close() {
        timer.safelyCancel()
        randomDrop.safelyCancel()
        autoQuit.safelyCancel()
        chestRefill.safelyCancel()
        WorldManager.unloadGame(this)
    }

    fun onPlayerDeath(player: Player) {
        val killerMessage = player.killer?.let {
            awardKillTo(it)
            messages.title.killer.safeFormat(it.name)
        }

        tributes -= player
        logTributes()

        player.spectate()

        for (item in player.inventory) {
            if (item != null && !item.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                world.dropItemNaturally(player.location, item)
            }
        }

        player.sendTitle(Title(messages.title.death, killerMessage))
        world.broadcastMessage(messages.chat.death.safeFormat(player.name))
        world.broadcastSound(Vector(0, 20, 0), Sound.ENTITY_GENERIC_EXPLODE, 4F, 0.75F)
        updateDisplays()

        if (getTributesLeft() == settings.trackingStickPlayersLeft) {
            world.broadcastMessage(messages.chat.trackingStickObtain)
            for (tribute in tributes) {
                tribute.inventory.addItem(settings.trackingStick)
            }
        }

        checkForWinner()
    }

    fun checkForWinner() {
        if (phase != Phase.ACTIVE) return

        when (getTributesLeft()) {
            1 -> {
                winner = tributes.first().also { lastTribute ->
                    world.broadcastMessage(messages.chat.winner.safeFormat(lastTribute.name))
                }
            }
            0 -> {
                world.broadcastMessage("${ChatColor.RED}Zero players are left. This shouldn't happen. Tell the devs about this!")
                world.broadcastMessage("${ChatColor.RED}Maybe this debug information will help:")
                for (log in tributeLog) {
                    world.broadcastMessage(log)
                }
            }
            else -> {
                return
            }
        }

        finalizeGame()
    }

    fun endAsDraw() {
        /*
        Something odd I noticed: In the third-party plugin's source code, if the game ends by a death and multiple
        players remain, then the cash reward is split equally amongst the remaining players*. However, I can't seem to
        think of a situation where this can happen. On a similar note, at the moment, I am not differentiating between
        losses due to a draw, and deaths. Should this be a concern to you, lmk

        * https://github.com/ShaneBeeStudios/HungerGames/blob/master/src/main/java/tk/shanebee/hg/game/Game.java#L316
         */

        world.broadcastMessage(messages.chat.draw)
        finalizeGame()
    }

    private fun finalizeGame() {
        autoQuit.runTaskLater(settings.resultsTime)
        randomDrop.safelyCancel()
        timer.safelyCancel()

        val winner0 = winner

        for (player in participants) {
            var (uuid, kills, wins, losses) = StatsManager[player]
            kills += killsFor(player)
            if (player == winner0) wins += 1 else losses += 1
            StatsManager[player] = PlayerStats(uuid, kills, wins, losses)
        }

        if (winner0 != null) {
            StickySurvival.economy?.depositPlayer(winner0, settings.reward)
            winner0.sendMessage(messages.chat.reward.safeFormat(settings.reward))
        }

        phase = Phase.COMPLETE
    }

    private fun maybeFill(location: Location) {
        if (filledChests[location]?.let { it >= refillCount } == true) return // chest needs no refills

        val chest = world.getBlockAt(location).state as Container

        val loot = when (chest.type) {
            in settings.bonusContainers -> settings.bonusLoot
            else -> settings.basicLoot
        }

        val inventories = if (chest is DoubleChest) {
            val inv = chest.getInventory() as DoubleChestInventory
            listOf(inv.leftSide, inv.rightSide)
        } else {
            listOf(chest.inventory)
        }

        for (inventory in inventories) {
            val loc = inventory.location ?: location
            var i = filledChests[loc] ?: 0
            var j = 0
            while (i < refillCount) {
                loot.insertItems(inventory)
                i += 1
                j += 1
            }
            info("Refilled @ $loc $j times")
            filledChests[loc] = refillCount
        }
    }

    fun openChest(location: Location) {
        currentlyOpenChests += location
        maybeFill(location)
    }

    fun closeChest(location: Location) {
        currentlyOpenChests -= location
    }

    fun fillOpenChests() {
        for (loc in currentlyOpenChests) {
            maybeFill(loc)
        }
    }

    fun getOrCreateRandomChestInventoryAt(location: Location) = randomChests[location] ?: run {
        val inv = Bukkit.createInventory(null, InventoryType.CHEST)
        settings.randomChestLoot.insertItems(inv)
        randomChests[location] = inv
        // ensure the chunk is loaded so the block can drop, unset force load when it does
        world.getChunkAt(location).isForceLoaded = true
        inv
    }

    fun destroyRandomChestInventory(inv: Inventory) {
        val location = randomChests.inverse()[inv] ?: return

        world.getBlockAt(location).type = Material.AIR
        for (slot in inv) {
            if (slot != null) {
                world.dropItemNaturally(location, slot)
            }
        }
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f)
        world.spawnParticle(Particle.EXPLOSION_NORMAL, location, 10)
        randomChests -= location
    }

    fun inventoryIsRandomChest(inv: Inventory) = randomChests.containsValue(inv)

    fun playerIsTribute(player: Player) = player in tributes

    fun getSpaceLeft() = (config.maxPlayers - usedSpawnPoints.size).let {
        if (it < 0) {
            warn("More players in a game in ${config.friendlyName} than allowed! Maximum: ${config.maxPlayers} Present: ${usedSpawnPoints.size}")
            0
        } else {
            it
        }
    }

    fun getTributesLeft() = tributes.size

    fun setKit(player: Player, kit: KitConfig) {
        kits[player] = kit
        player.sendMessage(messages.chat.kitSelect.safeFormat(kit.name))
    }

    fun removeKit(player: Player) {
        kits -= player
        player.sendMessage(messages.chat.kitRemove)
    }

    fun killsFor(player: Player) = kills[player] ?: 0

    private fun awardKillTo(player: Player) {
        kills[player] = killsFor(player) + 1
    }

    private fun logTributes() {
        tributeLog += "${tributes.joinToString()} at ${Bukkit.getServer().currentTick}"
    }

    fun useTrackingStick(player: Player, stick: ItemStack) {
        val uses = stick.itemMeta.persistentDataContainer.get(trackingStickKey, PersistentDataType.INTEGER) ?: return
        if (uses == 0) {
            player.sendMessage(messages.chat.trackingStickEmpty)
        } else {
            stick.itemMeta = stick.itemMeta.apply {
                persistentDataContainer.set(trackingStickKey, PersistentDataType.INTEGER, uses - 1)
                setDisplayName(trackingStickName(uses - 1))
            }
            val location = player.location
            val nearestPlayer = tributes.asSequence()
                .filter { it != player }
                .minByOrNull { it.location.distanceSquared(location) }
            if (nearestPlayer != null) {
                val dist = nearestPlayer.location.distance(location).roundToInt()
                val (x, y, z) = nearestPlayer.location.run { listOf(x, y, z) }.map { it.roundToInt() }
                player.sendMessage(messages.chat.trackingStickLocate.safeFormat(nearestPlayer.name, dist, x, y, z))
            }
        }
    }
}
