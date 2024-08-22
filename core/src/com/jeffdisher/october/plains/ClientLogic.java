package com.jeffdisher.october.plains;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

import com.jeffdisher.october.aspects.AspectRegistry;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.mutations.EntityChangeAttackEntity;
import com.jeffdisher.october.mutations.EntityChangeChangeHotbarSlot;
import com.jeffdisher.october.mutations.EntityChangeJump;
import com.jeffdisher.october.mutations.EntityChangeMove;
import com.jeffdisher.october.mutations.EntityChangeSetBlockLogicState;
import com.jeffdisher.october.mutations.EntityChangeSwapArmour;
import com.jeffdisher.october.mutations.EntityChangeSwim;
import com.jeffdisher.october.mutations.EntityChangeUseSelectedItemOnBlock;
import com.jeffdisher.october.mutations.EntityChangeUseSelectedItemOnEntity;
import com.jeffdisher.october.mutations.EntityChangeUseSelectedItemOnSelf;
import com.jeffdisher.october.mutations.IMutationEntity;
import com.jeffdisher.october.mutations.MutationEntityPushItems;
import com.jeffdisher.october.mutations.MutationEntityRequestItemPickUp;
import com.jeffdisher.october.mutations.MutationEntitySelectItem;
import com.jeffdisher.october.mutations.MutationPlaceSelectedBlock;
import com.jeffdisher.october.persistence.BasicWorldGenerator;
import com.jeffdisher.october.persistence.ResourceLoader;
import com.jeffdisher.october.process.ClientProcess;
import com.jeffdisher.october.process.ServerProcess;
import com.jeffdisher.october.server.MonitoringAgent;
import com.jeffdisher.october.server.ServerRunner;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.BodyPart;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CreativeInventory;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityConstants;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.EntityType;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.IMutablePlayerEntity;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.types.NonStackableItem;
import com.jeffdisher.october.types.PartialEntity;
import com.jeffdisher.october.types.WorldConfig;
import com.jeffdisher.october.utils.Assert;


public class ClientLogic
{
	public static final int ENTITY_ID = 1;
	public static final int PORT = 5678;
	/**
	 * The threshold for how we determine which direction a block is "facing" based on where it is clicked.
	 * If it is within this distance of an edge, it is facing that edge.  This means that the number must be < 0.5f.
	 */
	public static final double FACING_THRESHOLD = 0.3f;

	private final Environment _environment;
	private final BiConsumer<Entity, Entity> _thisEntityConsumer;
	private final Consumer<PartialEntity> _otherEntityConsumer;
	private final IntConsumer _unloadEntityConsumer;
	private final Consumer<IReadOnlyCuboidData> _changedCuboidConsumer;
	private final Consumer<CuboidAddress> _removedCuboidConsumer;

	private final ResourceLoader _loader;
	private final WorldConfig _config;
	private final ServerProcess _server;
	private final ClientProcess _client;
	private final ConsoleRunner _console;

	private Entity _thisEntity;
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids;

	// Local state information to avoid redundant events, etc.
	private boolean _didJump;

	public ClientLogic(Environment environment
			, BiConsumer<Entity, Entity> thisEntityConsumer
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
				
				// We will use the basic world generator, as that is our current standard generator.
				_config = new WorldConfig();
				boolean didLoadConfig = ResourceLoader.populateWorldConfig(worldDirectory, _config);
				BasicWorldGenerator worldGen = new BasicWorldGenerator(_environment, _config.basicSeed);
				if (!didLoadConfig)
				{
					// There is no config so ask the world-gen for the default spawn.
					EntityLocation spawnLocation = worldGen.getDefaultSpawnLocation();
					_config.worldSpawn = spawnLocation.getBlockLocation();
				}
				_loader = new ResourceLoader(worldDirectory
						, worldGen
						, _config.worldSpawn.toEntityLocation()
				);
				MonitoringAgent monitoringAgent = new MonitoringAgent();
				_server = new ServerProcess(PORT
						, ServerRunner.DEFAULT_MILLIS_PER_TICK
						, _loader
						, () -> System.currentTimeMillis()
						, monitoringAgent
						, _config
				);
				_client = new ClientProcess(new _ClientListener(), InetAddress.getLocalHost(), PORT, clientName);
				_console = ConsoleRunner.runInBackground(System.in
						, System.out
						, monitoringAgent
						, _config
				);
			}
			else
			{
				System.out.println("Connecting to server: " + serverAddress);
				_loader = null;
				_config = null;
				_server = null;
				_client = new ClientProcess(new _ClientListener(), serverAddress.getAddress(), serverAddress.getPort(), clientName);
				_console = null;
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

	public void stepHorizontal(EntityChangeMove.Direction direction)
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		_client.moveHorizontalFully(direction, currentTimeMillis);
	}

	public boolean jumpOrSwim()
	{
		// See if we can jump or swim.
		boolean didMove = false;
		// Filter for redundant events.
		if (!_didJump)
		{
			Function<AbsoluteLocation, BlockProxy> previousBlockLookUp = (AbsoluteLocation location) -> {
				IReadOnlyCuboidData cuboid = _cuboids.get(location.getCuboidAddress());
				return (null != cuboid)
						? new BlockProxy(location.getBlockAddress(), cuboid)
						: null
				;
			};
			EntityLocation location = _thisEntity.location();
			EntityLocation vector = _thisEntity.velocity();
			long currentTimeMillis = System.currentTimeMillis();
			
			if (EntityChangeJump.canJump(previousBlockLookUp
					, location
					, EntityConstants.getVolume(EntityType.PLAYER)
					, vector
			))
			{
				EntityChangeJump<IMutablePlayerEntity> jumpChange = new EntityChangeJump<>();
				_client.sendAction(jumpChange, currentTimeMillis);
				didMove = true;
				_didJump = true;
			}
			else if (EntityChangeSwim.canSwim(previousBlockLookUp
					, location
					, vector
			))
			{
				EntityChangeSwim<IMutablePlayerEntity> swimChange = new EntityChangeSwim<>();
				_client.sendAction(swimChange, currentTimeMillis);
				didMove = true;
				_didJump = true;
			}
		}
		return didMove;
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

	public void runAction(EntityLocation logicalLocation)
	{
		// We need to check our selected item and see what "action" is associated with it.
		int selectedKey = _thisEntity.hotbarItems()[_thisEntity.hotbarIndex()];
		AbsoluteLocation blockLocation = logicalLocation.getBlockLocation();
		BlockProxy proxy = new BlockProxy(blockLocation.getBlockAddress(), _cuboids.get(blockLocation.getCuboidAddress()));
		Block targetBlock = proxy.getBlock();
		
		// First, see if the target block has a general logic state we can change.
		IMutationEntity<IMutablePlayerEntity> change;
		if (EntityChangeSetBlockLogicState.canChangeBlockLogicState(targetBlock))
		{
			boolean existingState = EntityChangeSetBlockLogicState.getCurrentBlockLogicState(targetBlock);
			change = new EntityChangeSetBlockLogicState(blockLocation, !existingState);
		}
		else if (Entity.NO_SELECTION != selectedKey)
		{
			// Check if a special use exists for this item and block or if we are just placing.
			Inventory inventory = _getEntityInventory();
			Items stack = inventory.getStackForKey(selectedKey);
			NonStackableItem nonStack = inventory.getNonStackableForKey(selectedKey);
			Item selectedType = (null != stack) ? stack.type() : nonStack.type();
			
			// First, can we use this on the block.
			if (EntityChangeUseSelectedItemOnBlock.canUseOnBlock(selectedType, targetBlock))
			{
				change = new EntityChangeUseSelectedItemOnBlock(blockLocation);
			}
			// See if this block can just be activated, directly.
			else if (EntityChangeSetBlockLogicState.canChangeBlockLogicState(targetBlock))
			{
				boolean existingState = EntityChangeSetBlockLogicState.getCurrentBlockLogicState(targetBlock);
				change = new EntityChangeSetBlockLogicState(blockLocation, !existingState);
			}
			// Check to see if we can use it, directly.
			else if (EntityChangeUseSelectedItemOnSelf.canBeUsedOnSelf(selectedType))
			{
				change = new EntityChangeUseSelectedItemOnSelf();
			}
			// Finally, default to trying to place it.
			else
			{
				// The mutation will check proximity and collision.
				// We will need to decide which block the selection is "facing".
				AbsoluteLocation blockOutput = _determineFacingBlock(logicalLocation);
				change = new MutationPlaceSelectedBlock(blockLocation, blockOutput);
			}
		}
		else
		{
			// Nothing to do.
			change = null;
		}
		
		if (null != change)
		{
			long currentTimeMillis = System.currentTimeMillis();
			_client.sendAction(change, currentTimeMillis);
		}
	}

	public void applyToEntity(PartialEntity selectedEntity)
	{
		// We need to check our selected item and see if there is some interaction it has with this entity.
		int selectedKey = _thisEntity.hotbarItems()[_thisEntity.hotbarIndex()];
		if (Entity.NO_SELECTION != selectedKey)
		{
			Inventory inventory = _getEntityInventory();
			Items stack = inventory.getStackForKey(selectedKey);
			NonStackableItem nonStack = inventory.getNonStackableForKey(selectedKey);
			Item selectedType = (null != stack) ? stack.type() : nonStack.type();
			
			if (EntityChangeUseSelectedItemOnEntity.canUseOnEntity(selectedType, selectedEntity.type()))
			{
				EntityChangeUseSelectedItemOnEntity change = new EntityChangeUseSelectedItemOnEntity(selectedEntity.id());
				long currentTimeMillis = System.currentTimeMillis();
				_client.sendAction(change, currentTimeMillis);
			}
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
			FuelState fuel = cuboid.getDataSpecial(AspectRegistry.FUELLED, blockAddress);
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
		Inventory inventory = _getEntityInventory();
		Items stack = inventory.getStackForKey(blockInventoryKey);
		NonStackableItem nonStack = inventory.getNonStackableForKey(blockInventoryKey);
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

	public void hitEntity(PartialEntity selectedEntity)
	{
		EntityChangeAttackEntity attack = new EntityChangeAttackEntity(selectedEntity.id());
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
			// Write-back the world config.
			try
			{
				_loader.storeWorldConfig(_config);
			}
			catch (IOException e)
			{
				// This shouldn't happen since we already loaded it at the beginning so this would be a serious, and odd, problem.
				throw Assert.unexpected(e);
			}
			try
			{
				_console.stop();
			}
			catch (InterruptedException e)
			{
				// We are just shutting down so we don't expect anything here.
				throw Assert.unexpected(e);
			}
		}
	}


	private void _setEntity(Entity thisEntity)
	{
		_thisEntity = thisEntity;
		_didJump = false;
	}

	private static AbsoluteLocation _determineFacingBlock(EntityLocation logicalLocation)
	{
		// See the closest horizontal edge to the logicalLocation.  If the distance is below our threshold, that is the
		// block it is "facing".  Otherwise, we will "face" the block below.
		double logicalY = logicalLocation.y();
		double logicalX = logicalLocation.x();
		double northDistance = Math.ceil(logicalY) - logicalY;
		double southDistance = logicalY - Math.floor(logicalY);
		double eastDistance = Math.ceil(logicalX) - logicalX;
		double westDistance = logicalX - Math.floor(logicalX);
		double vertical = Math.min(northDistance, southDistance);
		double horizontal = Math.min(eastDistance, westDistance);
		
		AbsoluteLocation facing;
		if ((vertical <= horizontal) && (vertical <= FACING_THRESHOLD))
		{
			// We are close enough so see if this is north/sound.
			int yOffset = (northDistance == vertical) ? 1 : -1;
			facing = logicalLocation.getBlockLocation().getRelative(0, yOffset, 0);
		}
		else if ((vertical > horizontal) && (horizontal <= FACING_THRESHOLD))
		{
			// We are close enough so see if this is east/west.
			int xOffset = (eastDistance == horizontal) ? 1 : -1;
			facing = logicalLocation.getBlockLocation().getRelative(xOffset, 0, 0);
		}
		else
		{
			// We are close to the centre so just face down.
			facing = logicalLocation.getBlockLocation().getRelative(0, 0, -1);
		}
		return facing;
	}

	private Inventory _getEntityInventory()
	{
		Inventory inventory = _thisEntity.isCreativeMode()
				? CreativeInventory.fakeInventory()
				: _thisEntity.inventory()
		;
		return inventory;
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
		public void thisEntityDidLoad(Entity authoritativeEntity)
		{
			Assert.assertTrue(_assignedLocalEntityId == authoritativeEntity.id());
			// To start, we will use the authoritative data as the projection.
			_setEntity(authoritativeEntity);
			_thisEntityConsumer.accept(authoritativeEntity, authoritativeEntity);
		}
		@Override
		public void thisEntityDidChange(Entity authoritativeEntity, Entity projectedEntity)
		{
			Assert.assertTrue(_assignedLocalEntityId == authoritativeEntity.id());
			// Locally, we just use the projection.
			_setEntity(projectedEntity);
			_thisEntityConsumer.accept(authoritativeEntity, projectedEntity);
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
