package org.starficz.combataitweaks.capture

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MutableShipStatsAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.StatBonus
import org.json.JSONObject

/*
 * What each ship system does, as data (plan §11b). One profile per system id, from (first match wins):
 * 1. code: SystemProfiles.register, for systems a table can't describe;
 * 2. data/config/combataitweaks/system_profiles.csv, merged across mods (any mod can ship the same path with its own
 *    rows; a row with an existing id replaces it);
 * 3. learned: an offensive system nobody described is read off its ship's stats the first time it is seen fully on,
 *    keeping only what makes the ship stronger (so a learned profile can only make CAT more careful).
 *
 * A profile's values are the modifiers its stats script applies, written the same way: "x2" (modifyMult), "+50%" or
 * "-50%" (modifyPercent), "+40" (modifyFlat), several separated by spaces. Systems apply their stats under the id
 * "<system id> effect" (translation of obfuscated code: combat.systems.F), so the capture can take a system's own
 * modifiers out of a ship's stats and put the profile's in: the profile works on top of whatever else the ship has.
 *
 * Adding a stat: a [ProfileStat] entry (its column and the ship stat it modifies), then whatever reads it.
 */

/** How CAT's simulation uses a system. */
enum class SystemUse {
    /** Boosts its ship's weapons: an enemy uses it whenever it is ready and a boosted weapon could fire. */
    OFFENSIVE,
    /** Protects its ship: not modelled yet (plan §11b). */
    DEFENSIVE,
    /** Moves its ship: the protected ship's `+SYSTEM` movement options (decide/MobilityPhases). */
    MOBILITY,
}

/** A stats-script modifier: modifyFlat, modifyPercent and modifyMult together. */
class StatModifier(val flat: Float = 0f, val percent: Float = 0f, val mult: Float = 1f) {
    val isNone: Boolean get() = flat == 0f && percent == 0f && mult == 1f

    override fun toString(): String = buildList {
        if (mult != 1f) add("x$mult")
        if (percent != 0f) add((if (percent > 0f) "+" else "") + "$percent%")
        if (flat != 0f) add((if (flat > 0f) "+" else "") + "$flat")
    }.joinToString(" ")

    companion object {
        val NONE = StatModifier()

        /** "x2", "+50%", "-50%", "+40", or several separated by spaces; null for a blank cell. */
        fun parse(text: String): StatModifier? {
            val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var flat = 0f; var percent = 0f; var mult = 1f
            for (p in parts) {
                when {
                    p.startsWith("x") || p.startsWith("X") -> mult *= p.substring(1).toFloat()
                    p.endsWith("%") -> percent += p.dropLast(1).toFloat()
                    else -> flat += p.toFloat()
                }
            }
            return StatModifier(flat, percent, mult)
        }
    }
}

/**
 * A ship stat (MutableStat or StatBonus) as its modifier maps: both compute (base + base x percent / 100 + flat) x mult.
 */
class StatView(
    val base: Float,
    private val flats: Map<String, MutableStat.StatMod>,
    private val percents: Map<String, MutableStat.StatMod>,
    private val mults: Map<String, MutableStat.StatMod>,
) {
    /** The stat's modifiers under [id], as one modifier. */
    fun modifierOf(id: String): StatModifier =
        StatModifier(flats[id]?.value ?: 0f, percents[id]?.value ?: 0f, mults[id]?.value ?: 1f)

    /** Sums of every modifier but [id]'s, plus [with]: the (flat, percent, mult) the stat computes from. */
    fun sumsWithout(id: String, with: StatModifier?): Triple<Float, Float, Float> {
        var flat = with?.flat ?: 0f
        var percent = with?.percent ?: 0f
        var mult = with?.mult ?: 1f
        for ((k, m) in flats) if (k != id) flat += m.value
        for ((k, m) in percents) if (k != id) percent += m.value
        for ((k, m) in mults) if (k != id) mult *= m.value
        return Triple(flat, percent, mult)
    }

    /** The stat's value with [id]'s modifiers replaced by [with] (null: left out). */
    fun valueWith(id: String, with: StatModifier?): Float {
        val (flat, percent, mult) = sumsWithout(id, with)
        return (base + base * percent / 100f + flat) * mult
    }

    companion object {
        fun of(stat: MutableStat) = StatView(stat.baseValue, stat.flatMods, stat.percentMods, stat.multMods)
        fun of(stat: StatBonus, base: Float) = StatView(base, stat.flatBonuses, stat.percentBonuses, stat.multBonuses)
    }
}

/**
 * The stats a profile can set, by their column in system_profiles.csv. [statOf] is the ship stat; StatBonus stats take
 * the weapon's own base value (only flat modifiers depend on it).
 */
enum class ProfileStat(val column: String, val weapons: Boolean = true) {
    BALLISTIC_ROF("ballistic_rof"), ENERGY_ROF("energy_rof"), MISSILE_ROF("missile_rof"),
    BALLISTIC_DAMAGE("ballistic_damage"), ENERGY_DAMAGE("energy_damage"), MISSILE_DAMAGE("missile_damage"),
    BEAM_DAMAGE("beam_damage"),
    BALLISTIC_FLUX("ballistic_flux"), ENERGY_FLUX("energy_flux"), MISSILE_FLUX("missile_flux"), BEAM_FLUX("beam_flux"),
    BALLISTIC_RANGE("ballistic_range"), ENERGY_RANGE("energy_range"), MISSILE_RANGE("missile_range"),
    BEAM_RANGE("beam_range"),
    MAX_SPEED("max_speed", false), ACCELERATION("acceleration", false), DECELERATION("deceleration", false),
    SHIELD_DAMAGE_TAKEN("shield_damage_taken", false), ARMOR_DAMAGE_TAKEN("armor_damage_taken", false),
    HULL_DAMAGE_TAKEN("hull_damage_taken", false), SHIELD_UPKEEP("shield_upkeep", false);

    fun statOf(s: MutableShipStatsAPI, base: Float = 1f): StatView = when (this) {
        BALLISTIC_ROF -> StatView.of(s.ballisticRoFMult)
        ENERGY_ROF -> StatView.of(s.energyRoFMult)
        MISSILE_ROF -> StatView.of(s.missileRoFMult)
        BALLISTIC_DAMAGE -> StatView.of(s.ballisticWeaponDamageMult)
        ENERGY_DAMAGE -> StatView.of(s.energyWeaponDamageMult)
        MISSILE_DAMAGE -> StatView.of(s.missileWeaponDamageMult)
        BEAM_DAMAGE -> StatView.of(s.beamWeaponDamageMult)
        BALLISTIC_FLUX -> StatView.of(s.ballisticWeaponFluxCostMod, base)
        ENERGY_FLUX -> StatView.of(s.energyWeaponFluxCostMod, base)
        MISSILE_FLUX -> StatView.of(s.missileWeaponFluxCostMod, base)
        BEAM_FLUX -> StatView.of(s.beamWeaponFluxCostMult)
        BALLISTIC_RANGE -> StatView.of(s.ballisticWeaponRangeBonus, base)
        ENERGY_RANGE -> StatView.of(s.energyWeaponRangeBonus, base)
        MISSILE_RANGE -> StatView.of(s.missileWeaponRangeBonus, base)
        BEAM_RANGE -> StatView.of(s.beamWeaponRangeBonus, base)
        MAX_SPEED -> StatView.of(s.maxSpeed)
        ACCELERATION -> StatView.of(s.acceleration)
        DECELERATION -> StatView.of(s.deceleration)
        SHIELD_DAMAGE_TAKEN -> StatView.of(s.shieldDamageTakenMult)
        ARMOR_DAMAGE_TAKEN -> StatView.of(s.armorDamageTakenMult)
        HULL_DAMAGE_TAKEN -> StatView.of(s.hullDamageTakenMult)
        SHIELD_UPKEEP -> StatView.of(s.shieldUpkeepMult)
    }

    /** Whether [m] on this stat makes a ship's weapons stronger (more fire rate, damage or range, less flux). */
    fun strengthens(m: StatModifier): Boolean {
        val up = m.mult > 1f || m.percent > 0f || m.flat > 0f
        val down = m.mult < 1f || m.percent < 0f || m.flat < 0f
        return if (this in FLUX) down && !up else up && !down
    }

    companion object {
        val FLUX = setOf(BALLISTIC_FLUX, ENERGY_FLUX, MISSILE_FLUX, BEAM_FLUX)
    }
}

class SystemProfile(
    val systemId: String,
    val use: SystemUse,
    val stats: Map<ProfileStat, StatModifier>,
    /** Mobility: block vanilla's own use of the system whenever CAT isn't using it (v1's rule, plan §8.3). */
    val enforceOverride: Boolean = false,
    /** Where it came from, for the log and the trace: "code", the CSV row's source file, or "learned". */
    val source: String = "code",
    /**
     * Values for some hull sizes only ("frigate", "destroyer", "cruiser", "capital", "fighter"), over [stats]: the
     * damper cuts damage more on small ships. [forSize] resolves them.
     */
    val bySize: Map<String, Map<ProfileStat, StatModifier>> = emptyMap(),
) {
    operator fun get(stat: ProfileStat): StatModifier? = stats[stat]

    /** This profile for one hull size: its [bySize] values replace the plain ones. */
    fun forSize(size: String): SystemProfile {
        val sized = bySize[size] ?: return this
        return SystemProfile(systemId, use, stats + sized, enforceOverride, source)
    }

    override fun toString() = "$systemId ($use, $source): " + stats.entries.joinToString { "${it.key.column} ${it.value}" } +
        bySize.entries.joinToString("") { (size, m) -> "; $size: " + m.entries.joinToString { "${it.key.column} ${it.value}" } }

    companion object {
        /** A hull size's name in [bySize]. */
        fun sizeName(size: ShipAPI.HullSize?): String = when (size) {
            ShipAPI.HullSize.FIGHTER -> "fighter"
            ShipAPI.HullSize.FRIGATE -> "frigate"
            ShipAPI.HullSize.DESTROYER -> "destroyer"
            ShipAPI.HullSize.CRUISER -> "cruiser"
            ShipAPI.HullSize.CAPITAL_SHIP -> "capital"
            else -> "default"
        }
        val SIZES = setOf("fighter", "frigate", "destroyer", "cruiser", "capital")
    }
}

object SystemProfiles {
    const val CSV_PATH = "data/config/combataitweaks/system_profiles.csv"
    private const val MOD_ID = "CombatAITweaks"

    private val registered = HashMap<String, SystemProfile>()
    private val learned = HashMap<String, SystemProfile>()
    /** System ids already checked for learning without finding anything to learn. */
    private val nothingToLearn = HashSet<String>()
    private val fromCsv: Map<String, SystemProfile> by lazy { loadCsv() }

    /** Adds or replaces a system's profile from code (another mod's plugin, for example). Wins over the CSV. */
    @JvmStatic
    fun register(profile: SystemProfile) { registered[profile.systemId] = profile }

    @JvmStatic
    fun of(systemId: String): SystemProfile? = registered[systemId] ?: fromCsv[systemId] ?: learned[systemId]

    /** The ship system's profile, for the ship's hull size. */
    fun of(ship: ShipAPI): SystemProfile? = ship.system?.specAPI?.id?.let { of(it) }?.forSize(SystemProfile.sizeName(ship.hullSize))

    /**
     * The ship's defensive system with its profile (for the ship's hull size): the right-click system first, then the
     * ship system (a damper can sit in either, as `DamperFieldStats.getDamper`); null when neither has a defensive
     * profile.
     */
    fun defensive(ship: ShipAPI): Pair<ShipSystemAPI, SystemProfile>? {
        for (sys in listOfNotNull(ship.phaseCloak, ship.system)) {
            val p = sys.specAPI?.id?.let { of(it) } ?: continue
            if (p.use == SystemUse.DEFENSIVE) return sys to p.forSize(SystemProfile.sizeName(ship.hullSize))
        }
        return null
    }

    /** The id a system's stats script applies its modifiers under. */
    fun effectId(systemId: String) = "$systemId effect"

    /**
     * Learns [ship]'s system when nothing describes it and it is fully on now (effect level 1): its modifiers on the
     * weapon stats, only those that make the ship stronger. Called by the capture for every ship it captures.
     */
    fun learn(ship: ShipAPI) {
        val sys = ship.system ?: return
        val id = sys.specAPI?.id ?: return
        if (id in nothingToLearn || of(id) != null) return
        if (sys.state != ShipSystemAPI.SystemState.ACTIVE || sys.effectLevel < 0.999f) return
        val effect = effectId(id)
        val stats = HashMap<ProfileStat, StatModifier>()
        for (stat in ProfileStat.entries) {
            if (!stat.weapons) continue
            val m = stat.statOf(ship.mutableStats).modifierOf(effect)
            if (!m.isNone && stat.strengthens(m)) stats[stat] = m
        }
        if (stats.isEmpty()) { nothingToLearn += id; return }
        val profile = SystemProfile(id, SystemUse.OFFENSIVE, stats, source = "learned")
        learned[id] = profile
        Global.getLogger(SystemProfiles::class.java).info("CAT learned system profile $profile")
    }

    private fun loadCsv(): Map<String, SystemProfile> {
        val log = Global.getLogger(SystemProfiles::class.java)
        val result = HashMap<String, SystemProfile>()
        val rows = runCatching { Global.getSettings().getMergedSpreadsheetDataForMod("id", CSV_PATH, MOD_ID) }
            .onFailure { log.error("CAT: can't read $CSV_PATH", it) }.getOrNull() ?: return result
        for (r in 0 until rows.length()) {
            val row = rows.optJSONObject(r) ?: continue
            runCatching { parseRow(row) }
                .onSuccess { p -> if (p != null) result[p.systemId] = p }
                .onFailure { log.error("CAT: bad row in $CSV_PATH: $row", it) }
        }
        log.info("CAT: ${result.size} system profiles from $CSV_PATH")
        return result
    }

    private fun parseRow(row: JSONObject): SystemProfile? {
        val id = row.optString("id").trim()
        if (id.isEmpty() || id.startsWith("#")) return null
        val use = SystemUse.valueOf(row.optString("use").trim().uppercase())
        val stats = HashMap<ProfileStat, StatModifier>()
        val bySize = HashMap<String, HashMap<ProfileStat, StatModifier>>()
        for (stat in ProfileStat.entries) {
            for ((sizes, m) in parseCell(row.optString(stat.column))) {
                if (sizes == null) stats[stat] = m
                else for (size in sizes) bySize.getOrPut(size) { HashMap() }[stat] = m
            }
        }
        val enforce = row.optString("enforce_override").trim().equals("true", ignoreCase = true)
        return SystemProfile(id, use, stats, enforce, row.optString("fs_rowSource", CSV_PATH), bySize)
    }

    /**
     * A cell: a modifier ("x0.33"), or several parts separated by ";", each optionally for some hull sizes only
     * ("x0.33; cruiser capital: x0.5"). Returns (sizes or null for every size, modifier) per part.
     */
    fun parseCell(text: String): List<Pair<Set<String>?, StatModifier>> = text.split(";").mapNotNull { part ->
        val colon = part.indexOf(':')
        val sizes = if (colon < 0) null else part.substring(0, colon).trim().lowercase()
            .split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.toSet()
        sizes?.firstOrNull { it !in SystemProfile.SIZES }?.let { throw IllegalArgumentException("unknown hull size '$it'") }
        val m = StatModifier.parse(if (colon < 0) part else part.substring(colon + 1)) ?: return@mapNotNull null
        sizes to m
    }
}
