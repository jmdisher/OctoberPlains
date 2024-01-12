package com.jeffdisher.october.plains;

import java.util.function.Consumer;

import com.jeffdisher.october.client.ClientRunner;
import com.jeffdisher.october.client.SpeculativeProjection;
import com.jeffdisher.october.data.CuboidData;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.integration.LocalServerShim;
import com.jeffdisher.october.registries.AspectRegistry;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.server.ServerRunner;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
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
		catch (InterruptedException e)
		{
			// We don't use interruption.
			throw Assert.unexpected(e);
		}
		_projectionListener = new ProjectionListener();
		_clientListener = new ClientListener();
		_client = new ClientRunner(_shim.getClientAdapter(), _projectionListener, _clientListener);
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
		_shim.injectCuboidToServer(_generateColumnCuboid(new CuboidAddress((short)0, (short)0, (short)0)));
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
			_client.runPendingCalls(currentTimeMillis);
		}
	}

	public void stepSouth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(0.0f, -INCREMENT, currentTimeMillis);
			_client.runPendingCalls(currentTimeMillis);
		}
	}

	public void stepEast()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(+INCREMENT, 0.0f, currentTimeMillis);
			_client.runPendingCalls(currentTimeMillis);
		}
	}

	public void stepWest()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			_client.moveHorizontal(-INCREMENT, 0.0f, currentTimeMillis);
			_client.runPendingCalls(currentTimeMillis);
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
			_changedCuboidConsumer.accept(cuboid);
		}
		@Override
		public void cuboidDidLoad(IReadOnlyCuboidData cuboid)
		{
			_changedCuboidConsumer.accept(cuboid);
		}
		@Override
		public void cuboidDidUnload(CuboidAddress address)
		{
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
