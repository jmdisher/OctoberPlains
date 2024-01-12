package com.jeffdisher.october.plains;

import java.io.IOException;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;


public class OctoberPlains extends ApplicationAdapter
{
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
		// Draw the scene.
		_renderer.renderScene();
		
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
