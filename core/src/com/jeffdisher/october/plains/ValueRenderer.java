package com.jeffdisher.october.plains;

import com.jeffdisher.october.aspects.CraftAspect;
import com.jeffdisher.october.aspects.StationRegistry;
import com.jeffdisher.october.plains.WindowManager.EventHandler;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CraftOperation;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.types.NonStackableItem;


/**
 * The interface used when rendering a single instance of an item or crafting recipe on screen.  The render method is
 * called for each item visible in a window in each frame.
 * Factory methods are required to build these renderers for different parts of the interface.
 *
 * @param <T> The user-defined type to pass to the render method.
 */
public interface ValueRenderer<T>
{
	/**
	 * Renders a single item into the screen, potentially returning event handling information.
	 * 
	 * @param left The left-most GL x-coordinate of this item.
	 * @param bottom The bottom-most GL y-coordinate of this item.
	 * @param scale The size of the item.
	 * @param isMouseOver Whether the mouse is currently over the item.
	 * @param value The user-defined value underlying this item.
	 * @return An event handling to handle clicks on this item (can be null).
	 */
	EventHandler render(float left, float bottom, float scale, boolean isMouseOver, T value);

	public static ValueRenderer<Integer> buildEntityInventory(ClientLogic client, Inventory blockInventory, AbsoluteLocation blockLocation, Entity entity, boolean inFuelInventory, DrawInventoryItem drawHelper)
	{
		return (float left, float bottom, float scale, boolean isMouseOver, Integer key) -> {
			// See if this is stackable or not.
			Inventory entityInventory = entity.inventory();
			Items stack = entityInventory.getStackForKey(key);
			NonStackableItem nonStack = entityInventory.getNonStackableForKey(key);
			Item item = (null != stack) ? stack.type() : nonStack.type();
			int count = (null != stack) ? stack.count() : 0;
			int durability = (null != nonStack) ? nonStack.durability() : 0;
			drawHelper.drawItem(item, count, left, bottom, scale, isMouseOver, durability);
			
			EventHandler onClick = null;
			if (isMouseOver)
			{
				Runnable click = () -> {
					// Select.
					// If this already was selected, clear it.
					if (entity.hotbarItems()[entity.hotbarIndex()] == key)
					{
						client.setSelectedItem(0);
					}
					else
					{
						client.setSelectedItem(key);
					}
				};
				Runnable rightClick = () -> {
					// Transfer 1.
					client.pushItemsToTileInventory(blockLocation, key, 1, inFuelInventory);
				};
				Runnable shiftClick = () -> {
					// Transfer all.
					// Find out how many can fit in the block.
					MutableInventory checker = new MutableInventory((null != blockInventory) ? blockInventory : Inventory.start(StationRegistry.CAPACITY_BLOCK_EMPTY).finish());
					int max = checker.maxVacancyForItem(item);
					int toDrop = Math.min(count, max);
					if (toDrop > 0)
					{
						client.pushItemsToTileInventory(blockLocation, key, toDrop, inFuelInventory);
					}
				};
				onClick = new EventHandler(click, rightClick, shiftClick);
			}
			return onClick;
		};
	}

	public static ValueRenderer<Integer> buildBlockInventoryRenderer(ClientLogic client, Inventory blockInventory, AbsoluteLocation blockLocation, Inventory entityInventory, boolean inFuelInventory, DrawInventoryItem drawHelper)
	{
		return (float left, float bottom, float scale, boolean isMouseOver, Integer key) -> {
			// See if this is stackable or not.
			Items stack = blockInventory.getStackForKey(key);
			NonStackableItem nonStack = blockInventory.getNonStackableForKey(key);
			Item item = (null != stack) ? stack.type() : nonStack.type();
			int count = (null != stack) ? stack.count() : 0;
			int durability = (null != nonStack) ? nonStack.durability() : 0;
			drawHelper.drawItem(item, count, left, bottom, scale, isMouseOver, durability);
			
			EventHandler onClick = null;
			if (isMouseOver)
			{
				Runnable click = () -> {
					// Select.
					// Do nothing - this has no concept of selection.
				};
				Runnable rightClick = () -> {
					// Transfer 1.
					client.pullItemsFromTileInventory(blockLocation, key, 1, inFuelInventory);
				};
				Runnable shiftClick = () -> {
					// Transfer all.
					// Find out how many we can hold.
					MutableInventory checker = new MutableInventory(entityInventory);
					int max = checker.maxVacancyForItem(item);
					int toPickUp = Math.min(count, max);
					if (toPickUp > 0)
					{
						client.pullItemsFromTileInventory(blockLocation, key, toPickUp, inFuelInventory);
					}
				};
				onClick = new EventHandler(click, rightClick, shiftClick);
			}
			return onClick;
		};
	}

	public static ValueRenderer<Craft> buildCraftingRenderer(ClientLogic client, CraftOperation crafting, Inventory craftingInventory, AbsoluteLocation selectedStation, boolean canManuallyCraft, DrawCraftItem drawHelper)
	{
		return (float left, float bottom, float scale, boolean isMouseOver, Craft craft) -> {
			// We will only check the highlight if this is something we even could craft.
			boolean isPossibleCraft = ((null != craftingInventory) ? CraftAspect.canApply(craft, craftingInventory) : false);
			boolean canCraft = canManuallyCraft
					? isPossibleCraft
					: false
			;
			boolean shouldHighlight = canCraft && isMouseOver;
			// Check to see if this is something we are currently crafting.
			float progressBar = 0.0f;
			if ((null != crafting) && (crafting.selectedCraft() == craft))
			{
				progressBar = (float)crafting.completedMillis() / (float)craft.millisPerCraft;
			}
			// We will only show the first output type so see how many there are.
			Item type = craft.output[0];
			int count = 0;
			for (Item item : craft.output)
			{
				if (type == item)
				{
					count += 1;
				}
			}
			drawHelper.drawItem(type, count, left, bottom, scale, shouldHighlight, progressBar, isPossibleCraft);
			EventHandler onClick = null;
			if (shouldHighlight)
			{
				Runnable doCraft;
				if (null != selectedStation)
				{
					// Craft in table.
					doCraft = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraftInBlock(selectedStation, craft);
						}
					};
				}
				else
				{
					// Craft in inventory.
					doCraft = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraft(craft);
						}
					};
				}
				// For now, at least, just treat all the events the same way.
				onClick = new EventHandler(doCraft, doCraft, doCraft);
			}
			return onClick;
		};
	}


	public interface DrawInventoryItem
	{
		void drawItem(Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, int durability);
	}

	public interface DrawCraftItem
	{
		void drawItem(Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, float progressBar, boolean isPossibleCraft);
	}
}
