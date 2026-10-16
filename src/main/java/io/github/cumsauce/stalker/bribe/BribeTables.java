package io.github.cumsauce.stalker.bribe;

import io.github.cumsauce.stalker.config.StalkerConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What each villager profession values, in points, per PLAN.md section 6. The lookup core is pure (profession id,
 * item id, tag ids, plain integers) so the tables and the threshold decision are unit-testable without Minecraft; the
 * {@link Villager}/{@link ItemStack} overloads at the bottom bridge from the live objects.
 *
 * <p>Profession ids are the registry path without namespace: {@code farmer}, {@code nitwit}, and {@code none} for
 * unemployed ({@link VillagerProfession#NONE} is the unemployed key in 26.3).
 */
public final class BribeTables {
	// Internal keys are bare registry paths (normalized, no "minecraft:" prefix) to match the normalized lookups.
	private static final String EMERALD = "emerald";
	/** Tag ids the Minecraft wrapper can report for an offered stack; the pure core matches on these. */
	public static final String TAG_WOOL = "minecraft:wool";
	public static final String TAG_DYES = "minecraft:dyes";
	public static final String TAG_TERRACOTTA = "minecraft:terracotta";

	private static final Map<String, Map<String, Integer>> TABLES = Map.ofEntries(
			Map.entry("farmer", Map.ofEntries(
					entry("wheat", 1), entry("carrot", 1), entry("potato", 1), entry("beetroot", 1),
					entry("wheat_seeds", 1), entry("pumpkin_seeds", 1), entry("melon_seeds", 1),
					entry("beetroot_seeds", 1), entry("torchflower_seeds", 1), entry("pitcher_pod", 1),
					entry("hay_block", 2), entry("pumpkin", 2), entry("melon", 2),
					entry("bread", 3), entry("cake", 3),
					entry("golden_carrot", 8))),
			Map.entry("fisherman", Map.ofEntries(
					entry("cod", 1), entry("string", 1),
					entry("salmon", 2),
					entry("tropical_fish", 3),
					entry("pufferfish", 8))),
			Map.entry("shepherd", Map.ofEntries(
					entry("shears", 3))),
			Map.entry("fletcher", Map.ofEntries(
					entry("flint", 1), entry("feather", 1),
					entry("stick", 2),
					entry("arrow", 3),
					entry("bow", 8))),
			Map.entry("librarian", Map.ofEntries(
					entry("paper", 1),
					entry("book", 2),
					entry("ink_sac", 3),
					entry("enchanted_book", 8))),
			Map.entry("cartographer", Map.ofEntries(
					entry("paper", 1),
					entry("glass_pane", 2),
					entry("compass", 3), entry("map", 3), entry("filled_map", 3))),
			Map.entry("cleric", Map.ofEntries(
					entry("rotten_flesh", 1), entry("bone", 1),
					entry("gold_nugget", 2),
					entry("redstone", 3), entry("lapis_lazuli", 3),
					entry("ender_pearl", 8))),
			Map.entry("armorer", Map.ofEntries(
					entry("coal", 1), entry("iron_nugget", 1),
					entry("iron_ingot", 2),
					entry("lava_bucket", 3),
					entry("diamond", 8))),
			Map.entry("weaponsmith", Map.ofEntries(
					entry("coal", 1), entry("iron_nugget", 1),
					entry("iron_ingot", 2),
					entry("flint_and_steel", 3),
					entry("diamond_axe", 8), entry("diamond_sword", 8))),
			Map.entry("toolsmith", Map.ofEntries(
					entry("coal", 1), entry("iron_nugget", 1),
					entry("iron_ingot", 2),
					entry("stone_shovel", 3), entry("stone_pickaxe", 3), entry("stone_axe", 3),
					entry("stone_hoe", 3), entry("stone_sword", 3),
					entry("diamond", 8))),
			Map.entry("butcher", Map.ofEntries(
					entry("chicken", 1), entry("porkchop", 1),
					entry("beef", 2), entry("sweet_berries", 2),
					entry("rabbit_stew", 8))),
			Map.entry("leatherworker", Map.ofEntries(
					entry("leather", 1),
					entry("rabbit_hide", 2),
					entry("leather_helmet", 3), entry("leather_chestplate", 3),
					entry("leather_leggings", 3), entry("leather_boots", 3), entry("leather_horse_armor", 3),
					entry("saddle", 8))),
			Map.entry("mason", Map.ofEntries(
					entry("clay_ball", 1), entry("brick", 1),
					entry("quartz_block", 3))),
			Map.entry("none", Map.ofEntries(
					// Unemployed: "any raw food" / "any cooked food" are the food sets below.
					entry("emerald", 8))),
			// Nitwits value nothing: every offer is a pretend-accept (unless nitwitAcceptsEmerald).
			Map.entry("nitwit", Map.of()));

	/** "Any raw food" for the unemployed table. Keys are bare registry paths. */
	private static final Set<String> RAW_FOOD = Set.of(
			"beef", "porkchop", "chicken", "rabbit", "mutton",
			"cod", "salmon", "tropical_fish",
			"apple", "carrot", "potato", "beetroot", "melon_slice",
			"sweet_berries", "glow_berries");
	/** "Any cooked food" for the unemployed table. */
	private static final Set<String> COOKED_FOOD = Set.of(
			"cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_rabbit",
			"cooked_mutton", "cooked_cod", "cooked_salmon",
			"bread", "baked_potato", "pumpkin_pie", "cookie",
			"dried_kelp", "cake", "hay_block");
	/** "Cooked meats" in the butcher's 3-point column. */
	private static final Set<String> COOKED_MEATS = Set.of(
			"cooked_beef", "cooked_porkchop", "cooked_chicken",
			"cooked_mutton", "cooked_rabbit", "cooked_cod", "cooked_salmon");

	/** Tag-valued rows the tables can't express as plain items: profession id -> (bare tag path -> points). */
	private static final Map<String, Map<String, Integer>> TAG_TABLES = Map.of(
			"shepherd", Map.of("wool", 1, "dyes", 1),
			"mason", Map.of("terracotta", 2));

	private BribeTables() {}

	private static Map.Entry<String, Integer> entry(String item, int points) {
		return Map.entry(item, points);
	}

	/** The point table for a profession id ({@code farmer}, {@code nitwit}, {@code none}); never null. */
	public static Map<String, Integer> table(String professionId) {
		return TABLES.getOrDefault(normalize(professionId), Map.of());
	}

	/** All profession ids with tables ({@code farmer} ... {@code nitwit}, {@code none}). */
	public static List<String> professions() {
		return List.copyOf(TABLES.keySet());
	}

	/**
	 * What {@code itemId} is worth to {@code professionId}: the item's table row, else a matching tag row, else the
	 * raw/cooked food sets for the unemployed, else {@code emeraldValue} for an emerald, else 0.
	 *
	 * <p>Kept for unit tests; defaults to nitwits rejecting everything (see
	 * {@link #value(String, String, Set, int, boolean)} for the live path that respects the config flag).
	 *
	 * @param tags tag ids of the offered item ({@link #TAG_WOOL}, {@link #TAG_DYES}, {@link #TAG_TERRACOTTA})
	 */
	public static int value(String professionId, String itemId, Set<String> tags, int emeraldValue) {
		return value(professionId, itemId, tags, emeraldValue, false);
	}

	/**
	 * Same as {@link #value(String, String, Set, int)} but lets the caller decide whether nitwits accept an
	 * emerald ({@code config.nitwitAcceptsEmerald()} on the live path, where the trait is opt-in).
	 *
	 * @param tags tag ids of the offered item ({@link #TAG_WOOL}, {@link #TAG_DYES}, {@link #TAG_TERRACOTTA})
	 * @param nitwitAcceptsEmerald {@code true} when the live config wants nitwits to take emeralds
	 */
	public static int value(String professionId, String itemId, Set<String> tags, int emeraldValue,
			boolean nitwitAcceptsEmerald) {
		String profession = normalize(professionId);
		String item = normalize(itemId);
		if (item.equals(EMERALD)) {
			// Nitwits value nothing by default; the live path's config flag can override.
			return profession.equals("nitwit") ? (nitwitAcceptsEmerald ? emeraldValue : 0) : emeraldValue;
		}
		Map<String, Integer> table = TABLES.get(profession);
		if (table == null || table.isEmpty()) return 0;
		Integer row = table.get(item);
		if (row != null) return row;
		switch (profession) {
			case "butcher" -> {
				if (COOKED_MEATS.contains(item)) return 3;
			}
			case "none" -> {
				if (COOKED_FOOD.contains(item)) return 2;
				if (RAW_FOOD.contains(item)) return 1;
			}
			default -> {}
		}
		Map<String, Integer> tagTable = TAG_TABLES.get(profession);
		if (tagTable != null) {
			for (String tag : tags) {
				Integer tagRow = tagTable.get(normalize(tag));
				if (tagRow != null) return tagRow;
			}
		}
		return 0;
	}

	/** The threshold decision: {@code true} only when the offer is worth at least {@code threshold}. */
	public static boolean accepts(String professionId, String itemId, Set<String> tags, int threshold, int emeraldValue) {
		return value(professionId, itemId, tags, emeraldValue) >= threshold;
	}

	/** A one-line human-readable table for {@code /stalker bribe}. */
	public static String describe(String professionId) {
		String profession = normalize(professionId);
		String rows = TABLES.getOrDefault(profession, Map.of()).entrySet().stream()
				.sorted((a, b) -> a.getValue() != b.getValue()
						? Integer.compare(b.getValue(), a.getValue())
						: a.getKey().compareTo(b.getKey()))
				.map(e -> e.getKey() + "=" + e.getValue())
				.collect(Collectors.joining(", "));
		StringBuilder text = new StringBuilder(profession).append(": ").append(rows.isEmpty() ? "(nothing)" : rows);
		Map<String, Integer> tagTable = TAG_TABLES.get(profession);
		if (tagTable != null) {
			text.append("; by tag: ").append(tagTable.entrySet().stream()
					.map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining(", ")));
		}
		if (profession.equals("none")) text.append("; any cooked food=2, any raw food=1");
		return text.toString();
	}

	private static String normalize(String id) {
		String normalized = id.toLowerCase(Locale.ROOT);
		int colon = normalized.indexOf(':');
		return colon >= 0 ? normalized.substring(colon + 1) : normalized;
	}

	// ---- Minecraft-facing wrappers (not on the unit-test classpath; covered by game tests instead) ----

	/** The profession id of {@code villager}: its registry path ({@code farmer}, {@code none}, {@code nitwit}, ...). */
	public static String professionId(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("");
	}

	/** True when {@code villager} is a nitwit — every offer to it is a pretend-accept by default. */
	public static boolean isNitwit(Villager villager) {
		return villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
	}

	/**
	 * What the offered stack is worth to {@code villager}'s profession, using {@link StalkerConfig#get()} for the
	 * emerald value and the nitwit rule. Nitwits go through the same pure path as every other profession so the
	 * two overloads agree (otherwise a live nitwit+emerald with {@code nitwitAcceptsEmerald=true} would score
	 * above zero while the unit test, which calls the pure overload without the flag, scored zero for the
	 * same inputs).
	 */
	public static int value(Villager villager, ItemStack stack) {
		StalkerConfig config = StalkerConfig.get();
		boolean nitwit = isNitwit(villager);
		// Nitwits can only ever score on an emerald when the config flag is on; every other case short-circuits
		// to zero so the pure path doesn't have to know about ItemStack.
		if (nitwit && !(config.nitwitAcceptsEmerald() && stack.is(Items.EMERALD))) {
			return 0;
		}
		return value(professionId(villager), itemId(stack), tags(stack), config.emeraldValue(), nitwit);
	}

	/** True when the offered stack meets {@code bribeThreshold} for {@code villager}'s profession. */
	public static boolean accepts(Villager villager, ItemStack stack) {
		StalkerConfig config = StalkerConfig.get();
		return value(villager, stack) >= config.bribeThreshold();
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** The tag ids among the ones this class cares about that {@code stack}'s item belongs to. */
	private static Set<String> tags(ItemStack stack) {
		Set<String> found = new java.util.HashSet<>();
		if (stack.is(ItemTags.WOOL)) found.add(TAG_WOOL);
		if (stack.is(ItemTags.DYES)) found.add(TAG_DYES);
		if (stack.is(ItemTags.TERRACOTTA)) found.add(TAG_TERRACOTTA);
		return found;
	}
}
