package com.jeffdisher.october.plains;

import java.io.IOException;
import java.util.function.Consumer;

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

	private TextureAtlas _textureAtlas;
	private RenderSupport _renderer;
	private WindowManager _windowManager;

	private ClientLogic _client;

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
				}
				// Player.
				, "unknown.jpeg"
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
		Consumer<ClientLogic> clickButtonCapture = _windowManager.drawWindowsWithButtonCapture(glX, glY);
		
		// Handle inputs - we will only allow a single direction at a time.
		if (Gdx.input.isKeyJustPressed(Keys.SPACE))
		{
			_client.jump();
		}
		if (Gdx.input.isKeyPressed(Keys.DPAD_UP))
		{
			_client.stepNorth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_DOWN))
		{
			_client.stepSouth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_RIGHT))
		{
			_client.stepEast();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_LEFT))
		{
			_client.stepWest();
		}
		else if (Gdx.input.isKeyJustPressed(Keys.I))
		{
			_windowManager.toggleInventory();
		}
		else if (Gdx.input.isButtonJustPressed(0))
		{
			// See if they have a button.
			if (null != clickButtonCapture)
			{
				clickButtonCapture.accept(_client);
			}
			if (null != selection)
			{
				// If they press left click, start breaking a block.
				_client.beginBreakingBlock(selection);
			}
		}
		else if (Gdx.input.isButtonJustPressed(1))
		{
			if (null != selection)
			{
				// If they press right click, place our block (this will implicitly select stone).
				_client.placeBlock(selection);
			}
		}
		else
		{
			_client.doNothing();
		}
	}

	@Override
	public void dispose ()
	{
		_client.disconnect();
	}
}
