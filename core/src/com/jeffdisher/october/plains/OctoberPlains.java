package com.jeffdisher.october.plains;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.EntityType;
import com.jeffdisher.october.types.PartialEntity;


public class OctoberPlains extends ApplicationAdapter
{
	private final MouseHandler _mouseHandler = new MouseHandler(Math.round(1.0f / RenderSupport.TILE_EDGE_SIZE));
	private final WorldCache _worldCache = new WorldCache();
	private final String _clientName;
	private final InetSocketAddress _serverSocketAddress;

	private Environment _environment;
	private TextureAtlas _textureAtlas;
	private RenderSupport _renderer;
	private WindowManager _windowManager;

	private ClientLogic _client;

	public OctoberPlains(String[] commandLineArgs)
	{
		// See if we want to be single-player or connecting to a server.
		_CommandLineOptions options = _parseServerSocketAddress(commandLineArgs);
		_clientName = options.clientName;
		_serverSocketAddress = options.serverAddress;
	}

	@Override
	public void create ()
	{
		// Start up the shared environment.
		_environment = Environment.createSharedInstance();
		
		// Get the GLES20 context.
		GL20 gl = Gdx.graphics.getGL20();
		
		// Load the textures.
		try
		{
			// These are resolved by index so they must be loaded in the same order as the item registry.
			_textureAtlas = TextureAtlas.loadAtlas(gl, _environment.items.ITEMS_BY_TYPE
				, Map.of(EntityType.PLAYER, "entity_player.png"
						, EntityType.COW, "entity_cow.png"
						, EntityType.ORC, "entity_orc.png"
				)
				, Map.of(TextureAtlas.Auxiliary.NONE, "op.air.png"
						, TextureAtlas.Auxiliary.DEBRIS, "debris.png"
						, TextureAtlas.Auxiliary.BREAK_LIGHT, "break1.png"
						, TextureAtlas.Auxiliary.BREAK_MEDIUM, "break2.png"
						, TextureAtlas.Auxiliary.BREAK_HEAVY, "break3.png"
						, TextureAtlas.Auxiliary.ACTIVE_STATION, "active.png"
				)
				, "missing_texture.png"
			);
		}
		catch (IOException e)
		{
			// This is a fatal error.
			throw new AssertionError(e);
		}
		
		// Create the generic render support class.
		_renderer = new RenderSupport(_environment, gl, _textureAtlas);
		
		// Create the window manager.
		_windowManager = new WindowManager(_environment, gl, _textureAtlas, (AbsoluteLocation location) -> {
			return _worldCache.readBlock(location);
		});
		
		// At this point, we can also create the basic OctoberProject client and testing environment.
		_client = new ClientLogic(_environment
				, (Entity authoritativeEntity, Entity projectedEntity) -> {
					// The renderer and the mouse only want the projected location.
					EntityLocation projectedLocation = projectedEntity.location();
					_renderer.setThisEntityLocation(projectedLocation);
					_mouseHandler.setCentreLocation(projectedLocation);
					
					// The window manager needs both entities since it uses projected or authoritative for different data elements.
					_windowManager.setEntity(authoritativeEntity, projectedEntity);
				}
				, (PartialEntity entity) -> {
					// We notify both the renderer and the mouse handler about the entities.
					_renderer.setOtherEntity(entity);
					_mouseHandler.setOtherEntity(entity);
				}
				, (int entityId) -> {
					_renderer.removeEntity(entityId);
					_mouseHandler.removeOtherEntity(entityId);
				}
				, (IReadOnlyCuboidData cuboid) -> {
					// Update our data cache.
					_worldCache.setCuboid(cuboid);
					// Notify the renderer to redraw this cuboid.
					_renderer.setOneCuboid(cuboid);
				}
				, (CuboidAddress address) -> {
					// Delete thie from our cache.
					_worldCache.removeCuboid(address);
					// Notify the renderer to drop this from video memory.
					_renderer.removeCuboid(address);
				}
				, _clientName
				, _serverSocketAddress
		);
		_client.finishStartup();
		
		// Setup additional input handling.
		Gdx.input.setInputProcessor(new InputAdapter() {
			@Override
			public boolean scrolled(float amountX, float amountY)
			{
				// We will only handle the cases where the amountY is non-zero.
				boolean didHandle = false;
				if (0.0f != amountY)
				{
					if (amountY < 0.0f)
					{
						// Scroll up
						_renderer.changeZoom(0.1f);
					}
					else
					{
						// Scroll down
						_renderer.changeZoom(-0.1f);
					}
					didHandle = true;
				}
				return didHandle;
			}
			
		});
	}

	@Override
	public void render ()
	{
		// Find the mouse.
		float screenWidth = Gdx.graphics.getWidth();
		float screenHeight = Gdx.graphics.getHeight();
		float mouseX = (float)Gdx.input.getX();
		float mouseY = (float)Gdx.input.getY();
		// (screen coordinates are from the top-left and from 0-count whereas the scene is from bottom left and from -1.0 to 1.0).
		float glX = (2.0f * mouseX / screenWidth) - 1.0f;
		float glY = (2.0f * (screenHeight - mouseY) / screenHeight) - 1.0f;
		
		// Find out what we are hovering over in the tile background.
		int zOffset = (Gdx.input.isKeyPressed(Keys.Q))
				? 1
				: (Gdx.input.isKeyPressed(Keys.Z))
					? -1
					: 0
		;
		// The mouse handling for the tiles needs to know the renderer zoom level (overlay UI doesn't).
		float rendererZoom = _renderer.getZoom();
		float zoomX = glX / rendererZoom;
		float zoomY = glY / rendererZoom;
		EntityLocation logicalLocation = null;
		AbsoluteLocation selection = null;
		PartialEntity selectedEntity = _mouseHandler.entityUnderMouse(zoomX, zoomY);
		if (null == selectedEntity)
		{
			logicalLocation = _mouseHandler.getXyzTile(zoomX, zoomY, zOffset);
			selection = logicalLocation.getBlockLocation();
		}
		
		// Draw the scene.
		_renderer.renderScene((null != selectedEntity) ? selectedEntity.id() : 0, selection);
		
		// Draw any active windows over the scene and get the capture for anything we which can receive click events.
		WindowManager.EventHandler windowManagerEvent = _windowManager.drawWindowsWithButtonCapture(_client, glX, glY);
		
		// Handle inputs - we can handle some UI events at the same time as moving but only one move at a time.
		boolean didJump = false;
		if (Gdx.input.isKeyPressed(Keys.SPACE))
		{
			// We let them hold down the space key to make this experience seem more natural.
			didJump = _client.jumpOrSwim();
		}
		if (Gdx.input.isKeyJustPressed(Keys.I))
		{
			_windowManager.toggleInventory();
		}
		if (Gdx.input.isKeyJustPressed(Keys.F))
		{
			_windowManager.toggleFuelInventory();
		}
		if (Gdx.input.isKeyJustPressed(Keys.ESCAPE))
		{
			_windowManager.closeAllWindows();
		}
		
		// It looks like we may have to match these individually (arguably, assuming a relationship between them is wrong, anyway).
		if (Gdx.input.isKeyJustPressed(Keys.NUM_1))
		{
			_client.changeHotbar(0);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_2))
		{
			_client.changeHotbar(1);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_3))
		{
			_client.changeHotbar(2);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_4))
		{
			_client.changeHotbar(3);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_5))
		{
			_client.changeHotbar(4);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_6))
		{
			_client.changeHotbar(5);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_7))
		{
			_client.changeHotbar(6);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_8))
		{
			_client.changeHotbar(7);
		}
		else if (Gdx.input.isKeyJustPressed(Keys.NUM_9))
		{
			_client.changeHotbar(8);
		}
		
		// We will only allow a single direction movement at a time.
		if (didJump)
		{
			// Do nothing - we just prevent other movements.
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_UP) || Gdx.input.isKeyPressed(Keys.W))
		{
			_client.stepNorth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_DOWN) || Gdx.input.isKeyPressed(Keys.S))
		{
			_client.stepSouth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_RIGHT) || Gdx.input.isKeyPressed(Keys.D))
		{
			_client.stepEast();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_LEFT) || Gdx.input.isKeyPressed(Keys.A))
		{
			_client.stepWest();
		}
		else if (Gdx.input.isButtonPressed(0))
		{
			boolean isJustPressed = Gdx.input.isButtonJustPressed(0);
			if (null != windowManagerEvent)
			{
				// The they clicked something in the window overlay so run it if the button was just pressed.
				if (isJustPressed)
				{
					boolean isShiftHeld = Gdx.input.isKeyPressed(Keys.SHIFT_LEFT);
					if (isShiftHeld)
					{
						windowManagerEvent.shiftClick().run();
					}
					else
					{
						windowManagerEvent.click().run();
					}
				}
			}
			else
			{
				// They aren't using the windows so see if there is anything else they could be doing.
				if (null != selection)
				{
					// Whether they just pressed this or held it down, hit the block.
					_client.hitBlock(selection);
				}
				else if (null != selectedEntity)
				{
					// If they just clicked, we will record that they hit this entity.
					if (isJustPressed)
					{
						_client.hitEntity(selectedEntity);
					}
				}
			}
		}
		else if (Gdx.input.isButtonJustPressed(1))
		{
			if (null != windowManagerEvent)
			{
				windowManagerEvent.rightClick().run();
			}
			else if (null != selection)
			{
				// If we right-click on a crafting table, open that UI, apply right-click logic to the item, itself.
				if (!_windowManager.didOpenInventory(selection))
				{
					_client.runAction(logicalLocation);
				}
			}
			else if (null != selectedEntity)
			{
				// Use whatever we have selected on this entity.
				_client.applyToEntity(selectedEntity);
			}
		}
		else
		{
			// See if we have a crafting table open.
			AbsoluteLocation craftingTable = _windowManager.getActiveCraftingTable();
			if (null != craftingTable)
			{
				// Try to continue anything happening here.
				_client.beginCraftInBlock(craftingTable, null);
			}
			else
			{
				// Default to whatever else is going on.
				_client.doNothing();
			}
		}
	}

	@Override
	public void dispose ()
	{
		// Disconnect from the server.
		_client.disconnect();
		
		// Shut down anything the renderer has.
		_renderer.shutdown();
		
		// Tear-down the shared environment.
		Environment.clearSharedInstance();
		_environment = null;
	}


	private static _CommandLineOptions _parseServerSocketAddress(String[] commandLineArgs)
	{
		// Check the first arg for the mode.
		_CommandLineOptions options;
		// (we probably want to handle this parsing and validation elsewhere or differently but this will get us going without over-designing).
		if (commandLineArgs.length >= 1)
		{
			if ("--single".equals(commandLineArgs[0]))
			{
				options = new _CommandLineOptions("Local", null);
			}
			else if ("--multi".equals(commandLineArgs[0]))
			{
				if (4 == commandLineArgs.length)
				{
					String clientName = commandLineArgs[1];
					String host = commandLineArgs[2];
					int port = Integer.parseInt(commandLineArgs[3]);
					System.out.println("Resolving host: " + host);
					options = new _CommandLineOptions(clientName, new InetSocketAddress(host, port));
				}
				else
				{
					throw _usageError();
				}
			}
			else
			{
				throw _usageError();
			}
		}
		else
		{
			throw _usageError();
		}
		return options;
	}

	private static RuntimeException _usageError()
	{
		System.err.println("Args:  (--single)|(--multi user_name host port)");
		System.exit(1);
		return null;
	}


	private static record _CommandLineOptions(String clientName
			, InetSocketAddress serverAddress
	) {}
}
