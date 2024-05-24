package com.jeffdisher.october.plains;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.jeffdisher.october.aspects.AspectRegistry;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.mutations.EntityChangeAttackEntity;
import com.jeffdisher.october.mutations.EntityChangeChangeHotbarSlot;
import com.jeffdisher.october.mutations.EntityChangeJump;
import com.jeffdisher.october.mutations.EntityChangeSwapArmour;
import com.jeffdisher.october.mutations.EntityChangeUseSelectedItemOnBlock;
import com.jeffdisher.october.mutations.EntityChangeUseSelectedItemOnSelf;
import com.jeffdisher.october.mutations.IMutationEntity;
import com.jeffdisher.october.mutations.MutationEntityPushItems;
import com.jeffdisher.october.mutations.MutationEntityRequestItemPickUp;
import com.jeffdisher.october.mutations.MutationEntitySelectItem;
import com.jeffdisher.october.mutations.MutationPlaceSelectedBlock;
import com.jeffdisher.october.persistence.FlatWorldGenerator;
import com.jeffdisher.october.persistence.ResourceLoader;
import com.jeffdisher.october.process.ClientProcess;
import com.jeffdisher.october.process.ServerProcess;
import com.jeffdisher.october.server.ServerRunner;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.BodyPart;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.IMutablePlayerEntity;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.types.NonStackableItem;
import com.jeffdisher.october.types.PartialEntity;
import com.jeffdisher.october.utils.Assert;


public class ClientLogic
{
	public static final int ENTITY_ID = 1;
	public static final int PORT = 5678;

	private final Environment _environment;
	private final Consumer<Entity> _thisEntityConsumer;
	private final Consumer<PartialEntity> _otherEntityConsumer;
	private final IntConsumer _unloadEntityConsumer;
	private final Consumer<IReadOnlyCuboidData> _changedCuboidConsumer;
	private final Consumer<CuboidAddress> _removedCuboidConsumer;

	private final ServerProcess _server;
	private final ClientProcess _client;

	private Entity _thisEntity;
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids;

	// Local state information to avoid redundant events, etc.
	private boolean _didJump;

	public ClientLogic(Environment environment
			, Consumer<Entity> thisEntityConsumer
			, Consumer<PartialEntity> otherEntityConsumer
			, IntConsumer unloadEntityConsumer
			, Consumer<IReadOnlyCuboidData> changedCuboidConsumer
			, Consumer<CuboidAddress> removedCuboidConsumer
			, String clientName
			, InetSocketAddress serverAddress
	)
	{
		_environment = environment;
		_thisEntityConsumer = thisEntityConsumer;
		_otherEntityConsumer = otherEntityConsumer;
		_unloadEntityConsumer = unloadEntityConsumer;
		_changedCuboidConsumer = changedCuboidConsumer;
		_removedCuboidConsumer = removedCuboidConsumer;
		
		try
		{
			// If we weren't given a server address, start the internal server.
			if (null == serverAddress)
			{
				System.out.println("Starting local server for single-player...");
				// We will just store the world in the current directory.
				File worldDirectory = new File("world");
				if (!worldDirectory.isDirectory())
				{
					Assert.assertTrue(worldDirectory.mkdirs());
				}
				// We will just use the flat world generator since it should be populated with what we need for testing.
				ResourceLoader loader = new ResourceLoader(worldDirectory, new FlatWorldGenerator(true));
				_server = new ServerProcess(PORT, ServerRunner.DEFAULT_MILLIS_PER_TICK, loader, () -> System.currentTimeMillis());
				_client = new ClientProcess(new _ClientListener(), InetAddress.getLocalHost(), PORT, clientName);
			}
			else
			{
				System.out.println("Connecting to server: " + serverAddress);
				_server = null;
				_client = new ClientProcess(new _ClientListener(), serverAddress.getAddress(), serverAddress.getPort(), clientName);
			}
		}
		catch (IOException e)
		{
			// TODO:  Handle this network start-up failure or make sure it can't happen.
			throw Assert.unexpected(e);
		}
		_cuboids = new HashMap<>();
	}

	public void finishStartup()
	{
		// Wait for the initial entity data to appear.
		// We need to wait for a few ticks for everything to go through on the server and then be pushed through here.
		// TODO:  Better handle asynchronous start-up.
		try
		{
			long tick = _client.waitForLocalEntity(System.currentTimeMillis());
			_client.waitForTick(tick + 1, System.currentTimeMillis());
		}
		catch (InterruptedException e)
		{
			// We don't use interruption.
			throw Assert.unexpected(e);
		}
	}

	public void stepNorth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontalFully(0.0f, +1.0f, currentTimeMillis);
	}

	public void stepSouth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontalFully(0.0f, -1.0f, currentTimeMillis);
	}

	public void stepEast()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontalFully(+1.0f, 0.0f, currentTimeMillis);
	}

	public void stepWest()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontalFully(-1.0f, 0.0f, currentTimeMillis);
	}

	public boolean jump()
	{
		// We can only jump if we are on the ground.
		// TODO:  This is is the same approach used in SpatialHelpers._isBlockAligned() so it could have the same rounding failure.
		float z = _thisEntity.location().z();
		boolean didJump = false;
		if (!_didJump && (z == (float)Math.round(z)))
		{
			EntityChangeJump<IMutablePlayerEntity> jumpChange = new EntityChangeJump<>();
			long currentTimeMillis = System.currentTimeMillis();
			_client.sendAction(jumpChange, currentTimeMillis);
			didJump = true;
			_didJump = true;
		}
		return didJump;
	}

	public void doNothing()
	{
		long currentTimeMillis = System.currentTimeMillis();
		_client.doNothing(currentTimeMillis);
	}

	public void hitBlock(AbsoluteLocation blockLocation)
	{
		// Make sure that this is a block we can break.
		BlockProxy proxy = new BlockProxy(blockLocation.getBlockAddress(), _cuboids.get(blockLocation.getCuboidAddress()));
		long currentTimeMillis = System.currentTimeMillis();
		if (!_environment.blocks.canBeReplaced(proxy.getBlock()))
		{
			// This block is not the kind which can be replaced, meaning it can potentially be broken.
			_client.hitBlock(blockLocation, currentTimeMillis);
		}
		else
		{
			// If the block is air, we just treat this as a base where we should do nothing (this just avoids redundant client-side checks).
			_client.doNothing(currentTimeMillis);
		}
	}

	public void runAction(AbsoluteLocation blockLocation)
	{
		// We need to check our selected item and see what "action" is associated with it.
		int selectedKey = _thisEntity.hotbarItems()[_thisEntity.hotbarIndex()];
		if (Entity.NO_SELECTION != selectedKey)
		{
			// Check if a special use exists for this item and block or if we are just placing.
			Inventory inventory = _thisEntity.inventory();
			Items stack = inventory.getStackForKey(selectedKey);
			NonStackableItem nonStack = inventory.getNonStackableForKey(selectedKey);
			Item selectedType = (null != stack) ? stack.type() : nonStack.type();
			
			BlockProxy proxy = new BlockProxy(blockLocation.getBlockAddress(), _cuboids.get(blockLocation.getCuboidAddress()));
			Block targetBlock = proxy.getBlock();
			
			// First, can we use this on the block.
			IMutationEntity<IMutablePlayerEntity> change;
			if (EntityChangeUseSelectedItemOnBlock.canUseOnBlock(selectedType, targetBlock))
			{
				change = new EntityChangeUseSelectedItemOnBlock(blockLocation);
			}
			// Second, check to see if we can use it, directly.
			else if (EntityChangeUseSelectedItemOnSelf.canBeUsedOnSelf(selectedType))
			{
				change = new EntityChangeUseSelectedItemOnSelf();
			}
			// Finally, default to trying to place it.
			else
			{
				// The mutation will check proximity and collision.
				change = new MutationPlaceSelectedBlock(blockLocation);
			}
			
			long currentTimeMillis = System.currentTimeMillis();
			_client.sendAction(change, currentTimeMillis);
		}
	}

	public void pullItemsFromTileInventory(AbsoluteLocation location, int blockInventoryKey, int count, boolean useFuel)
	{
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		// For now, we shouldn't see not-yet-loaded cuboids here.
		Assert.assertTrue(null != cuboid);
		BlockAddress blockAddress = location.getBlockAddress();
		Inventory inventory;
		byte inventoryAspect;
		if (useFuel)
		{
			FuelState fuel = cuboid.getDataSpecial(AspectRegistry.FUELED, blockAddress);
			inventory = (null != fuel)
					? fuel.fuelInventory()
					: null
			;
			inventoryAspect = Inventory.INVENTORY_ASPECT_FUEL;
		}
		else
		{
			inventory = cuboid.getDataSpecial(AspectRegistry.INVENTORY, blockAddress);
			inventoryAspect = Inventory.INVENTORY_ASPECT_INVENTORY;
		}
		// This must exist to be calling this.
		Assert.assertTrue(null != inventory);
		// This must be a valid request.
		Items stack = inventory.getStackForKey(blockInventoryKey);
		NonStackableItem nonStack = inventory.getNonStackableForKey(blockInventoryKey);
		Assert.assertTrue((null != stack) != (null != nonStack));
		int available = (null != stack) ? stack.count() : 1;
		Assert.assertTrue(available >= count);
		
		MutationEntityRequestItemPickUp request = new MutationEntityRequestItemPickUp(location, blockInventoryKey, count, inventoryAspect);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(request, currentTimeMillis);
	}

	public void pushItemsToTileInventory(AbsoluteLocation location, int blockInventoryKey, int count, boolean useFuel)
	{
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		// For now, we shouldn't see not-yet-loaded cuboids here.
		Assert.assertTrue(null != cuboid);
		BlockAddress blockAddress = location.getBlockAddress();
		BlockProxy proxy = new BlockProxy(blockAddress, cuboid);
		Items stack = _thisEntity.inventory().getStackForKey(blockInventoryKey);
		NonStackableItem nonStack = _thisEntity.inventory().getNonStackableForKey(blockInventoryKey);
		Assert.assertTrue((null != stack) != (null != nonStack));
		Item type = (null != stack) ? stack.type() : nonStack.type();
		// Make sure that these can fit in the tile.
		Inventory targetInventory;
		byte inventoryAspect;
		if (useFuel)
		{
			// If we are pushing to the fuel slot, make sure that this is a valid type.
			if (_environment.fuel.millisOfFuel(type) > 0)
			{
				FuelState fuel = proxy.getFuel();
				targetInventory = (null != fuel)
						? fuel.fuelInventory()
						: null
				;
			}
			else
			{
				targetInventory = null;
			}
			inventoryAspect = Inventory.INVENTORY_ASPECT_FUEL;
		}
		else
		{
			targetInventory = proxy.getInventory();
			inventoryAspect = Inventory.INVENTORY_ASPECT_INVENTORY;
		}
		
		if (null != targetInventory)
		{
			MutableInventory inv = new MutableInventory(targetInventory);
			if (inv.maxVacancyForItem(type) >= count)
			{
				MutationEntityPushItems push = new MutationEntityPushItems(location, blockInventoryKey, count, inventoryAspect);
				long currentTimeMillis = System.currentTimeMillis();
				_client.sendAction(push, currentTimeMillis);
			}
		}
	}

	public void setSelectedItem(int itemKey)
	{
		MutationEntitySelectItem select = new MutationEntitySelectItem(itemKey);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(select, currentTimeMillis);
	}

	public void beginCraft(Craft craft)
	{
		long currentTimeMillis = System.currentTimeMillis();
		_client.craft(craft, currentTimeMillis);
	}

	public void beginCraftInBlock(AbsoluteLocation block, Craft craft)
	{
		long currentTimeMillis = System.currentTimeMillis();
		_client.craftInBlock(block, craft, currentTimeMillis);
	}

	public void hitEntity(int selectedEntity)
	{
		EntityChangeAttackEntity attack = new EntityChangeAttackEntity(selectedEntity);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(attack, currentTimeMillis);
	}

	public void changeHotbar(int index)
	{
		EntityChangeChangeHotbarSlot change = new EntityChangeChangeHotbarSlot(index);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(change, currentTimeMillis);
	}

	public void swapArmour(BodyPart armourPart, int selectedItem)
	{
		EntityChangeSwapArmour swap = new EntityChangeSwapArmour(armourPart, selectedItem);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(swap, currentTimeMillis);
	}

	public void disconnect()
	{
		_client.disconnect();
		if (null != _server)
		{
			_server.stop();
		}
	}


	private void _setEntity(Entity thisEntity)
	{
		_thisEntity = thisEntity;
		_didJump = false;
	}


	private class _ClientListener implements ClientProcess.IListener
	{
		private int _assignedLocalEntityId;
		@Override
		public void connectionClosed()
		{
			// TODO:  Handle this more gracefully in the future (we have no "connection interface" so there is not much to do beyond exit, at the moment).
			System.out.println("Connection closed");
			if (null != _server)
			{
				_server.stop();
			}
			System.exit(0);
		}
		@Override
		public void connectionEstablished(int assignedLocalEntityId)
		{
			_assignedLocalEntityId = assignedLocalEntityId;
		}
		@Override
		public void cuboidDidChange(IReadOnlyCuboidData cuboid)
		{
			_cuboids.put(cuboid.getCuboidAddress(), cuboid);
			_changedCuboidConsumer.accept(cuboid);
		}
		@Override
		public void cuboidDidLoad(IReadOnlyCuboidData cuboid)
		{
			_cuboids.put(cuboid.getCuboidAddress(), cuboid);
			_changedCuboidConsumer.accept(cuboid);
		}
		@Override
		public void cuboidDidUnload(CuboidAddress address)
		{
			_cuboids.remove(address);
			_removedCuboidConsumer.accept(address);
		}
		@Override
		public void thisEntityDidLoad(Entity entity)
		{
			Assert.assertTrue(_assignedLocalEntityId == entity.id());
			_setEntity(entity);
			_thisEntityConsumer.accept(entity);
		}
		@Override
		public void thisEntityDidChange(Entity entity)
		{
			Assert.assertTrue(_assignedLocalEntityId == entity.id());
			_setEntity(entity);
			_thisEntityConsumer.accept(entity);
		}
		@Override
		public void otherEntityDidChange(PartialEntity entity)
		{
			Assert.assertTrue(_assignedLocalEntityId != entity.id());
			_otherEntityConsumer.accept(entity);
		}
		@Override
		public void otherEntityDidLoad(PartialEntity entity)
		{
			Assert.assertTrue(_assignedLocalEntityId != entity.id());
			_otherEntityConsumer.accept(entity);
		}
		@Override
		public void otherEntityDidUnload(int id)
		{
			_unloadEntityConsumer.accept(id);
		}
	}
}
