package com.jeffdisher.october.plains;

import java.util.function.Consumer;

import com.jeffdisher.october.changes.IEntityChange;
import com.jeffdisher.october.client.ClientRunner;
import com.jeffdisher.october.client.IClientAdapter;
import com.jeffdisher.october.client.SpeculativeProjection;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.logic.EntityActionValidator;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.utils.Assert;
import com.jeffdisher.october.worldgen.CuboidGenerator;


public class ClientLogic
{
	public static final int ENTITY_ID = 1;
	public static final float INCREMENT = 0.02f;

	private final Consumer<Entity> _thisEntityConsumer;
	private final Consumer<IReadOnlyCuboidData> _changedCuboidConsumer;
	private final Consumer<CuboidAddress> _removedCuboidConsumer;

	private final FakeNetwork _network;
	private final ProjectionListener _projectionListener;
	private final ClientListener _clientListener;

	private final ClientRunner _client;
	private long _nextFakeCommitNumber;
	private Entity _thisEntity;
	private long _latestLocalCommit;

	public ClientLogic(Consumer<Entity> thisEntityConsumer
			, Consumer<IReadOnlyCuboidData> changedCuboidConsumer
			, Consumer<CuboidAddress> removedCuboidConsumer
	)
	{
		_thisEntityConsumer = thisEntityConsumer;
		_changedCuboidConsumer = changedCuboidConsumer;
		_removedCuboidConsumer = removedCuboidConsumer;
		
		_network = new FakeNetwork();
		_projectionListener = new ProjectionListener();
		_clientListener = new ClientListener();
		_client = new ClientRunner(_network, _projectionListener, _clientListener);
		_nextFakeCommitNumber = 0L;
		_latestLocalCommit = 0L;
	}

	public void finishStartup()
	{
		// At this point, we will just feed the test data into the client.
		_network.listener.adapterConnected(ENTITY_ID);
		_network.listener.receivedEntity(EntityActionValidator.buildDefaultEntity(ENTITY_ID));
		
		// Since the location is standing in 0.0, we need to load at least the 8 cuboids around the origin.
		// Note that we want them to stand on the ground so we will fill the bottom 4 with stone and the top 4 with air.
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)0, (short)0), ItemRegistry.AIR));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)-1, (short)0), ItemRegistry.AIR));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)-1, (short)0), ItemRegistry.AIR));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)0, (short)0), ItemRegistry.AIR));
		
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)0, (short)-1), ItemRegistry.STONE));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)0, (short)-1, (short)-1), ItemRegistry.STONE));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)-1, (short)-1), ItemRegistry.STONE));
		_network.listener.receivedCuboid(CuboidGenerator.createFilledCuboid(new CuboidAddress((short)-1, (short)0, (short)-1), ItemRegistry.STONE));
		_endTick(System.currentTimeMillis());
	}

	public void stepNorth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			EntityLocation oldLocation = _thisEntity.location();
			EntityLocation newLocation = new EntityLocation(oldLocation.x(), oldLocation.y() + INCREMENT, oldLocation.z());
			_client.moveTo(newLocation, currentTimeMillis);
			_endTick(currentTimeMillis);
		}
	}

	public void stepSouth()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			EntityLocation oldLocation = _thisEntity.location();
			EntityLocation newLocation = new EntityLocation(oldLocation.x(), oldLocation.y() - INCREMENT, oldLocation.z());
			_client.moveTo(newLocation, currentTimeMillis);
			_endTick(currentTimeMillis);
		}
	}

	public void stepEast()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			EntityLocation oldLocation = _thisEntity.location();
			EntityLocation newLocation = new EntityLocation(oldLocation.x() + INCREMENT, oldLocation.y(), oldLocation.z());
			_client.moveTo(newLocation, currentTimeMillis);
			_endTick(currentTimeMillis);
		}
	}

	public void stepWest()
	{
		// Make sure that there is no in-progress action, first.
		long currentTimeMillis = System.currentTimeMillis();
		if (!_client.isActivityInProgress(currentTimeMillis))
		{
			EntityLocation oldLocation = _thisEntity.location();
			EntityLocation newLocation = new EntityLocation(oldLocation.x() - INCREMENT, oldLocation.y(), oldLocation.z());
			_client.moveTo(newLocation, currentTimeMillis);
			_endTick(currentTimeMillis);
		}
	}


	private void _endTick(long currentTimeMillis)
	{
		_network.listener.receivedEndOfTick(_nextFakeCommitNumber++, _latestLocalCommit);
		_client.runPendingCalls(currentTimeMillis);
	}


	private class FakeNetwork implements IClientAdapter
	{
		public IClientAdapter.IListener listener;
		@Override
		public void connectAndStartListening(IClientAdapter.IListener listener)
		{
			Assert.assertTrue(null == this.listener);
			this.listener = listener;
		}
		@Override
		public void disconnect()
		{
		}
		@Override
		public void sendChange(IEntityChange change, long commitLevel)
		{
			// We just stuff this back in.
			_latestLocalCommit = commitLevel;
			this.listener.receivedChange(ENTITY_ID, change);
		}
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
