package com.jeffdisher.october.plains;

import java.io.IOException;

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
	private static int TEMP_COUNTER = 0;

	private TextureAtlas _textureAtlas;
	private RenderSupport _renderer;

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
					"unknown.jpeg",
			});
		}
		catch (IOException e)
		{
			// This is a fatal error.
			throw new AssertionError(e);
		}
		
		// Create the generic render support class.
		_renderer = new RenderSupport(gl, _textureAtlas);
		
		// At this point, we can also create the basic OctoberProject client and testing environment.
		_client = new ClientLogic((Entity entity) -> _renderer.setThisEntity(entity)
				, (IReadOnlyCuboidData cuboid) -> _renderer.setOneCuboid(cuboid)
				, (CuboidAddress address) -> _renderer.removeCuboid(address)
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
		int zOffset = (Gdx.input.isKeyPressed(Keys.Q))
				? 1
				: (Gdx.input.isKeyPressed(Keys.Z))
					? -1
					: 0
		;
		
		// Draw the scene.
		_renderer.renderScene(Integer.toString(TEMP_COUNTER), 0.0f, 0.0f, glX, glY, zOffset);
		TEMP_COUNTER += 1;
		
		// Handle inputs - we will only allow a single direction at a time.
		if (Gdx.input.isKeyJustPressed(Keys.SPACE))
		{
			_client.jump();
		}
		else if (Gdx.input.isKeyJustPressed(Keys.SHIFT_LEFT))
		{
			// We will pick up anything on this tile.
			_client.pickUpItemsOnOurTile();
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
		else if (Gdx.input.isButtonJustPressed(0))
		{
			// If they press left click, start breaking a block.
			AbsoluteLocation blockLocation = _renderer.entityOffset(glX, glY, zOffset);
			_client.beginBreakingBlock(blockLocation);
		}
		else if (Gdx.input.isButtonJustPressed(1))
		{
			// If they press right click, place our block (this will implicitly select stone).
			AbsoluteLocation blockLocation = _renderer.entityOffset(glX, glY, zOffset);
			_client.placeBlock(blockLocation);
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
