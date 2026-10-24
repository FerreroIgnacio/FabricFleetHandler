package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.itemgroup.ItemGroup;
import net.minecraft.util.collection.DefaultedList;

/**
 * Every block the game has, as the creative search lists them: each item of a block, in each of its
 * kinds (the wools' colours, the logs' woods). The catalog PlaceBlocks keeps its lists out of.
 */
final class PlaceCatalog {
	private static List<ItemStack> stacks;

	private PlaceCatalog() {
	}

	/** In the registry's order, each kind after the one before as the creative search has them. */
	static synchronized List<ItemStack> stacks() {
		if (stacks == null) {
			final DefaultedList<ItemStack> all = DefaultedList.of();

			for (Item item : Item.REGISTRY) {
				if (item instanceof BlockItem) {
					item.appendToItemGroup(ItemGroup.SEARCH, all);
				}
			}

			stacks = Collections.unmodifiableList(new ArrayList<ItemStack>(all));
		}

		return stacks;
	}

	/** The block as PlaceBlocks keys it, as the fleet names it: name@metadata. */
	static String key(ItemStack stack) {
		return Item.REGISTRY.getIdentifier(stack.getItem()) + "@" + stack.getData();
	}

	static List<String> keys() {
		final List<String> keys = new ArrayList<String>();

		for (ItemStack stack : stacks()) {
			final String key = key(stack);

			// The same kind twice (a block whose kinds the search shows alike) once.
			if (!keys.contains(key)) {
				keys.add(key);
			}
		}

		return keys;
	}
}
