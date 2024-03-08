package com.jeffdisher.october.plains;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.jeffdisher.october.aspects.FuelAspect;
import com.jeffdisher.october.aspects.InventoryAspect;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.CuboidData;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.mutations.EntityChangeJump;
import com.jeffdisher.october.mutations.MutationEntityPushItems;
import com.jeffdisher.october.mutations.MutationEntityRequestItemPickUp;
import com.jeffdisher.october.mutations.MutationEntitySelectItem;
import com.jeffdisher.october.mutations.MutationPlaceSelectedBlock;
import com.jeffdisher.october.persistence.FlatWorldGenerator;
import com.jeffdisher.october.persistence.ResourceLoader;
import com.jeffdisher.october.process.ClientProcess;
import com.jeffdisher.october.process.ServerProcess;
import com.jeffdisher.october.registries.AspectRegistry;
import com.jeffdisher.october.registries.Craft;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.server.ServerRunner;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.utils.Assert;
import com.jeffdisher.october.worldgen.CuboidGenerator;


public class ClientLogic
{
	public static final int ENTITY_ID = 1;
	// We have a walking speed limit of 4 blocks/second so we will pick something to push against that.
	// (too high and we will see jitter due to rejections, too low and it will feel too slow).
	public static final float INCREMENT = 0.05f;
	public static final int PORT = 5678;

	private final Consumer<Entity> _thisEntityConsumer;
	private final Consumer<Entity> _otherEntityConsumer;
	private final IntConsumer _unloadEntityConsumer;
	private final Consumer<IReadOnlyCuboidData> _changedCuboidConsumer;
	private final Consumer<CuboidAddress> _removedCuboidConsumer;

	private final ServerProcess _server;
	private final ClientProcess _client;

	private Entity _thisEntity;
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids;

	public ClientLogic(Consumer<Entity> thisEntityConsumer
			, Consumer<Entity> otherEntityConsumer
			, IntConsumer unloadEntityConsumer
			, Consumer<IReadOnlyCuboidData> changedCuboidConsumer
			, Consumer<CuboidAddress> removedCuboidConsumer
			, InetSocketAddress serverAddress
	)
	{
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
				// We will preload the initial starting area but that will be built on top of a standard flat world.
				ResourceLoader loader = new ResourceLoader(worldDirectory, new FlatWorldGenerator());
				_preload(loader);
				_server = new ServerProcess(PORT, ServerRunner.DEFAULT_MILLIS_PER_TICK, loader, () -> System.currentTimeMillis());
				_client = new ClientProcess(new _ClientListener(), InetAddress.getLocalHost(), PORT, "client");
			}
			else
			{
				System.out.println("Connecting to server: " + serverAddress);
				_server = null;
				_client = new ClientProcess(new _ClientListener(), serverAddress.getAddress(), serverAddress.getPort(), "client");
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
		_client.moveHorizontal(0.0f, +INCREMENT, currentTimeMillis);
	}

	public void stepSouth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontal(0.0f, -INCREMENT, currentTimeMillis);
	}

	public void stepEast()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontal(+INCREMENT, 0.0f, currentTimeMillis);
	}

	public void stepWest()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontal(-INCREMENT, 0.0f, currentTimeMillis);
	}

	public void jump()
	{
		EntityChangeJump jumpChange = new EntityChangeJump();
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(jumpChange, currentTimeMillis);
	}

	public void doNothing()
	{
		long currentTimeMillis = System.currentTimeMillis();
		_client.doNothing(currentTimeMillis);
	}

	public void hitBlock(AbsoluteLocation blockLocation)
	{
		// Make sure that this is a block we can break and there is nothing currently in progress.
		short blockNumber = _cuboids.get(blockLocation.getCuboidAddress()).getData15(AspectRegistry.BLOCK, blockLocation.getBlockAddress());
		Item type = ItemRegistry.ITEMS_BY_TYPE[blockNumber];
		long currentTimeMillis = System.currentTimeMillis();
		if (ItemRegistry.AIR != type)
		{
			// Damage the block.
			_client.hitBlock(blockLocation, currentTimeMillis);
		}
		else
		{
			// If the block is air, we just treat this as a base where we should do nothing (this just avoids redundant client-side checks).
			_client.doNothing(currentTimeMillis);
		}
	}

	public void placeBlock(AbsoluteLocation blockLocation)
	{
		// Make sure we have something selected.
		if (null != _thisEntity.selectedItem())
		{
			// The mutation will check proximity and collision.
			MutationPlaceSelectedBlock place = new MutationPlaceSelectedBlock(blockLocation);
			long currentTimeMillis = System.currentTimeMillis();
			_client.sendAction(place, currentTimeMillis);
		}
	}

	public void pullItemsFromTileInventory(AbsoluteLocation location, Item type, int count, boolean useFuel)
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
		Assert.assertTrue(inventory.items.get(type).count() >= count);
		
		MutationEntityRequestItemPickUp request = new MutationEntityRequestItemPickUp(location, new Items(type, count), inventoryAspect);
		long currentTimeMillis = System.currentTimeMillis();
		_client.sendAction(request, currentTimeMillis);
	}

	public void pushItemsToTileInventory(AbsoluteLocation location, Item type, int count, boolean useFuel)
	{
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		// For now, we shouldn't see not-yet-loaded cuboids here.
		Assert.assertTrue(null != cuboid);
		BlockAddress blockAddress = location.getBlockAddress();
		BlockProxy proxy = new BlockProxy(blockAddress, cuboid);
		// Make sure that these can fit in the tile.
		Inventory targetInventory;
		byte inventoryAspect;
		if (useFuel)
		{
			// If we are pushing to the fuel slot, make sure that this is a valid type.
			if (FuelAspect.millisOfFuel(type) > 0)
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
				MutationEntityPushItems push = new MutationEntityPushItems(location, new Items(type, count), inventoryAspect);
				long currentTimeMillis = System.currentTimeMillis();
				_client.sendAction(push, currentTimeMillis);
			}
		}
	}

	public void setSelectedItem(Item item)
	{
		MutationEntitySelectItem select = new MutationEntitySelectItem(item);
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

	public void disconnect()
	{
		_client.disconnect();
		if (null != _server)
		{
			_server.stop();
		}
	}


	private static void _preload(ResourceLoader loader)
	{
		// We will drop some logs on the ground to make craft testing possible before we add a more interesting world generator.
		CuboidData cuboid000 = _generateColumnCuboid(new CuboidAddress((short)0, (short)0, (short)0));
		Inventory starting = Inventory.start(InventoryAspect.CAPACITY_AIR)
				.add(ItemRegistry.LOG, 5)
				.finish();
		cuboid000.setDataSpecial(AspectRegistry.INVENTORY, new BlockAddress((byte)0, (byte)0, (byte)0), starting);
		
		// We will also add in some basic cuboids with a few "columns", just so that there is something we can use for navigation when testing.
		loader.preload(cuboid000);
		loader.preload(_generateColumnCuboid(new CuboidAddress((short)0, (short)-1, (short)0)));
		loader.preload(_generateColumnCuboid(new CuboidAddress((short)-1, (short)-1, (short)0)));
		loader.preload(_generateColumnCuboid(new CuboidAddress((short)-1, (short)0, (short)0)));
	}

	private static CuboidData _generateColumnCuboid(CuboidAddress address)
	{
		CuboidData cuboid = CuboidGenerator.createFilledCuboid(address, ItemRegistry.AIR);
		
		// Create some columns.
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte) 1, (byte) 1, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte) 1, (byte)30, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte)30, (byte)30, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte)30, (byte) 1, (byte)0), ItemRegistry.STONE.number());
		
		return cuboid;
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
		public void entityDidChange(Entity entity)
		{
			if (_assignedLocalEntityId == entity.id())
			{
				_thisEntity = entity;
				_thisEntityConsumer.accept(entity);
			}
			else
			{
				_otherEntityConsumer.accept(entity);
			}
		}
		@Override
		public void entityDidLoad(Entity entity)
		{
			if (_assignedLocalEntityId == entity.id())
			{
				_thisEntity = entity;
				_thisEntityConsumer.accept(entity);
			}
			else
			{
				_otherEntityConsumer.accept(entity);
			}
		}
		@Override
		public void entityDidUnload(int id)
		{
			_unloadEntityConsumer.accept(id);
		}
	}
}
