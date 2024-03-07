package com.jeffdisher.october.plains;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;


public class OctoberPlains extends ApplicationAdapter
{
	private final MouseHandler _mouseHandler = new MouseHandler(Math.round(1.0f / RenderSupport.TILE_EDGE_SIZE));
	private final WorldCache _worldCache = new WorldCache();
	private final InetSocketAddress _serverSocketAddress;

	private TextureAtlas _textureAtlas;
	private RenderSupport _renderer;
	private WindowManager _windowManager;

	private ClientLogic _client;

	public OctoberPlains(String[] commandLineArgs)
	{
		// See if we want to be single-player or connecting to a server.
		_serverSocketAddress = _parseServerSocketAddress(commandLineArgs);
	}

	@Override
	public void create ()
	{
		// Get the GLES20 context.
		GL20 gl = Gdx.graphics.getGL20();
		
		// Load the textures.
		try
		{
			// These are resolved by index so they must be loaded in the same order as the item registry.
			_textureAtlas = TextureAtlas.loadAtlas(gl, new String[] {
					"air.png",
					"stone.png",
					"log.png",
					"plank.png",
					"stone_brick.png",
					"crafting_table.png",
					"furnace.png",
				}
				, new String[] {
					"air.png",
					"break1.png",
					"break2.png",
					"break3.png",
					"active.png",
				}
				// Player.
				, "player.png"
				// Debris.
				, "debris.png"
			);
		}
		catch (IOException e)
		{
			// This is a fatal error.
			throw new AssertionError(e);
		}
		
		// Create the generic render support class.
		_renderer = new RenderSupport(gl, _textureAtlas);
		
		// Create the window manager.
		_windowManager = new WindowManager(gl, _textureAtlas, (AbsoluteLocation location) -> {
			return _worldCache.readBlock(location);
		});
		
		// At this point, we can also create the basic OctoberProject client and testing environment.
		_client = new ClientLogic((Entity entity) -> {
					_renderer.setThisEntity(entity);
					_mouseHandler.setCentreLocation(entity.location());
					_windowManager.setEntity(entity);
				}
				, (Entity entity) -> {
					_renderer.setOtherEntity(entity);
				}
				, (int entityId) -> {
					_renderer.removeEntity(entityId);
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
				, _serverSocketAddress
		);
		_client.finishStartup();
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
		AbsoluteLocation selection = _mouseHandler.getXyzTile(glX, glY, zOffset);
		
		// Draw the scene.
		_renderer.renderScene(selection);
		
		// Draw any active windows over the scene and get the capture for anything we which can receive click events.
		Runnable clickButtonCapture = _windowManager.drawWindowsWithButtonCapture(_client, glX, glY);
		
		// Handle inputs - we will only allow a single direction at a time.
		if (Gdx.input.isKeyJustPressed(Keys.SPACE))
		{
			_client.jump();
		}
		if (Gdx.input.isKeyPressed(Keys.DPAD_UP) || Gdx.input.isKeyPressed(Keys.W))
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
		else if (Gdx.input.isKeyJustPressed(Keys.I))
		{
			_windowManager.toggleInventory();
		}
		else if (Gdx.input.isKeyJustPressed(Keys.F))
		{
			_windowManager.toggleFuelInventory();
		}
		else if (Gdx.input.isButtonPressed(0))
		{
			// See if they have a button.
			if (Gdx.input.isButtonJustPressed(0) && (null != clickButtonCapture))
			{
				clickButtonCapture.run();
			}
			if (null != selection)
			{
				// As long as they are holding the left button, hit the block.
				_client.hitBlock(selection);
			}
		}
		else if (Gdx.input.isButtonJustPressed(1))
		{
			if (null != selection)
			{
				// If we right-click on a crafting table, open that UI, otherwise we will place the selected block.
				if (!_windowManager.didOpenInventory(selection))
				{
					// If they press right click, place our block (this will implicitly select stone).
					_client.placeBlock(selection);
				}
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
		_client.disconnect();
	}


	private static InetSocketAddress _parseServerSocketAddress(String[] commandLineArgs)
	{
		// Check the first arg for the mode.
		InetSocketAddress serverAddress;
		// (we probably want to handle this parsing and validation elsewhere or differently but this will get us going without over-designing).
		if (commandLineArgs.length >= 1)
		{
			if ("--single".equals(commandLineArgs[0]))
			{
				serverAddress = null;
			}
			else if ("--multi".equals(commandLineArgs[0]))
			{
				if (3 == commandLineArgs.length)
				{
					String host = commandLineArgs[1];
					int port = Integer.parseInt(commandLineArgs[2]);
					System.out.println("Resolving host: " + host);
					serverAddress = new InetSocketAddress(host, port);
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
		return serverAddress;
	}

	private static RuntimeException _usageError()
	{
		System.err.println("Args:  (--single)|(--multi host port)");
		System.exit(1);
		return null;
	}
}
