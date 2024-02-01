package com.jeffdisher.october.plains;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.jeffdisher.october.aspects.InventoryAspect;
import com.jeffdisher.october.client.ClientRunner;
import com.jeffdisher.october.client.SpeculativeProjection;
import com.jeffdisher.october.data.CuboidData;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.integration.LocalServerShim;
import com.jeffdisher.october.registries.AspectRegistry;
import com.jeffdisher.october.registries.Craft;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.server.ServerRunner;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
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

	private final Consumer<Entity> _thisEntityConsumer;
	private final Consumer<IReadOnlyCuboidData> _changedCuboidConsumer;
	private final Consumer<CuboidAddress> _removedCuboidConsumer;

	private final LocalServerShim _shim;
	private final ProjectionListener _projectionListener;
	private final ClientListener _clientListener;

	private final ClientRunner _client;
	private Entity _thisEntity;
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids;

	public ClientLogic(Consumer<Entity> thisEntityConsumer
			, Consumer<IReadOnlyCuboidData> changedCuboidConsumer
			, Consumer<CuboidAddress> removedCuboidConsumer
	)
	{
		_thisEntityConsumer = thisEntityConsumer;
		_changedCuboidConsumer = changedCuboidConsumer;
		_removedCuboidConsumer = removedCuboidConsumer;
		
		try
		{
			_shim = LocalServerShim.startedServerShim(ServerRunner.DEFAULT_MILLIS_PER_TICK, () -> System.currentTimeMillis());
		}
		catch (IOException e)
		{
			// TODO:  Handle this network start-up failure or make sure it can't happen.
			throw Assert.unexpected(e);
		}
		_projectionListener = new ProjectionListener();
		_clientListener = new ClientListener();
		_client = new ClientRunner(_shim.getClientAdapter(), _projectionListener, _clientListener);
		_cuboids = new HashMap<>();
	}

	public void finishStartup()
	{
		// Wait for the initial entity data to appear.
		try
		{
			_shim.waitForClient();
		}
		catch (InterruptedException e)
		{
			// We don't use interruption.
			throw Assert.unexpected(e);
		}
		
		// Since the location is standing in 0.0, we need to load at least the 8 cuboids around the origin.
		// Note that we want them to stand on the ground so we will fill the bottom 4 with stone and the top 4 with air.
		// (in order to better test inventory and crafting interfaces, we will drop a bunch of items on the ground where we start).
		CuboidData cuboid000 = _generateColumnCuboid(new CuboidAddress((short)0, (short)0, (short)0));
		Inventory starting = Inventory.start(InventoryAspect.CAPACITY_AIR)
				.add(ItemRegistry.STONE, 1)
				.add(ItemRegistry.LOG, 1)
				.add(ItemRegistry.PLANK, 1)
				.finish();
		cuboid000.setDataSpecial(AspectRegistry.INVENTORY, new BlockAddress((byte)0, (byte)0, (byte)0), starting);
		_shim.injectCuboidToServer(cuboid000);
		_shim.injectCuboidToServer(_generateColumnCuboid(new CuboidAddress((short)0, (short)-1, (short)0)));
		_shim.injectCuboidToServer(_generateColumnCuboid(new CuboidAddress((short)-1, (short)-1, (short)0)));
		_shim.injectCuboidToServer(_generateColumnCuboid(new CuboidAddress((short)-1, (short)0, (short)0)));
		
		_shim.injectCuboidToServer(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)0, (short)-1), ItemRegistry.STONE));
		_shim.injectCuboidToServer(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)-1, (short)-1), ItemRegistry.STONE));
		_shim.injectCuboidToServer(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)-1, (short)-1), ItemRegistry.STONE));
		_shim.injectCuboidToServer(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)0, (short)-1), ItemRegistry.STONE));
		
		// We need to wait for a few ticks for everything to go through on the server and then be pushed through here.
		// TODO:  Better handle asynchronous start-up.
		try
		{
			_shim.waitForTickAdvance(3L);
		}
		catch (InterruptedException e)
		{
			// We don't use interruption.
			throw Assert.unexpected(e);
		}
		_client.runPendingCalls(System.currentTimeMillis());
	}

	public void stepNorth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(0.0f, +INCREMENT, currentTimeMillis);
		}
	}

	public void stepSouth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(0.0f, -INCREMENT, currentTimeMillis);
		}
	}

	public void stepEast()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(+INCREMENT, 0.0f, currentTimeMillis);
		}
	}

	public void stepWest()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(-INCREMENT, 0.0f, currentTimeMillis);
		}
	}

	public void jump()
	{
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.jump(currentTimeMillis);
		}
	}

	public void doNothing()
	{
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.doNothing(currentTimeMillis);
		}
	}

	public void beginBreakingBlock(AbsoluteLocation blockLocation)
	{
		// We want to only consider breaking the block if it is within 2 blocks of where the entity currently is.
		int absX = Math.abs(blockLocation.x() - Math.round(_thisEntity.location().x()));
		int absY = Math.abs(blockLocation.y() - Math.round(_thisEntity.location().y()));
		int absZ = Math.abs(blockLocation.z() - Math.round(_thisEntity.location().z()));
		if ((absX <= 2) && (absY <= 2) && (absZ <= 2))
		{
			// Make sure that this is a block we can break and there is nothing currently in progress.
			short blockNumber = _cuboids.get(blockLocation.getCuboidAddress()).getData15(AspectRegistry.BLOCK, blockLocation.getBlockAddress());
			Item type = ItemRegistry.BLOCKS_BY_TYPE[blockNumber];
			long currentTimeMillis = System.currentTimeMillis();
			if ((ItemRegistry.AIR != type) && !_client.isActivityInProgress(currentTimeMillis))
			{
				// This returns the delay we need to wait until the block breaks, in millis.
				_client.beginBreakBlock(blockLocation, type, currentTimeMillis);
			}
		}
	}

	public void placeBlock(AbsoluteLocation blockLocation)
	{
		// Make sure we have something in our inventory.
		if (_thisEntity.inventory().currentEncumbrance > 0)
		{
			// We want to only consider placing the block if it is within 2 blocks of where the entity currently is.
			int absX = Math.abs(blockLocation.x() - Math.round(_thisEntity.location().x()));
			int absY = Math.abs(blockLocation.y() - Math.round(_thisEntity.location().y()));
			int absZ = Math.abs(blockLocation.z() - Math.round(_thisEntity.location().z()));
			if ((absX <= 2) && (absY <= 2) && (absZ <= 2))
			{
				long currentTimeMillis = System.currentTimeMillis();
				if (!_client.isActivityInProgress(currentTimeMillis))
				{
					_client.placeSelectedBlock(blockLocation, currentTimeMillis);
				}
			}
		}
	}

	public void pickUpItemsOnOurTile(Item type, int count)
	{
		AbsoluteLocation location = GeometryHelpers.getCentreAtFeet(_thisEntity);
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		// For now, we shouldn't see not-yet-loaded cuboids here.
		Assert.assertTrue(null != cuboid);
		Inventory inventory = cuboid.getDataSpecial(AspectRegistry.INVENTORY, location.getBlockAddress());
		// This must exist to be calling this.
		Assert.assertTrue(null != inventory);
		// This must be a valid request.
		Assert.assertTrue(inventory.items.get(type).count() >= count);
		
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.pullItemsFromInventory(location, new Items(type, count), currentTimeMillis);
		}
	}

	public void dropItemsOnOurTile(Item type, int count)
	{
		AbsoluteLocation location = GeometryHelpers.getCentreAtFeet(_thisEntity);
		IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
		// For now, we shouldn't see not-yet-loaded cuboids here.
		Assert.assertTrue(null != cuboid);
		// Make sure that these can fit in the tile.
		BlockAddress blockAddress = location.getBlockAddress();
		if (ItemRegistry.AIR.number() == cuboid.getData15(AspectRegistry.BLOCK, blockAddress))
		{
			Inventory existing = cuboid.getDataSpecial(AspectRegistry.INVENTORY, blockAddress);
			MutableInventory inv = new MutableInventory((null != existing) ? existing : Inventory.start(InventoryAspect.CAPACITY_AIR).finish());
			if (inv.maxVacancyForItem(type) >= count)
			{
				long currentTimeMillis = System.currentTimeMillis();
				if (!_client.isActivityInProgress(currentTimeMillis))
				{
					_client.pushItemsToInventory(location, new Items(type, count), currentTimeMillis);
				}
			}
		}
	}

	public void setSelectedItem(Item item)
	{
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.selectItemInInventory(item, currentTimeMillis);
		}
	}

	public void beginCraft(Craft craft)
	{
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.craft(craft, currentTimeMillis);
		}
	}

	public void disconnect()
	{
		_client.disconnect();
		try
		{
			_shim.waitForServerShutdown();
		}
		catch (InterruptedException e)
		{
			// We don't use interruption.
			throw Assert.unexpected(e);
		}
	}


	private CuboidData _generateColumnCuboid(CuboidAddress address)
	{
		CuboidData cuboid = CuboidGenerator.createFilledCuboid(address, ItemRegistry.AIR);
		
		// Create some columns.
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte) 1, (byte) 1, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte) 1, (byte)30, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte)30, (byte)30, (byte)0), ItemRegistry.STONE.number());
		cuboid.setData15(AspectRegistry.BLOCK, new BlockAddress((byte)30, (byte) 1, (byte)0), ItemRegistry.STONE.number());
		
		return cuboid;
	}

	private class ProjectionListener implements SpeculativeProjection.IProjectionListener
	{
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
			_thisEntity = entity;
			_thisEntityConsumer.accept(_thisEntity);
		}
		@Override
		public void entityDidLoad(Entity entity)
		{
			_thisEntity = entity;
			_thisEntityConsumer.accept(_thisEntity);
		}
		@Override
		public void entityDidUnload(int id)
		{
		}
	}


	private class ClientListener implements ClientRunner.IListener
	{
		@Override
		public void clientDidConnectAndLogin(int assignedLocalEntityId)
		{
		}
		@Override
		public void clientDisconnected()
		{
		}
	}
}
