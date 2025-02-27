package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.ColumnHeightMap;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.EntityType;
import com.jeffdisher.october.types.EntityVolume;
import com.jeffdisher.october.types.PartialEntity;
import com.jeffdisher.october.utils.Assert;


public class RenderSupport
{
	// The screen is 2.0/2.0 (-1.0 - 1.0) and we want roughly 40x40 tiles on screen, so use this tile edge size.
	public static final float TILE_EDGE_SIZE = 0.05f;
	public static final int CUBOID_EDGE_TILE_COUNT = 32;
	public static final int VERTICES_PER_SQUARE = 6;

	public static int fullyLinkedProgram(GL20 gl, String vertexSource, String fragmentSource, String[] attributesInOrder)
	{
		return _fullyLinkedProgram(gl, vertexSource, fragmentSource, attributesInOrder);
	}


	// We need to render 2 kinds of things:  (1) Cuboid layers, (2) entities.
	// We will just use a single pair of shaders, at least for now, for both of these cases:
	// -vertex shader will move the rendering location by x/y uniform and pass through the texture u/v coordinates attribute
	// -fragment shader will sample the referenced texture coordinates and apply an alpha value
	// These shaders will be used for both rendering the layers and also the entities, just using different uniforms.
	private final Environment _environment;
	private final GL20 _gl;
	private final TextureAtlas _textureAtlas;
	private final LayerManager _layerManager;
	
	private int _program;
	private int _uOffset;
	private int _uScale;
	private int _uSceneScale;
	private int _uSkyLight;
	private int _uTexture0;
	private int _uTexture1;
	private int _uLayerBrightness;
	private int _uLayerAlpha;
	private int _uColourBias;
	private int[] _entityBuffers;
	private int _layerMeshBuffer;

	private EntityLocation _projectedEntityLocation;
	private final Map<Integer, PartialEntity> _otherEntitiesById;
	private float _currentSceneScale;
	private float _currentSkyLightMultiplier;

	public RenderSupport(Environment environment, GL20 gl, TextureAtlas textureAtlas)
	{
		_environment = environment;
		_gl = gl;
		_textureAtlas = textureAtlas;
		_layerManager = new LayerManager(environment, gl, textureAtlas);
		
		// We want to honour alpha channels.
		_gl.glEnable(GL20.GL_BLEND);
		_gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
		
		// Create the shader program.
		_program = _fullyLinkedProgram(_gl
				, "#version 100\n"
						+ "attribute vec2 aPosition;\n"
						+ "attribute vec2 aTexture0;\n"
						+ "attribute vec2 aTexture1;\n"
						+ "attribute float aBlockLightMultiplier;\n"
						+ "attribute float aSkyLightMultiplier;\n"
						+ "uniform vec2 uOffset;\n"
						+ "uniform float uScale;\n"
						+ "uniform float uSceneScale;\n"
						+ "uniform float uSkyLight;\n"
						+ "varying vec2 vTexture0;\n"
						+ "varying vec2 vTexture1;\n"
						+ "varying float vLightMultiplier;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vTexture0 = aTexture0;\n"
						+ "	vTexture1 = aTexture1;\n"
						+ "	vLightMultiplier = clamp(aBlockLightMultiplier + (aSkyLightMultiplier * uSkyLight), 0.0, 1.0);\n"
						+ "	gl_Position = vec4(uSceneScale * ((uScale * aPosition.x) + uOffset.x), uSceneScale * ((uScale * aPosition.y) + uOffset.y), 0.0, 1.0);\n"
						+ "}\n"
				, "#version 100\n"
						+ "precision mediump float;\n"
						+ "uniform sampler2D uTexture0;\n"
						+ "uniform sampler2D uTexture1;\n"
						+ "uniform float uLayerBrightness;\n"
						+ "uniform float uLayerAlpha;\n"
						+ "uniform vec4 uColourBias;\n"
						+ "varying vec2 vTexture0;\n"
						+ "varying vec2 vTexture1;\n"
						+ "varying float vLightMultiplier;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec4 tex0 = texture2D(uTexture0, vTexture0);\n"
						+ "	vec4 tex1 = texture2D(uTexture1, vTexture1);\n"
						+ "	vec4 tex = mix(tex0, tex1, tex1.a);\n"
						+ "	vec4 biased = vec4(clamp(uColourBias.r + tex.r, 0.0, 1.0), clamp(uColourBias.g + tex.g, 0.0, 1.0), clamp(uColourBias.b + tex.b, 0.0, 1.0), clamp(uColourBias.a + tex.a, 0.0, 1.0));\n"
						+ "	gl_FragColor = vec4(uLayerBrightness * vLightMultiplier * biased.r, uLayerBrightness * vLightMultiplier * biased.g, uLayerBrightness * vLightMultiplier * biased.b, uLayerAlpha * biased.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture0",
						"aTexture1",
						"aBlockLightMultiplier",
						"aSkyLightMultiplier",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uScale = _gl.glGetUniformLocation(_program, "uScale");
		_uSceneScale = _gl.glGetUniformLocation(_program, "uSceneScale");
		_uSkyLight = _gl.glGetUniformLocation(_program, "uSkyLight");
		_uTexture0 = _gl.glGetUniformLocation(_program, "uTexture0");
		_uTexture1 = _gl.glGetUniformLocation(_program, "uTexture1");
		_uLayerBrightness = _gl.glGetUniformLocation(_program, "uLayerBrightness");
		_uLayerAlpha = _gl.glGetUniformLocation(_program, "uLayerAlpha");
		_uColourBias = _gl.glGetUniformLocation(_program, "uColourBias");
		
		// Define the entity mesh and texture for each entity type (in the future, we should probably avoid so many small representations).
		_entityBuffers = new int[environment.creatures.ENTITY_BY_NUMBER.length];
		for (EntityType type : environment.creatures.ENTITY_BY_NUMBER)
		{
			// Note that the first entry is null, for historical reasons.
			if (null != type)
			{
				_entityBuffers[type.number()] = _defineEntityBuffer(environment, _gl, _textureAtlas, type);
			}
		}
		
		// Define the layer mesh.
		_layerMeshBuffer = _defineLayerMeshBuffer(_gl);
		
		_otherEntitiesById = new HashMap<>();
		_currentSceneScale = 1.0f;
	}

	/**
	 * Renders a single frame of the scene.
	 * 
	 * @param selectedEntity The ID of the entity currently under the mouse (0 if there isn't one).
	 * @param selectedLocation The block currently under the mouse (can be null).
	 */
	public void renderScene(int selectedEntity, AbsoluteLocation selectedLocation)
	{
		// Process any background layer baking.
		_layerManager.completeBackgroundBakeRequest();
		
		// We render this relative to the entity, so figure out where it is.
		AbsoluteLocation entityBlockLocation = _projectedEntityLocation.getBlockLocation();
		float x = _projectedEntityLocation.x();
		float y = _projectedEntityLocation.y();
		
		// Determine which tile is selected under the mouse.
		CuboidAddress selectedCuboid = null;
		BlockAddress selectedBlock = null;
		if (null != selectedLocation)
		{
			selectedCuboid = selectedLocation.getCuboidAddress();
			
			// This may not be here if the server hasn't sent it yet.
			selectedBlock = _layerManager.containsCuboid(selectedCuboid)
					? selectedLocation.getBlockAddress()
					: null
			;
		}
		
		// Reset screen.
		_gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		_gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		_gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		_gl.glUseProgram(_program);
		
		// Make sure that the texture atlas is active.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.tileTextures);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glActiveTexture(GL20.GL_TEXTURE1);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.auxTextures);
		_gl.glUniform1i(_uTexture1, 1);
		
		// Set any starting uniform values.
		_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
		_gl.glUniform1f(_uScale, 1.0f);
		_gl.glUniform1f(_uSceneScale, _currentSceneScale);
		_gl.glUniform1f(_uSkyLight, _currentSkyLightMultiplier);
		
		// We want to render 9 tiles with 3 layers:  3x3x3, centred around the entity location.
		// (technically 4 tiles with 3 layers would be enough but that would require some extra logic)
		float layerBrightness = 0.50f;
		for (int zOffset = -1; zOffset <= 1; ++zOffset)
		{
			_gl.glUniform1f(_uLayerBrightness, layerBrightness);
			_gl.glUniform1f(_uLayerAlpha, (1 == zOffset) ? 0.5f : 1.0f);
			layerBrightness += 0.25f;
			for (int xOffset = -CUBOID_EDGE_TILE_COUNT; xOffset <= CUBOID_EDGE_TILE_COUNT; xOffset += CUBOID_EDGE_TILE_COUNT)
			{
				for (int yOffset = -CUBOID_EDGE_TILE_COUNT; yOffset <= CUBOID_EDGE_TILE_COUNT; yOffset += CUBOID_EDGE_TILE_COUNT)
				{
					AbsoluteLocation offsetLocation = entityBlockLocation.getRelative(xOffset, yOffset, zOffset);
					CuboidAddress address = offsetLocation.getCuboidAddress();
					byte zLayer = offsetLocation.getBlockAddress().z();
					
					int buffer = _layerManager.getBakedLayer(address, zLayer);
					if (0 != buffer)
					{
						// Be sure to position the camera above the entity, so calculate the offset where we will draw this layer.
						float xCamera = TILE_EDGE_SIZE * ((float)(address.x() * CUBOID_EDGE_TILE_COUNT) - x);
						float yCamera = TILE_EDGE_SIZE * ((float)(address.y() * CUBOID_EDGE_TILE_COUNT) - y);
						_gl.glUniform2f(_uOffset, xCamera, yCamera);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerMeshBuffer);
						_gl.glEnableVertexAttribArray(0);
						_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, buffer);
						_gl.glEnableVertexAttribArray(1);
						_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, LayerManager.SINGLE_VERTEX_BUFFER_BYTES, 0);
						_gl.glEnableVertexAttribArray(2);
						_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, LayerManager.SINGLE_VERTEX_BUFFER_BYTES, 2 * Float.BYTES);
						_gl.glEnableVertexAttribArray(3);
						_gl.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, LayerManager.SINGLE_VERTEX_BUFFER_BYTES, 4 * Float.BYTES);
						_gl.glEnableVertexAttribArray(4);
						_gl.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, LayerManager.SINGLE_VERTEX_BUFFER_BYTES, 5 * Float.BYTES);
						
						_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT * VERTICES_PER_SQUARE);
						
						// Check if this is where the selected tile is and then re-draw it to highlight it.
						if ((null != selectedBlock) && (zLayer == selectedBlock.z()) && selectedCuboid.equals(address))
						{
							// Give it a pinkish hue (with added alpha so we can select air blocks if placing a block).
							_gl.glUniform4f(_uColourBias, 0.5f, 0.0f, 0.5f, 0.5f);
							
							// Redraw the single tile.
							_gl.glDrawArrays(GL20.GL_TRIANGLES, VERTICES_PER_SQUARE * ((CUBOID_EDGE_TILE_COUNT * selectedBlock.y()) + selectedBlock.x()), VERTICES_PER_SQUARE);
							
							_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
						}
					}
				}
			}
			
			if (0 == zOffset)
			{
				// Draw the entity.
				_drawEntity(0.0f, 0.0f, _environment.creatures.PLAYER.volume().width(), _environment.creatures.PLAYER);
			}
			
			// See if there are any other entities at this z-level (we should organize this differently, or pre-sort it, in the future).
			int thisZ = Math.round(_projectedEntityLocation.z()) + zOffset;
			for (PartialEntity otherEntity : _otherEntitiesById.values())
			{
				EntityLocation location = otherEntity.location();
				int otherZ = Math.round(location.z());
				if (thisZ == otherZ)
				{
					// Figure out the offset.
					float xOffset = TILE_EDGE_SIZE * (location.x() - _projectedEntityLocation.x());
					float yOffset = TILE_EDGE_SIZE * (location.y() - _projectedEntityLocation.y());
					EntityVolume volume = otherEntity.type().volume();
					float scale = volume.width();
					
					// See if this is the entity under the mouse.
					if (otherEntity.id() == selectedEntity)
					{
						// Give it a red colour.
						_gl.glUniform4f(_uColourBias, 1.0f, 0.0f, 0.0f, 1.0f);
						_drawEntity(xOffset, yOffset, scale, otherEntity.type());
						_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
						
					}
					else
					{
						_drawEntity(xOffset, yOffset, scale, otherEntity.type());
					}
				}
			}
		}
	}

	public void setThisEntityLocation(EntityLocation projectedEntityLocation)
	{
		_projectedEntityLocation = projectedEntityLocation;
	}

	public void setOneCuboid(IReadOnlyCuboidData cuboid, ColumnHeightMap heightMap)
	{
		_layerManager.storeCuboid(cuboid, heightMap);
	}

	public void removeCuboid(CuboidAddress address)
	{
		_layerManager.removeCuboid(address);
	}

	public void setOtherEntity(PartialEntity entity)
	{
		_otherEntitiesById.put(entity.id(), entity);
	}

	public void removeEntity(int entityId)
	{
		PartialEntity old = _otherEntitiesById.remove(entityId);
		// This must have already been here.
		Assert.assertTrue(null != old);
	}

	public void changeZoom(float degree)
	{
		_currentSceneScale += degree;
		if (_currentSceneScale < 1.0f)
		{
			_currentSceneScale = 1.0f;
		}
		else if (_currentSceneScale > 4.0f)
		{
			_currentSceneScale = 4.0f;
		}
	}

	public float getZoom()
	{
		return _currentSceneScale;
	}

	public void setSkyLightMultiplier(float multiplier)
	{
		_currentSkyLightMultiplier = multiplier;
	}

	/**
	 * Shuts down any resources associated with the scene renderer.
	 */
	public void shutdown()
	{
		// The layer manager actually runs a background thread for the layer baking so shut it down.
		_layerManager.shutdown();
	}


	private static int _fullyLinkedProgram(GL20 gl, String vertexSource, String fragmentSource, String[] attributesInOrder)
	{
		int program = gl.glCreateProgram();
		_compileAndAttachShader(gl, program, GL20.GL_VERTEX_SHADER, vertexSource);
		_compileAndAttachShader(gl, program, GL20.GL_FRAGMENT_SHADER, fragmentSource);
		for (int index = 0; index < attributesInOrder.length; ++index)
		{
			gl.glBindAttribLocation(program, index, attributesInOrder[index]);
		}
		gl.glLinkProgram(program);
		return program;
	}

	private static int _compileAndAttachShader(GL20 gl, int program, int shaderType, String source)
	{
		int shader = gl.glCreateShader(shaderType);
		gl.glShaderSource(shader, source);
		gl.glCompileShader(shader);
		ByteBuffer direct = ByteBuffer.allocateDirect(Integer.BYTES);
		direct.order(ByteOrder.nativeOrder());
		IntBuffer buffer = direct.asIntBuffer();
		buffer.put(-1);
		((java.nio.Buffer) buffer).flip();
		gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, buffer);
		if (1 != buffer.get())
		{
			throw new AssertionError("Failed to compile");
		}
		gl.glAttachShader(program, shader);
		return shader;
	}

	private static int _defineEntityBuffer(Environment env, GL20 gl, TextureAtlas atlas, EntityType type)
	{
		// We just draw the player and drawn over air.
		float textureSize0 = atlas.tileCoordinateSize;
		float textureSize1 = atlas.entityCoordinateSize;
		float[] uv0 = atlas.baseOfTileTexture(env.special.AIR.item());
		float textureBase0U = uv0[0];
		float textureBase0V = uv0[1];
		float[] uv1 = atlas.baseOfEntityTexture(type);
		float textureBase1U = uv1[0];
		float textureBase1V = uv1[1];
		
		// Note that we will use full light for block light and sky light since entities are currently always full bright.
		float lightMultiplier = 1.0f;
		// NOTE:  We invert the textures coordinates here (probably not ideal).
		float[] vertices = new float[] {
				// Bottom left.
				0.0f, 0.0f,
					textureBase0U, textureBase0V + textureSize0,
					textureBase1U, textureBase1V + textureSize1,
					lightMultiplier,
					lightMultiplier,
				// Bottom right.
				TILE_EDGE_SIZE, 0.0f,
					textureBase0U + textureSize0, textureBase0V + textureSize0,
					textureBase1U + textureSize1, textureBase1V + textureSize1,
					lightMultiplier,
					lightMultiplier,
				// Top right.
				TILE_EDGE_SIZE, TILE_EDGE_SIZE,
					textureBase0U + textureSize0, textureBase0V,
					textureBase1U + textureSize1, textureBase1V,
					lightMultiplier,
					lightMultiplier,
				
				// Bottom left.
				0.0f, 0.0f,
					textureBase0U, textureBase0V + textureSize0,
					textureBase1U, textureBase1V + textureSize1,
					lightMultiplier,
					lightMultiplier,
				// Top right.
				TILE_EDGE_SIZE, TILE_EDGE_SIZE,
					textureBase0U + textureSize0, textureBase0V,
					textureBase1U + textureSize1, textureBase1V,
					lightMultiplier,
					lightMultiplier,
				// Top left.
				 0.0f, TILE_EDGE_SIZE,
					textureBase0U, textureBase0V,
					textureBase1U, textureBase1V,
					lightMultiplier,
					lightMultiplier,
		};
		
		ByteBuffer direct = ByteBuffer.allocateDirect(vertices.length * Float.BYTES);
		direct.order(ByteOrder.nativeOrder());
		direct.asFloatBuffer().put(vertices);
		((java.nio.Buffer) direct).position(0);
		
		int entityBuffer = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, entityBuffer);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, direct.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 2 * Float.BYTES);
		gl.glEnableVertexAttribArray(2);
		gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 4 * Float.BYTES);
		gl.glEnableVertexAttribArray(3);
		gl.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
		gl.glEnableVertexAttribArray(4);
		gl.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, 8 * Float.BYTES, 7 * Float.BYTES);
		return entityBuffer;
	}

	private static int _defineLayerMeshBuffer(GL20 gl)
	{
		// We render each layer as an abstract 32x32 tile mesh.
		// The common mesh has 2 floats per vertex:  x and y (z is constant 0.0 for everything).
		int commonLayerSizeBytes = 1
				// tiles per layer
				* (CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT)
				// triangles per tile
				* 2
				// vertices per triangle
				* 3
				// xy per vertex
				* (Float.BYTES * 2)
		;
		ByteBuffer commonData = ByteBuffer.allocateDirect(commonLayerSizeBytes);
		commonData.order(ByteOrder.nativeOrder());
		// Populate the common mesh.
		FloatBuffer meshBuffer = commonData.asFloatBuffer();
		for (int y = 0; y < CUBOID_EDGE_TILE_COUNT; ++y)
		{
			for (int x = 0; x < CUBOID_EDGE_TILE_COUNT; ++x)
			{
				float xCoord = (TILE_EDGE_SIZE * x);
				float yCoord = (TILE_EDGE_SIZE * y);
				
				float[] bl = new float[]{xCoord, yCoord};
				float[] br = new float[]{xCoord + TILE_EDGE_SIZE, yCoord};
				float[] tr = new float[]{xCoord + TILE_EDGE_SIZE, yCoord + TILE_EDGE_SIZE};
				float[] tl = new float[]{xCoord, yCoord + TILE_EDGE_SIZE};
				
				meshBuffer.put(bl);
				meshBuffer.put(br);
				meshBuffer.put(tr);
				
				meshBuffer.put(bl);
				meshBuffer.put(tr);
				meshBuffer.put(tl);
			}
		}
		((java.nio.Buffer) commonData).position(0);
		
		int commonMesh = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonMesh);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, commonLayerSizeBytes, commonData.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
		return commonMesh;
	}

	private void _drawEntity(float xOffset, float yOffset, float scale, EntityType type)
	{
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.tileTextures);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glActiveTexture(GL20.GL_TEXTURE1);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.entityTextures);
		_gl.glUniform1i(_uTexture1, 1);
		_gl.glUniform2f(_uOffset, xOffset, yOffset);
		_gl.glUniform1f(_uScale, scale);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _entityBuffers[type.number()]);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 2 * Float.BYTES);
		_gl.glEnableVertexAttribArray(2);
		_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 8 * Float.BYTES, 4 * Float.BYTES);
		_gl.glEnableVertexAttribArray(3);
		_gl.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 8 * Float.BYTES, 6 * Float.BYTES);
		_gl.glEnableVertexAttribArray(4);
		_gl.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, 8 * Float.BYTES, 7 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// (we switch the atlas in and out since this will likely be a different sprite atlas, later).
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.tileTextures);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glActiveTexture(GL20.GL_TEXTURE1);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.auxTextures);
		_gl.glUniform1i(_uTexture1, 1);
		_gl.glUniform1f(_uScale, 1.0f);
	}
}
